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
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage("Новое сообщение: " + content + " в чате: " + chatId));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
