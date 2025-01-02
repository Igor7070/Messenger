package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.dto.MessageDTO;
import com.example.unl_pos12.model.messenger.dto.UserDTO;
import com.example.unl_pos12.repo.ChatRepository;
import com.example.unl_pos12.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class WebSocketMessageController {
    @Autowired
    private MessageService messageService;
    @Autowired
    private ChatRepository chatRepository;

    @MessageMapping("/sendMessage")
    @SendTo("/topic/messages")
    public MessageDTO sendMessage(Message message) {
        System.out.println("Working method sendMessage...");
        System.out.println("Received message: " + message.getContent() +
                " from user: " + message.getSender().getUsername());

        // Убедитесь, что chatId установлен
        if (message.getChat() != null && message.getChat().getId() != null) {
            Chat chat = chatRepository.findById(message.getChat().getId())
                    .orElseThrow(() -> new RuntimeException("Chat not found"));
            message.setChat(chat);
        } else {
            throw new RuntimeException("Chat ID is missing");
        }

        // Сохраняем сообщение и преобразуем его в DTO
        Message savedMessage = messageService.saveMessage(message);
        return convertToDTO(savedMessage); // Преобразуем в DTO перед отправкой
    }

    @MessageMapping("/editMessage")
    @SendTo("/topic/messages")
    public Message editMessage(Message message) {
        System.out.println("Editing message: " + message.getId());
        return messageService.updateMessage(message); // Обновляем сообщение в базе данных
    }

    @MessageMapping("/deleteMessage")
    @SendTo("/topic/messages")
    public Message deleteMessage(Long id) {
        messageService.deleteMessage(id);
        Message deletedMessage = new Message();
        deletedMessage.setId(id);
        deletedMessage.setContent("Message deleted");
        return deletedMessage; // Возвращаем сообщение о удалении
    }

    private MessageDTO convertToDTO(Message message) {
        UserDTO sender = new UserDTO(message.getSender().getId(), message.getSender().getUsername());
        return new MessageDTO(message.getId(), message.getContent(), sender);
    }
}
