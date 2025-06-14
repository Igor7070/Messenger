package com.example.unl_pos12.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // Позволяет запросы ко всем эндпоинтам
                .allowedOrigins("https://unlimitedpossibilities12.org") // Укажите ваш источник https://unlimitedpossibilities12.org
                .allowedMethods("DELETE", "GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Указываем, где искать статические ресурсы, исключая /audio-transcription
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/", "classpath:/public/", "classpath:/resources/", "classpath:/META-INF/resources/")
                .resourceChain(true);
    }
}
