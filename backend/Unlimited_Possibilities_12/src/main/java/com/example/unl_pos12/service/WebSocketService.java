package com.example.unl_pos12.service;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class WebSocketService {
    private ConcurrentHashMap<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void registerSession(Long userId, WebSocketSession session) {
        sessions.put(userId, session);
    }

    public void unregisterSession(Long userId) {
        sessions.remove(userId);
    }

    public void sendNotification(Long recipientId, String content, Long chatId) {
        WebSocketSession session = sessions.get(recipientId);
        System.out.println("chatId: " + chatId);
        System.out.println("recipientId: " + recipientId);
        System.out.println("content: " + content);
        System.out.println("session: " + session);
        boolean isOpen = session.isOpen();
        System.out.println("isOpen: " + isOpen);
        if (session != null && isOpen) {
            try {
                session.sendMessage(new TextMessage("Новое сообщение: " + content + " в чате: " + chatId));
                System.out.println("Session from method sendNotification sent...");
            } catch (Exception e) {
                System.out.println("Error in sendNotification: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
