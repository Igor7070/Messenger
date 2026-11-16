package com.example.unlimitedpossibilities12.messenger.notife;

import android.app.Activity;

/**
 * Идущий разговор — то, что нужно знать остальным экранам приложения.
 *
 * Пока шёл звонок, выйти из него можно было только кнопкой «свернуть»: по
 * «назад» экран разговора закрывался, а вместе с ним обрывался и сам звонок.
 * Теперь экран остаётся жив позади, а по приложению за ним едет полоска
 * сверху — как в телеграме. Эти поля и говорят ей, чей идёт разговор и куда
 * возвращаться по нажатию.
 *
 * Заполняет их сам экран звонка, когда появляется, и очищает, когда уходит,
 * поэтому полоска живёт ровно столько, сколько жив разговор.
 */
public final class OngoingCall {

    private static volatile Class<? extends Activity> screen;
    private static volatile String partnerName;
    private static volatile long startedAt;

    private OngoingCall() {}

    /** Разговор начался: показываем полоску и знаем, куда по ней возвращаться. */
    public static void start(Class<? extends Activity> callScreen, String name) {
        screen = callScreen;
        partnerName = name;
        startedAt = 0;
    }

    /** Собеседники услышали друг друга — с этой минуты идёт отсчёт времени. */
    public static void connected() {
        if (startedAt == 0) startedAt = System.currentTimeMillis();
    }

    /** Разговор закончился. */
    public static void clear() {
        screen = null;
        partnerName = null;
        startedAt = 0;
    }

    public static boolean isActive() {
        return screen != null;
    }

    public static Class<? extends Activity> screen() {
        return screen;
    }

    public static String partnerName() {
        return partnerName;
    }

    /** Когда начался разговор, 0 — пока идёт соединение. */
    public static long startedAt() {
        return startedAt;
    }
}
