package com.example.parsing_vacancies.config;

import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // Позволяет запросы ко всем эндпоинтам
                .allowedOrigins("https://unlimitedpossibilities12.org") // Укажите ваш источник
                .allowedMethods("DELETE", "GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
