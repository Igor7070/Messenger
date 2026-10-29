package com.example.unlimitedpossibilities12.messenger;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.App;
import com.example.unlimitedpossibilities12.api.AuthToken;
import com.example.unlimitedpossibilities12.api.LoginRequest;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;
import com.example.unlimitedpossibilities12.messenger.notife.PushMessagingService;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Вход в мессенджер — первый экран приложения.
 *
 * Прежде приложение открывалось страницей с тремя разделами, а поля логина и
 * кнопка создания пользователя висели в шапке списка чатов и были видны даже
 * тому, кто уже вошёл. Теперь как в любом мессенджере: при запуске либо этот
 * экран, либо сразу чаты, если сессия ещё жива.
 *
 * Регистрация — вторая вкладка того же экрана, а не отдельное окно поверх
 * списка. Прежние диалоги (LoginDialogFragment, CreateUserDialogFragment) и
 * страница с разделами остались в проекте, просто больше не открываются.
 */
public class AuthActivity extends AppCompatActivity {

    private static final String TAG = "AuthActivity";

    private View content, progress;
    private TextView subtitle, switchHint, avatarName;
    private MaterialButton tabSignIn, tabSignUp, submit, avatarButton;
    private TextInputEditText usernameField, passwordField, bioField;
    private TextInputLayout bioLayout;

    private boolean signUpMode = false;
    private boolean sessionChecked = false;
    private Uri avatarUri;
    private ApiService apiService;

    private final ActivityResultLauncher<String> pickAvatar =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                avatarUri = uri;
                avatarName.setText("Photo selected");
                avatarName.setVisibility(View.VISIBLE);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        apiService = ApiClient.getApiService();

        content = findViewById(R.id.auth_content);
        progress = findViewById(R.id.auth_progress);
        subtitle = findViewById(R.id.auth_subtitle);
        switchHint = findViewById(R.id.auth_switch_hint);
        avatarName = findViewById(R.id.auth_avatar_name);
        tabSignIn = findViewById(R.id.auth_tab_sign_in);
        tabSignUp = findViewById(R.id.auth_tab_sign_up);
        submit = findViewById(R.id.auth_submit);
        avatarButton = findViewById(R.id.auth_avatar_button);
        usernameField = findViewById(R.id.auth_username);
        passwordField = findViewById(R.id.auth_password);
        bioField = findViewById(R.id.auth_bio);
        bioLayout = findViewById(R.id.auth_bio_layout);

        tabSignIn.setOnClickListener(v -> setMode(false));
        tabSignUp.setOnClickListener(v -> setMode(true));
        switchHint.setOnClickListener(v -> setMode(!signUpMode));
        avatarButton.setOnClickListener(v -> pickAvatar.launch("image/*"));
        submit.setOnClickListener(v -> {
            if (signUpMode) signUp(); else signIn();
        });

        setMode(false);
        checkSavedSession();
    }

    /**
     * Сохранённая сессия. Токен живёт 30 дней, поэтому обычно человек экрана
     * входа вообще не видит.
     *
     * Токен мог истечь или быть отозванным — проверяем одним запросом о себе.
     * Сеть недоступна — всё равно пускаем внутрь: офлайн-режим и сохранённая
     * переписка должны работать без сервера.
     */
    private void checkSavedSession() {
        int userId = getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);
        if (userId == -1 || AuthToken.get() == null) {
            String savedName = getSharedPreferences("prefs", MODE_PRIVATE).getString("username", null);
            if (userId != -1 && savedName != null) usernameField.setText(savedName);
            showForm();
            return;
        }

        // Сервер может отвечать долго (на бесплатном хостинге он ещё и
        // просыпается). Ждём недолго и идём внутрь: проверка нужна, чтобы
        // поймать истёкший токен, а не чтобы держать человека на заставке.
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (!sessionChecked) {
                sessionChecked = true;
                openMessenger();
            }
        }, 2500);

        apiService.getUserById(userId).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (sessionChecked) return;
                sessionChecked = true;
                if (response.code() == 401 || response.code() == 403) {
                    sessionExpired();
                } else {
                    openMessenger();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                if (sessionChecked) return;
                sessionChecked = true;
                Log.w(TAG, "Сессию проверить не удалось, пускаем офлайн: " + t.getMessage());
                openMessenger();
            }
        });
    }

    private void sessionExpired() {
        String savedName = getSharedPreferences("prefs", MODE_PRIVATE).getString("username", null);
        AuthToken.clear(this);
        getSharedPreferences("prefs", MODE_PRIVATE).edit().remove("userId").apply();
        if (savedName != null) usernameField.setText(savedName);
        showForm();
        Toast.makeText(this, "Your session has expired — please sign in again", Toast.LENGTH_LONG).show();
    }

    private void showForm() {
        progress.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    /** Переключение вкладок: поля «о себе» и фото нужны только при регистрации. */
    private void setMode(boolean signUp) {
        signUpMode = signUp;
        subtitle.setText(signUp ? "Create an account to start chatting" : "Sign in to continue");
        submit.setText(signUp ? "Create account" : "Sign in");
        switchHint.setText(signUp ? "Already have an account? Sign in" : "No account yet? Sign up");
        bioLayout.setVisibility(signUp ? View.VISIBLE : View.GONE);
        avatarButton.setVisibility(signUp ? View.VISIBLE : View.GONE);
        avatarName.setVisibility(signUp && avatarUri != null ? View.VISIBLE : View.GONE);
        highlightTabs();
    }

    private void highlightTabs() {
        tabSignIn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(signUpMode ? "#172033" : "#6366F1")));
        tabSignUp.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(signUpMode ? "#6366F1" : "#172033")));
        tabSignIn.setTextColor(android.graphics.Color.parseColor(signUpMode ? "#94A3B8" : "#FFFFFF"));
        tabSignUp.setTextColor(android.graphics.Color.parseColor(signUpMode ? "#FFFFFF" : "#94A3B8"));
    }

    private String text(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private void signIn() {
        String username = text(usernameField);
        String password = text(passwordField);
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Enter your username and password", Toast.LENGTH_SHORT).show();
            return;
        }

        setBusy(true);
        apiService.loginUser(new LoginRequest(username, password)).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    completeAuth(response.body());
                } else {
                    setBusy(false);
                    Toast.makeText(AuthActivity.this,
                            response.code() == 401 || response.code() == 404
                                    ? "Wrong username or password"
                                    : "Could not sign in (" + response.code() + ")",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                setBusy(false);
                Toast.makeText(AuthActivity.this, "No connection to the server", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void signUp() {
        String username = text(usernameField);
        String password = text(passwordField);
        String bio = text(bioField);
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Username and password are required", Toast.LENGTH_SHORT).show();
            return;
        }

        RequestBody usernameBody = RequestBody.create(MediaType.parse("text/plain"), username);
        RequestBody passwordBody = RequestBody.create(MediaType.parse("text/plain"), password);
        RequestBody bioBody = bio.isEmpty() ? null : RequestBody.create(MediaType.parse("text/plain"), bio);

        MultipartBody.Part avatarPart = null;
        if (avatarUri != null) {
            try {
                avatarPart = readAvatar(avatarUri);
            } catch (IOException e) {
                Toast.makeText(this, "Could not read the photo", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        setBusy(true);
        apiService.createUser(usernameBody, passwordBody, bioBody, avatarPart).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    completeAuth(response.body());
                } else {
                    setBusy(false);
                    Toast.makeText(AuthActivity.this,
                            response.code() == 409
                                    ? "That username is already taken"
                                    : "Could not create the account (" + response.code() + ")",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                setBusy(false);
                Toast.makeText(AuthActivity.this, "No connection to the server", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Читаем картинку целиком через ContentResolver.
     *
     * InputStream.available() у некоторых провайдеров возвращает не весь размер,
     * поэтому копируем до конца потока, иначе аватар уходил бы обрезанным.
     */
    private MultipartBody.Part readAvatar(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Поток недоступен");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            RequestBody body = RequestBody.create(MediaType.parse("image/*"), out.toByteArray());
            return MultipartBody.Part.createFormData("avatar", "avatar.jpg", body);
        }
    }

    /** Вход удался: сохраняем сессию, готовим ключи, поднимаем соединение. */
    private void completeAuth(User user) {
        if (user.getToken() != null) AuthToken.set(this, user.getToken());

        SharedPreferences.Editor prefs = getSharedPreferences("prefs", MODE_PRIVATE).edit();
        prefs.putInt("userId", user.getId());
        prefs.putString("username", user.getUsername());
        prefs.apply();

        // Приложение держит текущего пользователя у себя и по этому вызову
        // регистрирует устройство для push: иначе уведомления заработали бы
        // только после перезапуска.
        if (getApplication() instanceof App) {
            App app = (App) getApplication();
            app.setCurrentUserId(user.getId()); // внутри регистрирует устройство для push
            app.setCurrentUsername(user.getUsername());
        } else {
            PushMessagingService.registerCurrentToken(this);
        }

        AuthKeys.ensure(this, user.getId());

        startCallService("UPDATE_USER");
        startCallService("LOGIN_USER");

        openMessenger();
    }

    /** Сообщить сервису соединения о новом пользователе. minSdk 24 — отсюда проверка. */
    private void startCallService(String action) {
        Intent intent = new Intent(this, CallService.class);
        intent.putExtra("action", action);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void openMessenger() {
        Intent intent = new Intent(this, MessengerMainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }
}
