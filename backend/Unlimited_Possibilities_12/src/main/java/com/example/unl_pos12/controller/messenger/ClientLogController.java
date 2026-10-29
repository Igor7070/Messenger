package com.example.unl_pos12.controller.messenger;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Приём логов веб-клиента (ошибки, предупреждения, необработанные исключения).
 * Пишутся в stdout сервиса строкой «CLIENT-LOG [web] …», чтобы их можно было
 * читать через `railway logs` вместе с логами бэкенда, не копируя консоль
 * браузера вручную. Режим отладки; в проде отключается флагом на клиенте.
 */
@RestController
@RequestMapping("/api/client-log")
public class ClientLogController {
    private static final int MAX_LINE = 2000;
    private static final int MAX_BATCH = 50;

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody Map<String, Object> body) {
        Object userId = body.getOrDefault("userId", "-");
        Object page = body.getOrDefault("page", "-");
        Object entries = body.get("entries");
        if (!(entries instanceof List)) {
            return ResponseEntity.badRequest().build();
        }
        int n = 0;
        for (Object e : (List<?>) entries) {
            if (n++ >= MAX_BATCH) break;
            if (!(e instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) e;
            Object lv = m.get("level"); String level = String.valueOf(lv == null ? "log" : lv).toUpperCase();
            Object tx = m.get("text"); String text = String.valueOf(tx == null ? "" : tx);
            if (text.length() > MAX_LINE) text = text.substring(0, MAX_LINE) + "…";
            text = text.replace("\r", " ").replace("\n", " ⏎ ");
            System.out.println("CLIENT-LOG [web] user=" + userId + " page=" + page + " " + level + ": " + text);
        }
        return ResponseEntity.ok().build();
    }
}
