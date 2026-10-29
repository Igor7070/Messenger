package com.example.unlimitedpossibilities12.messenger.offline;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class OfflineChatStorage {

    private static final String PREF_NAME = "offline_chats";
    private final SharedPreferences prefs;
    private final Gson gson;

    public OfflineChatStorage(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        gson = new GsonBuilder()
                .registerTypeAdapter(Uri.class, new UriTypeAdapter())
                .setPrettyPrinting()           // для удобства отладки
                .create();
    }

    /**
     * Сохранение нового сообщения
     */
    public void saveMessage(String endpointId, OfflineMessage message) {
        List<OfflineMessage> chatHistory = getMessages(endpointId);
        chatHistory.add(message);

        String json = gson.toJson(chatHistory);
        prefs.edit().putString("chat_" + endpointId, json).apply();
    }

    /**
     * Получение всей истории чата
     */
    public List<OfflineMessage> getMessages(String endpointId) {
        String json = prefs.getString("chat_" + endpointId, null);
        if (json == null) return new ArrayList<>();

        Type type = new TypeToken<List<OfflineMessage>>(){}.getType();
        List<OfflineMessage> messages = gson.fromJson(json, type);

        // Защита от null
        if (messages == null) {
            return new ArrayList<>();
        }

        return messages;
    }

    // ====================== НОВЫЕ МЕТОДЫ (по messageId) ======================

    /**
     * Удаление сообщения по messageId
     */
    public void deleteMessage(String endpointId, String messageId) {
        if (messageId == null) return;

        List<OfflineMessage> chatHistory = getMessages(endpointId);

        boolean removed = chatHistory.removeIf(msg ->
                messageId.equals(msg.getMessageId())
        );

        if (removed) {
            String json = gson.toJson(chatHistory);
            prefs.edit().putString("chat_" + endpointId, json).apply();
        }
    }

    /**
     * Обновление сообщения (редактирование)
     */
    public void updateMessage(String endpointId, OfflineMessage updatedMessage) {
        if (updatedMessage == null || updatedMessage.getMessageId() == null) return;

        List<OfflineMessage> chatHistory = getMessages(endpointId);
        String targetId = updatedMessage.getMessageId();

        for (int i = 0; i < chatHistory.size(); i++) {
            if (targetId.equals(chatHistory.get(i).getMessageId())) {
                chatHistory.set(i, updatedMessage);
                break;
            }
        }

        String json = gson.toJson(chatHistory);
        prefs.edit().putString("chat_" + endpointId, json).apply();
    }

    /**
     * Переносит историю со старого endpointId на новый. Nearby выдаёт новый
     * идентификатор соединения при каждом подключении, поэтому после
     * переподключения переписка должна «переехать», а не начаться заново.
     */
    public void mergeChat(String oldEndpointId, String newEndpointId) {
        if (oldEndpointId == null || oldEndpointId.equals(newEndpointId)) return;
        List<OfflineMessage> oldMessages = getMessages(oldEndpointId);
        if (oldMessages.isEmpty()) return;
        List<OfflineMessage> merged = getMessages(newEndpointId);
        for (OfflineMessage m : oldMessages) {
            boolean dup = false;
            for (OfflineMessage e : merged) {
                if (m.getMessageId() != null && m.getMessageId().equals(e.getMessageId())) { dup = true; break; }
            }
            if (!dup) merged.add(m);
        }
        merged.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));
        prefs.edit()
                .putString("chat_" + newEndpointId, gson.toJson(merged))
                .remove("chat_" + oldEndpointId)
                .apply();
    }

    /** Меняет статус своего сообщения (в очереди / отправлено / доставлено). */
    public boolean updateStatus(String endpointId, String messageId, int status) {
        if (messageId == null) return false;
        List<OfflineMessage> chatHistory = getMessages(endpointId);
        for (OfflineMessage m : chatHistory) {
            if (messageId.equals(m.getMessageId())) {
                if (m.getStatus() >= status) return false; // статус только растёт
                m.setStatus(status);
                prefs.edit().putString("chat_" + endpointId, gson.toJson(chatHistory)).apply();
                return true;
            }
        }
        return false;
    }

    /**
     * Полностью очистить чат
     */
    public void clearChat(String endpointId) {
        prefs.edit().remove("chat_" + endpointId).apply();
    }

    // ====================== Адаптер для Uri ======================
    private static class UriTypeAdapter extends TypeAdapter<Uri> {
        @Override
        public void write(JsonWriter out, Uri value) throws IOException {
            out.value(value != null ? value.toString() : null);
        }

        @Override
        public Uri read(JsonReader in) throws IOException {
            String uriString = in.nextString();
            return uriString != null ? Uri.parse(uriString) : null;
        }
    }
}
