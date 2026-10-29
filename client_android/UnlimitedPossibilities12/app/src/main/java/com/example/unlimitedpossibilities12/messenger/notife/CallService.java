package com.example.unlimitedpossibilities12.messenger.notife;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.os.Vibrator;
import android.os.VibrationEffect;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.App;
import com.example.unlimitedpossibilities12.api.CallRequest;
import com.example.unlimitedpossibilities12.api.GroupCallRequest;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.GroupCallActivity;
import com.example.unlimitedpossibilities12.messenger.MessengerMainActivity;
import com.example.unlimitedpossibilities12.messenger.PersonalChatsActivity;
import com.example.unlimitedpossibilities12.messenger.PrivateChatActivity;
import com.example.unlimitedpossibilities12.messenger.SecretPrivateChatActivity;
import com.example.unlimitedpossibilities12.messenger.UserProfileActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.SimpleDateFormat;
import java.util.Date;

import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import ua.naiksoftware.stomp.Stomp;
import ua.naiksoftware.stomp.StompClient;
import ua.naiksoftware.stomp.dto.StompHeader;
import okhttp3.ResponseBody;

public class CallService extends Service {
    private static final String TAG = "CallService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "CallServiceChannel";

    /** Соединение поднялось — экранам чата пора перечитать клиента. */
    public static final String ACTION_SOCKET_READY = "com.example.unlimitedpossibilities12.SOCKET_READY";

    /**
     * Ссылка на живой сервис — чтобы push знал, доставляются ли сообщения по
     * постоянному соединению. Если да, уведомление показывает сервис, и push
     * своё не рисует: иначе на одно сообщение приходило два уведомления.
     */
    private static volatile CallService activeInstance;

    /** Идёт ли сейчас звонок — тогда сервис не останавливаем. */
    private static volatile boolean callInProgress;
    /** Сервис уничтожен: отложенные задачи должны это видеть. */
    private volatile boolean serviceDestroyed;
    /** Соединение открыто: у STOMP-клиента isConnected() в момент события ещё false. */
    private volatile boolean socketOpen;
    /** Чаты, на которые попросили подписаться до появления соединения. */
    private final java.util.Set<Long> pendingChatSubscriptions =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    public static boolean isCallInProgress() { return callInProgress; }

    public static void setCallInProgress(boolean inProgress) {
        callInProgress = inProgress;
        if (!inProgress) {
            // Экран звонка закрывается раньше, чем снимается эта отметка, поэтому
            // к моменту ухода приложения в фон сервис считал звонок активным и
            // оставался работать (зелёный индикатор не гас). Проверяем ещё раз.
            CallService service = activeInstance;
            if (service != null) {
                new Handler(Looper.getMainLooper()).postDelayed(service::stopIfIdleInBackground, 1500);
            }
        }
    }

    /** Звонок закончен и приложение не на экране — соединение держать незачем. */
    private void stopIfIdleInBackground() {
        if (callInProgress || serviceDestroyed) return;
        try {
            if (((App) getApplication()).isAppInForeground()) return;
            if (!com.example.unlimitedpossibilities12.messenger.notife.PushAvailability.isAvailable(this)) return;
            Log.d(TAG, "Звонок завершён, приложение в фоне — останавливаем сервис");
            sendOfflineStatus();
            stopHeartbeat();
            stopSelf();
        } catch (Exception e) {
            Log.w(TAG, "stopIfIdleInBackground: " + e.getMessage());
        }
    }

    /**
     * Уведомление про это событие уже показано? Общая защита для двух каналов:
     * сообщение приходит и по сокету, и push-ом, а уведомление нужно одно.
     */
    private static final java.util.Map<String, Long> recentNotifications = new java.util.concurrent.ConcurrentHashMap<>();

    /** Снять отметку (звонок завершился — следующий должен проходить сразу). */
    public static void clearNotified(String key) {
        recentNotifications.remove(key);
    }

    public static boolean markNotified(String key, long windowMs) {
        long now = System.currentTimeMillis();
        recentNotifications.entrySet().removeIf(e -> now - e.getValue() > 120000);
        Long last = recentNotifications.get(key);
        if (last != null && now - last < windowMs) return false; // уже показывали
        recentNotifications.put(key, now);
        return true;
    }

    public static boolean isSocketDelivering() {
        CallService s = activeInstance;
        return s != null && s.stompClient != null && s.stompClient.isConnected();
    }
    private static final String ACTION_SHOW_CALL_DIALOG = "com.example.unlimitedpossibilities12.SHOW_CALL_DIALOG";
    private static final String ACTION_NEW_MESSAGE = "com.example.unlimitedpossibilities12.NEW_MESSAGE";
    private static final String ACTION_NEW_NOTIFICATION = "com.example.unlimitedpossibilities12.NEW_NOTIFICATION";
    private static final int MAX_RECONNECT_DELAY_MS = 30000;
    private StompClient stompClient;
    private ApiService apiService;
    private int currentUserId = -1;
    private MediaPlayer callMediaPlayer;
    private MediaPlayer notificationMediaPlayer;
    private final IBinder binder = new LocalBinder();
    private final Set<Long> subscribedChatIds = new HashSet<>();
    private final List<Disposable> messageSubscriptions = new ArrayList<>();
    private Disposable typingSubscription;  // Отдельная подписка на typing, чтобы можно было отписаться
    private boolean isReconnecting = false;
    private boolean isCallSoundPlaying = false;
    private boolean hasSentAcceptCall = false;
    private int reconnectAttempt = 0;
    private Handler heartbeatHandler;
    private Runnable heartbeatRunnable;

    public class LocalBinder extends Binder {
        public CallService getService() {
            return CallService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        Log.d(TAG, "CallService onCreate");
        apiService = ApiClient.getApiService();
        currentUserId = getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);
        if (currentUserId != -1) {
            setupWebSocket();
            startHeartbeat();
            sendOnlineStatus();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (checkSelfPermission("android.permission.MANAGE_OWN_CALLS") == PackageManager.PERMISSION_GRANTED) {
                startForeground(NOTIFICATION_ID, createNotification("Call Service Running"), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
                Log.d(TAG, "Foreground Service started with phoneCall type");
            } else {
                Log.e(TAG, "MANAGE_OWN_CALLS permission not granted, stopping service");
                stopSelf();
            }
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Call Service Running"));
        }
    }

    private void startHeartbeat() {
        if (heartbeatHandler == null) {
            heartbeatHandler = new Handler(Looper.getMainLooper());
            heartbeatRunnable = new Runnable() {
                @Override
                public void run() {
                    if (currentUserId != -1) { // Убрана проверка isAppInForeground()
                        sendHeartbeat();
                    }
                    heartbeatHandler.postDelayed(this, 10000);
                }
            };
            heartbeatHandler.post(heartbeatRunnable);
        }
    }

    private void stopHeartbeat() {
        if (heartbeatHandler != null && heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatHandler = null;
            heartbeatRunnable = null;
        }
    }

    private void sendHeartbeat() {
        if (stompClient != null && stompClient.isConnected()) {
            try {
                JSONObject heartbeat = new JSONObject();
                heartbeat.put("userId", currentUserId);
                stompClient.send("/app/heartbeat", heartbeat.toString())
                        .subscribeOn(Schedulers.io())
                        .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> Log.d(TAG, "Heartbeat sent for userId: " + currentUserId),
                                throwable -> {
                                    Log.e(TAG, "Error sending heartbeat: " + throwable.getMessage());
                                    cleanupAndReconnect();
                                }
                        );
            } catch (Exception e) {
                Log.e(TAG, "Error creating heartbeat JSON: " + e.getMessage());
                cleanupAndReconnect();
            }
        } else {
            Log.w(TAG, "STOMP client not connected, cannot send heartbeat for userId: " + currentUserId);
            cleanupAndReconnect();
        }
    }

    public void sendOnlineStatus() {
        if (stompClient != null && stompClient.isConnected()) {
            try {
                JSONObject onlineMessage = new JSONObject();
                onlineMessage.put("userId", currentUserId);
                stompClient.send("/app/setOnline", onlineMessage.toString())
                        .subscribeOn(Schedulers.io())
                        .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> Log.d(TAG, "Online status sent for userId: " + currentUserId),
                                throwable -> {
                                    Log.e(TAG, "Error sending online status: " + throwable.getMessage());
                                    cleanupAndReconnect();
                                }
                        );
            } catch (Exception e) {
                Log.e(TAG, "Error creating online status JSON: " + e.getMessage());
                cleanupAndReconnect();
            }
        } else {
            Log.w(TAG, "STOMP client not connected, cannot send online status for userId: " + currentUserId);
            cleanupAndReconnect();
        }
    }

    public void sendOfflineStatus() {
        if (stompClient != null && stompClient.isConnected()) {
            try {
                JSONObject offlineMessage = new JSONObject();
                offlineMessage.put("userId", currentUserId);
                stompClient.send("/app/setOffline", offlineMessage.toString())
                        .subscribeOn(Schedulers.io())
                        .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> Log.d(TAG, "Offline status sent for userId: " + currentUserId),
                                throwable -> {
                                    Log.e(TAG, "Error sending offline status: " + throwable.getMessage());
                                    cleanupAndReconnect();
                                }
                        );
            } catch (Exception e) {
                Log.e(TAG, "Error creating offline status JSON: " + e.getMessage());
                cleanupAndReconnect();
            }
        } else {
            Log.w(TAG, "STOMP client not connected, cannot send offline status for userId: " + currentUserId);
            cleanupAndReconnect();
        }
    }

    private void subscribeToUserStatus() {
        String topic = "/topic/users/status";
        Log.d(TAG, "Subscribing to user status topic: " + topic);
        Disposable subscription = stompClient.topic(topic)
                .subscribeOn(Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        message -> {
                            String payload = message.getPayload();
                            try {
                                JSONObject json = new JSONObject(payload);
                                long userId = json.getLong("userId");
                                boolean online = json.getBoolean("online");
                                Intent intent = new Intent("com.example.unlimitedpossibilities12.USER_STATUS");
                                intent.putExtra("userId", userId);
                                intent.putExtra("online", online);
                                LocalBroadcastManager.getInstance(CallService.this).sendBroadcast(intent);
                                Log.d(TAG, "Broadcast sent for userId: " + userId + ", online: " + online);
                            } catch (Exception e) {
                                Log.e(TAG, "Error parsing user status: " + e.getMessage());
                            }
                        },
                        throwable -> {
                            Log.e(TAG, "User status subscription error: " + throwable.getMessage());
                            cleanupAndReconnect();
                        }
                );
        messageSubscriptions.add(subscription);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String extraAction = intent.getStringExtra("action");
            String action = intent.getAction();
            Log.d(TAG, "onStartCommand with action: " + action + ", extraAction: " + extraAction);

            if ("UPDATE_USER".equals(extraAction)) {
                int newUserId = getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);
                if (newUserId != -1 && newUserId != currentUserId) {
                    Log.d(TAG, "Updating userId from " + currentUserId + " to " + newUserId);
                    currentUserId = newUserId;
                    cleanupAndReconnect();
                } else {
                    Log.w(TAG, "Invalid or unchanged userId for UPDATE_USER: " + newUserId);
                }
            } else if ("LOGIN_USER".equals(extraAction)) {
                Log.d(TAG, "Processing LOGIN_USER for userId: " + currentUserId);
                startHeartbeat();
                sendOnlineStatus();
            } else if ("LOGOUT_USER".equals(extraAction)) {
                Log.d(TAG, "Processing LOGOUT_USER for userId: " + currentUserId);
                currentUserId = -1;
                stopHeartbeat();
                sendOfflineStatus();
                stopSelf();
            } else if ("REJECT_CALL".equals(extraAction)) {
                String roomId = intent.getStringExtra("roomId");
                CallRequest callRequest = (CallRequest) intent.getSerializableExtra("callRequest");
                GroupCallRequest groupCallRequest = (GroupCallRequest) intent.getSerializableExtra("groupCallRequest");
                if (roomId != null) {
                    stopCallSound();
                    sendSignal("rejectCall", roomId);
                    saveRejectedCall(callRequest, groupCallRequest); // Добавляем вызов
                    Log.d(TAG, "Received REJECT_CALL command for roomId: " + roomId);
                } else {
                    Log.e(TAG, "roomId is null for REJECT_CALL");
                }
            } else if ("ACCEPT_CALL".equals(extraAction)) {
                String roomId = intent.getStringExtra("roomId");
                if (roomId != null) {
                    stopCallSound();
                    sendSignal("acceptCall", roomId);
                    Log.d(TAG, "Received ACCEPT_CALL command for roomId: " + roomId);
                } else {
                    Log.e(TAG, "roomId is null for ACCEPT_CALL");
                }
            } else if ("APP_BACKGROUND_STOP".equals(extraAction)) {
                // Ушли в фон и push работают: отдаём доставку им, соединение гасим
                if (!callInProgress) {
                    Log.d(TAG, "Останавливаем сервис — доставку берут push");
                    sendOfflineStatus();
                    stopHeartbeat();
                    stopSelf();
                } else {
                    Log.d(TAG, "Идёт звонок — сервис не останавливаем");
                }
            } else if ("PUSH_INCOMING_GROUP_CALL".equals(extraAction)) {
                // Групповой вызов, пришедший push-ом (приложение было закрыто)
                GroupCallRequest pushGroup = new GroupCallRequest();
                pushGroup.setInitiatorId(intent.getStringExtra("callerId"));
                pushGroup.setInitiatorName(intent.getStringExtra("callerName"));
                pushGroup.setRoomId(intent.getStringExtra("roomId"));
                pushGroup.setType("group");
                String ids = intent.getStringExtra("recipientIds");
                if (ids != null && !ids.isEmpty()) {
                    pushGroup.setRecipientIds(new java.util.ArrayList<>(java.util.Arrays.asList(ids.split(","))));
                }
                callInProgress = true;
                Log.d(TAG, "Групповой вызов из push: roomId=" + pushGroup.getRoomId());
                handleIncomingGroupCall(pushGroup);
            } else if ("PUSH_INCOMING_CALL".equals(extraAction)) {
                // Разбудили push-ом о входящем звонке: соединение уже поднимается
                // в onCreate, а экран вызова показываем сразу, не дожидаясь его
                CallRequest pushCall = new CallRequest();
                pushCall.setCallerId(intent.getIntExtra("callerId", -1));
                pushCall.setRoomId(intent.getStringExtra("roomId"));
                pushCall.setType(intent.getStringExtra("callType"));
                callInProgress = true;
                Log.d(TAG, "Входящий звонок из push: roomId=" + pushCall.getRoomId());
                handleIncomingCall(pushCall);
            } else if ("STOP_CALL_SOUND".equals(action)) {
                stopCallSound();
                Log.d(TAG, "Received STOP_CALL_SOUND command");
            } else if ("SUBSCRIBE_CHAT".equals(extraAction)) {
                long chatId = intent.getLongExtra("chatId", -1);
                if (chatId != -1) {
                    // ────────────────────────────────────────────────
                    // ИЗМЕНЕНО: отписываемся от предыдущей typing-подписки (если была)
                    if (typingSubscription != null && !typingSubscription.isDisposed()) {
                        typingSubscription.dispose();
                        typingSubscription = null;
                        Log.d(TAG, "Отписка от старой typing-подписки перед сменой чата");
                    }
                    // ────────────────────────────────────────────────
                    subscribeToChatMessages(chatId);
                    subscribeToTyping(chatId); // Явно подписываемся на typing для нового чата
                    Log.d(TAG, "Received SUBSCRIBE_CHAT command for chatId: " + chatId);
                } else {
                    Log.e(TAG, "chatId is invalid for SUBSCRIBE_CHAT");
                }
            } else if ("com.example.unlimitedpossibilities12.APP_FOREGROUND".equals(action)) {
                sendOnlineStatus();
                Log.d(TAG, "App moved to foreground, online status sent");
            } else if ("com.example.unlimitedpossibilities12.APP_BACKGROUND".equals(action)) {
                sendOfflineStatus();
                Log.d(TAG, "App moved to background, offline status sent");
            } else {
                Log.w(TAG, "Unhandled intent action: " + action + ", extraAction: " + extraAction);
            }
        } else {
            Log.w(TAG, "Received null intent in onStartCommand");
        }
        return START_STICKY;
    }

    private void cleanupAndReconnect() {
        if (isReconnecting) {
            Log.d(TAG, "Reconnect already in progress, skipping");
            return;
        }
        isReconnecting = true;
        try {
            if (stompClient != null) {
                for (Disposable subscription : messageSubscriptions) {
                    subscription.dispose();
                }
                messageSubscriptions.clear();
                if (typingSubscription != null && !typingSubscription.isDisposed()) {
                    typingSubscription.dispose();
                    typingSubscription = null;
                    Log.d(TAG, "Typing subscription disposed during cleanup");
                }
                subscribedChatIds.clear();
                stompClient.disconnect();
                stompClient = null;
                Log.d(TAG, "Disconnected old WebSocket for userId: " + currentUserId);
            }
            long delay = Math.min(1000 * (1L << reconnectAttempt), MAX_RECONNECT_DELAY_MS);
            Log.d(TAG, "Scheduling reconnect attempt #" + reconnectAttempt + " with delay " + delay + "ms");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // Сервис могли остановить (ушли в фон, доставку взяли push) —
                // тогда переподключаться нельзя: «осиротевшее» соединение
                // продолжало жить и показывало вторые уведомления.
                if (serviceDestroyed) {
                    Log.d(TAG, "Сервис остановлен — переподключение отменено");
                    return;
                }
                setupWebSocket();
                isReconnecting = false;
                reconnectAttempt++;
            }, delay);
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup and reconnect: " + e.getMessage());
            isReconnecting = false;
        }
    }

    private Notification createNotification(String content) {
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MessengerMainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Messenger UP12 Call Service")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.presence_online)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Call Service",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void setupWebSocket() {
        if (serviceDestroyed) {
            Log.d(TAG, "Сервис остановлен — соединение не поднимаем");
            return;
        }
        if (stompClient != null && stompClient.isConnected()) {
            Log.d(TAG, "WebSocket already connected for userId: " + currentUserId);
            return;
        }
        Log.d(TAG, "Setting up WebSocket for userId: " + currentUserId);
        stompClient = Stomp.over(Stomp.ConnectionProvider.OKHTTP, "wss://unlimitedpossibilities12.org/ws/websocket");
        List<StompHeader> headers = new ArrayList<>();
        headers.add(new StompHeader("accept-version", "1.1,1.0"));
        headers.add(new StompHeader("heart-beat", "10000,10000"));
        if (com.example.unlimitedpossibilities12.api.AuthToken.bearer() != null) {
            headers.add(new StompHeader("Authorization", com.example.unlimitedpossibilities12.api.AuthToken.bearer()));
        }

        stompClient.withClientHeartbeat(10000).withServerHeartbeat(10000);

        stompClient.lifecycle()
                .subscribeOn(Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(lifecycleEvent -> {
                    switch (lifecycleEvent.getType()) {
                        case OPENED:
                            Log.d(TAG, "STOMP connection opened for user: " + currentUserId);
                            socketOpen = true;
                            subscribeToUserStatus();
                            reconnectAttempt = 0;
                            subscribeToCalls();
                            subscribeToGroupCalls();
                            subscribeToNotifications();
                            for (Long chatId : new HashSet<>(subscribedChatIds)) {
                                subscribeToChatMessages(chatId);
                            }
                            // ← Добавлена переподписка на typing для всех чатов
                            for (Long chatId : new HashSet<>(subscribedChatIds)) {
                                subscribeToTyping(chatId);
                            }
                            // Подписки, запрошенные до готовности соединения
                            for (Long chatId : new HashSet<>(pendingChatSubscriptions)) {
                                pendingChatSubscriptions.remove(chatId);
                                subscribeToChatMessages(chatId);
                                subscribeToTyping(chatId);
                            }
                            // Экран чата мог открыться раньше соединения и держит
                            // пустого клиента — сообщаем, что можно переподключиться
                            LocalBroadcastManager.getInstance(CallService.this)
                                    .sendBroadcast(new Intent(ACTION_SOCKET_READY));
                            break;
                        case ERROR:
                            Log.e(TAG, "STOMP connection error: " + lifecycleEvent.getException().getMessage());
                            if (!isReconnecting) {
                                cleanupAndReconnect();
                            }
                            break;
                        case CLOSED:
                            socketOpen = false;
                            Log.d(TAG, "STOMP connection closed");
                            if (!isReconnecting) {
                                cleanupAndReconnect();
                            }
                            break;
                    }
                }, throwable -> {
                    Log.e(TAG, "Lifecycle error: " + throwable.getMessage());
                    if (!isReconnecting) {
                        cleanupAndReconnect();
                    }
                });

        stompClient.connect(headers);
        Log.d(TAG, "Initiated WebSocket connection");
    }

    private void subscribeToCalls() {
        String topic = "/topic/calls/" + currentUserId;
        Log.d(TAG, "Subscribing to topic: " + topic);
        Disposable subscription = stompClient.topic(topic)
                .subscribeOn(Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        message -> {
                            String payload = message.getPayload();
                            Log.d(TAG, "Received call notification: " + payload);
                            try {
                                JSONObject json = new JSONObject(payload);
                                String type = json.optString("type", "voice");
                                if ("endCall".equals(type)) {
                                    String roomId = json.getString("roomId");
                                    clearNotified("call:" + roomId); // следующий звонок не считается повтором
                                    Log.d(TAG, "Received endCall signal for roomId: " + roomId);
                                    Intent intent = new Intent("com.example.unlimitedpossibilities12.END_CALL");
                                    intent.putExtra("roomId", roomId);
                                    LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
                                    Log.d(TAG, "Sent END_CALL broadcast for roomId: " + roomId);
                                    stopCallSound();
                                    hasSentAcceptCall = false;
                                    if (!isAppInForeground()) {
                                        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                                        manager.cancelAll();
                                        Log.d(TAG, "Cancelled call notification for roomId: " + roomId);
                                    }
                                } else if ("voice".equals(type) || "video".equals(type) || "translate".equals(type) || "voice_secure".equals(type)) {
                                    Log.d(TAG, "Call type received: " + type);
                                    CallRequest callRequest = new CallRequest();
                                    callRequest.setCallerId(json.getInt("callerId"));
                                    callRequest.setRoomId(json.getString("roomId"));
                                    callRequest.setType(type);
                                    handleIncomingCall(callRequest);
                                } else {
                                    Log.w(TAG, "Unknown call message type: " + type);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error parsing call notification: " + e.getMessage());
                            }
                        },
                        throwable -> {
                            Log.e(TAG, "Call subscription error: " + throwable.getMessage());
                            cleanupAndReconnect();
                        }
                );
        messageSubscriptions.add(subscription);
        Log.d(TAG, "Added call subscription, messageSubscriptions size: " + messageSubscriptions.size());
    }

    private void subscribeToGroupCalls() {
        String topic = "/topic/groupCalls/" + currentUserId;
        Log.d(TAG, "Subscribing to group calls topic: " + topic);
        Disposable subscription = stompClient.topic(topic)
                .subscribeOn(Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        message -> {
                            String payload = message.getPayload();
                            Log.d(TAG, "Received group call notification: " + payload);
                            try {
                                JSONObject json = new JSONObject(payload);
                                String type = json.optString("type", "group");
                                if ("group".equals(type)) {
                                    GroupCallRequest groupCallRequest = new GroupCallRequest();
                                    groupCallRequest.setType("group");
                                    groupCallRequest.setInitiatorId(json.getString("initiatorId"));
                                    groupCallRequest.setRoomId(json.getString("roomId"));
                                    groupCallRequest.setInitiatorName(json.getString("initiatorName"));
                                    JSONArray recipientIds = json.getJSONArray("recipientIds");
                                    List<String> participantIds = new ArrayList<>();
                                    for (int i = 0; i < recipientIds.length(); i++) {
                                        participantIds.add(recipientIds.getString(i));
                                    }
                                    groupCallRequest.setRecipientIds(participantIds);
                                    handleIncomingGroupCall(groupCallRequest);
                                } else {
                                    Log.w(TAG, "Unknown group call message type: " + type);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error parsing group call notification: " + e.getMessage());
                            }
                        },
                        throwable -> {
                            Log.e(TAG, "Group call subscription error: " + throwable.getMessage());
                            cleanupAndReconnect();
                        }
                );
        messageSubscriptions.add(subscription);
        Log.d(TAG, "Added group call subscription, messageSubscriptions size: " + messageSubscriptions.size());
    }

    private void subscribeToNotifications() {
        String topic = "/topic/notifications/" + currentUserId;
        Log.d(TAG, "Subscribing to notification topic: " + topic);
        Disposable subscription = stompClient.topic(topic)
                .subscribeOn(Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        message -> {
                            String notification = message.getPayload();
                            Log.d(TAG, "Received notification on topic " + topic + ": " + notification);
                            Pattern pattern = Pattern.compile("\\(chatId: (\\d+)\\)");
                            Matcher matcher = pattern.matcher(notification);
                            long chatId = -1;
                            if (matcher.find()) {
                                try {
                                    chatId = Long.parseLong(matcher.group(1));
                                } catch (NumberFormatException e) {
                                    Log.e(TAG, "Error parsing chatId from notification: " + e.getMessage());
                                }
                            }
                            if (chatId != -1) {
                                if (!isUserInChat(chatId)) {
                                    Intent broadcastIntent = new Intent(ACTION_NEW_NOTIFICATION);
                                    broadcastIntent.putExtra("notification", notification);
                                    LocalBroadcastManager.getInstance(CallService.this).sendBroadcast(broadcastIntent);
                                    Log.d(TAG, "Sent ACTION_NEW_NOTIFICATION broadcast for chatId=" + chatId);
                                    showMessageNotification(notification, chatId);
                                } else {
                                    Log.d(TAG, "User is in chatId=" + chatId + ", skipping notification");
                                }
                            } else {
                                Log.w(TAG, "No valid chatId found in notification: " + notification);
                            }
                        },
                        throwable -> {
                            Log.e(TAG, "Notification subscription error: " + throwable.getMessage());
                            cleanupAndReconnect();
                        }
                );
        messageSubscriptions.add(subscription);
        Log.d(TAG, "Added notification subscription, messageSubscriptions size: " + messageSubscriptions.size());
    }

    private boolean isUserInChat(long chatId) {
        if (!isAppInForeground()) {
            return false;
        }
        Activity currentActivity = ((App) getApplication()).getCurrentActivity();
        // Секретный чат — тоже чат: раньше проверялся только PrivateChatActivity,
        // и уведомление всплывало, даже когда пользователь сидел в этом же
        // секретном чате.
        if (currentActivity instanceof PrivateChatActivity
                || currentActivity instanceof SecretPrivateChatActivity) {
            Intent intent = currentActivity.getIntent();
            long currentChatId = intent.getLongExtra("chatId", -1);
            return currentChatId == chatId;
        }
        return false;
    }

    /** Маркер, который сервер добавляет в уведомление для секретных чатов. */
    private static final String SECRET_MARKER = "[secret]";

    private boolean isSecretChatNotification(String notificationText) {
        return notificationText != null && notificationText.contains(SECRET_MARKER);
    }

    private void showMessageNotification(String notificationText, long chatId) {
        apiService.checkChatExists(currentUserId, chatId).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseString = response.body().string();
                        if ("Chat exists in user's private chats".equals(responseString)) {
                            showNotification(notificationText, chatId);
                        } else {
                            addChatAndShowNotification(notificationText, chatId);
                        }
                    } else {
                        Log.w(TAG, "Chat does not exist for user: " + currentUserId + ", chatId: " + chatId + ", HTTP " + response.code());
                        addChatAndShowNotification(notificationText, chatId);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading check chat response: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Log.e(TAG, "Error checking chat existence: " + t.getMessage());
                addChatAndShowNotification(notificationText, chatId);
            }
        });
    }

    private void addChatAndShowNotification(String notificationText, long chatId) {
        apiService.addChatToUser(currentUserId, chatId).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Chat " + chatId + " added to user " + currentUserId);
                    showNotification(notificationText, chatId);
                    subscribeToChatMessages(chatId);
                } else {
                    Log.e(TAG, "Failed to add chat to user: HTTP " + response.code());
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Error adding chat: " + t.getMessage());
            }
        });
    }

    private void showNotification(String notificationText, long chatId) {
        // То же сообщение может прийти и push-ом — показываем один раз
        if (!markNotified("chat:" + chatId, 6000)) {
            Log.d(TAG, "Уведомление уже показано (push) — пропускаем");
            return;
        }
        createNotificationChannel();

        // По уведомлению из секретного чата открываем секретный экран, а не
        // обычный — иначе переписка E2EE показывалась в PrivateChatActivity
        // с ИИ-функциями и без расшифровки.
        boolean secret = isSecretChatNotification(notificationText);
        Intent intent = new Intent(this, secret ? SecretPrivateChatActivity.class : PrivateChatActivity.class);
        intent.putExtra("chatId", chatId);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int notificationId = (int) System.currentTimeMillis();
        PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Служебный маркер пользователю не показываем
        String displayText = secret
                ? notificationText.replace(" " + SECRET_MARKER, "").replace(SECRET_MARKER, "")
                : notificationText;

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(secret ? "New Secret Message" : "New Message")
                .setContentText(displayText)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(notificationId, notification);
        playNotificationSound();
        Log.d(TAG, "Notification shown for chatId=" + chatId + ": " + notificationText);
    }

    private void playNotificationSound() {
        try {
            if (notificationMediaPlayer == null) {
                notificationMediaPlayer = new MediaPlayer();
                Log.d(TAG, "Created new notification MediaPlayer");
            } else if (notificationMediaPlayer.isPlaying()) {
                notificationMediaPlayer.stop();
                notificationMediaPlayer.reset();
            }
            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.zvuk_dlya_soobsen);
            try {
                notificationMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                afd.close();
            }
            notificationMediaPlayer.setLooping(false);
            notificationMediaPlayer.prepare();
            notificationMediaPlayer.start();
            Log.d(TAG, "Notification sound played");

            notificationMediaPlayer.setOnCompletionListener(mp -> {
                try {
                    mp.reset();
                    mp.release();
                    notificationMediaPlayer = null;
                    Log.d(TAG, "Notification sound completed, MediaPlayer released");
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error releasing notification MediaPlayer on completion: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error playing notification sound: " + e.getMessage());
            releaseNotificationMediaPlayer();
        }
    }

    private void releaseNotificationMediaPlayer() {
        if (notificationMediaPlayer != null) {
            try {
                if (notificationMediaPlayer.isPlaying()) {
                    notificationMediaPlayer.stop();
                }
                notificationMediaPlayer.reset();
                notificationMediaPlayer.release();
                Log.d(TAG, "Notification MediaPlayer released");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error releasing notification MediaPlayer: " + e.getMessage());
            }
            notificationMediaPlayer = null;
        }
    }

    private void subscribeToChatMessages(long chatId) {
        if (subscribedChatIds.contains(chatId)) {
            Log.d(TAG, "Already subscribed to chatId: " + chatId);
            return;
        }
        // Сервис теперь стартует по требованию, поэтому запрос на подписку может
        // прийти раньше, чем поднимется соединение. Запоминаем и подпишемся,
        // когда оно откроется (иначе обращение к пустому клиенту роняло сервис).
        if (stompClient == null || !(socketOpen || stompClient.isConnected())) {
            pendingChatSubscriptions.add(chatId);
            Log.d(TAG, "Соединение ещё не готово — подписка на чат " + chatId + " отложена");
            return;
        }

        String topic = "/topic/chat/" + chatId + "/messages";
        Log.d(TAG, "Subscribing to chat topic: " + topic);
        Disposable subscription = stompClient.topic(topic)
                .subscribeOn(Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        message -> {
                            String payload = message.getPayload();
                            Log.d(TAG, "Received message on topic " + topic + ": " + payload);
                            Intent intent = new Intent(ACTION_NEW_MESSAGE);
                            intent.putExtra("messageJson", payload);
                            intent.putExtra("chatId", chatId);
                            LocalBroadcastManager.getInstance(CallService.this).sendBroadcast(intent);
                        },
                        throwable -> {
                            Log.e(TAG, "Chat subscription error for chatId " + chatId + ": " + throwable.getMessage());
                            cleanupAndReconnect();
                        }
                );

        messageSubscriptions.add(subscription);
        subscribedChatIds.add(chatId);
        Log.d(TAG, "Added chat subscription for chatId: " + chatId + ", subscriptions size: " + messageSubscriptions.size());
    }

    private void subscribeToTyping(long chatId) {
        if (typingSubscription != null && !typingSubscription.isDisposed()) {
            Log.d(TAG, "Already subscribed to typing for chatId: " + chatId);
            return;
        }
        if (stompClient == null || !(socketOpen || stompClient.isConnected())) {
            pendingChatSubscriptions.add(chatId);
            Log.d(TAG, "Соединение ещё не готово — подписка на typing " + chatId + " отложена");
            return;
        }

        String typingTopic = "/topic/chat/" + chatId + "/typing";
        Log.d(TAG, "Subscribing to typing topic: " + typingTopic);

        typingSubscription = stompClient.topic(typingTopic)
                .subscribeOn(Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        message -> {
                            String payload = message.getPayload();
                            Log.d(TAG, "Received TYPING event: " + payload);

                            try {
                                JSONObject json = new JSONObject(payload);
                                String type = json.optString("messageType");
                                JSONObject senderJson = json.optJSONObject("sender");
                                String username = senderJson != null ? senderJson.optString("username") : "Кто-то";
                                int senderId = senderJson != null ? senderJson.optInt("id", -1) : -1;

                                // Отправляем локальный broadcast в активность чата
                                Intent intent = new Intent("com.example.unlimitedpossibilities12.TYPING_EVENT");
                                intent.putExtra("chatId", chatId);
                                intent.putExtra("type", type);
                                intent.putExtra("username", username);
                                intent.putExtra("senderId", senderId);

                                LocalBroadcastManager.getInstance(CallService.this).sendBroadcast(intent);
                                Log.d(TAG, "Sent TYPING_EVENT broadcast for chatId=" + chatId + ", type=" + type);

                            } catch (Exception e) {
                                Log.e(TAG, "Error parsing typing payload: " + e.getMessage(), e);
                            }
                        },
                        throwable -> {
                            Log.e(TAG, "Typing subscription error for chatId " + chatId + ": " + throwable.getMessage());
                            // Не переподключаемся здесь — это сделает общий cleanupAndReconnect
                        }
                );

        Log.d(TAG, "Typing subscription added for chatId: " + chatId);
    }

    private void handleIncomingCall(CallRequest callRequest) {
        // Звонок приходит и по сокету, и push-ом — обрабатываем один раз.
        // Окно короткое: номер комнаты у пары собеседников всегда одинаковый
        // (room-1-3), и при длинном окне следующий звонок принимался за повтор.
        String roomKey = "call:" + callRequest.getRoomId();
        if (!markNotified(roomKey, 8000)) {
            Log.d(TAG, "Звонок " + callRequest.getRoomId() + " уже обрабатывается — пропускаем");
            return;
        }
        apiService.getUserById(callRequest.getCallerId()).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    callRequest.setCallerName(response.body().getUsername());
                    callRequest.setCallerAvatar(response.body().getAvatar());
                    if (isAppInForeground()) {
                        showIncomingCallDialog(callRequest);
                    } else {
                        showCallNotification(callRequest);
                    }
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Log.e(TAG, "Failed to fetch caller name: " + t.getMessage());
            }
        });
    }

    private void handleIncomingGroupCall(GroupCallRequest groupCallRequest) {
        // Приходит и по сокету, и push-ом — обрабатываем один раз
        if (!markNotified("call:" + groupCallRequest.getRoomId(), 8000)) {
            Log.d(TAG, "Групповой вызов " + groupCallRequest.getRoomId() + " уже обрабатывается — пропускаем");
            return;
        }
        apiService.getUserById(Integer.parseInt(groupCallRequest.getInitiatorId())).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    groupCallRequest.setInitiatorName(response.body().getUsername());
                    if (isAppInForeground()) {
                        showIncomingCallDialog(groupCallRequest);
                    } else {
                        showCallNotification(groupCallRequest);
                    }
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Log.e(TAG, "Failed to fetch initiator name: " + t.getMessage());
            }
        });
    }

    private boolean isAppInForeground() {
        boolean isForeground = ((App) getApplication()).isAppInForeground();
        Log.d(TAG, "Checked isAppInForeground: " + isForeground);
        return isForeground;
    }

    private void showIncomingCallDialog(Object callRequest) {
        Activity currentActivity = ((App) getApplication()).getCurrentActivity();
        Intent intent;

        if (callRequest instanceof CallRequest) {
            intent = new Intent(ACTION_SHOW_CALL_DIALOG);
            intent.putExtra("callRequest", (CallRequest) callRequest);
        } else if (callRequest instanceof GroupCallRequest) {
            GroupCallRequest groupCallRequest = (GroupCallRequest) callRequest;
            intent = new Intent(ACTION_SHOW_CALL_DIALOG);
            intent.putExtra("groupCallRequest", groupCallRequest);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        } else {
            Log.e(TAG, "Unknown call request type");
            return;
        }

        if (currentActivity != null && !currentActivity.isFinishing() && !currentActivity.isDestroyed()) {
            if (currentActivity instanceof MessengerMainActivity ||
                    currentActivity instanceof UserProfileActivity ||
                    currentActivity instanceof PersonalChatsActivity) {
                LocalBroadcastManager.getInstance(currentActivity).sendBroadcast(intent);
                Log.d(TAG, "Sent local broadcast to show call dialog in " + currentActivity.getClass().getSimpleName());
            } else {
                Log.w(TAG, "Current activity is not supported for call dialog: " + currentActivity.getClass().getSimpleName());
                if (callRequest instanceof CallRequest) {
                    intent = new Intent(this, MessengerMainActivity.class);
                    intent.putExtra("showCallDialog", true);
                    intent.putExtra("callRequest", (CallRequest) callRequest);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                    Log.d(TAG, "Started MessengerMainActivity for call dialog from unsupported activity");
                } else {
                    intent = new Intent(this, MessengerMainActivity.class);
                    intent.putExtra("showGroupCallDialog", true);
                    intent.putExtra("groupCallRequest", (GroupCallRequest) callRequest);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                    Log.d(TAG, "Started MessengerMainActivity for group call dialog from unsupported activity");
                }
            }
        } else {
            if (callRequest instanceof CallRequest) {
                intent = new Intent(this, MessengerMainActivity.class);
                intent.putExtra("showCallDialog", true);
                intent.putExtra("callRequest", (CallRequest) callRequest);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            } else {
                intent = new Intent(this, MessengerMainActivity.class);
                intent.putExtra("showGroupCallDialog", true);
                intent.putExtra("groupCallRequest", (GroupCallRequest) callRequest);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            }
            startActivity(intent);
            Log.d(TAG, "No current activity, started MessengerMainActivity for call dialog: " + intent.getComponent().getClassName());
        }
        playCallSound();
    }

    private void showCallNotification(Object callRequest) {
        Intent acceptIntent = new Intent(this, CallActionReceiver.class);
        acceptIntent.setAction("ACCEPT_CALL");
        int notificationId = (int) System.currentTimeMillis();
        acceptIntent.putExtra("notificationId", notificationId);
        String title;
        int icon;
        String roomId;
        String callerName;

        if (callRequest instanceof CallRequest) {
            CallRequest request = (CallRequest) callRequest;
            acceptIntent.putExtra("callRequest", request);
            roomId = request.getRoomId();
            callerName = request.getCallerName();
            switch (request.getType()) {
                case "video":
                    title = "Incoming Video Call";
                    icon = R.drawable.ic_video_call;
                    break;
                case "translate":
                    title = "Incoming Voice Call Translation";
                    icon = R.drawable.ic_translate_call;
                    break;
                case "voice_secure":
                    title = "Incoming Secure Call (E2EE)";
                    icon = R.drawable.ic_lock;  // ← твоя иконка замка
                    break;
                case "voice":
                default:
                    title = "Incoming Voice Call";
                    icon = android.R.drawable.ic_menu_call;
                    break;
            }
        } else if (callRequest instanceof GroupCallRequest) {
            GroupCallRequest request = (GroupCallRequest) callRequest;
            acceptIntent.putExtra("groupCallRequest", request);
            roomId = request.getRoomId();
            callerName = request.getInitiatorName();
            title = "Incoming Group Call";
            icon = R.drawable.ic_group_call;
        } else {
            Log.e(TAG, "Unknown call request type");
            return;
        }

        Intent rejectIntent = new Intent(this, CallActionReceiver.class);
        rejectIntent.setAction("REJECT_CALL");
        rejectIntent.putExtra("notificationId", notificationId);
        if (callRequest instanceof CallRequest) {
            rejectIntent.putExtra("callRequest", (CallRequest) callRequest);
        } else {
            rejectIntent.putExtra("groupCallRequest", (GroupCallRequest) callRequest);
        }

        PendingIntent acceptPendingIntent = PendingIntent.getBroadcast(this, 0, acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent rejectPendingIntent = PendingIntent.getBroadcast(this, 1, rejectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("From " + callerName)
                .setSmallIcon(icon)
                .addAction(android.R.drawable.ic_menu_call, "Accept", acceptPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectPendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(500);
            }
        }

        manager.notify(notificationId, notification);
        playCallSound();
        Log.d(TAG, "Call notification shown for roomId: " + roomId + ", type: " + (callRequest instanceof CallRequest ? ((CallRequest) callRequest).getType() : "group") + ", icon: " + icon);
    }

    public void playCallSound() {
        if (isCallSoundPlaying) {
            Log.d(TAG, "Call sound already playing, skipping");
            return;
        }
        try {
            if (callMediaPlayer == null) {
                callMediaPlayer = new MediaPlayer();
                Log.d(TAG, "Created new call MediaPlayer");
            } else if (callMediaPlayer.isPlaying()) {
                callMediaPlayer.stop();
                callMediaPlayer.reset();
            }
            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.melodiya_kovboy);
            try {
                callMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                afd.close();
            }
            callMediaPlayer.setLooping(true);
            callMediaPlayer.prepare();
            callMediaPlayer.start();
            isCallSoundPlaying = true;
            Log.d(TAG, "Call sound started");
        } catch (Exception e) {
            Log.e(TAG, "Error playing call sound: " + e.getMessage());
            releaseCallMediaPlayer();
        }
    }

    public void stopCallSound() {
        releaseCallMediaPlayer();
    }

    private void releaseCallMediaPlayer() {
        if (callMediaPlayer != null) {
            try {
                if (callMediaPlayer.isPlaying()) {
                    callMediaPlayer.stop();
                    Log.d(TAG, "Call sound stopped");
                }
                callMediaPlayer.reset();
                callMediaPlayer.release();
                Log.d(TAG, "Call MediaPlayer released");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error releasing call MediaPlayer: " + e.getMessage());
            }
            callMediaPlayer = null;
            isCallSoundPlaying = false;
        }
    }

    public void sendSignal(String type, String roomId) {
        if ("acceptCall".equals(type) && hasSentAcceptCall) {
            Log.d(TAG, "acceptCall signal already sent for roomId: " + roomId);
            return;
        }
        if (stompClient != null && stompClient.isConnected()) {
            try {
                JSONObject signal = new JSONObject();
                signal.put("from", currentUserId);
                JSONObject signalData = new JSONObject();
                signalData.put("type", type);
                signal.put("signal", signalData);
                String jsonBody = signal.toString();
                Log.d(TAG, "Sending signal: " + jsonBody + " to /app/signal/" + roomId);
                stompClient.send("/app/signal/" + roomId, jsonBody)
                        .subscribeOn(Schedulers.io())
                        .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> {
                                    Log.d(TAG, "Signal sent successfully: " + jsonBody);
                                    if ("acceptCall".equals(type)) {
                                        hasSentAcceptCall = true;
                                    }
                                },
                                throwable -> {
                                    Log.e(TAG, "Error sending signal: " + throwable.getMessage());
                                    cleanupAndReconnect();
                                }
                        );
            } catch (Exception e) {
                Log.e(TAG, "Error creating signal JSON: " + e.getMessage());
                cleanupAndReconnect();
            }
        } else {
            Log.e(TAG, "STOMP client not connected, cannot send signal for type: " + type);
            cleanupAndReconnect();
        }
    }

    public void requestUserStatus(long userId) {
        if (stompClient != null && stompClient.isConnected()) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("userId", userId);
                stompClient.send("/app/requestUserStatus", payload.toString())
                        .subscribeOn(Schedulers.io())
                        .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> Log.d(TAG, "Requested status for userId: " + userId),
                                throwable -> {
                                    Log.e(TAG, "Error requesting user status: " + throwable.getMessage());
                                    cleanupAndReconnect();
                                }
                        );
            } catch (Exception e) {
                Log.e(TAG, "Error creating requestUserStatus JSON: " + e.getMessage());
                cleanupAndReconnect();
            }
        } else {
            Log.w(TAG, "STOMP client not connected, cannot request status for userId: " + userId);
            cleanupAndReconnect();
        }
    }

    public StompClient getStompClient() {
        return stompClient;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void saveRejectedCall(CallRequest callRequest, GroupCallRequest groupCallRequest) {
        OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        JSONObject callData = new JSONObject();
        try {
            Log.d(TAG, "saveRejectedCall called with callRequest: " + (callRequest != null ? callRequest.toString() : "null") + ", groupCallRequest: " + (groupCallRequest != null ? groupCallRequest.toString() : "null"));
            if (groupCallRequest != null) {
                // Групповой звонок (оставляем как есть, чтобы поддерживать текущую функциональность)
                callData.put("initiatorId", groupCallRequest.getInitiatorId());
                JSONArray participantIds = new JSONArray();
                participantIds.put(groupCallRequest.getInitiatorId());
                for (String recipientId : groupCallRequest.getRecipientIds()) {
                    participantIds.put(recipientId);
                }
                callData.put("participants", participantIds);
                callData.put("direction", "incoming");
                callData.put("status", "rejected");
                callData.put("callType", "group");
                callData.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date()));
                callData.put("roomId", groupCallRequest.getRoomId());
            } else if (callRequest != null) {
                // Индивидуальный звонок (идентично VoiceCallActivity.saveCall)
                Log.d(TAG, "CallerId value: " + callRequest.getCallerId()); // Убрано getClass()
                Log.d(TAG, "CurrentUserId value: " + currentUserId); // Убрано getClass()
                Log.d(TAG, "Call type: " + callRequest.getType()); // Логируем тип звонка
                callData.put("caller", callRequest.getCallerId());
                callData.put("receiver", currentUserId);
                callData.put("direction", "incoming");
                callData.put("status", "rejected");
                callData.put("callType", callRequest.getType()); // Используем callRequest.getType()
                callData.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date()));
                callData.put("roomId", callRequest.getRoomId());
            } else {
                Log.e(TAG, "No valid call request provided for saving rejected call");
                return;
            }

            RequestBody body = RequestBody.create(callData.toString(), JSON);
            Request request = new Request.Builder()
                    .url("https://unlimitedpossibilities12.org/api/calls?userId=" + currentUserId)
                    .post(body)
                    .build();

            new Thread(() -> {
                try (okhttp3.Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "Rejected call saved to server with callType: " + callData.getString("callType") + ", JSON: " + callData.toString());
                    } else {
                        Log.e(TAG, "Failed to save rejected call, response code: " + response.code() + ", response body: " + (response.body() != null ? response.body().string() : "null"));
                    }
                } catch (IOException | JSONException e) {
                    Log.e(TAG, "Error saving rejected call: " + e.getMessage());
                }
            }).start();
        } catch (JSONException e) {
            Log.e(TAG, "Error creating rejected call JSON: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        serviceDestroyed = true;
        activeInstance = null;
        super.onDestroy();
        stopHeartbeat();
        try {
            if (stompClient != null) {
                for (Disposable subscription : messageSubscriptions) {
                    subscription.dispose();
                }
                messageSubscriptions.clear();
                subscribedChatIds.clear();

                // ← НОВОЕ: отписка от typing-подписки (чтобы не было утечек памяти)
                if (typingSubscription != null && !typingSubscription.isDisposed()) {
                    typingSubscription.dispose();
                    typingSubscription = null;
                    Log.d(TAG, "Typing subscription disposed in onDestroy");
                }

                stompClient.disconnect();
                stompClient = null;
                Log.d(TAG, "Disconnected WebSocket for STOP_SERVICE");
            }
            releaseCallMediaPlayer();
            releaseNotificationMediaPlayer();
            Log.d(TAG, "CallService destroyed");
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup and stop: " + e.getMessage());
        }
        stopSelf();
    }
}