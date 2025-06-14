package com.example.unl_pos12.config.messenger;

import com.example.unl_pos12.service.OpenAIService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

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
                        .setAllowedOrigins("https://unlimitedpossibilities12.org", "https://igor7070.github.io")
                        .setHandshakeHandler(new DefaultHandshakeHandler()); // Явный handshake
            }
        };
    }
}
