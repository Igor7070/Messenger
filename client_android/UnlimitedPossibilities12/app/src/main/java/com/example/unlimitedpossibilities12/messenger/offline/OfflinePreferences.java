package com.example.unlimitedpossibilities12.messenger.offline;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public class OfflinePreferences {

    private static final String PREF_NAME = "offline_preferences";

    private static final String KEY_NICKNAME = "user_nickname";
    private static final String KEY_AVATAR_URI = "user_avatar_uri";   // ← Новое

    private final SharedPreferences prefs;

    public OfflinePreferences(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ====================== НИКНЕЙМ ======================

    public String getNickname() {
        String defaultName = android.os.Build.MODEL;
        return prefs.getString(KEY_NICKNAME, defaultName);
    }

    public void setNickname(String nickname) {
        prefs.edit().putString(KEY_NICKNAME, nickname).apply();
    }

    // ====================== АВАТАР ======================

    public Uri getAvatarUri() {
        String uriString = prefs.getString(KEY_AVATAR_URI, null);
        return uriString != null ? Uri.parse(uriString) : null;
    }

    public void setAvatarUri(Uri avatarUri) {
        if (avatarUri != null) {
            prefs.edit().putString(KEY_AVATAR_URI, avatarUri.toString()).apply();
        } else {
            prefs.edit().remove(KEY_AVATAR_URI).apply();
        }
    }

    // ====================== MESH ======================

    /** Пересылка через соседние телефоны (по умолчанию выключена — прямой режим как раньше). */
    public boolean isMeshEnabled() { return prefs.getBoolean("mesh_enabled", false); }
    public void setMeshEnabled(boolean enabled) { prefs.edit().putBoolean("mesh_enabled", enabled).apply(); }

    // ====================== ДОПОЛНИТЕЛЬНО ======================

    public void clearAvatar() {
        prefs.edit().remove(KEY_AVATAR_URI).apply();
    }

    // Очистка всех настроек (если понадобится)
    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
