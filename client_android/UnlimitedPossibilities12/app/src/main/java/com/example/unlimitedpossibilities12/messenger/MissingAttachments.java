package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Вложения, которых больше нет на сервере.
 *
 * Файлы пропадают при переразвёртывании, а ссылка на них остаётся в сообщении.
 * Без памяти об этом каждый вход в чат выглядел одинаково: серая заглушка
 * картинки на мгновение появляется, сервер отвечает ошибкой — и она исчезает,
 * уступая место строке «Image unavailable». Запоминаем такие адреса и больше
 * не занимаем ими место. Через сутки пробуем снова: файл могут вернуть.
 */
public final class MissingAttachments {

    private static final String STORE = "missing_attachments";
    private static final long RECHECK_MS = 24L * 60 * 60 * 1000;

    private static final Map<String, Long> MEMORY = new ConcurrentHashMap<>();

    private MissingAttachments() {
    }

    /** Известно ли, что файла по этому адресу нет. */
    public static boolean isMissing(Context context, String url) {
        if (url == null || url.isEmpty()) return false;

        Long remembered = MEMORY.get(url);
        if (remembered == null) {
            long saved = store(context).getLong(url, 0);
            if (saved == 0) return false;
            remembered = saved;
            MEMORY.put(url, saved);
        }

        if (System.currentTimeMillis() - remembered > RECHECK_MS) {
            forget(context, url);
            return false;
        }
        return true;
    }

    /** Запомнить, что файл не загрузился. */
    public static void remember(Context context, String url) {
        if (url == null || url.isEmpty()) return;
        long now = System.currentTimeMillis();
        MEMORY.put(url, now);
        store(context).edit().putLong(url, now).apply();
    }

    /** Файл загрузился — забыть прежнюю неудачу. */
    public static void forget(Context context, String url) {
        if (url == null || url.isEmpty()) return;
        if (MEMORY.remove(url) == null && !store(context).contains(url)) return;
        store(context).edit().remove(url).apply();
    }

    private static SharedPreferences store(Context context) {
        return context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }
}
