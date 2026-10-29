package com.example.unlimitedpossibilities12.messenger;

import org.json.JSONObject;

/**
 * Формат открытого текста секретного сообщения с переводом.
 *
 * Внутри шифртекста может лежать либо просто строка («привет»), либо
 * JSON-обёртка {"text":"привет","translated":"hello","lang":"en"} — когда
 * отправитель перевёл сообщение у себя на устройстве до шифрования.
 * Тот же формат читает и пишет веб-клиент. Старые клиенты, не знающие
 * обёртки, покажут JSON как есть — поэтому обёртка используется только
 * когда перевод действительно включён.
 */
public final class SecretText {
    public final String text;        // оригинал
    public final String translated;  // перевод отправителя или null
    public final String lang;        // язык перевода (код ML Kit / BCP-47) или null

    private SecretText(String text, String translated, String lang) {
        this.text = text;
        this.translated = translated;
        this.lang = lang;
    }

    /** Разбирает содержимое: JSON-обёртка или обычная строка. */
    public static SecretText parse(String content) {
        if (content == null) return new SecretText("", null, null);
        String s = content.trim();
        if (s.startsWith("{") && s.endsWith("}") && s.contains("\"text\"")) {
            try {
                JSONObject o = new JSONObject(s);
                if (o.has("text")) {
                    String tr = o.optString("translated", null);
                    return new SecretText(o.getString("text"),
                            tr == null || tr.isEmpty() ? null : tr,
                            o.optString("lang", null));
                }
            } catch (Exception ignored) {
                // не наш JSON — показываем как обычный текст
            }
        }
        return new SecretText(content, null, null);
    }

    /** Собирает обёртку для отправки. */
    public static String wrap(String text, String translated, String lang) {
        try {
            JSONObject o = new JSONObject();
            o.put("text", text);
            o.put("translated", translated);
            o.put("lang", lang);
            return o.toString();
        } catch (Exception e) {
            return text;
        }
    }
}
