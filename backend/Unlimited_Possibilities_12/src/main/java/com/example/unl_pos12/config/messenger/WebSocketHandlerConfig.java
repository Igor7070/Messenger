package com.example.unl_pos12.config.messenger;

import com.example.unl_pos12.service.OpenAIService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
public class WebSocketHandlerConfig {

    @Bean
    public AudioWebSocketHandler audioWebSocketHandler(OpenAIService openAIService) {
        return new AudioWebSocketHandler(openAIService);
    }

    @Bean
    public WebSocketConfigurer webSocketConfigurer(AudioWebSocketHandler audioWebSocketHandler) {
        return new WebSocketConfigurer() {
            @Override
            public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
                registry.addHandler(audioWebSocketHandler, "/audio-transcription")
                        .setAllowedOrigins(
                                "https://igor7070.github.io",
                                "https://unlimitedpossibilities12.org",
                                "http://localhost:3000", // Для локального тестирования фронтенда
                                "http://10.0.2.2:3000"  // Для Android-эмулятора
                        )
                        .withSockJS(); // Добавляем поддержку SockJS
            }
        };
    }
}
