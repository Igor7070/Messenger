package com.example.unl_pos12.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // Отключаем защиту CSRF для теста
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/oauth2/**").permitAll() // Разрешаем доступ ко всем URL, связанным с OAuth2 аутентификацией
                        .requestMatchers("/messenger/**").authenticated() // Защита мессенджера
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
                            response.sendRedirect("/autorization_success");
                        })
                        .failureHandler((request, response, exception) -> {
                            System.out.println("Authentication Failure: " + exception.getMessage());
                            response.sendRedirect("/login?error");
                        })
                );

        return http.build();
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(Arrays.asList("https://igor7070.github.io/Messenger")); // Укажите адрес вашего клиента
        config.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        source.registerCorsConfiguration("/api/**", config); // Разрешаем CORS для API
        return new CorsFilter(source);
    }

    /*@Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(Arrays.asList("http://localhost:3000")); // Укажите адрес вашего клиента
        config.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        source.registerCorsConfiguration("/api/**", config); // Разрешаем CORS для API
        return new CorsFilter(source);
    }*/
}
