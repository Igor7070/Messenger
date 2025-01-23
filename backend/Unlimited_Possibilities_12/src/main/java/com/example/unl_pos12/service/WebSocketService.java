package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    private UserService userService; // Добавьте зависимость UserService

    public WebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void sendNotification(Long userId, Long recipientId, String content, Long chatId) {
        try {
            User user = userService.getUserById(userId);
            String userName = user.getUsername();

            String destination = "/topic/notifications/" + recipientId;
            String notificationMessage = String.format("New message: %s from:" +
                    " %s (chatId: %d)", content, userName, chatId);
            messagingTemplate.convertAndSend(destination, notificationMessage);
            System.out.println("Notification sent to user ID: " + recipientId);
        } catch (Exception e) {
            System.out.println("Error in method sendNotification: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
