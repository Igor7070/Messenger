package com.example.unl_pos12.controller.messenger;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Страница приглашения в группу.
 *
 * Прежде приглашение выглядело как https://igor7070.github.io/Messenger/#/invite/КОД —
 * код стоял после решётки, в «хвосте» адреса. Андроид по хвосту приложение не
 * ищет, поэтому нажатие на такую ссылку открывало браузер, а человеку
 * приходилось копировать её и вставлять в приложении руками.
 *
 * Теперь приглашение ведёт на наш домен, где код — часть пути. По такому адресу
 * телефон открывает приложение; если приложения нет, эта страница отправляет
 * человека в веб-клиент, в тот же самый разговор.
 */
@RestController
public class InviteLinkController {

    private static final String WEB_CLIENT = "https://igor7070.github.io/Messenger/#/invite/";

    @GetMapping(value = "/invite/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> invitePage(@PathVariable String token) {
        String safe = token.replaceAll("[^A-Za-z0-9_-]", "");
        String target = WEB_CLIENT + safe;
        String html = "<!doctype html><html lang=\"en\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Invitation to a group</title>"
                + "<meta http-equiv=\"refresh\" content=\"0; url=" + target + "\">"
                + "<style>body{margin:0;display:flex;align-items:center;justify-content:center;"
                + "min-height:100vh;background:#0F172A;color:#E2E8F0;"
                + "font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif}"
                + "a{color:#67E8F9}</style></head><body>"
                + "<p>Opening the invitation… <a href=\"" + target + "\">Continue</a></p>"
                + "</body></html>";
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(html);
    }

    /**
     * Подтверждение для андроида, что домен и приложение — одни руки. С ним
     * ссылка открывается прямо в приложении, без вопроса «чем открыть».
     * Отпечаток подписи задаётся переменной окружения ANDROID_CERT_SHA256
     * (несколько — через запятую): у отладочной и у выкладываемой сборки он
     * разный, и держать их в коде ни к чему.
     */
    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> assetLinks() {
        String raw = System.getenv("ANDROID_CERT_SHA256");
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.ok("[]");
        }
        StringBuilder prints = new StringBuilder();
        for (String print : raw.split(",")) {
            String value = print.trim();
            if (value.isEmpty()) continue;
            if (prints.length() > 0) prints.append(",");
            prints.append("\"").append(value).append("\"");
        }
        String json = "[{\"relation\":[\"delegate_permission/common.handle_all_urls\"],"
                + "\"target\":{\"namespace\":\"android_app\","
                + "\"package_name\":\"org.unlimitedpossibilities12.messenger\","
                + "\"sha256_cert_fingerprints\":[" + prints + "]}}]";
        return ResponseEntity.ok(json);
    }
}
