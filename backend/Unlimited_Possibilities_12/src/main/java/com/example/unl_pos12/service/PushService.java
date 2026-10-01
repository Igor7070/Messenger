package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.PushToken;
import com.example.unl_pos12.repo.PushTokenRepository;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Push-уведомления через Firebase Cloud Messaging.
 *
 * Уведомление будит приложение, даже когда оно закрыто, — без этого Android
 * усыпляет фоновое соединение и сообщения не доходят.
 *
 * Содержимое сообщений в push НЕ передаётся: уходит только «от кого» и id
 * чата, а текст клиент забирает сам и, если чат секретный, расшифровывает у
 * себя. Так серверы Google не видят переписку и сквозное шифрование остаётся
 * нетронутым.
 *
 * Ключ сервисного аккаунта берётся из переменной окружения FIREBASE_CREDENTIALS
 * (содержимое JSON-файла). Если её нет, сервис молча выключается — приложение
 * продолжает работать на постоянном соединении, как раньше.
 */
@Service
public class PushService {

    private final PushTokenRepository repository;
    private final String credentialsJson;
    private volatile boolean ready;

    @Autowired
    public PushService(PushTokenRepository repository,
                       @Value("${firebase.credentials:}") String credentialsJson) {
        this.repository = repository;
        this.credentialsJson = credentialsJson;
    }

    @PostConstruct
    void init() {
        if (credentialsJson == null || credentialsJson.isBlank()) {
            System.out.println("PushService: FIREBASE_CREDENTIALS не задан — push выключены");
            return;
        }
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(
                        new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)));
                FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(credentials).build());
            }
            ready = true;
            System.out.println("PushService: Firebase инициализирован, push включены");
        } catch (Exception e) {
            System.out.println("PushService: не удалось инициализировать Firebase: " + e.getMessage());
        }
    }

    public boolean isReady() { return ready; }

    /** Запомнить токен устройства (или переназначить его другому пользователю после перелогина). */
    @Transactional
    public void register(Long userId, String token, String platform) {
        if (userId == null || token == null || token.isBlank()) return;
        PushToken entity = repository.findByToken(token).orElseGet(PushToken::new);
        entity.setToken(token);
        entity.setUserId(userId);
        entity.setPlatform(platform != null ? platform : "android");
        entity.setUpdatedAt(ZonedDateTime.now());
        repository.save(entity);
        System.out.println("PushService: токен зарегистрирован для userId=" + userId);
    }

    /** Убрать токен (выход из аккаунта на этом устройстве). */
    @Transactional
    public void unregister(String token) {
        if (token == null || token.isBlank()) return;
        repository.deleteByToken(token);
    }

    /**
     * Отправить уведомление всем устройствам пользователя.
     *
     * @param title   от кого (имя отправителя)
     * @param body    короткая подпись без текста сообщения
     * @param data    chatId, secret и т.п. — по ним клиент открывает нужный экран
     */
    @Transactional
    public void sendToUser(Long userId, String title, String body, Map<String, String> data) {
        if (!ready || userId == null) return;
        List<PushToken> tokens = repository.findByUserId(userId);
        if (tokens.isEmpty()) return;

        Map<String, String> payload = new HashMap<>(data != null ? data : Map.of());
        payload.put("title", title != null ? title : "Messenger UP12");
        payload.put("body", body != null ? body : "New message");

        // Отправляем в отдельном потоке: вызов идёт из обработчика WebSocket, а
        // блокировать его сетевым запросом к Google нельзя — доставка сообщения
        // другим участникам не должна ждать Firebase.
        List<String> tokenValues = new java.util.ArrayList<>();
        for (PushToken t : tokens) tokenValues.add(t.getToken());
        sender.submit(() -> deliver(userId, tokenValues, payload));
    }

    private final java.util.concurrent.ExecutorService sender =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "push-sender");
                t.setDaemon(true);
                return t;
            });

    private void deliver(Long userId, List<String> tokenValues, Map<String, String> payload) {
        for (String token : tokenValues) {
            if (!sendOnce(userId, token, payload, false)) {
                // Транспортные сбои у Firebase случаются — пробуем ещё раз
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                sendOnce(userId, token, payload, true);
            }
        }
    }

    /** @return true, если отправлено или токен удалён как недействительный. */
    private boolean sendOnce(Long userId, String token, Map<String, String> payload, boolean lastAttempt) {
        try {
            Message message = Message.builder()
                    .setToken(token)
                    .putAllData(payload)
                    .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder()
                            .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                            .build())
                    .build();
            String id = FirebaseMessaging.getInstance().send(message);
            System.out.println("PushService: отправлено userId=" + userId + ", id=" + id);
            return true;
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                repository.deleteByToken(token);
                System.out.println("PushService: удалён недействительный токен userId=" + userId);
                return true;
            }
            System.out.println("PushService: ошибка отправки (" + code + "): " + e.getMessage()
                    + causeChain(e) + (lastAttempt ? " — повтор не помог" : " — повторим"));
            return false;
        } catch (Exception e) {
            System.out.println("PushService: ошибка отправки: " + e.getMessage() + causeChain(e));
            return false;
        }
    }

    private static String causeChain(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable c = e.getCause();
        int depth = 0;
        while (c != null && depth++ < 4) {
            sb.append(" <- ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            c = c.getCause();
        }
        return sb.toString();
    }

}
