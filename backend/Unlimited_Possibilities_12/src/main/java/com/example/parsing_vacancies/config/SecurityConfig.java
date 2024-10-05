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
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/login", "/oauth2/**").permitAll() // Разрешаем доступ к этим URL
                        .anyRequest().authenticated() // Все остальные запросы требуют аутентификации
                )
                .oauth2Login(oauth2 -> oauth2 // Настройка OAuth 2.0
                        .loginPage("/login") // Укажите страницу логина
                );

        return http.build();
    }
}
