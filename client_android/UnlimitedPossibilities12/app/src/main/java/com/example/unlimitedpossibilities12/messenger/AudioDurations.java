package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Длительность голосовых записей, однажды измеренная.
 *
 * Без этого каждая привязка пузыря начиналась с многоточия: плеер заново
 * качал запись, чтобы узнать длину, и время подмигивало при каждом входе в
 * чат и при прокрутке. Длина записи не меняется, поэтому запоминаем её по
 * адресу файла и показываем сразу.
 */
public final class AudioDurations {

    private static final String STORE = "audio_durations";

    private static final Map<String, Integer> MEMORY = new ConcurrentHashMap<>();

    private AudioDurations() {
    }

    /** Длительность в миллисекундах или 0, если ещё не знаем. */
    public static int get(Context context, String url) {
        if (url == null || url.isEmpty()) return 0;

        Integer remembered = MEMORY.get(url);
        if (remembered != null) return remembered;

        int saved = store(context).getInt(url, 0);
        if (saved > 0) MEMORY.put(url, saved);
        return saved;
    }

    /** Запомнить измеренную длительность. */
    public static void put(Context context, String url, int durationMs) {
        if (url == null || url.isEmpty() || durationMs <= 0) return;
        MEMORY.put(url, durationMs);
        store(context).edit().putInt(url, durationMs).apply();
    }

    /** Запись пропала — забываем и её длину. */
    public static void forget(Context context, String url) {
        if (url == null || url.isEmpty()) return;
        MEMORY.remove(url);
        store(context).edit().remove(url).apply();
    }

    private static SharedPreferences store(Context context) {
        return context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }
}
