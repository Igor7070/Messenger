package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.ChatInvite;
import com.example.unl_pos12.model.messenger.ChatMember;
import com.example.unl_pos12.repo.ChatInviteRepository;
import com.example.unl_pos12.repo.ChatMemberRepository;
import com.example.unl_pos12.repo.ChatRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Участники групповых чатов: кто состоит, с какой ролью и кто что может.
 *
 * Правила, о которых договорились:
 *  - владелец один, это создатель: удаляет чат, назначает и снимает админов,
 *    передаёт владение любому участнику, исключить его нельзя;
 *  - владелец ушёл — распоряжаются админы; админов нет — права переходят
 *    самому давнему участнику, иначе группа останется без управления;
 *  - группа закрыта по умолчанию: попасть можно по приглашению админа или по
 *    ссылке с токеном;
 *  - публичная группа видна всем и читается всеми, а запись означает вступление:
 *    так прежние открытые чаты продолжают работать, не требуя от людей лишнего
 *    действия.
 *
 * Чаты на двоих сюда не попадают: там участие выражено связью privateChats и
 * роли не нужны.
 */
@Service
public class ChatMemberService {

    private final ChatRepository chatRepository;
    private final ChatMemberRepository memberRepository;
    private final ChatInviteRepository inviteRepository;

    public ChatMemberService(ChatRepository chatRepository,
                             ChatMemberRepository memberRepository,
                             ChatInviteRepository inviteRepository) {
        this.chatRepository = chatRepository;
        this.memberRepository = memberRepository;
        this.inviteRepository = inviteRepository;
    }

    /**
     * Публичная ли группа.
     *
     * У чатов, созданных до появления поля, значение пустое — считаем их
     * публичными. Иначе единственный существующий групповой чат внезапно
     * закрылся бы от всех, включая тех, кто в нём переписывается.
     */
    public boolean isPublicGroup(Chat chat) {
        return chat != null && !Boolean.FALSE.equals(chat.getIsPublic());
    }

    public boolean isMember(Long chatId, Long userId) {
        return chatId != null && userId != null && memberRepository.existsByChatIdAndUserId(chatId, userId);
    }

    public Optional<ChatMember> membership(Long chatId, Long userId) {
        if (chatId == null || userId == null) return Optional.empty();
        return memberRepository.findByChatIdAndUserId(chatId, userId);
    }

    public List<ChatMember> members(Long chatId) {
        return memberRepository.findByChatIdOrderByJoinedAtAsc(chatId);
    }

    /** Создатель группы становится её владельцем и первым участником. */
    @Transactional
    public void addOwner(Long chatId, Long userId) {
        if (chatId == null || userId == null) return;
        if (memberRepository.existsByChatIdAndUserId(chatId, userId)) return;
        memberRepository.save(new ChatMember(chatId, userId, ChatMember.Role.OWNER));
    }

    /**
     * Запись в публичную группу означает вступление.
     *
     * Без этого человек, открывший публичную группу из общего списка, увидел бы
     * историю, но при попытке ответить получил бы отказ — и не понял почему.
     */
    @Transactional
    public void joinPublicOnWrite(Chat chat, Long userId) {
        if (chat == null || userId == null) return;
        if (chat.isPrivate() || Boolean.TRUE.equals(chat.getIsSecret())) return;
        if (!isPublicGroup(chat)) return;
        if (memberRepository.existsByChatIdAndUserId(chat.getId(), userId)) return;
        memberRepository.save(new ChatMember(chat.getId(), userId, ChatMember.Role.MEMBER));
        System.out.println("Joined public group on write: chat=" + chat.getId() + ", user=" + userId);
    }

    /**
     * Может ли человек распоряжаться группой: добавлять, исключать, менять имя.
     *
     * Если владелец вышел, его роль в таблице исчезла — тогда правá у админов.
     * Для чатов, созданных до появления участников, управлять нечему: там
     * членства ещё нет ни у кого, и мы никого не ограничиваем.
     */
    public boolean canManage(Long chatId, Long userId) {
        if (chatId == null || userId == null) return false;
        if (memberRepository.countByChatId(chatId) == 0) return true; // чат из прежних времён
        return membership(chatId, userId)
                .map(m -> m.getRole().canManageMembers())
                .orElse(false);
    }

    public boolean isOwner(Long chatId, Long userId) {
        return membership(chatId, userId)
                .map(m -> m.getRole() == ChatMember.Role.OWNER)
                .orElse(false);
    }

    @Transactional
    public ChatMember addMember(Long chatId, Long userId, ChatMember.Role role) {
        return memberRepository.findByChatIdAndUserId(chatId, userId)
                .orElseGet(() -> memberRepository.save(new ChatMember(chatId, userId, role)));
    }

    @Transactional
    public void removeMember(Long chatId, Long userId) {
        ChatMember member = memberRepository.findByChatIdAndUserId(chatId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a member"));
        if (member.getRole() == ChatMember.Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The owner cannot be removed");
        }
        memberRepository.delete(member);
    }

    /** Выход из группы. Если уходит владелец — ищем, кому оставить права. */
    @Transactional
    public void leave(Long chatId, Long userId) {
        ChatMember member = memberRepository.findByChatIdAndUserId(chatId, userId).orElse(null);
        if (member == null) return;
        boolean wasOwner = member.getRole() == ChatMember.Role.OWNER;
        memberRepository.delete(member);
        if (wasOwner) promoteSuccessor(chatId);
    }

    /**
     * Владелец ушёл: распоряжаются админы, а если их нет — самый давний
     * участник становится владельцем. Иначе остались бы группы, которые никто
     * не может ни настроить, ни удалить.
     */
    @Transactional
    public void promoteSuccessor(Long chatId) {
        List<ChatMember> admins = memberRepository.findByChatIdAndRole(chatId, ChatMember.Role.ADMIN);
        if (!admins.isEmpty()) return; // админы справятся сами
        List<ChatMember> rest = memberRepository.findByChatIdOrderByJoinedAtAsc(chatId);
        if (rest.isEmpty()) return;
        ChatMember oldest = rest.get(0);
        oldest.setRole(ChatMember.Role.OWNER);
        memberRepository.save(oldest);
        System.out.println("Ownership passed to the longest-standing member: chat=" + chatId
                + ", user=" + oldest.getUserId());
    }

    @Transactional
    public void setRole(Long chatId, Long userId, ChatMember.Role role) {
        ChatMember member = memberRepository.findByChatIdAndUserId(chatId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a member"));
        if (member.getRole() == ChatMember.Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Transfer ownership instead");
        }
        if (role == ChatMember.Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use the transfer endpoint");
        }
        member.setRole(role);
        memberRepository.save(member);
    }

    /** Передача владения: прежний владелец остаётся в группе админом. */
    @Transactional
    public void transferOwnership(Long chatId, Long fromUserId, Long toUserId) {
        ChatMember current = memberRepository.findByChatIdAndUserId(chatId, fromUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the owner"));
        if (current.getRole() != ChatMember.Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can transfer ownership");
        }
        ChatMember next = memberRepository.findByChatIdAndUserId(chatId, toUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That person is not a member"));
        current.setRole(ChatMember.Role.ADMIN);
        next.setRole(ChatMember.Role.OWNER);
        memberRepository.save(current);
        memberRepository.save(next);
    }

    // ---------- приглашения ----------

    @Transactional
    public ChatInvite createInvite(Long chatId, Long createdBy, Integer ttlHours, Integer maxUses) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant expires = (ttlHours != null && ttlHours > 0)
                ? Instant.now().plusSeconds(ttlHours * 3600L)
                : null;
        return inviteRepository.save(new ChatInvite(token, chatId, createdBy, expires,
                maxUses != null ? maxUses : 0));
    }

    public List<ChatInvite> invites(Long chatId) {
        return inviteRepository.findByChatIdOrderByCreatedAtDesc(chatId);
    }

    @Transactional
    public void revokeInvite(Long chatId, String token) {
        ChatInvite invite = inviteRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invite"));
        if (!invite.getChatId().equals(chatId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite belongs to another chat");
        }
        invite.setRevoked(true);
        inviteRepository.save(invite);
    }

    /** Приглашение по токену — для карточки перед вступлением. */
    public ChatInvite findInvite(String token) {
        return inviteRepository.findByToken(token).orElse(null);
    }

    public Chat chatOfInvite(ChatInvite invite) {
        return invite == null ? null : chatRepository.findById(invite.getChatId()).orElse(null);
    }

    /** Вступление по ссылке. Возвращает чат, в который человек попал. */
    @Transactional
    public Chat acceptInvite(String token, Long userId) {
        ChatInvite invite = inviteRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found"));
        if (!invite.isUsable()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This invite is no longer valid");
        }
        Chat chat = chatRepository.findById(invite.getChatId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));
        if (!memberRepository.existsByChatIdAndUserId(chat.getId(), userId)) {
            memberRepository.save(new ChatMember(chat.getId(), userId, ChatMember.Role.MEMBER));
            invite.setUses(invite.getUses() + 1);
            inviteRepository.save(invite);
        }
        return chat;
    }

    /** Самому вступить в публичную группу (закрытая — только по приглашению). */
    @Transactional
    public void join(Chat chat, Long userId) {
        if (!isPublicGroup(chat)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This group is private — you need an invite");
        }
        addMember(chat.getId(), userId, ChatMember.Role.MEMBER);
    }

    /** Чат удалён — чистим за собой: участников и выписанные приглашения. */
    @Transactional
    public void forgetChat(Long chatId) {
        memberRepository.deleteByChatId(chatId);
        inviteRepository.deleteByChatId(chatId);
    }
}
