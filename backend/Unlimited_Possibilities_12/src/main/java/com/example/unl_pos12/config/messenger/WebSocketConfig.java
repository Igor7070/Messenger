package com.example.unl_pos12.config.messenger;

import com.example.unl_pos12.model.messenger.UserStatusMessage;
import com.example.unl_pos12.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Autowired
    private UserService userService;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins("https://igor7070.github.io") // Ваш новый адрес
                .withSockJS();
    }

    /*@Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins("http://localhost:3000")
                .withSockJS();
    }*/

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String userId = event.getUser() != null ? event.getUser().getName() : null;
        if (userId != null) {
            try {
                Long id = Long.parseLong(userId);
                userService.setUserOnline(id, false);
                messagingTemplate.convertAndSend("/topic/users/status",
                        new UserStatusMessage(id, getUsername(id), false));
                System.out.println("User disconnected, set offline: " + id);
            } catch (NumberFormatException e) {
                System.err.println("Invalid userId format: " + userId);
            }
        }
    }

    private String getUsername(Long userId) {
        try {
            return userService.getUserById(userId).getUsername();
        } catch (Exception e) {
            return "Unknown";
        }
    }
}
