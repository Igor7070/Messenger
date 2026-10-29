package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.service.AuthzService;
import com.example.unl_pos12.service.PushService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Регистрация устройств для push-уведомлений.
 *
 * Токен привязывается к пользователю, от имени которого пришёл запрос, —
 * подменить чужой id нельзя (проверка в AuthzService).
 */
@RestController
@RequestMapping("/api/push")
public class PushTokenController {

    @Autowired
    private PushService pushService;
    @Autowired
    private AuthzService authz;

    @PostMapping("/token")
    public ResponseEntity<Void> register(@RequestBody Map<String, String> body, HttpServletRequest http) {
        String userIdRaw = body.get("userId");
        String token = body.get("token");
        if (userIdRaw == null || token == null) return ResponseEntity.badRequest().build();
        Long userId;
        try {
            userId = Long.parseLong(userIdRaw);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        }
        authz.requireSelf(http, userId); // токен можно регистрировать только себе
        pushService.register(userId, token, body.getOrDefault("platform", "android"));
        return ResponseEntity.ok().build();
    }

    /** Выход из аккаунта на устройстве — чтобы уведомления не приходили следующему. */
    @DeleteMapping("/token")
    public ResponseEntity<Void> unregister(@RequestBody Map<String, String> body) {
        pushService.unregister(body.get("token"));
        return ResponseEntity.ok().build();
    }
}
