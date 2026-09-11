package com.example.unl_pos12.service;

import com.example.unl_pos12.config.AuthTokenFilter;
import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.repo.ChatRepository;
import com.example.unl_pos12.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Проверка прав: «имеет ли пользователь из токена право на этот ресурс».
 *
 * Правила простые и совпадают с тем, что делает нормальный клиент:
 *  - свои данные (профиль, ключи, список чатов) — только владелец;
 *  - приватный/секретный чат — только его участник;
 *  - групповой чат — любой вошедший пользователь (участников у групп нет).
 *
 * Режим (свойство auth.authz):
 *  - "log" (сейчас): нарушения только пишутся в лог строкой AUTHZ-DENY,
 *    запрос выполняется. Так на живом трафике видно, не срабатывает ли
 *    правило «по пустякам», прежде чем включать отказы.
 *  - "enforce": нарушение -> 403.
 * Если у запроса нет userId из токена (auth.enforce=false пропустил его),
 * проверка прав не выполняется — это зона ответственности AuthTokenFilter.
 */
@Service
public class AuthzService {
    private final UserRepository userRepository;
    private final ChatRepository chatRepository;
    private final boolean enforce;

    public AuthzService(UserRepository userRepository, ChatRepository chatRepository,
                        @Value("${auth.authz:log}") String mode) {
        this.userRepository = userRepository;
        this.chatRepository = chatRepository;
        this.enforce = "enforce".equalsIgnoreCase(mode);
        System.out.println("AuthzService: mode=" + (enforce ? "enforce" : "log"));
    }

    public Long currentUserId(HttpServletRequest request) {
        Object v = request.getAttribute(AuthTokenFilter.ATTR_USER_ID);
        return v instanceof Long ? (Long) v : null;
    }

    private boolean isOneToOne(Chat chat) {
        return chat != null && (chat.isPrivate() || Boolean.TRUE.equals(chat.getIsSecret()));
    }

    /** Состоит ли пользователь в чате. Для групповых чатов — всегда true. */
    public boolean isChatMember(long userId, long chatId) {
        Chat chat = chatRepository.findById(chatId).orElse(null);
        if (chat == null) return true; // несуществующий чат пусть отдаёт свой 404 контроллер
        if (!isOneToOne(chat)) return true;
        return userRepository.isMemberOfChat(userId, chatId) > 0;
    }

    private void deny(HttpServletRequest request, String rule) {
        String line = "AUTHZ-DENY " + request.getMethod() + " " + request.getRequestURI()
                + " user=" + currentUserId(request) + " rule=" + rule;
        if (enforce) {
            System.out.println(line + " -> 403");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        System.out.println(line + " (log only)");
    }

    /** Ресурс принадлежит пользователю: userId в запросе == userId из токена. */
    public void requireSelf(HttpServletRequest request, Long userId) {
        Long me = currentUserId(request);
        if (me == null || userId == null) return;
        if (!me.equals(userId)) deny(request, "self:" + userId);
    }

    /** Пользователь — участник чата (для приватных и секретных). */
    public void requireChatAccess(HttpServletRequest request, Long chatId) {
        Long me = currentUserId(request);
        if (me == null || chatId == null) return;
        if (!isChatMember(me, chatId)) deny(request, "chat-member:" + chatId);
    }

    /**
     * Добавление чата пользователю: себе — всегда; собеседнику — только если
     * сам уже состоишь в этом чате (так клиент создаёт приватный чат на двоих).
     */
    public void requireCanAddChatTo(HttpServletRequest request, Long targetUserId, Long chatId) {
        Long me = currentUserId(request);
        if (me == null || targetUserId == null || chatId == null) return;
        if (me.equals(targetUserId)) return;
        Chat chat = chatRepository.findById(chatId).orElse(null);
        if (chat == null) return;
        // Только что созданный чат ещё ни у кого нет — создатель добавляет обоих
        long owners = userRepository.countOwnersOfChat(chatId);
        if (owners == 0) return;
        if (userRepository.isMemberOfChat(me, chatId) == 0) deny(request, "add-chat-to:" + targetUserId);
    }
}
