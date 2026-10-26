package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.ChatInvite;
import com.example.unl_pos12.model.messenger.ChatMember;
import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.repo.ChatRepository;
import com.example.unl_pos12.repo.UserRepository;
import com.example.unl_pos12.service.AuthzService;
import com.example.unl_pos12.service.ChatMemberService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Участники групповых чатов: кто состоит, кого добавить, кого исключить,
 * ссылки-приглашения и выход из группы.
 *
 * Права проверяются здесь же, а не в клиенте: прятать кнопку недостаточно,
 * запрос можно послать и без неё.
 */
@RestController
@RequestMapping("/api/chats/{chatId}")
public class ChatMemberController {

    private final ChatMemberService chatMembers;
    private final AuthzService authz;
    private final ChatRepository chatRepository;
    private final UserRepository userRepository;

    public ChatMemberController(ChatMemberService chatMembers, AuthzService authz,
                                ChatRepository chatRepository, UserRepository userRepository) {
        this.chatMembers = chatMembers;
        this.authz = authz;
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
    }

    /** Список участников с ролями и именами — для экрана «Участники». */
    @GetMapping("/members")
    public List<Map<String, Object>> members(@PathVariable Long chatId, HttpServletRequest request) {
        authz.requireChatAccess(request, chatId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMember member : chatMembers.members(chatId)) {
            Map<String, Object> row = new HashMap<>();
            row.put("userId", member.getUserId());
            row.put("role", member.getRole().name());
            row.put("joinedAt", member.getJoinedAt().toString());
            userRepository.findById(member.getUserId()).ifPresent(user -> {
                row.put("username", user.getUsername());
                row.put("avatar", user.getAvatar());
            });
            result.add(row);
        }
        return result;
    }

    /** Добавить человека в группу — владельцу и админам. */
    @PostMapping("/members/{userId}")
    public ResponseEntity<?> addMember(@PathVariable Long chatId, @PathVariable Long userId,
                                       HttpServletRequest request) {
        authz.requireChatManager(request, chatId);
        requireGroup(chatId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        chatMembers.addMember(chatId, user.getId(), ChatMember.Role.MEMBER);
        return ResponseEntity.ok().build();
    }

    /** Исключить. Владельца исключить нельзя — это проверяет сервис. */
    @DeleteMapping("/members/{userId}")
    public ResponseEntity<?> removeMember(@PathVariable Long chatId, @PathVariable Long userId,
                                          HttpServletRequest request) {
        Long me = authz.currentUserId(request);
        if (me != null && me.equals(userId)) {
            // Выйти может каждый сам, права админа для этого не нужны
            chatMembers.leave(chatId, me);
            return ResponseEntity.ok().build();
        }
        authz.requireChatManager(request, chatId);
        chatMembers.removeMember(chatId, userId);
        return ResponseEntity.ok().build();
    }

    /** Выйти из группы. Если уходит владелец, права переходят по правилам. */
    @PostMapping("/leave")
    public ResponseEntity<?> leave(@PathVariable Long chatId, HttpServletRequest request) {
        Long me = authz.currentUserId(request);
        if (me == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        chatMembers.leave(chatId, me);
        return ResponseEntity.ok().build();
    }

    /** Самостоятельно вступить — только в публичную группу. */
    @PostMapping("/join")
    public ResponseEntity<?> join(@PathVariable Long chatId, HttpServletRequest request) {
        Long me = authz.currentUserId(request);
        if (me == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        chatMembers.join(requireGroup(chatId), me);
        return ResponseEntity.ok().build();
    }

    /** Назначить или снять админа — только владельцу. */
    @PutMapping("/members/{userId}/role")
    public ResponseEntity<?> setRole(@PathVariable Long chatId, @PathVariable Long userId,
                                     @RequestBody Map<String, String> body, HttpServletRequest request) {
        Long me = authz.currentUserId(request);
        if (me != null && !chatMembers.isOwner(chatId, me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can change roles");
        }
        String role = body.getOrDefault("role", "MEMBER");
        chatMembers.setRole(chatId, userId, ChatMember.Role.valueOf(role.toUpperCase()));
        return ResponseEntity.ok().build();
    }

    /** Передать владение участнику группы. */
    @PostMapping("/transfer/{userId}")
    public ResponseEntity<?> transfer(@PathVariable Long chatId, @PathVariable Long userId,
                                      HttpServletRequest request) {
        Long me = authz.currentUserId(request);
        if (me == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        chatMembers.transferOwnership(chatId, me, userId);
        return ResponseEntity.ok().build();
    }

    /** Выпустить ссылку-приглашение: срок в часах и предел числа входов. */
    @PostMapping("/invites")
    public Map<String, Object> createInvite(@PathVariable Long chatId,
                                            @RequestBody(required = false) Map<String, Object> body,
                                            HttpServletRequest request) {
        authz.requireChatManager(request, chatId);
        requireGroup(chatId);
        Long me = authz.currentUserId(request);
        Integer ttlHours = body != null && body.get("ttlHours") != null
                ? ((Number) body.get("ttlHours")).intValue() : null;
        Integer maxUses = body != null && body.get("maxUses") != null
                ? ((Number) body.get("maxUses")).intValue() : null;
        ChatInvite invite = chatMembers.createInvite(chatId, me, ttlHours, maxUses);
        Map<String, Object> result = new HashMap<>();
        result.put("token", invite.getToken());
        result.put("expiresAt", invite.getExpiresAt() != null ? invite.getExpiresAt().toString() : null);
        result.put("maxUses", invite.getMaxUses());
        return result;
    }

    @GetMapping("/invites")
    public List<Map<String, Object>> invites(@PathVariable Long chatId, HttpServletRequest request) {
        authz.requireChatManager(request, chatId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatInvite invite : chatMembers.invites(chatId)) {
            Map<String, Object> row = new HashMap<>();
            row.put("token", invite.getToken());
            row.put("createdAt", invite.getCreatedAt().toString());
            row.put("expiresAt", invite.getExpiresAt() != null ? invite.getExpiresAt().toString() : null);
            row.put("maxUses", invite.getMaxUses());
            row.put("uses", invite.getUses());
            row.put("usable", invite.isUsable());
            result.add(row);
        }
        return result;
    }

    @DeleteMapping("/invites/{token}")
    public ResponseEntity<?> revokeInvite(@PathVariable Long chatId, @PathVariable String token,
                                          HttpServletRequest request) {
        authz.requireChatManager(request, chatId);
        chatMembers.revokeInvite(chatId, token);
        return ResponseEntity.ok().build();
    }

    private Chat requireGroup(Long chatId) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));
        if (chat.isPrivate() || Boolean.TRUE.equals(chat.getIsSecret())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is a one-to-one chat");
        }
        return chat;
    }
}
