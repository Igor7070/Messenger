package com.example.unl_pos12.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Токены доступа к API: «userId.expiresAt.HMAC-SHA256(userId.expiresAt)».
 *
 * Без внешних библиотек и без состояния на сервере: подпись проверяется
 * секретом из переменной окружения AUTH_SECRET. Клиенты получают токен при
 * логине/регистрации и шлют его в заголовке Authorization: Bearer ...
 *
 * Если AUTH_SECRET не задан, используется запасное значение — оно годится
 * только для локального запуска; на Railway переменную нужно задать, иначе
 * токены можно подделать, зная исходники.
 */
@Service
public class AuthTokenService {
    /** Срок жизни токена: 30 дней. */
    private static final long TOKEN_TTL_MS = 30L * 24 * 60 * 60 * 1000;

    private final byte[] secret;

    public AuthTokenService(@Value("${auth.secret:}") String configuredSecret) {
        String s = (configuredSecret == null || configuredSecret.isEmpty())
                ? "dev-only-secret-change-me-on-railway"
                : configuredSecret;
        if (configuredSecret == null || configuredSecret.isEmpty()) {
            System.err.println("WARNING: AUTH_SECRET is not set, using an insecure default token secret");
        }
        this.secret = s.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(long userId) {
        long expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS;
        String payload = userId + "." + expiresAt;
        return payload + "." + sign(payload);
    }

    /** userId из валидного токена или null, если токен пустой, подделан или просрочен. */
    public Long verify(String token) {
        if (token == null || token.isEmpty()) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        try {
            long userId = Long.parseLong(parts[0]);
            long expiresAt = Long.parseLong(parts[1]);
            if (expiresAt < System.currentTimeMillis()) return null;
            String expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            return userId;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
