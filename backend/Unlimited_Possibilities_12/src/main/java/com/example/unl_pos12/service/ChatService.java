package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.repo.ChatRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class ChatService {
    @Autowired
    private ChatRepository chatRepository;

    public Chat createChat(Chat chat) {
        // Проверка только для групповых чатов
        if (!chat.isPrivate() && chatExists(chat.getName())) {
            throw new RuntimeException("A chat with this name already exists");
        }
        return chatRepository.save(chat);
    }

    public List<Chat> getAllChats() {
        return chatRepository.findAll();
    }

    public void deleteChat(Long id) {
        chatRepository.deleteById(id);
    }

    public List<Message> getMessagesByChatId(Long chatId) {
        Chat chat = chatRepository.findById(chatId).orElseThrow(() -> new RuntimeException("Chat not found"));
        return chat.getMessages();
    }

    public Chat getChatById(Long id) {
        return chatRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
    }

    public Long findChatByUsers(String name1, String name2) {
        String chatName1 = name1 + "_" + name2;
        String chatName2 = name2 + "_" + name1;

        List<Chat> chats = chatRepository.findByNameIn(Arrays.asList(chatName1, chatName2));
        if (!chats.isEmpty()) {
            return chats.get(0).getId(); // Возвращаем ID первого найденного чата
        }
        return null; // Если чат не найден
    }

    public Long getLastChatId() {
        return chatRepository.findTopByOrderByIdDesc().getId(); // Предполагается, что у вас есть такой метод в репозитории
    }

    public boolean chatExists(String name) {
        return chatRepository.findByName(name) != null;
    }
}
