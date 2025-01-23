package com.example.unl_pos12.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void sendNotification(Long recipientId, String content, Long chatId) {
        try {
            String destination = "/topic/notifications/" + recipientId;
            messagingTemplate.convertAndSend(destination, "Новое сообщение: " + content + " в чате: " + chatId);
            System.out.println("Notification sent to user ID: " + recipientId);
        } catch (Exception e) {
            System.out.println("Error in method sendNotification: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
