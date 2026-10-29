package com.example.unlimitedpossibilities12.messenger;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.AuthToken;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.LoginRequest;
import com.example.unlimitedpossibilities12.api.PublicKeyRequest;
import com.example.unlimitedpossibilities12.api.User;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import com.goterl.lazysodium.SodiumAndroid;
import com.goterl.lazysodium.interfaces.Box;
import com.goterl.lazysodium.interfaces.SecretBox;

import android.util.Base64;

import java.io.IOException;
import java.security.KeyStore;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class LoginDialogFragment extends DialogFragment {
    private TextInputEditText usernameEditText, passwordEditText;
    private MaterialButton loginButton;
    private OnLoginListener listener;
    private static final String TAG = "LoginDialog";
    private static final String KEYSTORE_ALIAS = "secret_chat_key";
    private SodiumAndroid sodium = new SodiumAndroid();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_login, container, false);

        usernameEditText = view.findViewById(R.id.username_edit_text);
        passwordEditText = view.findViewById(R.id.password_edit_text);
        loginButton = view.findViewById(R.id.login_button);

        loginButton.setOnClickListener(v -> login());

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setWindowAnimations(R.style.DialogLoginAnimation);

            // Устанавливаем ширину диалога (90% экрана)
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.8);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);

            // Явно центрируем диалог
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.CENTER;
            params.x = 0; // Сбрасываем любое смещение по X
            params.y = 0; // Сбрасываем любое смещение по Y
            window.setAttributes(params);

            // Логируем размеры для отладки
            Log.d("LoginDialog", "Dialog width: " + width + ", Screen width: " + getResources().getDisplayMetrics().widthPixels);
        }
    }

    private void login() {
        String username = usernameEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();
        if (!username.isEmpty() && !password.isEmpty()) {
            LoginRequest request = new LoginRequest(username, password);
            ApiClient.getApiService().loginUser(request).enqueue(new Callback<User>() {
                @Override
                public void onResponse(Call<User> call, Response<User> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        User user = response.body();
                        if (user.getToken() != null) AuthToken.set(requireContext(), user.getToken());
                        checkAndGenerateKeys(user.getId()); // [ДОБАВЛЕНО] Проверка и генерация ключей
                        if (listener != null) {
                            listener.onLogin(user);
                        }
                        dismiss();
                    } else {
                        Toast.makeText(getContext(), response.message(), Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onFailure(Call<User> call, Throwable t) {
                    Toast.makeText(getContext(), "Error logging in", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(getContext(), "Fields cannot be empty", Toast.LENGTH_SHORT).show();
        }
    }

    public void setOnLoginListener(OnLoginListener listener) {
        this.listener = listener;
    }

    public interface OnLoginListener {
        void onLogin(User user);
    }

    private void checkAndGenerateKeys(long userId) {
        try {
            String alias = KEYSTORE_ALIAS + "_" + userId;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            String publicKeyAlias = "public_key_" + userId;
            String publicKeyBase64;

            // Ключ лежит в SharedPreferences, а не в AndroidKeyStore — проверяем там же.
            // Проверка через keyStore.containsAlias() всегда была ложной и при каждом
            // логине выпускала новую пару, обесценивая уже присланные сообщения.
            boolean hasStoredKeyPair = prefs.contains("encrypted_private_key_" + alias)
                    && prefs.contains("nonce_" + alias)
                    && prefs.contains("secretbox_key_" + alias)
                    && prefs.getString(publicKeyAlias, null) != null;

            if (!hasStoredKeyPair) {
                byte[] privateKey = new byte[Box.SECRETKEYBYTES];
                byte[] publicKey = new byte[Box.PUBLICKEYBYTES];
                sodium.crypto_box_keypair(publicKey, privateKey);
                savePrivateKeyToKeystore(privateKey, alias);
                publicKeyBase64 = Base64.encodeToString(publicKey, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
                prefs.edit().putString(publicKeyAlias, publicKeyBase64).apply();
                Log.d(TAG, "Generated new key pair for userId: " + userId + ", publicKey: " + publicKeyBase64 + ", length: " + publicKeyBase64.length());
            } else {
                publicKeyBase64 = prefs.getString(publicKeyAlias, null);
                if (publicKeyBase64 == null) {
                    Log.e(TAG, "Public key not found in SharedPreferences for userId: " + userId);
                    throw new IllegalStateException("Public key missing for existing private key");
                }
                publicKeyBase64 = publicKeyBase64.replace("=", "");
                prefs.edit().putString(publicKeyAlias, publicKeyBase64).apply();
                Log.d(TAG, "Retrieved public key for userId: " + userId + ", publicKey: " + publicKeyBase64 + ", length: " + publicKeyBase64.length());
            }

            sendPublicKeyToServer(userId, publicKeyBase64);
        } catch (Exception e) {
            Log.e(TAG, "Error checking/generating keys: " + e.getMessage());
            Toast.makeText(getContext(), "Failed to initialize encryption keys", Toast.LENGTH_SHORT).show();
        }
    }

    private void savePrivateKeyToKeystore(byte[] privateKey, String alias) {
        try {
            byte[] key = new byte[SecretBox.KEYBYTES];
            sodium.randombytes_buf(key, SecretBox.KEYBYTES);
            byte[] nonce = new byte[SecretBox.NONCEBYTES];
            sodium.randombytes_buf(nonce, SecretBox.NONCEBYTES);

            byte[] encryptedPrivateKey = new byte[privateKey.length + SecretBox.MACBYTES];
            int result = sodium.crypto_secretbox_easy(encryptedPrivateKey, privateKey, privateKey.length, nonce, key);
            if (result != 0) {
                throw new RuntimeException("Failed to encrypt private key");
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            String encryptedKeyBase64 = Base64.encodeToString(encryptedPrivateKey, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
            String nonceBase64 = Base64.encodeToString(nonce, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
            String secretBoxKeyBase64 = Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");

            prefs.edit()
                    .putString("encrypted_private_key_" + alias, encryptedKeyBase64)
                    .putString("nonce_" + alias, nonceBase64)
                    .putString("secretbox_key_" + alias, secretBoxKeyBase64)
                    .apply();

            Log.d(TAG, "Private key encrypted and saved to SharedPreferences for alias: " + alias +
                    ", encryptedKeyBase64: " + encryptedKeyBase64 + ", length: " + encryptedKeyBase64.length() +
                    ", nonceBase64: " + nonceBase64 + ", length: " + nonceBase64.length() +
                    ", secretBoxKeyBase64: " + secretBoxKeyBase64 + ", length: " + secretBoxKeyBase64.length());
        } catch (Exception e) {
            Log.e(TAG, "Error saving private key: " + e.getMessage());
            Toast.makeText(getContext(), "Failed to save private key", Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] getPrivateKeyFromKeystore(String alias) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            String encryptedKeyBase64 = prefs.getString("encrypted_private_key_" + alias, null);
            String nonceBase64 = prefs.getString("nonce_" + alias, null);
            String secretBoxKeyBase64 = prefs.getString("secretbox_key_" + alias, null);

            if (encryptedKeyBase64 == null || nonceBase64 == null || secretBoxKeyBase64 == null) {
                Log.e(TAG, "Encrypted private key, nonce or secretbox key not found for alias: " + alias);
                return null;
            }

            byte[] encryptedPrivateKey = decodeBase64(encryptedKeyBase64);
            byte[] nonce = decodeBase64(nonceBase64);
            byte[] key = decodeBase64(secretBoxKeyBase64);

            byte[] decryptedPrivateKey = new byte[encryptedPrivateKey.length - SecretBox.MACBYTES];
            int result = sodium.crypto_secretbox_open_easy(decryptedPrivateKey, encryptedPrivateKey, encryptedPrivateKey.length, nonce, key);
            if (result != 0) {
                Log.e(TAG, "Failed to decrypt private key for alias: " + alias);
                return null;
            }

            Log.d(TAG, "Private key retrieved and decrypted for alias: " + alias);
            return decryptedPrivateKey;
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving private key: " + e.getMessage());
            return null;
        }
    }

    private byte[] decodeBase64(String base64Str) {
        try {
            return Base64.decode(base64Str, Base64.URL_SAFE | Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed to decode Base64 as URL_SAFE, trying DEFAULT: " + e.getMessage());
            return Base64.decode(base64Str, Base64.DEFAULT);
        }
    }

    private void sendPublicKeyToServer(long userId, String publicKey) {
        ApiService apiService = ApiClient.getApiService();
        PublicKeyRequest request = new PublicKeyRequest(userId, publicKey);
        apiService.uploadPublicKey(request).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Public key uploaded successfully for userId: " + userId);
                } else {
                    try {
                        Log.e(TAG, "Failed to upload public key: code=" + response.code() + ", error=" + response.errorBody().string());
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading error body: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Error uploading public key: " + t.getMessage());
            }
        });
    }
}