package com.example.unlimitedpossibilities12.messenger.notife;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import com.goterl.lazysodium.interfaces.Sign;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Подпись звонка: подтверждаем, что по ту сторону именно собеседник.
 *
 * Звук и видео шифрует сам WebRTC, и сервер их не слышит. Открытой оставалась
 * установка связи: тот, кто передаёт служебные сообщения, мог подменить ключи
 * и слушать разговор. Поэтому каждая сторона подписывает своё описание
 * соединения ключом Ed25519, а другая проверяет подпись.
 *
 * Ключи заводятся на каждое устройство отдельно. Иначе телефон и браузер
 * затирали бы ключ друг друга, и собеседник видел бы «ключ сменился» при
 * каждом переходе с одного на другое — предупреждение, которое срабатывает
 * зря, через неделю перестают читать.
 *
 * Ключ знакомого устройства запоминается. Дальше возможны три случая:
 * устройство новое — спокойная отметка; ключ знакомого устройства совпал —
 * подтверждено; ключ знакомого устройства другой — предупреждение, потому что
 * это выглядит как подмена ключа тем, кто их раздаёт.
 *
 * Ключи раздаёт наш сервер, поэтому его враньё подписью не ловится. Для этого
 * на экране звонка есть код из обоих ключей: он одинаков у обеих сторон, и
 * сверенный вслух код отмечается как «подтверждено вами» — после этого любая
 * подмена видна сразу.
 */
public final class CallGuard {

    private static final String TAG = "CallGuard";
    private static final String PREFS = "secure_call";
    private static final String TRUST_PREFS = "call_trust";
    private static final String DEVICE_ID = "device_id";
    private static final String API = "https://unlimitedpossibilities12.org/api/sign_keys";
    private static final String DEVICES_API = "https://unlimitedpossibilities12.org/api/device_keys";

    /** Что известно о собеседнике на том конце. */
    public enum State {
        /** Ключи ещё сверяются. */
        CHECKING,
        /** Подписи нет: связь шифруется, но кто ответил — неизвестно. */
        ENCRYPTED,
        /** Устройство новое: подпись сошлась, ключ запомнен. */
        NEW_DEVICE,
        /** Знакомое устройство с тем же ключом. */
        VERIFIED,
        /** Код сверен вслух — самое надёжное, что у нас есть. */
        CONFIRMED,
        /** У знакомого устройства другой ключ — повод насторожиться. */
        CHANGED,
        /** Подпись не сошлась: разговор надо прекращать. */
        FAILED
    }

    /** Буквы кода: шесть значков из этого набора читаются вслух без ошибок. */
    private static final String[] EMOJI = {
            "🐶", "🐱", "🦊", "🐻", "🐼", "🐨", "🦁", "🐷",
            "🐸", "🐵", "🦉", "🦆", "🐢", "🐳", "🐬", "🐝",
            "🦋", "🌻", "🌲", "🍏", "🍋", "🍓", "🍉", "🍇",
            "🥕", "🍄", "🍞", "🧀", "🍕", "🍔", "🍟", "🍩",
            "🍪", "☕", "🍵", "⚽", "🏀", "🎾", "🎸", "🎺",
            "🎻", "🥁", "🚗", "🚕", "🚲", "✈", "🚀", "⛵",
            "⌚", "💡", "🔑", "🔔", "🎁", "📷", "📚", "✏",
            "⭐", "🌈", "☀", "🌙", "❄", "🔥", "💧", "🍀"
    };

    private final Context context;
    private final String userId;
    private final String deviceId;
    private final LazySodiumAndroid sodium = new LazySodiumAndroid(new SodiumAndroid());

    private byte[] myPublicKey;
    private byte[] myPrivateKey;
    /** Ключи устройств собеседника, как их отдал сервер. */
    private final Map<String, String> partnerDevices = new HashMap<>();
    private String partnerId;
    private String partnerDeviceId;
    private byte[] partnerPublicKey;
    private State state = State.CHECKING;

    public CallGuard(Context context, String userId) {
        this.context = context.getApplicationContext();
        this.userId = userId;
        this.deviceId = loadOrCreateDeviceId(this.context);
    }

    /** Постоянная метка устройства; живёт столько же, сколько установка. */
    private static String loadOrCreateDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(DEVICE_ID, id).apply();
        }
        return id;
    }

    public String deviceId() {
        return deviceId;
    }

    /**
     * Готовим ключи: свои берём из хранилища или заводим, список устройств
     * собеседника спрашиваем у сервера. Всё в отдельном потоке — звонок не ждёт.
     */
    public void prepare(String partnerId, Runnable done) {
        this.partnerId = partnerId;
        new Thread(() -> {
            try {
                loadOrCreateKeys();
                fetchPartnerDevices(partnerId);
            } catch (Exception e) {
                Log.w(TAG, "Не удалось подготовить ключи звонка: " + e.getMessage());
            }
            if (done != null) done.run();
        }).start();
    }

    private void loadOrCreateKeys() {
        String prefKey = "secure_sign_" + userId;
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(prefKey, null);
        if (stored != null) {
            String[] parts = stored.split("\\|", 2);
            if (parts.length == 2) {
                myPublicKey = decode(parts[0]);
                myPrivateKey = decode(parts[1]);
            }
        }
        // Повреждённую пару не чиним, а заводим новую: подписывать всё равно нечем
        if (myPublicKey == null || myPublicKey.length != Sign.PUBLICKEYBYTES
                || myPrivateKey == null || myPrivateKey.length != Sign.SECRETKEYBYTES) {
            myPublicKey = new byte[Sign.PUBLICKEYBYTES];
            myPrivateKey = new byte[Sign.SECRETKEYBYTES];
            sodium.cryptoSignKeypair(myPublicKey, myPrivateKey);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(prefKey, encode(myPublicKey) + "|" + encode(myPrivateKey))
                    .apply();
            Log.d(TAG, "Заведена новая пара ключей для подписи звонков");
        }
        publishPublicKey();
    }

    private void publishPublicKey() {
        OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
        // Ключ этого устройства
        post(client, DEVICES_API, "{\"userId\":\"" + userId + "\",\"deviceId\":\"" + deviceId
                + "\",\"publicKey\":\"" + encode(myPublicKey) + "\"}");
        // Прежний общий слот: с ним работают ещё не обновлённые собеседники
        post(client, API, "{\"userId\":\"" + userId + "\",\"publicKey\":\"" + encode(myPublicKey) + "\"}");
    }

    private void post(OkHttpClient client, String url, String json) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "Ключ не принят сервером (" + url + "): " + response.code());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Ключ не ушёл на сервер (" + url + "): " + e.getMessage());
        }
    }

    private void fetchPartnerDevices(String partnerId) {
        if (partnerId == null || partnerId.isEmpty()) return;
        OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
        try {
            Request request = new Request.Builder().url(DEVICES_API + "/" + partnerId).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONArray devices = new JSONArray(response.body().string());
                    for (int i = 0; i < devices.length(); i++) {
                        JSONObject device = devices.getJSONObject(i);
                        partnerDevices.put(device.getString("deviceId"), device.getString("publicKey"));
                    }
                    Log.d(TAG, "Устройств у собеседника: " + partnerDevices.size());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Список устройств собеседника не получен: " + e.getMessage());
        }
        // Собеседник на старом приложении: у него только общий ключ
        if (partnerDevices.isEmpty()) {
            try {
                Request request = new Request.Builder().url(API + "/" + partnerId).build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject json = new JSONObject(response.body().string());
                        partnerDevices.put("legacy", json.getString("publicKey"));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Общий ключ собеседника не получен: " + e.getMessage());
            }
        }
    }

    /** Подпись под описанием соединения; null — если подписывать нечем. */
    public String sign(String sdp) {
        if (myPrivateKey == null || sdp == null) return null;
        try {
            byte[] signature = new byte[Sign.BYTES];
            sodium.cryptoSignDetached(signature, sdp.getBytes(), sdp.length(), myPrivateKey);
            return encode(signature);
        } catch (Exception e) {
            Log.w(TAG, "Не удалось подписать описание соединения: " + e.getMessage());
            return null;
        }
    }

    /**
     * Проверка подписи собеседника.
     *
     * Ключ берём у сервера — по метке того устройства, которое подписало.
     * Ключ из самого сообщения проверять бессмысленно: кто подменил сообщение,
     * тот приложит и свой ключ.
     */
    public State verify(String sdp, String signatureB64, String senderDeviceId) {
        if (signatureB64 == null || signatureB64.isEmpty()) {
            state = State.ENCRYPTED;
            return state;
        }
        String keyB64 = senderDeviceId != null ? partnerDevices.get(senderDeviceId) : null;
        if (keyB64 == null && partnerDevices.size() == 1) {
            // Старое приложение без метки устройства — у него ключ один
            keyB64 = partnerDevices.values().iterator().next();
            senderDeviceId = partnerDevices.keySet().iterator().next();
        }
        if (keyB64 == null) {
            state = State.ENCRYPTED;
            return state;
        }
        try {
            partnerPublicKey = decode(keyB64);
            partnerDeviceId = senderDeviceId;
            byte[] signature = decode(signatureB64);
            boolean valid = sodium.cryptoSignVerifyDetached(signature, sdp.getBytes(), sdp.length(), partnerPublicKey);
            if (!valid) {
                state = State.FAILED;
                return state;
            }
            state = rememberAndJudge(keyB64);
        } catch (Exception e) {
            Log.w(TAG, "Подпись не разобрана: " + e.getMessage());
            state = State.FAILED;
        }
        return state;
    }

    /**
     * Что мы знали об этом устройстве раньше.
     *
     * Запомненный ключ — это и есть защита от сервера: подменить ключ он может
     * только один раз, и это будет видно.
     */
    private State rememberAndJudge(String keyB64) {
        SharedPreferences trust = context.getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE);
        String known = trust.getString(trustKey(), null);
        if (known == null) {
            trust.edit().putString(trustKey(), keyB64).apply();
            return State.NEW_DEVICE;
        }
        if (!known.equals(keyB64)) {
            Log.w(TAG, "У знакомого устройства собеседника другой ключ");
            return State.CHANGED;
        }
        return trust.getBoolean(confirmedKey(), false) ? State.CONFIRMED : State.VERIFIED;
    }

    /** Человек сверил код вслух: с этой минуты подмена будет видна. */
    public void confirmByCode() {
        if (partnerPublicKey == null) return;
        context.getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE).edit()
                .putString(trustKey(), encode(partnerPublicKey))
                .putBoolean(confirmedKey(), true)
                .apply();
        state = State.CONFIRMED;
    }

    /** Ключ сменился, но человек сверил код заново — принимаем новый. */
    public void acceptChangedKey() {
        if (partnerPublicKey == null) return;
        context.getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE).edit()
                .putString(trustKey(), encode(partnerPublicKey))
                .putBoolean(confirmedKey(), false)
                .apply();
        state = State.VERIFIED;
    }

    private String trustKey() {
        return "key_" + partnerId + "_" + partnerDeviceId;
    }

    private String confirmedKey() {
        return "confirmed_" + partnerId + "_" + partnerDeviceId;
    }

    public State state() {
        return state;
    }

    /**
     * Код разговора: шесть значков из обоих ключей. У обеих сторон он
     * одинаков, поэтому его сверяют вслух.
     */
    public String fingerprint() {
        byte[] hash = fingerprintHash();
        if (hash == null) return null;
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) code.append(' ');
            code.append(EMOJI[hash[i] & 0x3F]);
        }
        return code.toString();
    }

    /** Те же шесть значков цифрами — на случай, если значки не видно. */
    public String fingerprintDigits() {
        byte[] hash = fingerprintHash();
        if (hash == null) return null;
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) code.append(' ');
            code.append((hash[i] & 0xFF) % 10);
        }
        return code.toString();
    }

    private byte[] fingerprintHash() {
        if (myPublicKey == null || partnerPublicKey == null) return null;
        try {
            byte[] first = myPublicKey;
            byte[] second = partnerPublicKey;
            // Порядок не должен зависеть от того, кто кому звонил
            if (compare(first, second) > 0) {
                byte[] swap = first;
                first = second;
                second = swap;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(first);
            digest.update(second);
            return digest.digest();
        } catch (Exception e) {
            return null;
        }
    }

    private static int compare(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (diff != 0) return diff;
        }
        return a.length - b.length;
    }

    private static String encode(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
    }

    private static byte[] decode(String text) {
        return Base64.decode(text, Base64.URL_SAFE | Base64.NO_WRAP);
    }
}
