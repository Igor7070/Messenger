package com.example.unlimitedpossibilities12.messenger.offline;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Очередь исходящих сообщений офлайн-чата.
 *
 * Если собеседника в момент отправки не видно (вышел из радиуса, выключил
 * видимость, ещё не соединились), сообщение не теряется, а ложится сюда и
 * уходит само, как только он станет доступен. Очередь переживает закрытие
 * приложения.
 *
 * Ключ — endpointId собеседника: для узлов сети он стабилен (mesh:<nodeId>),
 * для прямых соседей Nearby выдаёт новый при каждом соединении, поэтому там
 * очередь работает в пределах сеанса.
 */
public final class OfflineOutbox {
    private static final String PREF_NAME = "offline_outbox";
    private static final String KEY = "queue";
    /** Сколько держать неотправленное, прежде чем выбросить (сутки). */
    private static final long TTL_MS = 24L * 60 * 60 * 1000;

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public OfflineOutbox(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static final class Item {
        public String endpointId;
        public String messageId;
        public String payload;   // строка ровно в том виде, в каком её ждёт собеседник
        public long queuedAt;

        Item(String endpointId, String messageId, String payload) {
            this.endpointId = endpointId;
            this.messageId = messageId;
            this.payload = payload;
            this.queuedAt = System.currentTimeMillis();
        }
    }

    public synchronized List<Item> all() {
        String json = prefs.getString(KEY, null);
        if (json == null) return new ArrayList<>();
        try {
            Type type = new TypeToken<List<Item>>() {}.getType();
            List<Item> list = gson.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public synchronized void add(String endpointId, String messageId, String payload) {
        List<Item> list = all();
        for (Item i : list) if (messageId.equals(i.messageId)) return; // уже в очереди
        list.add(new Item(endpointId, messageId, payload));
        save(list);
    }

    public synchronized void remove(String messageId) {
        List<Item> list = all();
        if (list.removeIf(i -> messageId.equals(i.messageId))) save(list);
    }

    /** Просроченное выбрасываем, чтобы очередь не росла бесконечно. */
    public synchronized List<Item> pending() {
        List<Item> list = all();
        long now = System.currentTimeMillis();
        boolean changed = list.removeIf(i -> now - i.queuedAt > TTL_MS);
        if (changed) save(list);
        return list;
    }

    /** Переносит очередь со старого идентификатора соединения на новый. */
    public synchronized void reassign(String oldEndpointId, String newEndpointId) {
        if (oldEndpointId == null || oldEndpointId.equals(newEndpointId)) return;
        List<Item> list = all();
        boolean changed = false;
        for (Item i : list) {
            if (oldEndpointId.equals(i.endpointId)) { i.endpointId = newEndpointId; changed = true; }
        }
        if (changed) save(list);
    }

    public synchronized boolean isQueued(String messageId) {
        for (Item i : all()) if (messageId.equals(i.messageId)) return true;
        return false;
    }

    private void save(List<Item> list) {
        prefs.edit().putString(KEY, gson.toJson(list)).apply();
    }
}
