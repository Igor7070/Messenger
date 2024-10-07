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
                        .defaultSuccessUrl("/convenient_job_search", true) // Перенаправление на колбек после успешной аутентификации
                );

        return http.build();
    }
}
