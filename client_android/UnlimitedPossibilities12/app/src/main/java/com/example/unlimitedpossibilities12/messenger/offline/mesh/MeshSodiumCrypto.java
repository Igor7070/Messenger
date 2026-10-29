package com.example.unlimitedpossibilities12.messenger.offline.mesh;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.goterl.lazysodium.SodiumAndroid;
import com.goterl.lazysodium.interfaces.Box;

/**
 * Шифрование mesh-пакетов: X25519 crypto_box (тот же примитив, что в секретном
 * чате). Пара ключей узла генерируется один раз на установку и хранится в
 * SharedPreferences офлайн-режима — аккаунт для офлайна не нужен.
 *
 * Формат шифртекста: base64(nonce || box).
 */
public final class MeshSodiumCrypto implements MeshRouter.MeshCrypto {
    private static final String PREFS = "offline_mesh";
    private final SodiumAndroid sodium = new SodiumAndroid();
    private final byte[] publicKey = new byte[Box.PUBLICKEYBYTES];
    private final byte[] privateKey = new byte[Box.SECRETKEYBYTES];

    public MeshSodiumCrypto(Context ctx) { this(ctx, PREFS); }

    /** Отдельное имя prefs — отдельная пара ключей (нужно тестовому узлу). */
    public MeshSodiumCrypto(Context ctx, String prefsName) {
        SharedPreferences p = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String pk = p.getString("pk", null), sk = p.getString("sk", null);
        if (pk != null && sk != null) {
            System.arraycopy(Base64.decode(pk, Base64.NO_WRAP), 0, publicKey, 0, publicKey.length);
            System.arraycopy(Base64.decode(sk, Base64.NO_WRAP), 0, privateKey, 0, privateKey.length);
        } else {
            sodium.crypto_box_keypair(publicKey, privateKey);
            p.edit().putString("pk", Base64.encodeToString(publicKey, Base64.NO_WRAP))
                    .putString("sk", Base64.encodeToString(privateKey, Base64.NO_WRAP)).apply();
        }
    }

    /** Стабильный идентификатор узла — первые 16 hex-символов публичного ключа. */
    public String nodeId() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", publicKey[i]));
        return sb.toString();
    }

    @Override
    public String myPublicKey() { return Base64.encodeToString(publicKey, Base64.NO_WRAP); }

    @Override
    public String encryptFor(String recipientPublicKey, String plain) {
        try {
            byte[] rpk = Base64.decode(recipientPublicKey, Base64.NO_WRAP);
            byte[] msg = plain.getBytes("UTF-8");
            byte[] nonce = new byte[Box.NONCEBYTES];
            sodium.randombytes_buf(nonce, nonce.length);
            byte[] cipher = new byte[msg.length + Box.MACBYTES];
            if (sodium.crypto_box_easy(cipher, msg, msg.length, nonce, rpk, privateKey) != 0) return null;
            byte[] out = new byte[nonce.length + cipher.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(cipher, 0, out, nonce.length, cipher.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String decrypt(String senderPublicKey, String cipherB64) {
        try {
            if (senderPublicKey == null || cipherB64 == null) return null;
            byte[] spk = Base64.decode(senderPublicKey, Base64.NO_WRAP);
            byte[] all = Base64.decode(cipherB64, Base64.NO_WRAP);
            if (all.length <= Box.NONCEBYTES + Box.MACBYTES) return null;
            byte[] nonce = new byte[Box.NONCEBYTES];
            byte[] cipher = new byte[all.length - Box.NONCEBYTES];
            System.arraycopy(all, 0, nonce, 0, Box.NONCEBYTES);
            System.arraycopy(all, Box.NONCEBYTES, cipher, 0, cipher.length);
            byte[] plain = new byte[cipher.length - Box.MACBYTES];
            if (sodium.crypto_box_open_easy(plain, cipher, cipher.length, nonce, spk, privateKey) != 0) return null;
            return new String(plain, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
