package com.example.parsing_vacancies.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // Отключаем защиту CSRF для теста
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/oauth2/**").permitAll() // Разрешаем доступ ко всем URL, связанным с OAuth2 аутентификацией
                        .anyRequest().permitAll() // Разрешаем доступ ко всем остальным URL
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .authorizationEndpoint(a -> a
                                .baseUri("/oauth2/authorization")
                        )
                        .redirectionEndpoint(r -> r
                                .baseUri("/oauth2/callback")
                        )
                        .successHandler((request, response, authentication) -> {
                            System.out.println("Authentication Success: " + authentication.getName());
                            response.sendRedirect("/oauth2/callback?authSuccess=true");
                        })
                        .failureHandler((request, response, exception) -> {
                            System.out.println("Authentication Failure: " + exception.getMessage());
                            response.sendRedirect("/login?error");
                        })
                );

        return http.build();
    }
}
