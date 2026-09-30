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

        for (PushToken t : tokens) {
            try {
                // Только data-сообщение: уведомление рисует само приложение,
                // поэтому текст переписки в облако не уходит.
                Message message = Message.builder()
                        .setToken(t.getToken())
                        .putAllData(payload)
                        .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder()
                                .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                                .build())
                        .build();
                FirebaseMessaging.getInstance().send(message);
            } catch (FirebaseMessagingException e) {
                MessagingErrorCode code = e.getMessagingErrorCode();
                if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                    // Приложение удалено или токен протух — чистим
                    repository.deleteByToken(t.getToken());
                    System.out.println("PushService: удалён недействительный токен userId=" + userId);
                } else {
                    System.out.println("PushService: ошибка отправки (" + code + "): " + e.getMessage());
                }
            } catch (Exception e) {
                System.out.println("PushService: ошибка отправки: " + e.getMessage());
            }
        }
    }
}
