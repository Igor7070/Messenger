package com.example.unlimitedpossibilities12.messenger.notife;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.unlimitedpossibilities12.messenger.PrivateChatActivity;
import com.example.unlimitedpossibilities12.messenger.SecretPrivateChatActivity;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/**
 * Push-уведомления (Firebase Cloud Messaging).
 *
 * Нужны потому, что Android усыпляет фоновые процессы: без push сообщения
 * доходят только пока жив CallService. Push будит приложение, даже если оно
 * закрыто.
 *
 * Сервер присылает только «от кого» и id чата — текста сообщения в push нет,
 * чтобы переписка не проходила через серверы Google. Содержимое приложение
 * забирает само, а в секретных чатах ещё и расшифровывает у себя.
 */
public class PushMessagingService extends FirebaseMessagingService {
    private static final String TAG = "PushMessaging";
    private static final String CHANNEL_ID = "MessagesChannel";
    private static final String PREFS = "push";
    private static final String KEY_TOKEN = "fcm_token";
    private static final String KEY_SENT_FOR_USER = "sent_for_user";
    private static final String API_BASE = "https://unlimitedpossibilities12.org/api/";

    @Override
    public void onNewToken(String token) {
        Log.d(TAG, "Новый токен устройства");
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_TOKEN, token)
                .remove(KEY_SENT_FOR_USER) // токен сменился — отправить заново
                .apply();
        sendTokenToServer(this);
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Map<String, String> data = remoteMessage.getData();
        Log.d(TAG, "Push получен: " + data);

        // ===== ВХОДЯЩИЙ ЗВОНОК =====
        // Поднимаем соединение и показываем экран вызова. Android разрешает
        // запустить службу из push высокого приоритета, даже когда приложение спало.
        if ("call".equals(data.get("type"))) {
            boolean group = "group".equals(data.get("callType"));
            Intent callIntent = new Intent(this, CallService.class);
            callIntent.putExtra("action", group ? "PUSH_INCOMING_GROUP_CALL" : "PUSH_INCOMING_CALL");
            if (group) {
                callIntent.putExtra("callerId", data.get("callerId"));
                callIntent.putExtra("callerName", data.get("callerName"));
                callIntent.putExtra("recipientIds", data.get("recipientIds"));
            } else {
                callIntent.putExtra("callerId", (int) parseLong(data.get("callerId"), -1));
            }
            callIntent.putExtra("roomId", data.get("roomId"));
            callIntent.putExtra("callType", data.get("callType"));
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(callIntent);
                } else {
                    startService(callIntent);
                }
            } catch (Exception e) {
                Log.w(TAG, "Не удалось поднять сервис для звонка: " + e.getMessage());
            }
            return;
        }

        // Сообщение: если соединения нет — поднимем его, чтобы текст пришёл и
        // отметился прочитанным; уведомление показываем сами.
        long chatId = parseLong(data.get("chatId"), -1);
        boolean secret = "true".equals(data.get("secret"));
        String title = data.get("title");
        String body = data.get("body");

        // Живо постоянное соединение — уведомление показывает CallService,
        // push тогда служит только «будильником»
        if (CallService.isSocketDelivering()) {
            Log.d(TAG, "Соединение живо — уведомление показывает CallService");
            return;
        }
        // Страховка от гонки: если сокет успел показать уведомление о том же
        // сообщении секундой раньше, второго не будет
        if (!CallService.markNotified("chat:" + chatId, 6000)) {
            Log.d(TAG, "Уведомление об этом сообщении уже показано");
            return;
        }
        // Чат открыт на экране — уведомление ни к чему
        if (isChatOpen(chatId)) {
            Log.d(TAG, "Чат " + chatId + " открыт — уведомление не показываем");
            return;
        }
        showNotification(chatId, secret,
                title != null ? title : "Messenger UP12",
                body != null ? body : "New message");
    }

    /** Открыт ли сейчас этот чат на экране — тогда уведомление не нужно. */
    private boolean isChatOpen(long chatId) {
        try {
            android.app.Activity current = ((com.example.unlimitedpossibilities12.api.App) getApplication()).getCurrentActivity();
            if (current instanceof com.example.unlimitedpossibilities12.messenger.PrivateChatActivity
                    || current instanceof com.example.unlimitedpossibilities12.messenger.SecretPrivateChatActivity) {
                return current.getIntent().getLongExtra("chatId", -1) == chatId;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void showNotification(long chatId, boolean secret, String title, String body) {
        createChannel();

        Intent intent = new Intent(this, secret ? SecretPrivateChatActivity.class : PrivateChatActivity.class);
        intent.putExtra("chatId", chatId);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, (int) chatId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(secret ? title + " · secret chat" : title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pendingIntent)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify((int) chatId, notification);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Messages",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("New messages while the app is closed");
            manager.createNotificationChannel(channel);
        }
    }

    // ---------- регистрация токена на нашем сервере ----------

    /** Запросить токен у Firebase и отправить на сервер (вызывать после входа). */
    public static void registerCurrentToken(Context context) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                Log.w(TAG, "Не удалось получить токен FCM", task.getException());
                return;
            }
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_TOKEN, task.getResult()).apply();
            sendTokenToServer(context);
        });
    }

    /**
     * Отправляет токен на бэкенд, если пользователь вошёл. Повторно для того же
     * пользователя не отправляем — токен уже сохранён на сервере.
     */
    public static void sendTokenToServer(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String token = prefs.getString(KEY_TOKEN, null);
        int userId = context.getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);
        if (token == null || userId <= 0) return;
        if (String.valueOf(userId).equals(prefs.getString(KEY_SENT_FOR_USER, null))) return;

        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("userId", String.valueOf(userId));
                json.put("token", token);
                json.put("platform", "android");
                OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
                Request request = new Request.Builder()
                        .url(API_BASE + "push/token")
                        .post(RequestBody.create(json.toString(), MediaType.parse("application/json")))
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        prefs.edit().putString(KEY_SENT_FOR_USER, String.valueOf(userId)).apply();
                        Log.d(TAG, "Токен зарегистрирован на сервере для userId=" + userId);
                    } else {
                        Log.w(TAG, "Сервер отклонил токен: " + response.code());
                    }
                }
            } catch (IOException | org.json.JSONException e) {
                Log.w(TAG, "Ошибка отправки токена: " + e.getMessage());
            }
        }).start();
    }

    /** Выход из аккаунта: уведомления не должны приходить следующему пользователю. */
    public static void unregister(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String token = prefs.getString(KEY_TOKEN, null);
        prefs.edit().remove(KEY_SENT_FOR_USER).apply();
        if (token == null) return;
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("token", token);
                OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
                Request request = new Request.Builder()
                        .url(API_BASE + "push/token")
                        .delete(RequestBody.create(json.toString(), MediaType.parse("application/json")))
                        .build();
                client.newCall(request).execute().close();
            } catch (Exception e) {
                Log.w(TAG, "Ошибка снятия токена: " + e.getMessage());
            }
        }).start();
    }

    private static long parseLong(String s, long fallback) {
        try {
            return s != null ? Long.parseLong(s) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
