package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.ChatInvite;
import com.example.unl_pos12.service.ChatMemberService;
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
    private static final String SITE = "https://unlimitedpossibilities12.org";

    private final ChatMemberService chatMembers;

    public InviteLinkController(ChatMemberService chatMembers) {
        this.chatMembers = chatMembers;
    }

    @GetMapping(value = "/invite/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> invitePage(@PathVariable String token) {
        String safe = token.replaceAll("[^A-Za-z0-9_-]", "");
        String target = WEB_CLIENT + safe;

        // Карточка ссылки. Прежде страница не говорила о себе ничего, и в
        // переписке приглашение выглядело голой строкой адреса с тёмной
        // плашкой «Invitation to a group» под ней. Теперь страница называет
        // группу по имени, показывает её снимок и число участников — ровно
        // то, что увидит человек, нажав ссылку.
        String title = "Invitation to a group";
        String description = "Join the conversation in Messenger UP12";
        String image = SITE + "/favicon.ico";
        try {
            ChatInvite invite = chatMembers.findInvite(safe);
            Chat chat = invite != null ? chatMembers.chatOfInvite(invite) : null;
            if (chat != null) {
                title = chat.getName() != null ? chat.getName() : title;
                int members = chatMembers.members(chat.getId()).size();
                String kind = chatMembers.isPublicGroup(chat) ? "Public group" : "Private group";
                description = kind + " · " + members + (members == 1 ? " member" : " members");
                if (chat.getAvatar() != null && !chat.getAvatar().isBlank()) {
                    image = SITE + chat.getAvatar();
                }
            }
        } catch (Exception ignored) {
            // Приглашение могли отозвать — страница всё равно должна открыться
        }

        String html = "<!doctype html><html lang=\"en\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + escape(title) + "</title>"
                + "<meta property=\"og:site_name\" content=\"Messenger UP12\">"
                + "<meta property=\"og:type\" content=\"website\">"
                + "<meta property=\"og:title\" content=\"" + escape(title) + "\">"
                + "<meta property=\"og:description\" content=\"" + escape(description) + "\">"
                + "<meta property=\"og:image\" content=\"" + escape(image) + "\">"
                + "<meta name=\"twitter:card\" content=\"summary\">"
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

    /** Имя группы попадает в разметку, поэтому кавычки и скобки обезвреживаем. */
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
