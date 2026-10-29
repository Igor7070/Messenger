package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.PublicKeyRequest;
import com.goterl.lazysodium.SodiumAndroid;
import com.goterl.lazysodium.interfaces.Box;
import com.goterl.lazysodium.interfaces.SecretBox;

import java.io.IOException;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Ключи секретных чатов при входе в аккаунт.
 *
 * Тот же код раньше лежал двумя копиями — в LoginDialogFragment и
 * CreateUserDialogFragment. Экран входа (AuthActivity) обращается сюда, чтобы
 * ключи выпускались и отправлялись на сервер ровно так же, как прежде: имена
 * записей в SharedPreferences и кодировка Base64 совпадают, иначе уже
 * присланные секретные сообщения перестали бы расшифровываться.
 *
 * Пара выпускается один раз на пользователя: повторный вход переиспользует
 * сохранённую, а на сервер каждый раз уходит открытая часть — он мог потерять
 * её при переустановке базы.
 */
public final class AuthKeys {

    private static final String TAG = "AuthKeys";
    private static final String KEYSTORE_ALIAS = "secret_chat_key";

    private AuthKeys() {}

    /** Убедиться, что у пользователя есть пара ключей, и отдать серверу открытую часть. */
    public static void ensure(Context context, long userId) {
        SodiumAndroid sodium = new SodiumAndroid();
        try {
            String alias = KEYSTORE_ALIAS + "_" + userId;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String publicKeyAlias = "public_key_" + userId;
            String publicKeyBase64;

            boolean hasStoredKeyPair = prefs.contains("encrypted_private_key_" + alias)
                    && prefs.contains("nonce_" + alias)
                    && prefs.contains("secretbox_key_" + alias)
                    && prefs.getString(publicKeyAlias, null) != null;

            if (!hasStoredKeyPair) {
                byte[] privateKey = new byte[Box.SECRETKEYBYTES];
                byte[] publicKey = new byte[Box.PUBLICKEYBYTES];
                sodium.crypto_box_keypair(publicKey, privateKey);
                savePrivateKey(context, sodium, privateKey, alias);
                publicKeyBase64 = Base64.encodeToString(publicKey, Base64.URL_SAFE | Base64.NO_WRAP)
                        .replace("=", "");
                prefs.edit().putString(publicKeyAlias, publicKeyBase64).apply();
                Log.d(TAG, "Выпущена новая пара ключей для userId=" + userId);
            } else {
                publicKeyBase64 = prefs.getString(publicKeyAlias, null);
                if (publicKeyBase64 == null) {
                    Log.e(TAG, "Открытый ключ не найден для userId=" + userId);
                    return;
                }
                publicKeyBase64 = publicKeyBase64.replace("=", "");
                prefs.edit().putString(publicKeyAlias, publicKeyBase64).apply();
                Log.d(TAG, "Используем сохранённую пару ключей для userId=" + userId);
            }

            sendPublicKey(userId, publicKeyBase64);
        } catch (Exception e) {
            Log.e(TAG, "Не удалось подготовить ключи: " + e.getMessage());
        }
    }

    private static void savePrivateKey(Context context, SodiumAndroid sodium, byte[] privateKey, String alias) {
        byte[] key = new byte[SecretBox.KEYBYTES];
        sodium.randombytes_buf(key, SecretBox.KEYBYTES);
        byte[] nonce = new byte[SecretBox.NONCEBYTES];
        sodium.randombytes_buf(nonce, SecretBox.NONCEBYTES);

        byte[] encrypted = new byte[privateKey.length + SecretBox.MACBYTES];
        int result = sodium.crypto_secretbox_easy(encrypted, privateKey, privateKey.length, nonce, key);
        if (result != 0) throw new RuntimeException("Не удалось зашифровать закрытый ключ");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putString("encrypted_private_key_" + alias,
                        Base64.encodeToString(encrypted, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", ""))
                .putString("nonce_" + alias,
                        Base64.encodeToString(nonce, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", ""))
                .putString("secretbox_key_" + alias,
                        Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", ""))
                .apply();
    }

    private static void sendPublicKey(long userId, String publicKey) {
        PublicKeyRequest request = new PublicKeyRequest(userId, publicKey);
        ApiClient.getApiService().uploadPublicKey(request).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Открытый ключ отправлен на сервер, userId=" + userId);
                } else {
                    try {
                        Log.e(TAG, "Сервер не принял ключ: код=" + response.code()
                                + ", тело=" + (response.errorBody() != null ? response.errorBody().string() : ""));
                    } catch (IOException e) {
                        Log.e(TAG, "Ошибка чтения ответа: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Ошибка отправки ключа: " + t.getMessage());
            }
        });
    }
}
