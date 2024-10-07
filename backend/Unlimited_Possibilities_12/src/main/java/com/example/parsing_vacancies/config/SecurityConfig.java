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
                .csrf(csrf -> csrf.disable()) // Отключаем защиту CSRF
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().permitAll() // Разрешаем доступ ко всем URL
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login") // Укажите страницу логина
                        .defaultSuccessUrl("/oauth2/callback", true) // Перенаправление на колбек после успешной аутентификации
                        .failureUrl("/login?error") // Обработка ошибок
                        .authorizationEndpoint(authorization -> authorization
                                .baseUri("/oauth2/authorize"))
                        .redirectionEndpoint(redirection -> redirection
                                .baseUri("/oauth2/callback"))
                );

        return http.build();
    }
}
