package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.HeartbeatMessage;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.repo.ChatRepository;
import com.example.unl_pos12.service.MessageService;
import com.example.unl_pos12.service.UserService;
import com.example.unl_pos12.service.WebSocketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.util.Map;

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
        System.out.println("Received heartbeat for userId: " + message.getUserId());
        userService.setUserOnline(message.getUserId(), true);
        webSocketService.sendUserStatusUpdate(message.getUserId(), true);
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

    // Новый эндпоинт для установки статуса онлайн (Пока неиспользуемый)
    @MessageMapping("/setOnline")
    public void handleSetOnline(@Payload HeartbeatMessage message) {
        System.out.println("Received setOnline for userId: " + message.getUserId());
        userService.setUserOnline(message.getUserId(), true);
        webSocketService.sendUserStatusUpdate(message.getUserId(), true);
    }


    @MessageMapping("/setOffline")
    public void handleSetOffline(@Payload HeartbeatMessage message) {
        System.out.println("Received setOffline for userId: " + message.getUserId());
        userService.setUserOnline(message.getUserId(), false);
        webSocketService.sendUserStatusUpdate(message.getUserId(), false);
    }
}
