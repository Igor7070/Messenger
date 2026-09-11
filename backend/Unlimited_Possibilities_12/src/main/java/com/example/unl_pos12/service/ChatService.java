package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.repo.ChatRepository;
import com.example.unl_pos12.repo.MessageRepository;
import com.example.unl_pos12.repo.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class ChatService {
    @Autowired
    private ChatRepository chatRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MessageRepository messageRepository;

    @Transactional
    public Chat createChat(Chat chat) {
        boolean oneToOne = chat.isPrivate() || Boolean.TRUE.equals(chat.getIsSecret());

        if (!oneToOne) {
            // Групповые чаты: имя должно быть уникальным
            if (chatExists(chat.getName())) {
                throw new RuntimeException("A chat with this name already exists");
            }
            return chatRepository.save(chat);
        }

        // Приватные и секретные чаты — один на пару пользователей, имя строится
        // из их логинов. Раньше секретный чат сюда не попадал: веб-клиент шлёт
        // только isSecret без private, и он проходил проверку уникальности как
        // групповой — а если в базе оставался «осиротевший» чат с тем же именем,
        // клиент получал 409 и не мог открыть секретный чат повторно.
        Chat existing = chatRepository.findByName(chat.getName());
        if (existing == null) {
            return chatRepository.save(chat);
        }

        long owners = userRepository.countOwnersOfChat(existing.getId());
        if (owners > 0) {
            // Чат ещё есть хотя бы у одного из пары — отдаём его же, иначе
            // собеседники разъедутся по двум разным чатам с одним именем.
            // Клиент дальше сам добавит себя через /private-chats/{id}/add.
            System.out.println("Chat " + chat.getName() + " already exists (id=" + existing.getId()
                    + ", owners=" + owners + "), reusing it");
            return existing;
        }

        // Никто им не владеет — это остаток удалённого обоими чата.
        // Для секретного чата чистый лист важен: старую переписку не поднимаем.
        System.out.println("Chat " + chat.getName() + " is orphaned (id=" + existing.getId()
                + "), deleting it before creating a fresh one");
        messageRepository.deleteByChatId(existing.getId());
        chatRepository.delete(existing);
        chatRepository.flush();
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
