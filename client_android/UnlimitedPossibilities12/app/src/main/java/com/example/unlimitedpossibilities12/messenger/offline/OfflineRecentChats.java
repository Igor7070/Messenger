package com.example.unlimitedpossibilities12.messenger.offline;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class OfflineRecentChats {

    private static final String PREF_NAME = "offline_recent_chats";
    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public OfflineRecentChats(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void addRecentChat(String endpointId, String name) {
        List<RecentChat> chats = getRecentChats();

        // Убираем дубликат, если есть
        chats.removeIf(chat -> chat.endpointId.equals(endpointId));

        chats.add(0, new RecentChat(endpointId, name)); // Новый сверху

        // Ограничиваем до 20 последних
        if (chats.size() > 20) chats = chats.subList(0, 20);

        String json = gson.toJson(chats);
        prefs.edit().putString("recent_chats", json).apply();
    }

    /** Убирает собеседника из списка недавних (история сообщений не трогается). */
    /** Собеседник переподключился под новым endpointId — обновляем запись. */
    public void replaceEndpoint(String oldEndpointId, String newEndpointId, String name) {
        replaceEndpoint(oldEndpointId, newEndpointId, name, null);
    }

    public void replaceEndpoint(String oldEndpointId, String newEndpointId, String name, String peerId) {
        List<RecentChat> chats = getRecentChats();
        boolean changed = false;
        for (java.util.Iterator<RecentChat> it = chats.iterator(); it.hasNext(); ) {
            RecentChat c = it.next();
            if (c.endpointId.equals(newEndpointId)) { it.remove(); changed = true; }
            else if (c.endpointId.equals(oldEndpointId)) {
                c.endpointId = newEndpointId;
                c.name = name;
                if (peerId != null) c.peerId = peerId;
                changed = true;
            }
        }
        if (changed) prefs.edit().putString("recent_chats", gson.toJson(chats)).apply();
    }

    /** Запомнить постоянный идентификатор у записи (узнаётся при соединении). */
    public void setPeerId(String endpointId, String peerId) {
        if (peerId == null) return;
        List<RecentChat> chats = getRecentChats();
        boolean changed = false;
        for (RecentChat c : chats) {
            if (c.endpointId.equals(endpointId) && !peerId.equals(c.peerId)) { c.peerId = peerId; changed = true; }
        }
        if (changed) prefs.edit().putString("recent_chats", gson.toJson(chats)).apply();
    }

    public void removeRecentChat(String endpointId) {
        List<RecentChat> chats = getRecentChats();
        chats.removeIf(chat -> chat.endpointId.equals(endpointId));
        prefs.edit().putString("recent_chats", gson.toJson(chats)).apply();
    }

    public List<RecentChat> getRecentChats() {
        String json = prefs.getString("recent_chats", null);
        if (json == null) return new ArrayList<>();

        Type type = new TypeToken<List<RecentChat>>(){}.getType();
        List<RecentChat> chats = gson.fromJson(json, type);
        return chats != null ? chats : new ArrayList<>();
    }

    public static class RecentChat {
        public String endpointId;
        public String name;
        /** Постоянный идентификатор собеседника (ключ устройства); null у старых записей. */
        public String peerId;

        public RecentChat(String endpointId, String name) {
            this.endpointId = endpointId;
            this.name = name;
        }
    }
}