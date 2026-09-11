package com.example.unl_pos12.config.messenger;

import com.example.unl_pos12.service.AuthTokenService;
import com.example.unl_pos12.service.AuthzService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверка токена и прав на WebSocket (STOMP).
 *
 *  - CONNECT: заголовок Authorization: Bearer <token> -> userId в атрибутах сессии.
 *  - SUBSCRIBE /topic/chat/{id}/… — только участник приватного/секретного чата;
 *    /topic/notifications/{userId} — только владелец.
 *  - SEND /app/sendMessage/chat/{id}, editMessage, deleteMessage, readMessage,
 *    typing — только участник чата.
 *
 * Режимы те же, что у HTTP: auth.enforce (нужен ли токен на CONNECT) и
 * auth.authz (log|enforce для прав). В режиме log нарушения только пишутся
 * строкой WS-AUTHZ-DENY / WS-AUTH-MISSING, соединение работает как раньше.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {
    private static final String ATTR_USER_ID = "wsUserId";
    private static final Pattern CHAT_TOPIC = Pattern.compile("^/topic/chat/(\\d+)(/.*)?$");
    private static final Pattern NOTIF_TOPIC = Pattern.compile("^/topic/notifications/(\\d+)$");
    private static final Pattern CHAT_APP = Pattern.compile("^/app/(?:sendMessage/chat|editMessage|deleteMessage|readMessage|typing)/(\\d+)$");

    private final AuthTokenService tokenService;
    private final AuthzService authz;
    private final boolean enforceToken;
    private final boolean enforceAuthz;

    public StompAuthInterceptor(AuthTokenService tokenService, AuthzService authz,
                                @Value("${auth.enforce:false}") boolean enforceToken,
                                @Value("${auth.authz:log}") String authzMode) {
        this.tokenService = tokenService;
        this.authz = authz;
        this.enforceToken = enforceToken;
        this.enforceAuthz = "enforce".equalsIgnoreCase(authzMode);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;
        Map<String, Object> session = accessor.getSessionAttributes();

        switch (accessor.getCommand()) {
            case CONNECT: {
                String header = accessor.getFirstNativeHeader("Authorization");
                String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7).trim() : null;
                Long userId = tokenService.verify(token);
                if (userId != null) {
                    if (session != null) session.put(ATTR_USER_ID, userId);
                } else {
                    // Без токена соединение НЕ рвём: до входа клиент слушает публичные
                    // топики (статусы пользователей, групповые чаты). Всё, что требует
                    // прав (приватные чаты, уведомления), режется на SUBSCRIBE/SEND ниже —
                    // там без userId в сессии доступ к таким топикам не даётся.
                    System.out.println("WS-AUTH-MISSING CONNECT" + (token == null ? " (no token)" : " (invalid token)"));
                }
                return message;
            }
            case SUBSCRIBE: {
                Long me = session == null ? null : (Long) session.get(ATTR_USER_ID);
                String dest = accessor.getDestination();
                if (dest == null) return message;
                Matcher m = CHAT_TOPIC.matcher(dest);
                if (m.matches()) {
                    long chatId = Long.parseLong(m.group(1));
                    // Без токена: групповые чаты можно (isChatMember для них true), приватные — нет
                    if (me == null ? !authz.isChatMember(-1L, chatId) : !authz.isChatMember(me, chatId)) {
                        return deny(message, "SUBSCRIBE " + dest, me);
                    }
                    return message;
                }
                m = NOTIF_TOPIC.matcher(dest);
                if (m.matches() && (me == null || Long.parseLong(m.group(1)) != me)) {
                    return deny(message, "SUBSCRIBE " + dest, me);
                }
                return message;
            }
            case SEND: {
                Long me = session == null ? null : (Long) session.get(ATTR_USER_ID);
                String dest = accessor.getDestination();
                if (dest == null) return message;
                Matcher m = CHAT_APP.matcher(dest);
                if (m.matches()) {
                    long chatId = Long.parseLong(m.group(1));
                    if (me == null || !authz.isChatMember(me, chatId)) return deny(message, "SEND " + dest, me);
                }
                return message;
            }
            default:
                return message;
        }
    }

    private Message<?> deny(Message<?> message, String what, Long me) {
        String line = "WS-AUTHZ-DENY " + what + " user=" + me;
        if (enforceAuthz) {
            System.out.println(line + " -> dropped");
            return null; // сообщение не доходит до брокера/контроллера
        }
        System.out.println(line + " (log only)");
        return message;
    }
}
