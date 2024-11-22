package com.example.unl_pos12.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableWebSecurity
public class SecurityConfig implements WebMvcConfigurer {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/oauth2/**").permitAll()
                        .requestMatchers("/api/**").permitAll() // Разрешаем доступ к API всем
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .authorizationEndpoint(a -> a.baseUri("/oauth2/authorization"))
                        .redirectionEndpoint(r -> r.baseUri("/oauth2/callback"))
                        .successHandler((request, response, authentication) -> {
                            System.out.println("Authentication Success: " + authentication.getName());
                            response.sendRedirect("/authorization_success");
                        })
                        .failureHandler((request, response, exception) -> {
                            System.out.println("Authentication Failure: " + exception.getMessage());
                            response.sendRedirect("/login?error");
                        })
                );

        return http.build();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**") // Разрешаем CORS для всех маршрутов API
                .allowedOrigins("http://localhost:3000") // Укажите адрес вашего клиента
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowCredentials(true);
    }
}
