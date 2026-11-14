package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки приложения в одном месте.
 *
 * До этого единого экрана настроек не было вовсе: уведомления нельзя было
 * приглушить, язык перевода выбирался заново в каждой переписке, а
 * офлайн-режим жил в выпадающем меню списка чатов. Значения лежат в тех же
 * «prefs», что и остальное про текущего человека.
 */
public final class AppSettings {

    private static final String PREFS = "prefs";

    private static final String KEY_MESSAGE_NOTIFICATIONS = "notifications_messages";
    private static final String KEY_NOTIFICATION_SOUND = "notifications_sound";
    private static final String KEY_DEFAULT_TRANSLATION = "default_translation_language";

    /** Язык перевода по умолчанию, пока в переписке не выбран свой. */
    public static final String AUTO = Languages.AUTO;

    private AppSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean messageNotifications(Context context) {
        return prefs(context).getBoolean(KEY_MESSAGE_NOTIFICATIONS, true);
    }

    public static void setMessageNotifications(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_MESSAGE_NOTIFICATIONS, on).apply();
    }

    public static boolean notificationSound(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFICATION_SOUND, true);
    }

    public static void setNotificationSound(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_SOUND, on).apply();
    }

    /**
     * Язык перевода по умолчанию. Переписка, где язык выбран руками, им и
     * пользуется — это значение только для тех, где выбора ещё не делали.
     */
    public static String defaultTranslationLanguage(Context context) {
        String saved = prefs(context).getString(KEY_DEFAULT_TRANSLATION, AUTO);
        return saved == null || saved.isEmpty() ? AUTO : saved;
    }

    public static void setDefaultTranslationLanguage(Context context, String code) {
        prefs(context).edit().putString(KEY_DEFAULT_TRANSLATION, code).apply();
    }

}
