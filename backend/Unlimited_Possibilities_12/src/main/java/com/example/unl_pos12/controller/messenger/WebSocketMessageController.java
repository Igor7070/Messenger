package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.*;
import com.example.unl_pos12.repo.ChatRepository;
import com.example.unl_pos12.repo.PublicKeyHistoryRepository;
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

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Controller
public class WebSocketMessageController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.unl_pos12.service.AuthzService authz;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.unl_pos12.service.ChatMemberService chatMembers;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.unl_pos12.service.PushService pushService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.unl_pos12.repo.UserRepository userRepository;
    @Autowired
    private MessageService messageService;
    @Autowired
    private ChatRepository chatRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private WebSocketService webSocketService;
    @Autowired
    private PublicKeyHistoryRepository publicKeyHistoryRepository; // [ADD]

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
        System.out.println("Received clientMessageId: " + message.getClientMessageId());
        // В журнал — только длина: логи Railway хранятся отдельно от базы и
        // доступны всем, кто войдёт в аккаунт, а это второй экземпляр переписки
        System.out.println("Received message: length=" + (message.getContent() != null ? message.getContent().length() : 0) +
                ", messageType=" + message.getMessageType() +
                ", from user: " + (message.getSender() != null ? message.getSender().getUsername() : "null"));
        System.out.println("Message from chat: " + message.getChat());
        System.out.println("Message from chatName: " + message.getChat().getName());
        System.out.println("Message from chat id: " + (message.getChat() != null
                ? message.getChat().getId() : "null"));

        // Проверка chatId
        if (message.getChat() != null && message.getChat().getId() != null) {
            Chat chat = chatRepository.findById(message.getChat().getId())
                    .orElseThrow(() -> new RuntimeException("Chat not found"));
            message.setChat(chat);
        } else {
            throw new RuntimeException("Chat ID is missing");
        }

        // Проверка для секретных чатов
        if (message.getChat().getIsSecret() != null && message.getChat().getIsSecret()) {
            if ("text".equals(message.getMessageType())) {
                if (message.getEncryptedContent() == null || message.getNonce() == null) {
                    throw new RuntimeException("Encrypted content or nonce is missing for secret chat text message");
                }
                // Установка publicKeyId для текстовых сообщений
                PublicKeyHistory publicKeyHistory = publicKeyHistoryRepository.findByUserIdAndValidUntilIsNull(message.getSender().getId());
                if (publicKeyHistory != null) {
                    message.setPublicKeyId(publicKeyHistory.getId());
                    System.out.println("Set publicKeyId: " + publicKeyHistory.getId() + " for text message from userId: " + message.getSender().getId());
                } else {
                    System.out.println("No active public key found for senderId: " + message.getSender().getId());
                }
                message.setContent(null);
                message.setTranslatedContent(null);
                message.setTranscribedContent(null);
            } else if ("file".equals(message.getMessageType()) || "audio".equals(message.getMessageType())) {
                // [ADD] Проверяем наличие encryptedContent и nonce для файловых сообщений
                if (message.getEncryptedContent() == null || message.getNonce() == null) {
                    throw new RuntimeException("Encrypted content or nonce is missing for secret chat file/audio message");
                }
                PublicKeyHistory publicKeyHistory = publicKeyHistoryRepository.findByUserIdAndValidUntilIsNull(message.getSender().getId());
                if (publicKeyHistory != null) {
                    message.setPublicKeyId(publicKeyHistory.getId());
                    System.out.println("Set publicKeyId: " + publicKeyHistory.getId() + " for file/audio message from userId: " + message.getSender().getId());
                } else {
                    System.out.println("No active public key found for senderId: " + message.getSender().getId());
                }
                message.setContent(null);
                message.setTranslatedContent(null);
                message.setTranscribedContent(null);
                message.setFileUrl(null); // Очищаем fileUrl, так как он зашифрован в encryptedContent
            } else {
                throw new RuntimeException("Invalid message type for secret chat: " + message.getMessageType());
            }
        } else {
            message.setEncryptedContent(null);
            message.setNonce(null);
            if (message.getContent() == null && message.getFileUrl() == null) {
                throw new RuntimeException("Content or fileUrl is required for non-secret chat");
            }
        }

        message.setDelivered_status(true); // Устанавливаем статус доставки
        message.setTimestamp(ZonedDateTime.now()); // [ADD] Установка timestamp для нового сообщения
        Message saved = messageService.saveMessage(message, null); // file = null, так как клиент отправляет fileUrl
        joinPublicGroupOnWrite(saved);
        pushToChatMembers(saved);
        return saved;
    }

    /**
     * Push всем участникам чата, кроме отправителя.
     *
     * Раньше push зависел от того, что клиент сам вызовет /api/messages/notify:
     * веб-версия делала это через три секунды, разыскивая своё сообщение по
     * тексту, и при повторе того же текста уведомление не уходило вовсе.
     * Теперь сервер шлёт push в момент сохранения — это единственная точка,
     * через которую проходят сообщения всех клиентов.
     *
     * Текста сообщения в push нет: только имя отправителя и id чата.
     */
    /**
     * Человек ответил в публичной группе — значит, он в ней состоит.
     * Без этого он читал бы её из общего списка, но числился посторонним: не
     * получал бы push и пропал бы из списка участников.
     */
    private void joinPublicGroupOnWrite(Message saved) {
        if (saved == null || saved.getChat() == null || saved.getSender() == null) return;
        try {
            chatMembers.joinPublicOnWrite(saved.getChat(), saved.getSender().getId());
        } catch (Exception e) {
            System.out.println("joinPublicGroupOnWrite error: " + e.getMessage());
        }
    }

    private void pushToChatMembers(Message saved) {
        if (pushService == null || saved == null || saved.getChat() == null) return;
        try {
            Long chatId = saved.getChat().getId();
            Long senderId = saved.getSender() != null ? saved.getSender().getId() : null;
            boolean secret = Boolean.TRUE.equals(saved.getChat().getIsSecret());
            String senderName = saved.getSender() != null ? saved.getSender().getUsername() : "Messenger UP12";

            java.util.Map<String, String> data = new java.util.HashMap<>();
            data.put("chatId", String.valueOf(chatId));
            data.put("senderId", String.valueOf(senderId));
            data.put("secret", String.valueOf(secret));

            // Получатели: для чата на двоих — связь privateChats, для группы —
            // таблица участников. Раньше искали только по privateChats, и
            // групповые сообщения не вызывали push вовсе.
            java.util.Set<Long> recipients = new java.util.LinkedHashSet<>();
            for (com.example.unl_pos12.model.messenger.User member : userRepository.findMembersOfChat(chatId)) {
                recipients.add(member.getId());
            }
            for (com.example.unl_pos12.model.messenger.ChatMember member : chatMembers.members(chatId)) {
                recipients.add(member.getUserId());
            }
            for (Long recipient : recipients) {
                if (senderId != null && senderId.equals(recipient)) continue; // себе не шлём
                pushService.sendToUser(recipient, senderName, "New message", data);
            }
        } catch (Exception e) {
            System.out.println("pushToChatMembers error: " + e.getMessage());
        }
    }

    @MessageMapping("/editMessage/{chatId}")
    @SendTo("/topic/chat/{chatId}/messages")
    public Message editMessage(@DestinationVariable String chatId, Message message,
                               org.springframework.messaging.simp.SimpMessageHeaderAccessor headers) {
        System.out.println("Editing message: " + message.getId());
        if (!mayChangeMessage(headers, message.getId(), "editMessage")) return null;
        return messageService.updateMessage(message); // Обновляем сообщение в базе данных
    }

    /**
     * Своё ли это сообщение. Перехватчик STOMP проверяет только членство в чате,
     * то есть в групповом чате пропускает всех, а в чате на двоих разрешил бы
     * собеседнику править мои сообщения. REST-путь уже закрыт, закрываем и этот.
     */
    private boolean mayChangeMessage(org.springframework.messaging.simp.SimpMessageHeaderAccessor headers,
                                     Long messageId, String what) {
        Long me = null;
        if (headers != null && headers.getSessionAttributes() != null) {
            Object v = headers.getSessionAttributes()
                    .get(com.example.unl_pos12.config.messenger.StompAuthInterceptor.ATTR_USER_ID);
            if (v instanceof Long) me = (Long) v;
        }
        if (authz.isMessageAuthor(me, messageId)) return true;
        System.out.println("WS-AUTHZ-DENY " + what + " user=" + me + " message=" + messageId);
        return !authz.isEnforcing(); // в режиме наблюдения только пишем в лог
    }

    @MessageMapping("/deleteMessage/{chatId}")
    @SendTo("/topic/chat/{chatId}/messages") // Замените на правильную тему
    public Message deleteMessage(@DestinationVariable String chatId, Long id,
                                 org.springframework.messaging.simp.SimpMessageHeaderAccessor headers) {
        if (!mayChangeMessage(headers, id, "deleteMessage")) return null;
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

    @MessageMapping("/typing/{chatId}")
    @SendTo("/topic/chat/{chatId}/typing")
    public Message handleTyping(
            @DestinationVariable String chatId,
            @Payload Message typingMessage) {

        System.out.println("Received typing event in chat " + chatId +
                " from user: " + (typingMessage.getSender() != null ? typingMessage.getSender().getUsername() : "unknown") +
                ", type: " + typingMessage.getMessageType());

        // Минимальная валидация
        if (typingMessage.getSender() == null || typingMessage.getSender().getId() == null) {
            System.out.println("Typing ignored: sender missing");
            return null; // или можно бросить исключение, но лучше просто игнорировать
        }

        if (!"TYPING".equals(typingMessage.getMessageType()) &&
                !"STOP_TYPING".equals(typingMessage.getMessageType())) {
            System.out.println("Invalid typing messageType: " + typingMessage.getMessageType());
            return null;
        }

        // Не сохраняем в базу — это временное событие
        // Просто возвращаем объект дальше — он уйдёт всем подписчикам
        typingMessage.setTimestamp(ZonedDateTime.now());
        typingMessage.setDelivered_status(true); // опционально

        return typingMessage;
    }
}
