package com.example.unlimitedpossibilities12.messenger;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.ContextThemeWrapper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ListPopupWindow;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.CallRequest;
import com.example.unlimitedpossibilities12.api.Chat;
import com.example.unlimitedpossibilities12.api.GroupCallRequest;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;
import com.example.unlimitedpossibilities12.messenger.popupMenu.PopupMenuAdapter;
import com.example.unlimitedpossibilities12.messenger.popupMenu.PopupMenuItem;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class UserProfileActivity extends AppCompatActivity {
    private static final String TAG = "UserProfileActivity";
    private static final String ACTION_SHOW_CALL_DIALOG = "com.example.unlimitedpossibilities12.SHOW_CALL_DIALOG";
    private static final String ACTION_NEW_NOTIFICATION = "com.example.unlimitedpossibilities12.NEW_NOTIFICATION";
    private static final String ACTION_END_CALL = "com.example.unlimitedpossibilities12.END_CALL";
    private TextView usernameTextView, avatarTextView, bioTextView, bioTitle, statusTextView;
    private View statusIndicator;
    private EditText bioEditText;
    private ImageView avatarImageView, menuButton, bioEditIcon;
    private Button updateButton, personalChatsButton, sendMessageButton, callButton, videoCallButton, avatarInputButton;
    private LinearLayout currentUserButtons;
    private ApiService apiService;
    private int currentUserId, userId;
    private User user;
    private MediaPlayer mediaPlayer;
    private CallRequest incomingCall;
    private boolean userInteracted = false;
    private Uri newAvatarUri;
    private CallService callService;
    private GroupCallRequest incomingGroupCall;
    private boolean isBound = false;
    private boolean isUserOnline = false;
    private BroadcastReceiver callReceiver, notificationReceiver, endCallReceiver, userStatusReceiver;
    // ADDED: Кнопка для вызова с переводом
    private Button translateCallButton;

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CallService.LocalBinder binder = (CallService.LocalBinder) service;
            callService = binder.getService();
            isBound = true;
            Log.d(TAG, "Connected to CallService");
            handleIntent(getIntent());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            callService = null;
            Log.d(TAG, "Disconnected from CallService");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_profile);

        // Запрещаем поворот экрана
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        apiService = ApiClient.getApiService();
        mediaPlayer = null; // Инициализация отложена до необходимости

        // Инициализация компонентов
        usernameTextView = findViewById(R.id.username_text_view);
        avatarImageView = findViewById(R.id.avatar_image_view);
        avatarTextView = findViewById(R.id.avatar_text_view);
        bioTitle = findViewById(R.id.bio_title);
        bioTextView = findViewById(R.id.bio_text_view);
        bioEditText = findViewById(R.id.bio_edit_text);
        avatarInputButton = findViewById(R.id.avatar_input_button);
        updateButton = findViewById(R.id.update_button);
        personalChatsButton = findViewById(R.id.personal_chats_button);
        currentUserButtons = findViewById(R.id.current_user_buttons);
        sendMessageButton = findViewById(R.id.send_message_button);
        callButton = findViewById(R.id.call_button);
        videoCallButton = findViewById(R.id.video_call_button);
        translateCallButton = findViewById(R.id.translate_call_button); // ADDED: Инициализация кнопки вызова с переводом
        menuButton = findViewById(R.id.menu_button);
        bioEditIcon = findViewById(R.id.bio_edit_icon);
        statusTextView = findViewById(R.id.status_text_view);
        statusIndicator = findViewById(R.id.status_indicator);

        // Иконка ассистента в нижнем правом углу
        ImageView assistantIcon = findViewById(R.id.assistant_icon);
        assistantIcon.setOnClickListener(v -> {
            AssistantChatDialogFragment dialog = new AssistantChatDialogFragment();
            dialog.show(getSupportFragmentManager(), "AssistantChatDialog");
        });
        assistantIcon.bringToFront();

        // Анимация увеличения при нажатии (такая же, как в главной активности)
        assistantIcon.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate()
                                .scaleX(1.20f)
                                .scaleY(1.20f)
                                .alpha(0.90f)
                                .setDuration(80)
                                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                                .start();
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(220)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                        break;
                }
                return false;
            }
        });

        // Получение параметров
        currentUserId = getIntent().getIntExtra("currentUserId", -1);
        userId = getIntent().getIntExtra("userId", -1);

        // Загрузка данных пользователя
        fetchUser();

        // Обработка взаимодействия пользователя
        findViewById(android.R.id.content).setOnClickListener(v -> userInteracted = true);

        // Логика кнопок
        avatarInputButton.setOnClickListener(v -> openFilePicker());
        updateButton.setOnClickListener(v -> handleUpdateUser());
        personalChatsButton.setOnClickListener(v -> handlePersonalChats());
        sendMessageButton.setOnClickListener(v -> handleSendMessage());
        callButton.setOnClickListener(v -> handleCall());
        videoCallButton.setOnClickListener(v -> handleVideoCall());
        translateCallButton.setOnClickListener(v -> handleTranslateCall()); // ADDED: Обработчик для кнопки вызова с переводом
        menuButton.setOnClickListener(v -> showSettingsMenu());

        // Привязка к CallService
        Intent serviceIntent = new Intent(this, CallService.class);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);

        // Инициализация BroadcastReceiver для звонков
        callReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Received broadcast: " + intent.toString());
                CallRequest callRequest = (CallRequest) intent.getSerializableExtra("callRequest");
                GroupCallRequest groupCallRequest = (GroupCallRequest) intent.getSerializableExtra("groupCallRequest");
                if (callRequest != null && !isFinishing() && !isDestroyed()) {
                    incomingCall = callRequest;
                    showIncomingCallDialog(callRequest);
                } else if (groupCallRequest != null && !isFinishing() && !isDestroyed()) {
                    incomingGroupCall = groupCallRequest;
                    showIncomingGroupCallDialog(groupCallRequest);
                } else {
                    Log.w(TAG, "Received call broadcast but activity is not in a valid state or no valid request. Extras: " + intent.getExtras());
                }
            }
        };

        // Инициализация BroadcastReceiver для уведомлений
        notificationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String notification = intent.getStringExtra("notification");
                if (notification != null) {
                    Pattern pattern = Pattern.compile("\\(chatId: (\\d+)\\)");
                    Matcher matcher = pattern.matcher(notification);
                    long chatId = -1;
                    if (matcher.find()) {
                        try {
                            chatId = Long.parseLong(matcher.group(1));
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "Error parsing chatId: " + e.getMessage());
                        }
                    }
                    if (chatId != -1) {
                        Toast.makeText(UserProfileActivity.this, notification, Toast.LENGTH_LONG).show();
                    } else {
                        Log.w(TAG, "No valid chatId in notification: " + notification);
                    }
                }
            }
        };

        // Инициализация BroadcastReceiver для endCall
        endCallReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Received END_CALL broadcast with intent: " + intent.toString());
                String roomId = intent.getStringExtra("roomId");
                Log.d(TAG, "END_CALL for roomId: " + roomId + ", incomingCall: " + (incomingCall != null ? incomingCall.getRoomId() : "null"));
                if (roomId == null) {
                    Log.e(TAG, "roomId is null in END_CALL broadcast");
                    return;
                }
                if (incomingCall != null && roomId.equals(incomingCall.getRoomId())) {
                    Log.d(TAG, "Processing endCall for matching roomId: " + roomId);
                    // Закрываем диалог входящего вызова
                    FragmentManager fm = getSupportFragmentManager();
                    IncomingCallDialogFragment dialog = (IncomingCallDialogFragment) fm.findFragmentByTag("IncomingCallDialog");
                    if (dialog != null && dialog.isAdded()) {
                        try {
                            dialog.dismissAllowingStateLoss();
                            Log.d(TAG, "Incoming call dialog dismissed for roomId: " + roomId);
                        } catch (Exception e) {
                            Log.e(TAG, "Error dismissing dialog: " + e.getMessage());
                        }
                    } else {
                        Log.d(TAG, "No active dialog found for IncomingCallDialog");
                    }
                    // Показываем уведомление об отмене звонка
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(UserProfileActivity.this, "The call was cancelled.", Toast.LENGTH_LONG).show();
                        Log.d(TAG, "Toast shown for roomId: " + roomId);
                    }
                    // Сбрасываем incomingCall
                    incomingCall = null;
                    Log.d(TAG, "incomingCall reset for roomId: " + roomId);
                } else {
                    Log.w(TAG, "No matching incoming call for roomId: " + roomId + ", current incomingCall: " + (incomingCall != null ? incomingCall.getRoomId() : "null"));
                }
            }
        };

        userStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long receivedUserId = intent.getLongExtra("userId", -1);
                boolean online = intent.getBooleanExtra("online", false);
                if (receivedUserId == userId && !isFinishing() && !isDestroyed()) {
                    updateUserStatus(online);
                }
            }
        };
    }

    private void updateUserStatus(boolean online) {
        isUserOnline = online;

        statusTextView.setText(online ? "Online" : "Offline");
        statusIndicator.setVisibility(online ? View.VISIBLE : View.GONE);

        if (userId == currentUserId) {
            // === СВОЙ ПРОФИЛЬ ===
            statusTextView.setVisibility(View.GONE);           // Скрываем текст
            // statusTextView.setLayoutParams(...) не трогаем — чтобы не было прыжка
        } else {
            // === ЧУЖОЙ ПРОФИЛЬ ===
            statusTextView.setVisibility(View.VISIBLE);

            // Позиционируем текст под именем только для чужого профиля
            statusTextView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL | Gravity.TOP
            ) {{
                topMargin = (int) (44 * getResources().getDisplayMetrics().density); // 44dp
            }});

            statusTextView.requestLayout();
        }

        // Логирование (оставил для отладки)
        Log.d(TAG, "Updated status for userId: " + userId +
                ", online: " + online +
                ", isOwnProfile: " + (userId == currentUserId) +
                ", statusTextView visibility: " + statusTextView.getVisibility());
    }

    @Override
    protected void onStart() {
        super.onStart();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(callReceiver,
                new IntentFilter(ACTION_SHOW_CALL_DIALOG));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(notificationReceiver,
                new IntentFilter(ACTION_NEW_NOTIFICATION));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(endCallReceiver,
                new IntentFilter(ACTION_END_CALL));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(userStatusReceiver,
                new IntentFilter("com.example.unlimitedpossibilities12.USER_STATUS"));
        Log.d(TAG, "Receivers registered in onStart, including endCallReceiver for " + ACTION_END_CALL);
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(callReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(notificationReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(endCallReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(userStatusReceiver);
        Log.d(TAG, "Receivers unregistered in onStop, including endCallReceiver");
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void fetchUser() {
        Call<User> call = apiService.getUserById(userId);
        call.enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    user = response.body();
                    usernameTextView.setText(user.getUsername());
                    // Сбрасываем анимации и устанавливаем видимость для био
                    bioTextView.clearAnimation();
                    bioEditText.clearAnimation();
                    bioTextView.setAlpha(1f);
                    bioTextView.setVisibility(View.VISIBLE);
                    bioEditText.setVisibility(View.GONE);
                    bioTextView.setText(user.getBio() != null ? user.getBio() : "");
                    bioEditText.setText(user.getBio() != null ? user.getBio() : "");

                    // Загрузка аватара
                    if (user.getAvatar() != null && !user.getAvatar().isEmpty()) {
                        String avatarUrl = "https://unlimitedpossibilities12.org" + user.getAvatar() + "?t=" + System.currentTimeMillis();
                        Glide.with(UserProfileActivity.this)
                                .load(avatarUrl)
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .skipMemoryCache(true)
                                .circleCrop()
                                .transition(DrawableTransitionOptions.withCrossFade())
                                .into(avatarImageView);
                        avatarTextView.setVisibility(View.GONE);

                        avatarImageView.setOnClickListener(v -> {
                            Intent intent = new Intent(UserProfileActivity.this, FullScreenImageActivity.class);
                            intent.putExtra("imageUrl", avatarUrl);
                            intent.putExtra("username", user.getUsername());
                            startActivity(intent);
                        });
                    } else {
                        Glide.with(UserProfileActivity.this)
                                .load(R.drawable.default_messenger_avatar)
                                .circleCrop()
                                .transition(DrawableTransitionOptions.withCrossFade())
                                .into(avatarImageView);
                        avatarTextView.setText(user.getUsername().substring(0, 1).toUpperCase());
                        avatarTextView.setVisibility(View.VISIBLE);

                        avatarImageView.setOnClickListener(v -> {
                            Intent intent = new Intent(UserProfileActivity.this, FullScreenImageActivity.class);
                            intent.putExtra("username", user.getUsername());
                            startActivity(intent);
                        });
                    }

                    // Управление отступами и видимостью
                    ConstraintLayout.LayoutParams avatarParams = (ConstraintLayout.LayoutParams) findViewById(R.id.avatar_card).getLayoutParams();
                    TextView statusTextView = findViewById(R.id.status_text_view);
                    ConstraintLayout.LayoutParams containerParams = (ConstraintLayout.LayoutParams) findViewById(R.id.username_container).getLayoutParams();

                    if (userId == currentUserId) {
                        currentUserButtons.setVisibility(View.VISIBLE);
                        avatarInputButton.setVisibility(View.VISIBLE);
                        menuButton.setVisibility(View.VISIBLE);
                        bioEditIcon.setVisibility(View.VISIBLE);
                        bioEditIcon.setOnClickListener(v -> {
                            bioTextView.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                                bioTextView.setVisibility(View.GONE);
                                bioEditText.setVisibility(View.VISIBLE);
                                bioEditText.animate().alpha(1f).setDuration(200).start();
                            }).start();
                        });
                        bioEditText.setOnEditorActionListener((v, actionId, event) -> {
                            if (actionId == EditorInfo.IME_ACTION_DONE) {
                                bioEditText.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                                    bioEditText.setVisibility(View.GONE);
                                    bioTextView.setText(bioEditText.getText());
                                    bioTextView.setVisibility(View.VISIBLE);
                                    bioTextView.setAlpha(1f);
                                    bioTextView.animate().alpha(1f).setDuration(200).start();
                                }).start();
                                return true;
                            }
                            return false;
                        });
                        // Скрываем статус и минимизируем отступы
                        statusTextView.setVisibility(View.GONE);
                        statusIndicator.setVisibility(View.VISIBLE);
                        // Устанавливаем начальный статус индикатора
                        if (isBound && callService != null && callService.getStompClient() != null && callService.getStompClient().isConnected()) {
                            callService.requestUserStatus(currentUserId);
                        }
                        avatarParams.topMargin = 0; // 0dp для текущего пользователя
                        containerParams.topMargin = 0; // Без изменений для текущего пользователя
                        findViewById(R.id.avatar_card).setLayoutParams(avatarParams);
                        findViewById(R.id.username_container).setLayoutParams(containerParams);
                        Log.d(TAG, "Removed status and set avatar margin to 0dp, container margin to 0dp for current user, userId: " + userId);
                    } else {
                        sendMessageButton.setVisibility(View.VISIBLE);
                        findViewById(R.id.call_buttons_container).setVisibility(View.VISIBLE);
                        findViewById(R.id.translate_call_container).setVisibility(View.VISIBLE);
                        menuButton.setVisibility(View.VISIBLE); // [Добавлено] Показываем кнопку
                        updateUserStatus(user.isOnline());
                        statusTextView.setVisibility(View.VISIBLE);
                        statusTextView.setLayoutParams(new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                Gravity.CENTER_HORIZONTAL | Gravity.TOP
                        ) {{
                            topMargin = (int) (44 * getResources().getDisplayMetrics().density); // 44dp
                        }});
                        avatarParams.topMargin = (int) (2 * getResources().getDisplayMetrics().density); // 2dp
                        containerParams.topMargin = (int) (-16 * getResources().getDisplayMetrics().density); // -16dp
                        findViewById(R.id.avatar_card).setLayoutParams(avatarParams);
                        findViewById(R.id.username_container).setLayoutParams(containerParams);
                        Log.d(TAG, "Set initial status from REST API for userId: " + userId + ", online: " + user.isOnline() +
                                ", container topMargin: -4dp");
                        if (isBound && callService != null && callService.getStompClient() != null && callService.getStompClient().isConnected()) {
                            callService.requestUserStatus(userId);
                        }
                    }
                } else {
                    Toast.makeText(UserProfileActivity.this, "Failed to fetch user data", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Toast.makeText(UserProfileActivity.this, "Error fetching user: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showSettingsMenu() {
        ListPopupWindow listPopupWindow = new ListPopupWindow(this);
        listPopupWindow.setAnchorView(menuButton);
        listPopupWindow.setModal(true);
        listPopupWindow.setDropDownGravity(Gravity.END);
        listPopupWindow.setWidth(550);
        listPopupWindow.setVerticalOffset(4);

        listPopupWindow.setBackgroundDrawable(getDrawable(R.drawable.popup_menu_background));

        List<PopupMenuItem> menuItems = new ArrayList<>();

        if (userId == currentUserId) {
            menuItems.add(new PopupMenuItem("Delete account", null, Color.parseColor("#E2E8F0")));
        } else {
            menuItems.add(new PopupMenuItem("Open Secret Chat",
                    ContextCompat.getDrawable(this, R.drawable.ic_lock),
                    Color.parseColor("#E2E8F0")));

            menuItems.add(new PopupMenuItem("Secure Call (E2EE)",
                    ContextCompat.getDrawable(this, R.drawable.ic_secure_call),
                    Color.parseColor("#4ADE80"))); // салатовый
        }

        PopupMenuAdapter adapter = new PopupMenuAdapter(this, menuItems);
        listPopupWindow.setAdapter(adapter);

        listPopupWindow.setOnItemClickListener((parent, view, position, id) -> {
            String title = menuItems.get(position).title;

            if ("Delete account".equals(title)) {
                showDeleteAccountDialog();
            } else if ("Open Secret Chat".equals(title)) {
                handleSecretChat();
            } else if ("Secure Call (E2EE)".equals(title)) {
                handleSecureCall();
            }
            listPopupWindow.dismiss();
        });

        listPopupWindow.show();
    }

    private void showDeleteAccountDialog() {
        DeleteAccountDialogFragment dialog = new DeleteAccountDialogFragment(() -> handleDeleteAccount());
        dialog.show(getSupportFragmentManager(), "DeleteAccountDialog");
    }

    private void handleDeleteAccount() {
        apiService.deleteUser(currentUserId).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                Log.d(TAG, "Delete user response code: " + response.code());
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseBody = response.body().string();
                        Log.d(TAG, "Delete user response: " + responseBody);
                        // Очищаем SharedPreferences
                        getSharedPreferences("prefs", MODE_PRIVATE)
                                .edit()
                                .remove("userId")
                                .remove("username")
                                .apply();
                        Log.d(TAG, "SharedPreferences cleared");
                        // Перенаправляем на MessengerMainActivity
                        Intent intent = new Intent(UserProfileActivity.this, MessengerMainActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        Log.d(TAG, "Starting MessengerMainActivity");
                        finish();
                        Log.d(TAG, "UserProfileActivity finished");
                        Toast.makeText(UserProfileActivity.this, "Account deleted successfully!", Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading response body: " + e.getMessage());
                        Toast.makeText(UserProfileActivity.this, "Error processing response", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Log.e(TAG, "Failed to delete account: HTTP " + response.code());
                    Toast.makeText(UserProfileActivity.this, "Failed to delete account: " + response.code(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Log.e(TAG, "Error deleting account: " + t.getMessage());
                Toast.makeText(UserProfileActivity.this, "Error deleting account: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            newAvatarUri = data.getData();
            if (newAvatarUri != null) {
                Glide.with(this).load(newAvatarUri).circleCrop().into(avatarImageView);
            }
        }
    }

    private void handleUpdateUser() {
        RequestBody bioBody = RequestBody.create(MediaType.parse("text/plain"), bioEditText.getText().toString());
        MultipartBody.Part avatarPart = null;
        if (newAvatarUri != null) {
            try {
                InputStream inputStream = getContentResolver().openInputStream(newAvatarUri);
                byte[] bytes = new byte[inputStream.available()];
                inputStream.read(bytes);
                inputStream.close();
                RequestBody avatarBody = RequestBody.create(MediaType.parse("image/*"), bytes);
                avatarPart = MultipartBody.Part.createFormData("avatar", "avatar.jpg", avatarBody);
            } catch (Exception e) {
                Toast.makeText(this, "Error loading avatar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        apiService.updateUser(userId, bioBody, avatarPart).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful()) {
                    // Сбрасываем анимации и устанавливаем видимость
                    bioTextView.clearAnimation();
                    bioEditText.clearAnimation();
                    bioEditText.setVisibility(View.GONE);
                    bioTextView.setVisibility(View.VISIBLE);
                    bioTextView.setAlpha(1f);
                    newAvatarUri = null;
                    Toast.makeText(UserProfileActivity.this, "User updated successfully!", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    fetchUser(); // Загружаем новые данные
                } else {
                    Toast.makeText(UserProfileActivity.this, "Failed to update user: " + response.code(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Toast.makeText(UserProfileActivity.this, "Error updating user: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handlePersonalChats() {
        Intent intent = new Intent(this, PersonalChatsActivity.class);
        intent.putExtra("currentUserId", currentUserId);
        startActivity(intent);
    }

    private void handleSendMessage() {
        if (currentUserId == -1) {
            Toast.makeText(this, "You need to create an account or log in", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Unauthorized user attempted to send a message to userId: " + userId);
            return;
        }

        if (currentUserId == userId) {
            Toast.makeText(this, "You cannot send a message to yourself", Toast.LENGTH_SHORT).show();
            return;
        }

        final String targetUsername = user.getUsername();
        apiService.getUserById(currentUserId).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String currentUsername = response.body().getUsername();
                    getSharedPreferences("prefs", MODE_PRIVATE)
                            .edit()
                            .putString("username", currentUsername)
                            .apply();
                    String chatName = targetUsername + "_" + currentUsername;

                    apiService.checkChatByUsers(targetUsername, currentUsername).enqueue(new Callback<Map<String, Object>>() {
                        @Override
                        public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                Object chatIdObj = response.body().get("chatId");
                                long chatId = chatIdObj instanceof Number ? ((Number) chatIdObj).longValue() : -1;
                                if (chatId != -1) {
                                    apiService.checkChatExists(currentUserId, chatId).enqueue(new Callback<ResponseBody>() {
                                        @Override
                                        public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                                            try {
                                                if (response.isSuccessful() && response.body() != null) {
                                                    String responseString = response.body().string();
                                                    if ("Chat exists in user's private chats".equals(responseString)) {
                                                        navigateToPrivateChat(chatId);
                                                        Toast.makeText(UserProfileActivity.this, "Navigating to chat with " + targetUsername + "!", Toast.LENGTH_SHORT).show();
                                                    } else {
                                                        Toast.makeText(UserProfileActivity.this, "Error checking chat existence: " + responseString, Toast.LENGTH_SHORT).show();
                                                    }
                                                } else if (response.code() == 404) {
                                                    apiService.addChatToUser(currentUserId, chatId).enqueue(new Callback<Void>() {
                                                        @Override
                                                        public void onResponse(Call<Void> call, Response<Void> response) {
                                                            if (response.isSuccessful()) {
                                                                navigateToPrivateChat(chatId);
                                                                Toast.makeText(UserProfileActivity.this, "Navigating to chat with " + targetUsername + "!", Toast.LENGTH_SHORT).show();
                                                            } else {
                                                                Toast.makeText(UserProfileActivity.this, "Failed to add chat to user", Toast.LENGTH_SHORT).show();
                                                            }
                                                        }

                                                        @Override
                                                        public void onFailure(Call<Void> call, Throwable t) {
                                                            Toast.makeText(UserProfileActivity.this, "Error adding chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                                                        }
                                                    });
                                                } else {
                                                    Toast.makeText(UserProfileActivity.this, "Error checking chat existence: HTTP " + response.code(), Toast.LENGTH_SHORT).show();
                                                }
                                            } catch (IOException e) {
                                                Toast.makeText(UserProfileActivity.this, "Error reading response: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                            }
                                        }

                                        @Override
                                        public void onFailure(Call<ResponseBody> call, Throwable t) {
                                            Toast.makeText(UserProfileActivity.this, "Error checking chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                } else {
                                    Chat newChat = new Chat(chatName, true);
                                    apiService.createChat(newChat).enqueue(new Callback<Chat>() {
                                        @Override
                                        public void onResponse(Call<Chat> call, Response<Chat> response) {
                                            if (response.isSuccessful() && response.body() != null) {
                                                navigateToPrivateChat(response.body().getId());
                                                Toast.makeText(UserProfileActivity.this, "Navigating to chat with " + targetUsername + "!", Toast.LENGTH_SHORT).show();
                                            } else {
                                                Toast.makeText(UserProfileActivity.this, "Failed to create chat", Toast.LENGTH_SHORT).show();
                                            }
                                        }

                                        @Override
                                        public void onFailure(Call<Chat> call, Throwable t) {
                                            Toast.makeText(UserProfileActivity.this, "Error creating chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                            } else {
                                Toast.makeText(UserProfileActivity.this, "Failed to check chat: " + response.code(), Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                            Toast.makeText(UserProfileActivity.this, "Error checking chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Toast.makeText(UserProfileActivity.this, "Failed to fetch current user", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Toast.makeText(UserProfileActivity.this, "Error fetching current user: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void navigateToPrivateChat(long chatId) {
        Intent intent = new Intent(this, PrivateChatActivity.class);
        intent.putExtra("chatId", chatId);
        startActivity(intent);
    }

    private void handleCall() {
        if (currentUserId == -1) {
            Toast.makeText(this, "You need to create an account or log in", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Unauthorized user attempted to call userId: " + userId);
            return;
        }

        String roomId = "room-" + currentUserId + "-" + userId;
        CallRequest requestBody = new CallRequest(currentUserId, userId, roomId, user.getUsername(), "voice");
        Log.d(TAG, "Initiating call with roomId: " + roomId);

        apiService.initiateCall(requestBody).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    apiService.getUserById(currentUserId).enqueue(new Callback<User>() {
                        @Override
                        public void onResponse(Call<User> call, Response<User> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                String currentUserName = response.body().getUsername();
                                Log.d(TAG, "Caller: " + currentUserName + ", Recipient: " + user.getUsername());

                                Intent intent = new Intent(UserProfileActivity.this, VoiceCallActivity.class);
                                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                                intent.putExtra("recipientId", String.valueOf(userId));
                                intent.putExtra("roomId", roomId);
                                intent.putExtra("callerName", currentUserName);
                                intent.putExtra("recipientName", user.getUsername());
                                intent.putExtra("isCaller", true);
                                startActivity(intent);
                                Toast.makeText(UserProfileActivity.this, "Calling " + user.getUsername() + "...", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(UserProfileActivity.this, "Failed to fetch caller name", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<User> call, Throwable t) {
                            Toast.makeText(UserProfileActivity.this, "Error fetching caller name: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Toast.makeText(UserProfileActivity.this, "Failed to initiate call: " + response.code(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Failed to initiate call: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Toast.makeText(UserProfileActivity.this, "Error initiating call: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Error initiating call: " + t.getMessage());
            }
        });
    }

    private void handleVideoCall() {
        if (currentUserId == -1) {
            Toast.makeText(this, "You need to create an account or log in", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Unauthorized user attempted to video call userId: " + userId);
            return;
        }

        String roomId = "room-" + currentUserId + "-" + userId;
        CallRequest requestBody = new CallRequest(currentUserId, userId, roomId, user.getUsername(), "video");
        Log.d(TAG, "Initiating video call with roomId: " + roomId);

        apiService.initiateCall(requestBody).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    apiService.getUserById(currentUserId).enqueue(new Callback<User>() {
                        @Override
                        public void onResponse(Call<User> call, Response<User> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                String currentUserName = response.body().getUsername();
                                Log.d(TAG, "Caller: " + currentUserName + ", Recipient: " + user.getUsername());

                                Intent intent = new Intent(UserProfileActivity.this, VideoCallActivity.class);
                                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                                intent.putExtra("recipientId", String.valueOf(userId));
                                intent.putExtra("roomId", roomId);
                                intent.putExtra("callerName", currentUserName);
                                intent.putExtra("recipientName", user.getUsername());
                                intent.putExtra("isCaller", true);
                                startActivity(intent);
                                Toast.makeText(UserProfileActivity.this, "Video calling " + user.getUsername() + "...", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(UserProfileActivity.this, "Failed to fetch caller name", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<User> call, Throwable t) {
                            Toast.makeText(UserProfileActivity.this, "Error fetching caller name: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Toast.makeText(UserProfileActivity.this, "Failed to initiate video call: " + response.code(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Failed to initiate video call: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Toast.makeText(UserProfileActivity.this, "Error initiating video call: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Error initiating video call: " + t.getMessage());
            }
        });
    }

    private void stopCallSoundLocally() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                    Log.d(TAG, "Call sound stopped");
                }
                mediaPlayer.reset();
                mediaPlayer.release();
                mediaPlayer = null;
                Log.d(TAG, "Call sound stopped and MediaPlayer released locally");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping MediaPlayer: " + e.getMessage());
            }
        }
    }

    private void handleIntent(Intent intent) {
        if (intent != null) {
            if (intent.getBooleanExtra("showCallDialog", false)) {
                CallRequest callRequest = (CallRequest) intent.getSerializableExtra("callRequest");
                if (callRequest != null) {
                    incomingCall = callRequest;
                    showIncomingCallDialog(callRequest);
                }
            } else if (intent.getBooleanExtra("showGroupCallDialog", false)) {
                GroupCallRequest groupCallRequest = (GroupCallRequest) intent.getParcelableExtra("groupCallRequest");
                if (groupCallRequest != null) {
                    incomingGroupCall = groupCallRequest;
                    showIncomingGroupCallDialog(groupCallRequest);
                }
            }
        }
    }

    private void showIncomingCallDialog(CallRequest callRequest) {
        if (isFinishing() || isDestroyed()) {
            Log.w(TAG, "Activity is finishing or destroyed, cannot show dialog");
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved()) {
            Log.w(TAG, "FragmentManager state is saved, cannot show dialog");
            return;
        }

        // Проверяем, не открыт ли уже диалог
        IncomingCallDialogFragment existingDialog = (IncomingCallDialogFragment) fm.findFragmentByTag("IncomingCallDialog");
        if (existingDialog != null && existingDialog.isAdded()) {
            Log.d(TAG, "Incoming call dialog already shown for roomId: " + callRequest.getRoomId());
            return;
        }

        // Показываем уведомление
        String callerName = callRequest.getCallerName() != null ? callRequest.getCallerName() : "User " + callRequest.getCallerId();
        String notificationMessage;
        switch (callRequest.getType()) {
            case "video":
                notificationMessage = getString(R.string.incoming_video_call, callerName);
                break;
            case "voice":
            default:
                notificationMessage = getString(R.string.incoming_voice_call, callerName);
                break;
        }
        Toast.makeText(this, notificationMessage, Toast.LENGTH_SHORT).show();

        IncomingCallDialogFragment dialog = new IncomingCallDialogFragment(callRequest, this::acceptCall, this::rejectCall);
        try {
            dialog.show(fm, "IncomingCallDialog");
            Log.d(TAG, "IncomingCallDialog shown for roomId: " + callRequest.getRoomId() + ", type: " + callRequest.getType());
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error showing IncomingCallDialog: " + e.getMessage());
        }
    }

    private void acceptCall() {
        if (incomingCall == null) {
            Log.e(TAG, "Cannot accept call: incomingCall is null");
            return;
        }

        if (isBound && callService != null) {
            callService.stopCallSound();
            callService.sendSignal("acceptCall", incomingCall.getRoomId());
        } else {
            Log.w(TAG, "CallService not bound, stopping sound locally");
            stopCallSoundLocally();
        }

        apiService.getUserById(incomingCall.getCallerId()).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String callerName = response.body().getUsername();
                    apiService.getUserById(currentUserId).enqueue(new Callback<User>() {
                        @Override
                        public void onResponse(Call<User> call, Response<User> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                String recipientName = response.body().getUsername();
                                Intent intent;
                                switch (incomingCall.getType()) {
                                    case "video":
                                        intent = new Intent(UserProfileActivity.this, VideoCallActivity.class);
                                        break;
                                    case "translate":
                                        intent = new Intent(UserProfileActivity.this, VoiceCallWithTranslateActivity.class);
                                        break;
                                    case "voice_secure":  // ← ДОБАВЬ ЭТОТ CASE!
                                        intent = new Intent(UserProfileActivity.this, SecureVoiceCallActivity.class);
                                        break;
                                    case "voice":
                                    default:
                                        intent = new Intent(UserProfileActivity.this, VoiceCallActivity.class);
                                        break;
                                }
                                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                                intent.putExtra("recipientId", String.valueOf(incomingCall.getCallerId()));
                                intent.putExtra("roomId", incomingCall.getRoomId());
                                intent.putExtra("isCaller", false);
                                intent.putExtra("callerName", callerName);
                                intent.putExtra("recipientName", incomingCall.getCallerName());
                                startActivity(intent);
                                Toast.makeText(UserProfileActivity.this,
                                        "video".equals(incomingCall.getType()) ?
                                                getString(R.string.video_call_accepted) :
                                                getString(R.string.voice_call_accepted),
                                        Toast.LENGTH_SHORT).show();
                                incomingCall = null;
                            }
                        }

                        @Override
                        public void onFailure(Call<User> call, Throwable t) {
                            Log.e(TAG, "Error fetching recipient info: " + t.getMessage());
                        }
                    });
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Log.e(TAG, "Error fetching caller info: " + t.getMessage());
            }
        });
    }

    private void rejectCall() {
        if (incomingCall == null) {
            Log.e(TAG, "Cannot reject call: incomingCall is null");
            return;
        }

        if (isBound && callService != null) {
            callService.stopCallSound();
            callService.sendSignal("rejectCall", incomingCall.getRoomId());
        } else {
            Log.w(TAG, "CallService not bound, stopping sound locally");
            stopCallSoundLocally();
        }

        incomingCall = null;
        Log.d(TAG, "Call rejected");
    }

    private void showIncomingGroupCallDialog(GroupCallRequest groupCallRequest) {
        if (isFinishing() || isDestroyed()) {
            Log.w(TAG, "Activity is finishing or destroyed, cannot show group call dialog");
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved()) {
            Log.w(TAG, "FragmentManager state is saved, cannot show group call dialog");
            return;
        }
        // Проверяем, не открыт ли уже диалог
        IncomingCallDialogFragment existingDialog = (IncomingCallDialogFragment) fm.findFragmentByTag("IncomingGroupCallDialog");
        if (existingDialog != null && existingDialog.isAdded()) {
            Log.d(TAG, "Incoming group call dialog already shown for roomId: " + groupCallRequest.getRoomId());
            return;
        }

        // Показываем уведомление
        String initiatorName = groupCallRequest.getInitiatorName() != null ? groupCallRequest.getInitiatorName() : "User " + groupCallRequest.getInitiatorId();
        String notificationMessage = getString(R.string.incoming_group_call, initiatorName);
        Toast.makeText(this, notificationMessage, Toast.LENGTH_SHORT).show();

        // Передаем GroupCallRequest в диалог
        IncomingCallDialogFragment dialog = new IncomingCallDialogFragment(groupCallRequest, this::acceptGroupCall, this::rejectGroupCall);
        try {
            dialog.show(fm, "IncomingGroupCallDialog");
            Log.d(TAG, "IncomingGroupCallDialog shown for roomId: " + groupCallRequest.getRoomId());
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error showing IncomingGroupCallDialog: " + e.getMessage());
        }
    }

    private void acceptGroupCall() {
        if (incomingGroupCall == null) {
            Log.e(TAG, "Cannot accept group call: incomingGroupCall is null");
            return;
        }

        if (isBound && callService != null) {
            callService.stopCallSound();
            callService.sendSignal("acceptCall", incomingGroupCall.getRoomId());
        } else {
            Log.w(TAG, "CallService not bound, stopping sound locally");
            stopCallSoundLocally();
        }

        Intent intent = new Intent(this, GroupCallActivity.class);
        intent.putExtra("initiatorId", Integer.parseInt(incomingGroupCall.getInitiatorId()));
        intent.putExtra("initiatorName", incomingGroupCall.getInitiatorName());
        intent.putStringArrayListExtra("selectedUsersIds", new ArrayList<>(incomingGroupCall.getRecipientIds()));
        intent.putExtra("roomId", incomingGroupCall.getRoomId());
        intent.putExtra("isInitiator", false);
        startActivity(intent);
        Toast.makeText(this, "Group call accepted", Toast.LENGTH_SHORT).show();
        incomingGroupCall = null;
    }

    private void rejectGroupCall() {
        if (incomingGroupCall == null) {
            Log.e(TAG, "Cannot reject group call: incomingGroupCall is null");
            return;
        }

        if (isBound && callService != null) {
            callService.stopCallSound();
            callService.sendSignal("rejectCall", incomingGroupCall.getRoomId());
        } else {
            Log.w(TAG, "CallService not bound, stopping sound locally");
            stopCallSoundLocally();
        }

        incomingGroupCall = null;
        Log.d(TAG, "Group call rejected");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isBound && callService != null) {
            handleIntent(intent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
                Log.d(TAG, "MediaPlayer released in onDestroy");
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error releasing MediaPlayer in onDestroy: " + e.getMessage());
        }
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(callReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(notificationReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(endCallReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(userStatusReceiver);
        Log.d(TAG, "Receivers unregistered in onDestroy");
    }

    // ADDED: Обработка вызова с переводом
    private void handleTranslateCall() {
        if (currentUserId == -1) {
            Toast.makeText(this, "You need to create an account or log in", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Unauthorized user attempted to translate call userId: " + userId);
            return;
        }

        String roomId = "room-" + currentUserId + "-" + userId;
        CallRequest requestBody = new CallRequest(currentUserId, userId, roomId, user.getUsername(), "translate");
        Log.d(TAG, "Initiating translate call with roomId: " + roomId);

        apiService.initiateCall(requestBody).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    apiService.getUserById(currentUserId).enqueue(new Callback<User>() {
                        @Override
                        public void onResponse(Call<User> call, Response<User> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                String currentUserName = response.body().getUsername();
                                Log.d(TAG, "Caller: " + currentUserName + ", Recipient: " + user.getUsername());

                                Intent intent = new Intent(UserProfileActivity.this, VoiceCallWithTranslateActivity.class);
                                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                                intent.putExtra("recipientId", String.valueOf(userId));
                                intent.putExtra("roomId", roomId);
                                intent.putExtra("callerName", currentUserName);
                                intent.putExtra("recipientName", user.getUsername());
                                intent.putExtra("isCaller", true);
                                startActivity(intent);
                                Toast.makeText(UserProfileActivity.this, "Calling " + user.getUsername() + " with translation...", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(UserProfileActivity.this, "Failed to fetch caller name", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<User> call, Throwable t) {
                            Toast.makeText(UserProfileActivity.this, "Error fetching caller name: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Toast.makeText(UserProfileActivity.this, "Failed to initiate translate call: " + response.code(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Failed to initiate translate call: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Toast.makeText(UserProfileActivity.this, "Error initiating translate call: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Error initiating translate call: " + t.getMessage());
            }
        });
    }

    // [Добавлено] Обработка секретного чата
    private void handleSecretChat() {
        if (currentUserId == -1) {
            Toast.makeText(this, "You need to create an account or log in", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Unauthorized user attempted to start a secret chat with userId: " + userId);
            return;
        }

        if (currentUserId == userId) {
            Toast.makeText(this, "You cannot start a secret chat with yourself", Toast.LENGTH_SHORT).show();
            return;
        }

        final String targetUsername = user.getUsername();
        apiService.getUserById(currentUserId).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String currentUsername = response.body().getUsername();
                    getSharedPreferences("prefs", MODE_PRIVATE)
                            .edit()
                            .putString("username", currentUsername)
                            .apply();
                    String expectedChatName = targetUsername.compareTo(currentUsername) < 0 ?
                            targetUsername + "_" + currentUsername + "_secret" :
                            currentUsername + "_" + targetUsername + "_secret";

                    // Проверяем существующие чаты текущего пользователя
                    apiService.getPrivateChats(currentUserId).enqueue(new Callback<List<Chat>>() {
                        @Override
                        public void onResponse(Call<List<Chat>> call, Response<List<Chat>> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                List<Chat> chats = response.body();
                                Log.d(TAG, "Fetched " + chats.size() + " private chats for userId: " + currentUserId);
                                for (Chat chat : chats) {
                                    Log.d(TAG, "Checking chat: id=" + chat.getId() + ", name=" + chat.getName() + ", isSecret=" + chat.getIsSecret());
                                    if (chat.getIsSecret() != null && chat.getIsSecret() &&
                                            chat.getName().equals(expectedChatName)) {
                                        navigateToSecretChat(chat.getId());
                                        Toast.makeText(UserProfileActivity.this,
                                                "Navigating to existing secret chat with " + targetUsername + " (chatId: " + chat.getId() + ")",
                                                Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                }
                                Log.d(TAG, "No existing secret chat found for " + targetUsername + ", creating new one");
                                // Если секретный чат не найден, создаем новый
                                createNewSecretChat(expectedChatName, targetUsername);
                            } else {
                                Log.e(TAG, "Failed to fetch chats: HTTP " + response.code());
                                Toast.makeText(UserProfileActivity.this,
                                        "Failed to fetch chats: " + response.code(), Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<List<Chat>> call, Throwable t) {
                            Log.e(TAG, "Error fetching chats: " + t.getMessage());
                            Toast.makeText(UserProfileActivity.this,
                                    "Error fetching chats: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Log.e(TAG, "Failed to fetch current user");
                    Toast.makeText(UserProfileActivity.this,
                            "Failed to fetch current user", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Log.e(TAG, "Error fetching current user: " + t.getMessage());
                Toast.makeText(UserProfileActivity.this,
                        "Error fetching current user: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // [Добавлено] Создание нового секретного чата
    private void createNewSecretChat(String chatName, String targetUsername) {
        Chat newChat = new Chat(chatName, true);
        newChat.setIsSecret(true); // Устанавливаем флаг секретного чата
        apiService.createChat(newChat).enqueue(new Callback<Chat>() {
            @Override
            public void onResponse(Call<Chat> call, Response<Chat> response) {
                if (response.isSuccessful() && response.body() != null) {
                    long chatId = response.body().getId();
                    // Привязка к текущему пользователю
                    apiService.addChatToUser(currentUserId, chatId).enqueue(new Callback<Void>() {
                        @Override
                        public void onResponse(Call<Void> call, Response<Void> response) {
                            if (response.isSuccessful()) {
                                // Привязка к целевому пользователю
                                apiService.addChatToUser(userId, chatId).enqueue(new Callback<Void>() {
                                    @Override
                                    public void onResponse(Call<Void> call, Response<Void> response) {
                                        if (response.isSuccessful()) {
                                            navigateToSecretChat(chatId);
                                            Toast.makeText(UserProfileActivity.this,
                                                    "Navigating to secret chat with " + targetUsername + "!", Toast.LENGTH_SHORT).show();
                                        } else {
                                            Toast.makeText(UserProfileActivity.this,
                                                    "Failed to add secret chat to target user", Toast.LENGTH_SHORT).show();
                                        }
                                    }

                                    @Override
                                    public void onFailure(Call<Void> call, Throwable t) {
                                        Toast.makeText(UserProfileActivity.this,
                                                "Error adding secret chat to target user: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                                    }
                                });
                            } else {
                                Toast.makeText(UserProfileActivity.this,
                                        "Failed to add secret chat to current user", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<Void> call, Throwable t) {
                            Toast.makeText(UserProfileActivity.this,
                                    "Error adding secret chat to current user: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Toast.makeText(UserProfileActivity.this, "Failed to create secret chat", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<Chat> call, Throwable t) {
                Toast.makeText(UserProfileActivity.this, "Error creating secret chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // [Добавлено] Переход к секретному чату
    private void navigateToSecretChat(long chatId) {
        Intent intent = new Intent(this, SecretPrivateChatActivity.class);
        intent.putExtra("chatId", chatId);
        intent.putExtra("currentUserId", currentUserId);
        intent.putExtra("partnerId", userId);
        startActivity(intent);
    }

    private void handleSecureCall() {
        if (currentUserId == -1) {
            Toast.makeText(this, "You need to create an account or log in", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentUserId == userId) {
            Toast.makeText(this, "You cannot call yourself", Toast.LENGTH_SHORT).show();
            return;
        }

        String roomId = "room-" + currentUserId + "-" + userId;
        CallRequest request = new CallRequest(currentUserId, userId, roomId, user.getUsername(), "voice_secure");

        apiService.initiateCall(request).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    apiService.getUserById(currentUserId).enqueue(new Callback<User>() {
                        @Override
                        public void onResponse(Call<User> call, Response<User> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                String callerName = response.body().getUsername();

                                Intent intent = new Intent(UserProfileActivity.this, SecureVoiceCallActivity.class);
                                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                                intent.putExtra("recipientId", String.valueOf(userId));
                                intent.putExtra("roomId", roomId);
                                intent.putExtra("isCaller", true);
                                intent.putExtra("callerName", callerName);
                                intent.putExtra("recipientName", user.getUsername());
                                startActivity(intent);

                                Toast.makeText(UserProfileActivity.this,
                                        "Secure call to " + user.getUsername() + "...", Toast.LENGTH_SHORT).show();
                            }
                        }
                        @Override
                        public void onFailure(Call<User> call, Throwable t) {
                            Toast.makeText(UserProfileActivity.this, "Error", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Toast.makeText(UserProfileActivity.this, "Failed to initiate secure call", Toast.LENGTH_SHORT).show();
                }
            }
            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Toast.makeText(UserProfileActivity.this, "Network error", Toast.LENGTH_SHORT).show();
            }
        });
    }
}