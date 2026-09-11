package com.example.unl_pos12.config;

import com.example.unl_pos12.service.AuthTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Проверка токена на /api/**.
 *
 * Работает в двух режимах (свойство auth.enforce):
 *  - false (сейчас): запросы без валидного токена ПРОПУСКАЮТСЯ, но пишутся
 *    в лог строкой "AUTH-MISSING method path". Так можно на живом трафике
 *    убедиться, что оба клиента шлют токен во всех вызовах, прежде чем
 *    включать обязательную проверку.
 *  - true: такие запросы получают 401, кроме публичных путей ниже.
 *
 * userId из токена кладётся в атрибут запроса "authUserId" — контроллеры
 * смогут сверять его с userId из пути/тела.
 */
@Component
public class AuthTokenFilter extends OncePerRequestFilter {
    public static final String ATTR_USER_ID = "authUserId";

    /** Пути, доступные без токена: вход, регистрация, публичные файлы. */
    private static final Set<String> PUBLIC_EXACT = Set.of(
            "/api/users/login",
            "/api/users"          // POST — регистрация (GET списка защищаем в enforce-режиме отдельно)
    );
    private static final String[] PUBLIC_PREFIXES = {
            "/api/files/download/",
            "/api/preview"
    };

    private final AuthTokenService tokenService;
    private final boolean enforce;

    public AuthTokenFilter(AuthTokenService tokenService, @Value("${auth.enforce:false}") boolean enforce) {
        this.tokenService = tokenService;
        this.enforce = enforce;
        System.out.println("AuthTokenFilter: enforce=" + enforce);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("/api/users/login".equals(path)) return true;
        if ("/api/users".equals(path) && "POST".equalsIgnoreCase(method)) return true;
        for (String p : PUBLIC_PREFIXES) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7).trim() : null;
        Long userId = tokenService.verify(token);

        if (userId != null) {
            request.setAttribute(ATTR_USER_ID, userId);
        } else if (!isPublic(request)) {
            if (enforce) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Unauthorized\"}");
                return;
            }
            System.out.println("AUTH-MISSING " + request.getMethod() + " " + request.getRequestURI()
                    + (token == null ? " (no token)" : " (invalid token)"));
        }
        chain.doFilter(request, response);
    }
}
