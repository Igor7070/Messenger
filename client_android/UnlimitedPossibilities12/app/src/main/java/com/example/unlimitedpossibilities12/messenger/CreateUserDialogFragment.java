package com.example.unlimitedpossibilities12.messenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.fragment.app.DialogFragment;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.AuthToken;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.PublicKeyRequest;
import com.example.unlimitedpossibilities12.api.User;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import com.goterl.lazysodium.SodiumAndroid;
import com.goterl.lazysodium.interfaces.Box;
import com.goterl.lazysodium.interfaces.SecretBox;

import android.util.Base64;
import java.security.KeyStore;


public class CreateUserDialogFragment extends DialogFragment {
    private TextInputEditText usernameEditText, passwordEditText, bioEditText;
    private MaterialButton selectAvatarButton, createButton, cancelButton;
    private Uri avatarUri;
    private OnUserCreatedListener listener;
    private static final String TAG = "CreateUserDialog";
    private static final String KEYSTORE_ALIAS = "secret_chat_key";
    private SodiumAndroid sodium = new SodiumAndroid();

    public interface OnUserCreatedListener {
        void onUserCreated(User user);
    }

    public void setOnUserCreatedListener(OnUserCreatedListener listener) {
        this.listener = listener;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

        View view = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_create_user, null);

        usernameEditText = view.findViewById(R.id.username_edit_text);
        passwordEditText = view.findViewById(R.id.password_edit_text);
        bioEditText = view.findViewById(R.id.bio_edit_text);
        selectAvatarButton = view.findViewById(R.id.select_avatar_button);
        createButton = view.findViewById(R.id.create_button);
        cancelButton = view.findViewById(R.id.cancel_button);

        selectAvatarButton.setOnClickListener(v -> openFilePicker());
        createButton.setOnClickListener(v -> createUser());
        cancelButton.setOnClickListener(v -> dismiss());

        builder.setView(view);
        Dialog dialog = builder.create();

        // Делаем фон прозрачным, чтобы CardView полностью контролировал внешний вид
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // Анимация (если она у тебя работает)
        if (dialog.getWindow() != null) {
            dialog.getWindow().setWindowAnimations(R.style.DialogAnimation_create_new_user);
        }

        return dialog;
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(intent, 1);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == Activity.RESULT_OK && data != null) {
            avatarUri = data.getData();
            selectAvatarButton.setText("Avatar selected");
            selectAvatarButton.setIconResource(R.drawable.check_icon);
        }
    }

    private void createUser() {
        String username = usernameEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();
        String bio = bioEditText.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(getActivity(), "Имя пользователя и пароль обязательны", Toast.LENGTH_SHORT).show();
            return;
        }

        ApiService apiService = ApiClient.getApiService();

        RequestBody usernameBody = RequestBody.create(MediaType.parse("text/plain"), username);
        RequestBody passwordBody = RequestBody.create(MediaType.parse("text/plain"), password);
        RequestBody bioBody = bio.isEmpty() ? null : RequestBody.create(MediaType.parse("text/plain"), bio);

        MultipartBody.Part avatarPart = null;
        if (avatarUri != null) {
            try {
                InputStream inputStream = getActivity().getContentResolver().openInputStream(avatarUri);
                byte[] bytes = new byte[inputStream.available()];
                inputStream.read(bytes);
                inputStream.close();
                RequestBody avatarBody = RequestBody.create(MediaType.parse("image/*"), bytes);
                avatarPart = MultipartBody.Part.createFormData("avatar", "avatar.jpg", avatarBody);
            } catch (IOException e) {
                Toast.makeText(getActivity(), "Ошибка загрузки аватара: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        apiService.createUser(usernameBody, passwordBody, bioBody, avatarPart).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    User newUser = response.body();
                    if (newUser.getToken() != null) AuthToken.set(requireContext(), newUser.getToken());
                    generateAndSaveKeys(newUser.getId()); // [ДОБАВЛЕНО] Генерация ключей
                    if (listener != null) {
                        listener.onUserCreated(newUser);
                    }
                    dismiss();
                } else {
                    Log.e("CreateUserDialog", "Не удалось создать пользователя: " + response.code());
                    Toast.makeText(getActivity(), "Не удалось создать пользователя: " + response.code(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Log.e("CreateUserDialog", "Ошибка создания пользователя: " + t.getMessage());
                Toast.makeText(getActivity(), "Ошибка создания пользователя: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void generateAndSaveKeys(long userId) {
        try {
            String alias = KEYSTORE_ALIAS + "_" + userId;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
            String publicKeyAlias = "public_key_" + userId;
            String publicKeyBase64;

            // Ключ лежит в SharedPreferences, а не в AndroidKeyStore — проверяем там же
            // (см. ту же правку в LoginDialogFragment и SecretPrivateChatActivity).
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
            Log.e(TAG, "Error generating or sending keys: " + e.getMessage());
            Toast.makeText(getActivity(), "Failed to initialize encryption keys", Toast.LENGTH_SHORT).show();
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

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
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
            Toast.makeText(getActivity(), "Failed to save private key", Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] getPrivateKeyFromKeystore(String alias) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
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
