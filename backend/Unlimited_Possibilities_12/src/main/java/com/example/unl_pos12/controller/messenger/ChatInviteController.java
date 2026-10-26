package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.ChatInvite;
import com.example.unl_pos12.service.AuthzService;
import com.example.unl_pos12.service.ChatMemberService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Вступление по ссылке-приглашению.
 *
 * Отдельно от /api/chats/{chatId}, потому что приглашённый ещё не знает номера
 * чата — у него на руках только токен из ссылки.
 */
@RestController
@RequestMapping("/api/invites")
public class ChatInviteController {

    private final ChatMemberService chatMembers;
    private final AuthzService authz;

    public ChatInviteController(ChatMemberService chatMembers, AuthzService authz) {
        this.chatMembers = chatMembers;
        this.authz = authz;
    }

    /**
     * Что это за группа — до вступления.
     *
     * Без карточки человек нажимал ссылку и оказывался внутри, не видя ни
     * названия, ни размера группы. Показываем то же, что Telegram: имя,
     * картинку и число участников, а решение оставляем за ним.
     */
    @GetMapping("/{token}")
    public ResponseEntity<?> preview(@PathVariable String token, HttpServletRequest request) {
        Long me = authz.currentUserId(request);
        ChatInvite invite = chatMembers.findInvite(token);
        if (invite == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        Chat chat = chatMembers.chatOfInvite(invite);
        if (chat == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        Map<String, Object> result = new HashMap<>();
        result.put("chatId", chat.getId());
        result.put("name", chat.getName());
        result.put("avatar", chat.getAvatar());
        result.put("isPublic", chatMembers.isPublicGroup(chat));
        result.put("members", chatMembers.members(chat.getId()).size());
        result.put("usable", invite.isUsable());
        result.put("alreadyMember", me != null && chatMembers.isMember(chat.getId(), me));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{token}/accept")
    public ResponseEntity<?> accept(@PathVariable String token, HttpServletRequest request) {
        Long me = authz.currentUserId(request);
        if (me == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Chat chat = chatMembers.acceptInvite(token, me);
        Map<String, Object> result = new HashMap<>();
        result.put("chatId", chat.getId());
        result.put("name", chat.getName());
        return ResponseEntity.ok(result);
    }
}
