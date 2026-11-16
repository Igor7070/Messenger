package com.example.unlimitedpossibilities12.messenger.notife;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import com.goterl.lazysodium.interfaces.Sign;

import org.json.JSONObject;

import java.security.MessageDigest;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Подпись звонка: подтверждаем, что по ту сторону именно собеседник.
 *
 * Звук и видео в звонке и так зашифрованы — этим занимается сам WebRTC, и
 * сервер видит только служебные сообщения. Открытой оставалась одна щель: тот,
 * кто держит сигнализацию, мог подменить ключи в момент установки связи и
 * слушать разговор. Поэтому каждая сторона подписывает своё описание
 * соединения ключом Ed25519 и проверяет подпись собеседника.
 *
 * Раньше это жило в отдельном «защищённом звонке», который надо было выбрать
 * вручную — то есть защита работала только тогда, когда о ней вспомнили.
 * Теперь она в каждом голосовом и видеозвонке.
 *
 * Открытые ключи раздаёт наш сервер, поэтому проверка ловит подмену по дороге,
 * но не враньё самого сервера. Чтобы закрыть и это, на экране звонка есть
 * отпечаток: короткий код из обоих ключей, одинаковый у обеих сторон. Сверили
 * вслух один раз — и подмена будет видна.
 */
public final class CallGuard {

    private static final String TAG = "CallGuard";
    private static final String PREFS = "secure_call";
    private static final String API = "https://unlimitedpossibilities12.org/api/sign_keys";

    /** Состояние разговора с точки зрения подтверждения собеседника. */
    public enum State {
        /** Ключи ещё не сверили. */
        CHECKING,
        /** Подпись сошлась: по ту сторону тот, за кого себя выдают. */
        VERIFIED,
        /** Собеседник подписи не прислал — связь шифруется, но не подтверждена. */
        UNVERIFIED,
        /** Подпись не сошлась: разговор надо прекращать. */
        FAILED
    }

    private final Context context;
    private final String userId;
    private final LazySodiumAndroid sodium = new LazySodiumAndroid(new SodiumAndroid());

    private byte[] myPublicKey;
    private byte[] myPrivateKey;
    private byte[] partnerPublicKey;
    private State state = State.CHECKING;

    public CallGuard(Context context, String userId) {
        this.context = context.getApplicationContext();
        this.userId = userId;
    }

    /**
     * Готовим ключи: свои берём из хранилища или заводим заново, чужой
     * спрашиваем у сервера. Всё в отдельном потоке — звонок не ждёт.
     */
    public void prepare(String partnerId, Runnable done) {
        new Thread(() -> {
            try {
                loadOrCreateKeys();
                fetchPartnerKey(partnerId);
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
        try {
            OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
            Request request = new Request.Builder()
                    .url(API)
                    .post(RequestBody.create(
                            "{\"userId\":\"" + userId + "\",\"publicKey\":\"" + encode(myPublicKey) + "\"}",
                            MediaType.parse("application/json")))
                    .build();
            client.newCall(request).execute().close();
        } catch (Exception e) {
            Log.w(TAG, "Открытый ключ не ушёл на сервер: " + e.getMessage());
        }
    }

    private void fetchPartnerKey(String partnerId) {
        if (partnerId == null || partnerId.isEmpty()) return;
        try {
            OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
            Request request = new Request.Builder().url(API + "/" + partnerId).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject json = new JSONObject(response.body().string());
                    partnerPublicKey = decode(json.getString("publicKey"));
                    Log.d(TAG, "Ключ собеседника получен");
                } else {
                    Log.d(TAG, "У собеседника ещё нет ключа подписи");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Ключ собеседника не получен: " + e.getMessage());
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
     * Ключ берём только тот, что выдал сервер: ключ из самого сообщения
     * проверять бессмысленно — кто подменил сообщение, тот приложит и свой
     * ключ.
     */
    public State verify(String sdp, String signatureB64) {
        if (signatureB64 == null || signatureB64.isEmpty()) {
            state = State.UNVERIFIED;
            return state;
        }
        if (partnerPublicKey == null || partnerPublicKey.length != Sign.PUBLICKEYBYTES) {
            state = State.UNVERIFIED;
            return state;
        }
        try {
            byte[] signature = decode(signatureB64);
            boolean valid = sodium.cryptoSignVerifyDetached(signature, sdp.getBytes(), sdp.length(), partnerPublicKey);
            state = valid ? State.VERIFIED : State.FAILED;
        } catch (Exception e) {
            Log.w(TAG, "Подпись не разобрана: " + e.getMessage());
            state = State.FAILED;
        }
        return state;
    }

    public State state() {
        return state;
    }

    /**
     * Отпечаток разговора: короткий код из обоих открытых ключей. У обеих
     * сторон он одинаков, поэтому его сверяют вслух.
     */
    public String fingerprint() {
        if (myPublicKey == null || partnerPublicKey == null) return null;
        try {
            byte[] first = myPublicKey;
            byte[] second = partnerPublicKey;
            // Порядок не должен зависеть от того, кто звонил
            if (compare(first, second) > 0) {
                byte[] swap = first;
                first = second;
                second = swap;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(first);
            digest.update(second);
            byte[] hash = digest.digest();

            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                if (i > 0 && i % 4 == 0) code.append(' ');
                code.append((hash[i] & 0xFF) % 10);
            }
            return code.toString();
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
