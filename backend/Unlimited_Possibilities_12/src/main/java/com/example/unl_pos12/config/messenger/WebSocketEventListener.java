package com.example.unl_pos12.config.messenger;

import com.example.unl_pos12.service.UserService;
import com.example.unl_pos12.service.WebSocketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {
    @Autowired
    private UserService userService;
    @Autowired
    private WebSocketService webSocketService;

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String userId = event.getUser() != null ? event.getUser().getName() : null;
        if (userId != null) {
            try {
                Long id = Long.parseLong(userId);
                userService.setUserOnline(id, false);
                webSocketService.sendUserStatusUpdate(id, false);
                System.out.println("User disconnected, set offline: " + id);
            } catch (NumberFormatException e) {
                System.err.println("Invalid userId format: " + userId);
            }
        }
    }
}
