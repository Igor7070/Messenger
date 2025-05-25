package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.HeartbeatMessage;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.repo.ChatRepository;
import com.example.unl_pos12.service.MessageService;
import com.example.unl_pos12.service.UserService;
import com.example.unl_pos12.service.WebSocketService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Controller
public class WebSocketMessageController {
    @Autowired
    private MessageService messageService;
    @Autowired
    private ChatRepository chatRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private WebSocketService webSocketService;

    private static final long OFFLINE_TIMEOUT = 20_000; // 20 секунд

    @PostConstruct
    public void init() {
        startHeartbeatCheck(); // Запускаем проверку таймаута при старте
    }

    @MessageMapping("/sendMessage/chat/{chatId}")
    @SendTo("/topic/chat/{chatId}/messages")
    public Message sendMessage(@DestinationVariable String chatId, Message message) {
        System.out.println("Working method sendMessage...");
        System.out.println("Received message object: " + message);
        System.out.println("chatId: " + chatId);
        System.out.println("Received message: " + message.getContent() +
                " from user: " + message.getSender().getUsername());
        System.out.println("Message from chat: " + message.getChat());
        System.out.println("Message from chatName: " + message.getChat().getName());
        System.out.println("Message from chat id: " + (message.getChat() != null
                ? message.getChat().getId() : "null"));

        // Убедитесь, что chatId установлен
        if (message.getChat() != null && message.getChat().getId() != null) {
            Chat chat = chatRepository.findById(message.getChat().getId())
                    .orElseThrow(() -> new RuntimeException("Chat not found"));
            message.setChat(chat);
        } else {
            throw new RuntimeException("Chat ID is missing");
        }

        message.setDelivered_status(true); // Устанавливаем статус доставки
        return messageService.saveMessage(message, null); // Замените null на файл, если он есть
    }

    @MessageMapping("/editMessage/{chatId}")
    @SendTo("/topic/chat/{chatId}/messages")
    public Message editMessage(@DestinationVariable String chatId, Message message) {
        System.out.println("Editing message: " + message.getId());
        return messageService.updateMessage(message); // Обновляем сообщение в базе данных
    }

    @MessageMapping("/deleteMessage/{chatId}")
    @SendTo("/topic/chat/{chatId}/messages") // Замените на правильную тему
    public Message deleteMessage(@DestinationVariable String chatId, Long id) {
        messageService.deleteMessage(id);
        Message deletedMessage = new Message();
        deletedMessage.setId(id);
        deletedMessage.setContent("Message deleted");
        return deletedMessage; // Возвращаем сообщение о удалении
    }

    // Обработчик для статуса прочтения...
    @MessageMapping("/readMessage/{chatId}")
    @SendTo("/topic/chat/{chatId}/messages")
    public Message readMessage(@DestinationVariable String chatId, Long messageId) {
        // Обновите статус прочтения сообщения в базе данных, если необходимо
        System.out.println("Marking message as read: " + messageId);
        Message message = messageService.markAsRead(messageId);
        return message;
    }

    @MessageMapping("/heartbeat")
    public void handleHeartbeat(@Payload HeartbeatMessage message) {
        Long userId = message.getUserId();
        System.out.println("Received heartbeat for userId: " + userId);
        boolean wasOnline = userService.isUserOnline(userId);
        userService.updateHeartbeat(userId); // Обновляем lastHeartbeat
        userService.setUserOnline(userId, true); // Устанавливаем онлайн
        if (!wasOnline) {
            webSocketService.sendUserStatusUpdate(userId, true); // Отправляем при смене статуса
        }
    }

    @MessageMapping("/requestUserStatus")
    public void handleRequestUserStatus(@Payload Map<String, Long> payload) {
        Long userId = payload.get("userId");
        if (userId != null) {
            try {
                boolean isOnline = userService.getUserById(userId).isOnline();
                webSocketService.sendUserStatusUpdate(userId, isOnline);
                System.out.println("Requested status for userId: " + userId + ", online: " + isOnline);
            } catch (RuntimeException e) {
                System.err.println("User not found for userId: " + userId);
            }
        } else {
            System.err.println("Invalid userId in requestUserStatus payload");
        }
    }

    @MessageMapping("/setOnline")
    public void handleSetOnline(@Payload HeartbeatMessage message) {
        Long userId = message.getUserId();
        System.out.println("Received setOnline for userId: " + userId);
        boolean wasOnline = userService.isUserOnline(userId);
        userService.setUserOnline(userId, true); // Устанавливаем онлайн и lastHeartbeat
        if (!wasOnline) {
            webSocketService.sendUserStatusUpdate(userId, true);
        }
    }

    @MessageMapping("/setOffline")
    public void handleSetOffline(@Payload HeartbeatMessage message) {
        Long userId = message.getUserId();
        System.out.println("Received setOffline for userId: " + userId);
        boolean wasOnline = userService.isUserOnline(userId);
        userService.setUserOnline(userId, false); // Устанавливаем оффлайн и сбрасываем lastHeartbeat
        if (wasOnline) {
            webSocketService.sendUserStatusUpdate(userId, false);
        }
    }

    private void startHeartbeatCheck() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            List<User> onlineUsers = userService.getOnlineUsers();
            for (User user : onlineUsers) {
                Long lastHeartbeat = user.getLastHeartbeat();
                if (lastHeartbeat != null && currentTime - lastHeartbeat > OFFLINE_TIMEOUT) {
                    System.out.println("User " + user.getId() + " timed out, setting offline");
                    boolean wasOnline = userService.isUserOnline(user.getId());
                    userService.setUserOnline(user.getId(), false);
                    if (wasOnline) {
                        webSocketService.sendUserStatusUpdate(user.getId(), false);
                    }
                }
            }
        }, 5, 5, TimeUnit.SECONDS); // Проверяем каждые 5 секунд
    }
}
