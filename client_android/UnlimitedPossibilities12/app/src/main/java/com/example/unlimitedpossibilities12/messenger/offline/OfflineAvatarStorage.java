package com.example.unlimitedpossibilities12.messenger.offline;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class OfflineAvatarStorage {

    private static final String TAG = "AvatarStorage";
    private static final String PREF_NAME = "offline_avatars";

    private final Context context;
    private final SharedPreferences prefs;

    public OfflineAvatarStorage(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Сохраняет аватар из URI (для полученных аватаров)
     */
    public void saveAvatar(String endpointId, Uri avatarUri) {
        if (avatarUri != null) {
            prefs.edit().putString(endpointId, avatarUri.toString()).apply();
            Log.d(TAG, "Аватар сохранён по URI для " + endpointId);
        }
    }

    /**
     * Подготавливает аватар для отправки (копирует в кэш и возвращает file:// URI)
     * Используется при отправке своего аватара
     */
    public Uri prepareAvatarForSending(Uri originalUri) {
        if (originalUri == null) return null;

        try {
            File cacheFile = new File(context.getCacheDir(), "my_avatar_send.jpg");

            try (InputStream is = context.getContentResolver().openInputStream(originalUri);
                 FileOutputStream fos = new FileOutputStream(cacheFile)) {

                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
            }

            Log.d(TAG, "Аватар подготовлен для отправки: " + cacheFile.length() + " байт");
            return Uri.fromFile(cacheFile);

        } catch (Exception e) {
            Log.e(TAG, "Ошибка подготовки аватара для отправки", e);
            return null;
        }
    }

    /**
     * Получить URI аватара
     */
    public Uri getAvatar(String endpointId) {
        String uriStr = prefs.getString(endpointId, null);
        if (uriStr != null) {
            Uri uri = Uri.parse(uriStr);
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                if (!file.exists()) {
                    Log.w(TAG, "Файл аватара не найден: " + uri.getPath());
                    return null;
                }
            }
            return uri;
        }
        return null;
    }

    /**
     * Удалить аватар
     */
    public void removeAvatar(String endpointId) {
        Uri uri = getAvatar(endpointId);
        if (uri != null && "file".equals(uri.getScheme())) {
            File file = new File(uri.getPath());
            if (file.exists()) {
                file.delete();
            }
        }

        prefs.edit().remove(endpointId).apply();
        Log.d(TAG, "Аватар удалён для " + endpointId);
    }

    /**
     * Очистить всё
     */
    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
