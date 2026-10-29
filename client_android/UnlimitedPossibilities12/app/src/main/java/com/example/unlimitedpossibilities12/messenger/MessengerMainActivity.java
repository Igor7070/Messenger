package com.example.unlimitedpossibilities12.messenger;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.CallRequest;
import com.example.unlimitedpossibilities12.api.Chat;
import com.example.unlimitedpossibilities12.api.GroupCallRequest;
import com.example.unlimitedpossibilities12.api.Message;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;
import com.example.unlimitedpossibilities12.messenger.offline.OfflineModeActivity;
import com.google.android.material.button.MaterialButton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MessengerMainActivity extends AppCompatActivity {
    private static final String TAG = "MessengerMainActivity";
    private static final String ACTION_SHOW_CALL_DIALOG = "com.example.unlimitedpossibilities12.SHOW_CALL_DIALOG";
    private static final String ACTION_NEW_NOTIFICATION = "com.example.unlimitedpossibilities12.NEW_NOTIFICATION";
    private static final String ACTION_CHAT_UPDATED = "com.example.unlimitedpossibilities12.CHAT_UPDATED";
    private static final String ACTION_NEW_MESSAGE = "com.example.unlimitedpossibilities12.NEW_MESSAGE";
    private static final String ACTION_END_CALL = "com.example.unlimitedpossibilities12.END_CALL";
    private RecyclerView usersRecyclerView, chatsRecyclerView;
    private EditText searchUsersEditText, searchChatsEditText, chatNameEditText;
    private MaterialButton createChatButton, createUserButton, loginButton, logoutButton;
    private android.widget.CheckBox publicGroupCheckBox;
    private Toolbar toolbar;
    private TextView titleTextView;
    private TextView toolbarTitle; // название раздела в шапке
    private LinearLayout usersContainer, chatsContainer;
    private List<User> usersList = new ArrayList<>();
    private List<Chat> chatsList = new ArrayList<>();
    private UsersAdapter usersAdapter;
    private ChatsAdapter chatsAdapter;
    private ApiService apiService;
    private int currentUserId = -1;
    private MediaPlayer mediaPlayer;
    private CallRequest incomingCall;
    private boolean userInteracted = false;
    private CallService callService;
    private GroupCallRequest incomingGroupCall;
    private boolean isBound = false;
    private boolean shouldRefreshChats = false; // Флаг для управления обновлением чатов
    // Связь подводит на первом запросе чаще, чем кажется: холодный старт сервера,
    // переключение с Wi-Fi на мобильную сеть. Пробуем ещё раз, а не сдаёмся.
    private static final int CHATS_MAX_ATTEMPTS = 4;
    private int chatsFetchAttempt = 0;
    private boolean chatsFetchScheduled = false;
    private int usersFetchAttempt = 0;
    private com.google.android.material.floatingactionbutton.FloatingActionButton createGroupCallButton; //Переменные для вкладки Calls и FAB
    private BroadcastReceiver callReceiver, notificationReceiver, chatUpdatedReceiver, messageReceiver, endCallReceiver, userStatusReceiver;
    private RecyclerView callsRecyclerView;
    private CallsAdapter callsAdapter;
    private List<com.example.unlimitedpossibilities12.api.Call> callsList = new ArrayList<>();
    private LinearLayout callsContainer;
    private LinearLayout actionBar;
    private ImageView offlineModeButton; // Оффлайн режим (переехал в меню «ещё»)
    private ImageView toolbarSearchButton, toolbarMenuButton;
    private View offlineStrip; // полоса состояния, пока включён офлайн-режим
    private com.google.android.material.floatingactionbutton.FloatingActionButton newChatButton;
    // === КАСТОМНАЯ НИЖНЯЯ ПАНЕЛЬ ===
    private LinearLayout navUsersLayout, navChatsLayout, navCallsLayout, navProfileLayout;
    private ImageView navUsersIcon, navChatsIcon, navCallsIcon, navProfileIcon;
    private TextView navUsersText, navChatsText, navCallsText, navProfileText;
    private com.google.android.material.imageview.ShapeableImageView navProfileAvatar;
    private ProfileFragment profileFragment; // Фрагмент Профиля
    private BroadcastReceiver profileUpdatedReceiver;

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

        // Без аккаунта внутри мессенджера делать нечего: сервер всё равно
        // ответит 401, а экраны останутся пустыми. Отправляем ко входу —
        // сюда можно попасть и по нажатию на уведомление.
        if (getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1) == -1) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main_messenger);

        // Запрещаем поворот экрана
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        apiService = ApiClient.getApiService();
        mediaPlayer = null; // Инициализация отложена до необходимости

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        // Инициализация кнопки оффлайн режима
        offlineModeButton = findViewById(R.id.offline_mode_button);
        toolbarTitle = findViewById(R.id.toolbar_title);
        toolbarSearchButton = findViewById(R.id.toolbar_search_button);
        toolbarMenuButton = findViewById(R.id.toolbar_menu_button);
        offlineStrip = findViewById(R.id.offline_strip);
        newChatButton = findViewById(R.id.new_chat_button);

        loginButton = findViewById(R.id.login_button);
        logoutButton = findViewById(R.id.logout_button);
        titleTextView = findViewById(R.id.title_text_view);
        usersContainer = findViewById(R.id.users_container);
        chatsContainer = findViewById(R.id.chats_container);
        usersRecyclerView = findViewById(R.id.users_recycler_view);
        chatsRecyclerView = findViewById(R.id.chats_recycler_view);
        searchUsersEditText = findViewById(R.id.search_users_edit_text);
        searchChatsEditText = findViewById(R.id.search_chats_edit_text);
        chatNameEditText = findViewById(R.id.chat_name_edit_text);
        createChatButton = findViewById(R.id.create_chat_button);
        publicGroupCheckBox = findViewById(R.id.public_group_checkbox);
        android.widget.TextView joinByLink = findViewById(R.id.join_by_link_text);
        if (joinByLink != null) joinByLink.setOnClickListener(v -> showJoinByLinkDialog());
        createUserButton = findViewById(R.id.create_user_button);
        createGroupCallButton = findViewById(R.id.create_group_call_button);

        // === ИНИЦИАЛИЗАЦИЯ КАСТОМНОЙ НИЖНЕЙ ПАНЕЛИ ===
        navUsersLayout = findViewById(R.id.nav_users_layout);
        navChatsLayout = findViewById(R.id.nav_chats_layout);
        navCallsLayout = findViewById(R.id.nav_calls_layout);
        navProfileLayout = findViewById(R.id.nav_profile_layout);

        navUsersIcon = findViewById(R.id.nav_users_icon);
        navChatsIcon = findViewById(R.id.nav_chats_icon);
        navCallsIcon = findViewById(R.id.nav_calls_icon);
        navProfileIcon = findViewById(R.id.nav_profile_icon);
        navProfileAvatar = findViewById(R.id.nav_profile_avatar);

        navUsersText = findViewById(R.id.nav_users_text);
        navChatsText = findViewById(R.id.nav_chats_text);
        navCallsText = findViewById(R.id.nav_calls_text);
        navProfileText = findViewById(R.id.nav_profile_text);

        // Обработчики нажатий
        navUsersLayout.setOnClickListener(v -> {
            showUsers();
            highlightTabs(0);
        });

        navChatsLayout.setOnClickListener(v -> {
            showChats();
            highlightTabs(1);
        });

        navCallsLayout.setOnClickListener(v -> {
            showCalls();
            highlightTabs(2);
        });

        navProfileLayout.setOnClickListener(v -> {
            highlightTabs(3);

            if (currentUserId == -1) {
                Toast.makeText(this, "Please log in to view your profile", Toast.LENGTH_SHORT).show();
                showLoginDialog();
            } else {
                showProfile();
            }
        });

        // Иконка ассистента. Плавающий кружок убран: он загораживал поиск, а
        // сам ассистент теперь закреплённой строкой в списке разговоров.
        ImageView assistantIcon = findViewById(R.id.assistant_icon);
        assistantIcon.setOnClickListener(v -> openAssistant());
        assistantIcon.setVisibility(View.GONE);
        assistantIcon.bringToFront();
        // Анимация увеличения при нажатии
        assistantIcon.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate()
                                .scaleX(1.20f)     // ← вот здесь сильнее, можно попробовать 1.18f – 1.25f
                                .scaleY(1.20f)
                                .alpha(0.90f)      // чуть сильнее затемнение для контраста
                                .setDuration(80)   // быстрее, чтобы было "щелчково"
                                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                                .start();
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(220)  // чуть дольше возврат — приятнее
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                        break;
                }
                return false;
            }
        });

        // === Обработчик иконки Оффлайн режима ===
        if (offlineModeButton != null) {
            offlineModeButton.setOnClickListener(v -> openOfflineMode());
        }

        // Поиск разворачивается по значку: постоянного поля над списком больше нет
        if (toolbarSearchButton != null) toolbarSearchButton.setOnClickListener(v -> toggleChatSearch());
        if (toolbarMenuButton != null) toolbarMenuButton.setOnClickListener(v -> showChatsMenu());
        if (newChatButton != null) newChatButton.setOnClickListener(v -> showNewChatMenu());
        View offlineStripOpen = findViewById(R.id.offline_strip_open);
        if (offlineStripOpen != null) offlineStripOpen.setOnClickListener(v -> openOfflineMode());

        usersRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        chatsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        usersAdapter = new UsersAdapter(user -> {
            Toast.makeText(this, "Переход к " + user.getUsername(), Toast.LENGTH_SHORT).show();
        });
        chatsAdapter = new ChatsAdapter(chat -> {
            if (chat.getId() == ChatsAdapter.ASSISTANT_ID) {
                openAssistant();
                return;
            }
            if (currentUserId == -1) {
                Toast.makeText(MessengerMainActivity.this, "You need to create an account or log in", Toast.LENGTH_SHORT).show();
                return;
            }
            // Список теперь общий, поэтому открываем экран по типу разговора
            Intent intent;
            if (Boolean.TRUE.equals(chat.getIsSecret())) {
                intent = new Intent(MessengerMainActivity.this, SecretPrivateChatActivity.class);
                intent.putExtra("chatId", chat.getId());
            } else if (chat.isPrivate()) {
                intent = new Intent(MessengerMainActivity.this, PrivateChatActivity.class);
                intent.putExtra("chatId", chat.getId());
            } else {
                intent = new Intent(MessengerMainActivity.this, GroupChatsActivity.class);
                intent.putExtra("chatId", chat.getId());
                intent.putExtra("chatName", chat.getName());
            }
            shouldRefreshChats = true; // Устанавливаем флаг перед переходом в чат
            startActivity(intent);
        });
        chatsAdapter.setCurrentUsername(getSharedPreferences("prefs", MODE_PRIVATE).getString("username", ""));
        // Долгое нажатие — действия с группой; личные чаты пока без меню
        chatsAdapter.setOnChatLongClickListener(chat -> {
            if (currentUserId == -1) return;
            if (chat.isPrivate() || Boolean.TRUE.equals(chat.getIsSecret())) return;
            showGroupMenu(chat);
        });
        usersRecyclerView.setAdapter(usersAdapter);
        chatsRecyclerView.setAdapter(chatsAdapter);

        callsContainer = findViewById(R.id.calls_container);
        callsRecyclerView = findViewById(R.id.calls_recycler_view);
        callsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        actionBar = findViewById(R.id.action_bar);
        callsAdapter = new CallsAdapter(currentUserId, getSharedPreferences("prefs", MODE_PRIVATE).getString("username", "Unknown"), new CallsAdapter.OnCallActionListener() {
            @Override
            public void onDeleteCall(long callId) {
                deleteCall(callId);
            }

            @Override
            public void onRedialCall(com.example.unlimitedpossibilities12.api.Call call) {
                // Пустая реализация, так как редял теперь обрабатывается в адаптере
            }

            @Override
            public void onDeleteSelectedCalls(List<Long> callIds) {
                if (currentUserId == -1) {
                    Toast.makeText(MessengerMainActivity.this, "You need to log in to delete calls", Toast.LENGTH_SHORT).show();
                    return;
                }
                new Thread(() -> {
                    boolean hasErrors = false;
                    for (Long callId : callIds) {
                        try {
                            Response<Void> response = apiService.deleteCall(callId, currentUserId).execute();
                            if (response.isSuccessful()) {
                                callsList.removeIf(c -> c.getId() == callId);
                                Log.d(TAG, "Call deleted: id=" + callId);
                            } else {
                                Log.e(TAG, "Failed to delete call: id=" + callId + ", code=" + response.code());
                                hasErrors = true;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error deleting call: id=" + callId + ", error=" + e.getMessage());
                            hasErrors = true;
                        }
                    }
                    boolean finalHasErrors = hasErrors;
                    runOnUiThread(() -> {
                        callsAdapter.updateCalls(callsList, usersList);
                        Toast.makeText(MessengerMainActivity.this, finalHasErrors ? "Error deleting some calls" : "Selected calls deleted", Toast.LENGTH_SHORT).show();
                    });
                }).start();
            }
        }, actionBar, apiService);
        // Обновляем CallsAdapter данными из SharedPreferences для авторизованного пользователя
        int savedUserId = getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);
        String savedUsername = getSharedPreferences("prefs", MODE_PRIVATE).getString("username", "Unknown");
        if (savedUserId != -1) {
            callsAdapter.updateUser(savedUserId, savedUsername);
            Log.d(TAG, "Updated CallsAdapter with user from SharedPreferences: userId=" + savedUserId + ", username=" + savedUsername);
        }
        callsRecyclerView.setAdapter(callsAdapter);

        createChatButton.setOnClickListener(v -> createChat());
        createUserButton.setOnClickListener(v -> showCreateUserDialog());
        loginButton.setOnClickListener(v -> showLoginDialog());
        logoutButton.setOnClickListener(v -> logoutUser());

        searchUsersEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) { filterUsers(s.toString()); }
        });
        searchChatsEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) { filterChats(s.toString()); }
        });

        findViewById(android.R.id.content).setOnClickListener(v -> userInteracted = true);

        currentUserId = getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);
        fetchChats();
        fetchUsers();
        updateLoginState();
        updateProfileIcon();

        showChats();
        highlightTabs(1);

        Intent serviceIntent = new Intent(this, CallService.class);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);

        // Изменено: Открытие диалога выбора пользователей при клике на FAB
        createGroupCallButton.setOnClickListener(v -> {
            if (currentUserId == -1) {
                Toast.makeText(MessengerMainActivity.this, "You need to log in to start a group call", Toast.LENGTH_SHORT).show();
                return;
            }
            GroupCallUserSelectionDialogFragment dialog = GroupCallUserSelectionDialogFragment.newInstance(currentUserId);
            dialog.setOnStartGroupCallListener((selectedUsers, initiatorId, initiatorName) -> {
                Intent intent = new Intent(MessengerMainActivity.this, GroupCallActivity.class);
                ArrayList<String> selectedUsersIds = new ArrayList<>();
                for (User user : selectedUsers) {
                    selectedUsersIds.add(String.valueOf(user.getId()));
                }
                intent.putStringArrayListExtra("selectedUsersIds", selectedUsersIds);
                intent.putExtra("initiatorId", initiatorId);
                intent.putExtra("initiatorName", initiatorName);
                intent.putExtra("isInitiator", true);
                intent.putExtra("roomId", UUID.randomUUID().toString());
                startActivity(intent);
            });
            dialog.show(getSupportFragmentManager(), "GroupCallUserSelectionDialog");
        });

        callReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                CallRequest callRequest = (CallRequest) intent.getSerializableExtra("callRequest");
                GroupCallRequest groupCallRequest = (GroupCallRequest) intent.getSerializableExtra("groupCallRequest");
                if (callRequest != null && !isFinishing() && !isDestroyed()) {
                    incomingCall = callRequest;
                    showIncomingCallDialog(callRequest);
                } else if (groupCallRequest != null && !isFinishing() && !isDestroyed()) {
                    incomingGroupCall = groupCallRequest;
                    showIncomingGroupCallDialog(groupCallRequest);
                } else {
                    Log.w(TAG, "Received call broadcast but activity is not in a valid state or no valid request");
                }
            }
        };

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
                        Toast.makeText(MessengerMainActivity.this, notification, Toast.LENGTH_LONG).show();
                    } else {
                        Log.w(TAG, "No valid chatId in notification: " + notification);
                    }
                }
            }
        };

        chatUpdatedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long chatId = intent.getLongExtra("chatId", -1);
                if (chatId != -1) {
                    Log.d(TAG, "Received chat updated broadcast for chatId: " + chatId);
                    fetchChats();
                }
            }
        };

        messageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long chatId = intent.getLongExtra("chatId", -1);
                if (chatId != -1) {
                    Log.d(TAG, "Received new message for chatId: " + chatId);
                    fetchChats();
                }
            }
        };

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
                        Toast.makeText(MessengerMainActivity.this, "The caller cancelled the call.", Toast.LENGTH_LONG).show();
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
                long userId = intent.getLongExtra("userId", -1);
                boolean online = intent.getBooleanExtra("online", false);
                if (userId != -1) {
                    usersAdapter.updateUserStatus(userId, online);
                    Log.d(TAG, "User status updated: userId=" + userId + ", online=" + online);
                }
            }
        };

        profileUpdatedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int updatedUserId = intent.getIntExtra("userId", -1);
                if (updatedUserId == currentUserId) {
                    Log.d(TAG, "Received PROFILE_UPDATED broadcast");
                    fetchUsers();           // обновляем список пользователей
                    updateProfileIcon();    // обновляем аватар в нижней панели
                }
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(callReceiver,
                new IntentFilter(ACTION_SHOW_CALL_DIALOG));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(notificationReceiver,
                new IntentFilter(ACTION_NEW_NOTIFICATION));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(chatUpdatedReceiver,
                new IntentFilter(ACTION_CHAT_UPDATED));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(messageReceiver,
                new IntentFilter(ACTION_NEW_MESSAGE));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(endCallReceiver,
                new IntentFilter(ACTION_END_CALL));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(userStatusReceiver,
                new IntentFilter("com.example.unlimitedpossibilities12.USER_STATUS"));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(
                profileUpdatedReceiver, new IntentFilter("PROFILE_UPDATED"));
        Log.d(TAG, "Receivers registered in onStart, including endCallReceiver for " + ACTION_END_CALL);
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(callReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(notificationReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(chatUpdatedReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(messageReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(endCallReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(userStatusReceiver);

        // Отписываемся от обновления профиля
        if (profileUpdatedReceiver != null) {
            LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(profileUpdatedReceiver);
        }

        Log.d(TAG, "Receivers unregistered in onStop, including endCallReceiver");
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void showUsers() {
        titleTextView.setText("Users");
        usersContainer.setVisibility(View.VISIBLE);
        chatsContainer.setVisibility(View.GONE);
    }

    private void showChats() {
        titleTextView.setText("Group Chats");
        usersContainer.setVisibility(View.GONE);
        chatsContainer.setVisibility(View.VISIBLE);
    }

    // Добавлено: Метод для отображения вкладки Calls
    private void showCalls() {
        titleTextView.setText("Calls");
        usersContainer.setVisibility(View.GONE);
        chatsContainer.setVisibility(View.GONE);
        callsContainer.setVisibility(View.VISIBLE);
        createGroupCallButton.setVisibility(View.VISIBLE);
        fetchCalls();
    }

    private void showProfile() {
        // Скрываем общий заголовок (title_text_view)
        titleTextView.setVisibility(View.GONE);

        // Скрываем все основные контейнеры
        usersContainer.setVisibility(View.GONE);
        chatsContainer.setVisibility(View.GONE);
        callsContainer.setVisibility(View.GONE);
        createGroupCallButton.setVisibility(View.GONE);

        // Показываем контейнер фрагмента
        View fragmentContainer = findViewById(R.id.fragment_container);
        if (fragmentContainer != null) {
            fragmentContainer.setVisibility(View.VISIBLE);
        }

        if (profileFragment == null) {
            profileFragment = ProfileFragment.newInstance(currentUserId);
        }

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, profileFragment)
                .commit();
    }

    // Изменено: Поддержка четырех вкладок (Users, Group Chats, Calls, Profile)
    private void highlightTabs(int selectedTab) {
        resetAllTabs();

        View fragmentContainer = findViewById(R.id.fragment_container);
        ImageView profileMenuButton = findViewById(R.id.profile_menu_button); // ← Добавили

        // Акцент один на всё приложение — индиго; прежние жёлтый, зелёный и
        // красный выбивались из палитры и спорили друг с другом
        final int accent = 0xFF818CF8;

        if (toolbarSearchButton != null) toolbarSearchButton.setVisibility(selectedTab == 1 ? View.VISIBLE : View.GONE);
        if (toolbarMenuButton != null) toolbarMenuButton.setVisibility(selectedTab == 1 ? View.VISIBLE : View.GONE);
        if (newChatButton != null) newChatButton.setVisibility(selectedTab == 1 ? View.VISIBLE : View.GONE);
        refreshOfflineStrip();

        if (selectedTab == 0) { // Contacts
            navUsersText.setTextColor(accent);
            navUsersIcon.setColorFilter(accent);

            titleTextView.setVisibility(View.VISIBLE);
            usersContainer.setVisibility(View.VISIBLE);
            chatsContainer.setVisibility(View.GONE);
            callsContainer.setVisibility(View.GONE);
            if (fragmentContainer != null) fragmentContainer.setVisibility(View.GONE);
            if (profileMenuButton != null) profileMenuButton.setVisibility(View.GONE); // Скрываем
            createGroupCallButton.setVisibility(View.GONE);
            titleTextView.setText("Contacts");
            if (toolbarTitle != null) toolbarTitle.setText("Contacts");

        } else if (selectedTab == 1) { // Chats
            navChatsText.setTextColor(accent);
            navChatsIcon.setColorFilter(accent);

            // Заголовок живёт в шапке: второй крупной надписи над списком не нужно
            titleTextView.setVisibility(View.GONE);
            usersContainer.setVisibility(View.GONE);
            chatsContainer.setVisibility(View.VISIBLE);
            callsContainer.setVisibility(View.GONE);
            if (fragmentContainer != null) fragmentContainer.setVisibility(View.GONE);
            if (profileMenuButton != null) profileMenuButton.setVisibility(View.GONE); // Скрываем
            createGroupCallButton.setVisibility(View.GONE);
            if (toolbarTitle != null) toolbarTitle.setText("Chats");

        } else if (selectedTab == 2) { // Calls
            navCallsText.setTextColor(accent);
            navCallsIcon.setColorFilter(accent);

            titleTextView.setVisibility(View.VISIBLE);
            usersContainer.setVisibility(View.GONE);
            chatsContainer.setVisibility(View.GONE);
            callsContainer.setVisibility(View.VISIBLE);
            if (fragmentContainer != null) fragmentContainer.setVisibility(View.GONE);
            if (profileMenuButton != null) profileMenuButton.setVisibility(View.GONE); // Скрываем
            createGroupCallButton.setVisibility(View.VISIBLE);
            titleTextView.setText("Calls");
            if (toolbarTitle != null) toolbarTitle.setText("Calls");
            fetchCalls();

        } else if (selectedTab == 3) { // Profile
            navProfileText.setTextColor(accent);
            navProfileIcon.setColorFilter(accent);
            if (toolbarTitle != null) toolbarTitle.setText("Profile");

            titleTextView.setVisibility(View.GONE);
            usersContainer.setVisibility(View.GONE);
            chatsContainer.setVisibility(View.GONE);
            callsContainer.setVisibility(View.GONE);
            createGroupCallButton.setVisibility(View.GONE);
            if (fragmentContainer != null) fragmentContainer.setVisibility(View.VISIBLE);
            if (profileMenuButton != null) profileMenuButton.setVisibility(View.VISIBLE); // Показываем

            showProfile();
        }
    }

    private void resetAllTabs() {
        int inactiveColor = 0xFF94A3B8;

        navUsersText.setTextColor(inactiveColor);
        navUsersIcon.setColorFilter(inactiveColor);

        navChatsText.setTextColor(inactiveColor);
        navChatsIcon.setColorFilter(inactiveColor);

        navCallsText.setTextColor(inactiveColor);
        navCallsIcon.setColorFilter(inactiveColor);

        navProfileText.setTextColor(inactiveColor);
        navProfileIcon.setColorFilter(inactiveColor);
    }

    /**
     * Удаление группового чата. Правило то же, что на сервере: удаляет создатель,
     * а у чатов, созданных до появления поля createdBy, владельца нет — их может
     * удалить любой, иначе они остались бы навсегда.
     */
    /** Действия над группой: участники с приглашениями и удаление. */
    private void showGroupMenu(Chat chat) {
        String[] actions = {"Members and invites", "Delete group chat"};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle(chat.getName())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        GroupMembersDialogFragment.newInstance(chat, currentUserId)
                                .show(getSupportFragmentManager(), "group-members");
                    } else {
                        confirmDeleteGroupChat(chat);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Вступление по ссылке-приглашению. Поле заполняем из буфера обмена, если
     * там уже лежит ссылка: её обычно только что прислали в другом мессенджере.
     */
    private void showJoinByLinkDialog() {
        if (currentUserId == -1) {
            Toast.makeText(this, "You need to log in first", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Paste the invite link");
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (text != null && text.toString().contains("/invite/")) input.setText(text.toString());
            }
        } catch (Exception ignored) {
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle("Join by invite link")
                .setView(input)
                .setPositiveButton("Join", (dialog, which) -> acceptInvite(input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void acceptInvite(String linkOrToken) {
        if (linkOrToken.isEmpty()) return;
        // Из ссылки берём хвост после /invite/, но принимаем и голый токен
        int at = linkOrToken.lastIndexOf("/invite/");
        String token = at >= 0 ? linkOrToken.substring(at + "/invite/".length()) : linkOrToken;
        token = token.replaceAll("[^A-Za-z0-9]", "");
        if (token.isEmpty()) {
            Toast.makeText(this, "That does not look like an invite link", Toast.LENGTH_SHORT).show();
            return;
        }
        // Сначала показываем, куда именно зовут: раньше переход сразу зачислял
        // в группу, и человек узнавал её название уже внутри
        final String inviteToken = token;
        apiService.previewInvite(token).enqueue(new Callback<java.util.Map<String, Object>>() {
            @Override
            public void onResponse(Call<java.util.Map<String, Object>> call,
                                   Response<java.util.Map<String, Object>> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    Toast.makeText(MessengerMainActivity.this,
                            "This invite is no longer valid", Toast.LENGTH_LONG).show();
                    return;
                }
                java.util.Map<String, Object> card = response.body();
                if (Boolean.FALSE.equals(card.get("usable"))) {
                    Toast.makeText(MessengerMainActivity.this,
                            "This invite is no longer valid", Toast.LENGTH_LONG).show();
                    return;
                }
                if (Boolean.TRUE.equals(card.get("alreadyMember"))) {
                    Toast.makeText(MessengerMainActivity.this,
                            "You are already in this group", Toast.LENGTH_LONG).show();
                    fetchChats();
                    return;
                }
                Object name = card.get("name");
                Object members = card.get("members");
                int count = members instanceof Number ? ((Number) members).intValue() : 0;
                String kind = Boolean.TRUE.equals(card.get("isPublic")) ? "Public group" : "Private group";
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(MessengerMainActivity.this, R.style.AlertDialog_UP12)
                        .setTitle(String.valueOf(name))
                        .setMessage(kind + " · " + count + (count == 1 ? " member" : " members"))
                        .setPositiveButton("Join group", (d, w) -> joinByToken(inviteToken))
                        .setNegativeButton("Not now", null)
                        .show();
            }

            @Override
            public void onFailure(Call<java.util.Map<String, Object>> call, Throwable t) {
                Log.e(TAG, "Invite preview failed: " + t.getMessage());
                Toast.makeText(MessengerMainActivity.this, "Could not reach the server", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Собственно вступление — по нажатию в карточке. */
    private void joinByToken(String token) {
        apiService.acceptInvite(token).enqueue(new Callback<java.util.Map<String, Object>>() {
            @Override
            public void onResponse(Call<java.util.Map<String, Object>> call,
                                   Response<java.util.Map<String, Object>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Object name = response.body().get("name");
                    Toast.makeText(MessengerMainActivity.this,
                            "You joined \"" + name + "\"", Toast.LENGTH_LONG).show();
                    fetchChats();
                } else {
                    Toast.makeText(MessengerMainActivity.this,
                            response.code() == 410 ? "This invite is no longer valid" : "Could not join",
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<java.util.Map<String, Object>> call, Throwable t) {
                Log.e(TAG, "Invite failed: " + t.getMessage());
                Toast.makeText(MessengerMainActivity.this, "Could not reach the server", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmDeleteGroupChat(Chat chat) {
        Long owner = chat.getCreatedBy();
        if (owner != null && owner != currentUserId) {
            Toast.makeText(this, "Only the person who created this group chat can delete it",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle("Delete group chat")
                .setMessage("Delete \"" + chat.getName() + "\" for everyone? This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> deleteGroupChat(chat))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteGroupChat(Chat chat) {
        apiService.deleteChat(chat.getId()).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Group chat deleted: " + chat.getId());
                    Toast.makeText(MessengerMainActivity.this, "Group chat deleted", Toast.LENGTH_SHORT).show();
                    fetchChats();
                } else {
                    Log.e(TAG, "Failed to delete group chat: HTTP " + response.code());
                    Toast.makeText(MessengerMainActivity.this,
                            response.code() == 403
                                    ? "Only the person who created this group chat can delete it"
                                    : "Could not delete the chat",
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Error deleting group chat: " + t.getMessage());
                Toast.makeText(MessengerMainActivity.this, "Could not delete the chat", Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Единый список разговоров: групповые чаты и личные (включая секретные) в
     * одном месте, как в любом мессенджере.
     *
     * Прежде здесь были только группы, а личная переписка пряталась на
     * отдельном экране — человек открывал приложение и не видел того, ради
     * чего пришёл. Оба запроса идут параллельно, список собираем, когда
     * ответят оба.
     */
    private void fetchChats() {
        chatsFetchScheduled = false;
        List<Chat> collected = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger pending = new AtomicInteger(2);
        AtomicInteger failed = new AtomicInteger(0);

        Runnable finishOne = () -> {
            if (pending.decrementAndGet() != 0) return;
            if (failed.get() > 0) {
                // Чаты никуда не делись — не дошёл запрос. То, что уже на экране,
                // оставляем, и пробуем загрузить снова.
                retryChatsLater();
                return;
            }
            chatsFetchAttempt = 0;
            chatsList.clear();
            chatsList.addAll(collected);
            runOnUiThread(() -> {
                if (chatsList.isEmpty()) publishChats();
                else fetchLastMessages();
            });
        };

        apiService.getChats().enqueue(new Callback<List<Chat>>() {
            @Override
            public void onResponse(Call<List<Chat>> call, Response<List<Chat>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    for (Chat chat : response.body()) {
                        if (!chat.isPrivate() && !Boolean.TRUE.equals(chat.getIsSecret())) collected.add(chat);
                    }
                    Log.d(TAG, "Group chats: " + collected.size());
                } else {
                    Log.e(TAG, "Failed to fetch chats: code=" + response.code());
                    failed.incrementAndGet();
                }
                finishOne.run();
            }

            @Override
            public void onFailure(Call<List<Chat>> call, Throwable t) {
                Log.e(TAG, "Error fetching chats: " + t.getMessage());
                failed.incrementAndGet();
                finishOne.run();
            }
        });

        if (currentUserId == -1) {
            finishOne.run();
            return;
        }

        apiService.getPrivateChats(currentUserId).enqueue(new Callback<List<Chat>>() {
            @Override
            public void onResponse(Call<List<Chat>> call, Response<List<Chat>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    collected.addAll(response.body());
                    Log.d(TAG, "Private chats: " + response.body().size());
                } else {
                    Log.e(TAG, "Failed to fetch private chats: code=" + response.code());
                    failed.incrementAndGet();
                }
                finishOne.run();
            }

            @Override
            public void onFailure(Call<List<Chat>> call, Throwable t) {
                Log.e(TAG, "Error fetching private chats: " + t.getMessage());
                failed.incrementAndGet();
                finishOne.run();
            }
        });
    }

    /**
     * Повторная попытка загрузить список: 1.5, 3, 6, 12 секунд. Если и после
     * этого пусто — говорим человеку о связи, а не делаем вид, что чатов нет.
     */
    private void retryChatsLater() {
        if (chatsFetchScheduled) return;

        if (chatsFetchAttempt >= CHATS_MAX_ATTEMPTS) {
            chatsFetchAttempt = 0;
            runOnUiThread(() -> {
                if (chatsList.isEmpty()) {
                    Toast.makeText(MessengerMainActivity.this,
                            "Couldn't load chats. Check your connection", Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        long delay = 1500L * (1L << chatsFetchAttempt);
        chatsFetchAttempt++;
        chatsFetchScheduled = true;
        Log.d(TAG, "Список чатов не загрузился, повтор через " + delay + " мс");
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::fetchChats, delay);
    }

    /**
     * Показать собранный список: свежие разговоры сверху, ассистент закреплён
     * первой строкой. Если идёт поиск, применяем его, чтобы набранное не
     * пропадало при обновлении.
     */
    private void publishChats() {
        List<Chat> ordered = new ArrayList<>(chatsList);
        java.util.Collections.sort(ordered, (left, right) -> {
            String a = left.getTimestamp() == null ? "" : left.getTimestamp();
            String b = right.getTimestamp() == null ? "" : right.getTimestamp();
            return b.compareTo(a); // ISO-время сравнивается как строка
        });
        ordered.add(0, assistantRow());

        chatsAdapter.setCurrentUsername(getSharedPreferences("prefs", MODE_PRIVATE).getString("username", ""));
        chatsAdapter.updateChats(ordered);

        String searchTerm = searchChatsEditText == null ? "" : searchChatsEditText.getText().toString();
        if (!searchTerm.isEmpty()) chatsAdapter.filter(searchTerm);
    }

    /** Ассистент — строка списка, а не плавающая кнопка поверх экрана. */
    private Chat assistantRow() {
        Chat assistant = new Chat();
        assistant.setId(ChatsAdapter.ASSISTANT_ID);
        assistant.setName("UP12 Assistant");
        assistant.setLastMessage("Ask me to draft a reply or explain a text");
        assistant.setTimestamp("");
        assistant.setUnreadCount(0);
        return assistant;
    }

    private void openAssistant() {
        new AssistantChatDialogFragment().show(getSupportFragmentManager(), "AssistantChatDialog");
    }

    private void openOfflineMode() {
        startActivity(new Intent(MessengerMainActivity.this, OfflineModeActivity.class));
    }

    /** Полоса под шапкой видна, только когда офлайн-режим действительно включён. */
    private void refreshOfflineStrip() {
        if (offlineStrip == null) return;
        boolean enabled = new com.example.unlimitedpossibilities12.messenger.offline.OfflinePreferences(this).isMeshEnabled();
        offlineStrip.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    /** Лупа в шапке: поле поиска появляется и прячется вместе с набранным. */
    private void toggleChatSearch() {
        if (searchChatsEditText == null) return;
        boolean showing = searchChatsEditText.getVisibility() == View.VISIBLE;
        if (showing) {
            searchChatsEditText.setText("");
            searchChatsEditText.setVisibility(View.GONE);
        } else {
            searchChatsEditText.setVisibility(View.VISIBLE);
            searchChatsEditText.requestFocus();
        }
    }

    /**
     * Тёмное меню со значками, выпадающее из-под нажатой кнопки.
     *
     * Системное окно со списком рисуется по светлой теме и всплывает посреди
     * экрана — посреди тёмного списка это выглядело чужеродно. Здесь своя
     * разметка: подложка с рамкой, значок у каждого пункта, у офлайн-режима
     * переключатель состояния.
     */
    private android.widget.PopupWindow buildMenu(View content) {
        android.widget.PopupWindow popup = new android.widget.PopupWindow(
                content,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(18f);
        return popup;
    }

    /** Меню «ещё»: то, что раньше висело полями над списком. */
    private void showChatsMenu() {
        if (toolbarMenuButton == null) return;
        View content = getLayoutInflater().inflate(R.layout.popup_menu_chats, null);
        android.widget.PopupWindow popup = buildMenu(content);

        boolean offlineOn = new com.example.unlimitedpossibilities12.messenger.offline.OfflinePreferences(this).isMeshEnabled();
        com.google.android.material.switchmaterial.SwitchMaterial offlineSwitch =
                content.findViewById(R.id.menu_offline_switch);
        if (offlineSwitch != null) offlineSwitch.setChecked(offlineOn);
        ImageView offlineIcon = content.findViewById(R.id.menu_offline_icon);
        if (offlineIcon != null) offlineIcon.setColorFilter(offlineOn ? 0xFF67E8F9 : 0xFF94A3B8);

        content.findViewById(R.id.menu_new_group).setOnClickListener(v -> {
            popup.dismiss();
            showCreateGroupDialog();
        });
        content.findViewById(R.id.menu_join_link).setOnClickListener(v -> {
            popup.dismiss();
            showJoinByLinkDialog();
        });
        content.findViewById(R.id.menu_offline).setOnClickListener(v -> {
            popup.dismiss();
            openOfflineMode();
        });

        int dx = (int) (-8 * getResources().getDisplayMetrics().density);
        popup.showAsDropDown(toolbarMenuButton, dx, 0, android.view.Gravity.END);
    }

    /** Кнопка «написать»: с чего начать новый разговор. Меню поднимается над кнопкой. */
    private void showNewChatMenu() {
        if (newChatButton == null) return;
        View content = getLayoutInflater().inflate(R.layout.popup_menu_new_chat, null);
        android.widget.PopupWindow popup = buildMenu(content);

        content.findViewById(R.id.menu_new_private).setOnClickListener(v -> {
            popup.dismiss();
            // Личная переписка начинается с профиля человека
            showUsers();
            highlightTabs(0);
            Toast.makeText(this, "Pick a person to write to", Toast.LENGTH_SHORT).show();
        });
        content.findViewById(R.id.menu_new_group_2).setOnClickListener(v -> {
            popup.dismiss();
            showCreateGroupDialog();
        });
        content.findViewById(R.id.menu_join_link_2).setOnClickListener(v -> {
            popup.dismiss();
            showJoinByLinkDialog();
        });

        // Меню должно раскрываться вверх: под кнопкой места нет
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int lift = -(content.getMeasuredHeight() + newChatButton.getHeight()
                + (int) (8 * getResources().getDisplayMetrics().density));
        popup.showAsDropDown(newChatButton, 0, lift, android.view.Gravity.END);
    }

    /**
     * Создание группы — окном, а не полями над списком.
     *
     * Значения кладём в прежние (теперь скрытые) поля экрана и зовём старый
     * createChat(): так остаётся одна проверка имени и один запрос к серверу.
     */
    private void showCreateGroupDialog() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final android.widget.EditText name = new android.widget.EditText(this);
        name.setHint("Group name");
        name.setTextColor(0xFFE2E8F0);
        name.setHintTextColor(0xFF94A3B8);
        box.addView(name);

        final android.widget.CheckBox publicBox = new android.widget.CheckBox(this);
        publicBox.setText("Public group — anyone can find and read it");
        publicBox.setTextColor(0xFF94A3B8);
        publicBox.setTextSize(13);
        box.addView(publicBox);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle("New group")
                .setView(box)
                .setPositiveButton("Create", (dialog, which) -> {
                    if (chatNameEditText != null) chatNameEditText.setText(name.getText().toString());
                    if (publicGroupCheckBox != null) publicGroupCheckBox.setChecked(publicBox.isChecked());
                    createChat();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void fetchLastMessages() {
        SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
        AtomicInteger pendingRequests = new AtomicInteger(chatsList.size());
        for (Chat chat : chatsList) {
            apiService.getMessagesByChatId(chat.getId()).enqueue(new Callback<List<Message>>() {
                @Override
                public void onResponse(Call<List<Message>> call, Response<List<Message>> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        List<Message> messages = response.body();
                        if (!messages.isEmpty()) {
                            Message lastMessage = messages.get(messages.size() - 1);
                            chat.setLastMessage(previewFor(chat, lastMessage));
                            chat.setTimestamp(lastMessage.getTimestamp());
                            chat.setUnreadCount(unreadFor(chat, messages, prefs));
                        } else {
                            chat.setLastMessage("No messages yet");
                            chat.setTimestamp("");
                            chat.setUnreadCount(0);
                        }
                        Log.d(TAG, "Messages fetched for chat " + chat.getId() + ": unreadCount=" + chat.getUnreadCount());
                    } else {
                        Log.e(TAG, "Failed to fetch messages for chat " + chat.getId() + ": code=" + response.code());
                        chat.setLastMessage("No messages yet");
                        chat.setTimestamp("");
                        chat.setUnreadCount(0);
                    }
                    if (pendingRequests.decrementAndGet() == 0) {
                        runOnUiThread(() -> publishChats());
                    }
                }

                @Override
                public void onFailure(Call<List<Message>> call, Throwable t) {
                    Log.e(TAG, "Error fetching messages for chat " + chat.getId() + ": " + t.getMessage());
                    chat.setLastMessage("No messages yet");
                    chat.setTimestamp("");
                    chat.setUnreadCount(0);
                    if (pendingRequests.decrementAndGet() == 0) {
                        runOnUiThread(() -> publishChats());
                    }
                }
            });
        }
    }

    /**
     * Строка превью под именем чата.
     *
     * Показываем то же, что любой мессенджер: кто написал («Вы», а в группе —
     * имя отправителя), а вместо голой ссылки во всю строку — её адрес.
     * У вложений вместо пустоты — тип: голосовое, фото, файл.
     */
    private String previewFor(Chat chat, Message message) {
        String type = message.getMessageType();
        String body;
        if ("audio".equals(type)) {
            body = "Voice message";
        } else if ("file".equals(type)) {
            body = isImage(message) ? "Photo" : "File";
        } else {
            body = message.getContent();
        }

        if (body == null || body.trim().isEmpty()) {
            // В секретном чате текст лежит зашифрованным, и ключа у списка нет
            boolean secret = Boolean.TRUE.equals(chat.getIsSecret())
                    || message.getEncryptedContent() != null;
            body = secret ? "Encrypted message" : "No messages yet";
        }

        body = shortenLinks(body.trim());

        boolean mine = message.getSender() != null && message.getSender().getId() == currentUserId;
        if (mine) return "You: " + body;

        boolean group = !chat.isPrivate() && !Boolean.TRUE.equals(chat.getIsSecret());
        if (group && message.getSender() != null && message.getSender().getUsername() != null) {
            return message.getSender().getUsername() + ": " + body;
        }
        return body;
    }

    private boolean isImage(Message message) {
        String contentType = message.getContentType();
        if (contentType != null && contentType.startsWith("image")) return true;
        String url = message.getFileUrl();
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif");
    }

    /** Ссылка целиком съедает строку, поэтому оставляем адрес сайта. */
    private String shortenLinks(String text) {
        if (text == null || !text.contains("http")) return text;
        StringBuilder out = new StringBuilder();
        for (String word : text.split("\\s+")) {
            out.append(word.startsWith("http://") || word.startsWith("https://")
                    ? shortUrl(word) : word).append(' ');
        }
        return out.toString().trim();
    }

    private String shortUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) return url;
            if (host.startsWith("www.")) host = host.substring(4);
            boolean hasMore = (uri.getPath() != null && uri.getPath().length() > 1)
                    || uri.getFragment() != null || uri.getQuery() != null;
            return hasMore ? host + "/…" : host;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Непрочитанные считаются по-разному, и это не прихоть.
     *
     * В личных и секретных чатах у сообщения есть признак «прочитано» с
     * сервера — он и решает; раньше этим пользовался отдельный экран личных
     * чатов. У групп такого признака нет, там сравниваем со временем
     * последнего посещения, которое чат сохраняет при выходе.
     *
     * Когда общий список собрали, счёт по времени посещения применялся ко
     * всем подряд — у личных чатов его никто не записывает, поэтому число
     * непрочитанных не менялось, сколько ни открывай переписку.
     */
    private int unreadFor(Chat chat, List<Message> messages, SharedPreferences prefs) {
        boolean oneToOne = chat.isPrivate() || Boolean.TRUE.equals(chat.getIsSecret());
        if (oneToOne) {
            int unread = 0;
            for (Message message : messages) {
                if (!message.isRead() && message.getSender() != null
                        && message.getSender().getId() != currentUserId) {
                    unread++;
                }
            }
            return unread;
        }
        return calculateUnreadCount(messages, prefs.getString("last_visit_chat_" + chat.getId(), null));
    }

    private int calculateUnreadCount(List<Message> messages, String lastVisit) {
        if (lastVisit == null) {
            return messages.size(); // Чат не открывался, все сообщения непрочитанные
        }
        try {
            Instant lastVisitTime = Instant.parse(lastVisit);
            int unreadCount = 0;
            for (Message message : messages) {
                if (message.getTimestamp() != null && !message.getTimestamp().isEmpty()) {
                    Instant messageTime = Instant.parse(message.getTimestamp());
                    if (messageTime.isAfter(lastVisitTime)) {
                        unreadCount++;
                    }
                }
            }
            return unreadCount;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing timestamps: " + e.getMessage());
            return 0;
        }
    }

    /** Список людей тоже не должен молча оставаться пустым из-за одной осечки. */
    private void retryUsersLater() {
        if (usersFetchAttempt >= CHATS_MAX_ATTEMPTS) {
            usersFetchAttempt = 0;
            runOnUiThread(() -> {
                if (usersList.isEmpty()) {
                    Toast.makeText(MessengerMainActivity.this,
                            "Couldn't load contacts. Check your connection", Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        long delay = 1500L * (1L << usersFetchAttempt);
        usersFetchAttempt++;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::fetchUsers, delay);
    }

    private void fetchUsers() {
        apiService.getUsers().enqueue(new Callback<List<User>>() {
            @Override
            public void onResponse(Call<List<User>> call, Response<List<User>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Log.d(TAG, "Users received: " + response.body().size());
                    usersFetchAttempt = 0;
                    usersList.clear();
                    usersList.addAll(response.body());

                    runOnUiThread(() -> {
                        usersAdapter.updateUsers(usersList);
                        updateProfileIcon();
                    });
                } else {
                    Log.e(TAG, "Failed to fetch users: " + response.code());
                    retryUsersLater();
                }
            }

            @Override
            public void onFailure(Call<List<User>> call, Throwable t) {
                Log.e(TAG, "Error fetching users: " + t.getMessage());
                retryUsersLater();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            fetchUsers();
        }
    }

    private void createChat() {
        String chatName = chatNameEditText.getText().toString().trim();
        if (!chatName.isEmpty()) {
            Chat newChat = new Chat(chatName);
            // Закрытая по умолчанию: в неё зовут по ссылке, а не находят в списке
            boolean makePublic = publicGroupCheckBox != null && publicGroupCheckBox.isChecked();
            newChat.setIsPublic(makePublic);
            apiService.createChat(newChat).enqueue(new Callback<Chat>() {
                @Override
                public void onResponse(Call<Chat> call, Response<Chat> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        chatsList.add(response.body());
                        runOnUiThread(() -> {
                            chatsAdapter.updateChats(chatsList);
                            chatNameEditText.setText("");
                            Toast.makeText(MessengerMainActivity.this, makePublic
                                            ? "Public group created — anyone can find it"
                                            : "Private group created — invite people from the member list",
                                    Toast.LENGTH_LONG).show();
                        });
                    } else {
                        Log.e(TAG, "Failed to create chat: " + response.code());
                        Toast.makeText(MessengerMainActivity.this, "Failed to create chat", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onFailure(Call<Chat> call, Throwable t) {
                    Log.e(TAG, "Error creating chat: " + t.getMessage());
                    Toast.makeText(MessengerMainActivity.this, "Error creating chat", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(this, "Chat name cannot be empty", Toast.LENGTH_SHORT).show();
        }
    }

    private void filterUsers(String term) {
        usersAdapter.filter(term);
    }

    private void filterChats(String term) {
        chatsAdapter.filter(term);
    }

    public int getCurrentUserId() {
        return currentUserId;
    }

    private void showCreateUserDialog() {
        CreateUserDialogFragment dialog = new CreateUserDialogFragment();
        dialog.setOnUserCreatedListener(user -> {
            usersList.add(user);
            fetchUsers();
            Toast.makeText(this, "User created successfully!", Toast.LENGTH_SHORT).show();
        });
        dialog.show(getSupportFragmentManager(), "CreateUserDialog");
    }

    private void showLoginDialog() {
        LoginDialogFragment dialog = new LoginDialogFragment();
        dialog.setOnLoginListener(user -> {
            currentUserId = user.getId();
            String username = user.getUsername();
            getSharedPreferences("prefs", MODE_PRIVATE)
                    .edit()
                    .putInt("userId", currentUserId)
                    .putString("username", username)
                    .apply();
            callsAdapter.updateUser(currentUserId, username);
            profileFragment = null; // Сброс ProfileFragment, чтобы он загрузил данные нового пользователя
            updateLoginState();
            updateProfileIcon(); // ← Для надёжности
            // Отправляем действие UPDATE_USER в CallService
            Intent serviceIntent = new Intent(this, CallService.class);
            serviceIntent.putExtra("action", "UPDATE_USER");
            startForegroundService(serviceIntent);
            Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show();
            if (isBound && callService != null) {
                callService.sendOnlineStatus();
            }
            // Добавляем отправку LOGIN_USER
            Intent loginIntent = new Intent(this, CallService.class);
            loginIntent.putExtra("action", "LOGIN_USER");
            startForegroundService(loginIntent);
        });
        dialog.show(getSupportFragmentManager(), "LoginDialog");
    }

    private void logoutUser() {
        com.example.unlimitedpossibilities12.messenger.notife.PushMessagingService.unregister(this);
        currentUserId = -1;
        getSharedPreferences("prefs", MODE_PRIVATE)
                .edit()
                .remove("userId")
                .remove("username") // Удаляем username при выходе
                .apply();
        profileFragment = null; // Сброс фрагмента профиля
        updateLoginState();
        updateProfileIcon();
        if (isBound && callService != null) {
            callService.sendOfflineStatus();
            Intent serviceIntent = new Intent(this, CallService.class);
            serviceIntent.putExtra("action", "LOGOUT_USER");
            startService(serviceIntent);
        }
        Toast.makeText(this, "Logged out successfully!", Toast.LENGTH_SHORT).show();
    }

    private void updateLoginState() {
        // Вход и выход больше не живут в шапке списка чатов: попасть сюда без
        // аккаунта нельзя, а «Log out» лежит в меню профиля. Кнопки оставлены
        // в разметке скрытыми, чтобы не ломать остальной код этого экрана.
        if (loginButton != null) loginButton.setVisibility(View.GONE);
        if (logoutButton != null) logoutButton.setVisibility(View.GONE);

        updateProfileIcon();
    }

    /**
     * Выход из аккаунта — из меню профиля.
     *
     * Токен доступа тоже сбрасываем: он жил 30 дней и без этого следующий
     * вошедший на телефоне отправлял бы запросы от имени прежнего.
     */
    public void logoutAndReturnToAuth() {
        logoutUser();
        com.example.unlimitedpossibilities12.api.AuthToken.clear(this);
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void updateProfileIcon() {
        if (currentUserId == -1) {
            navProfileIcon.setVisibility(View.VISIBLE);
            navProfileAvatar.setVisibility(View.GONE);
            return;
        }

        // Авторизован
        navProfileIcon.setVisibility(View.GONE);
        navProfileAvatar.setVisibility(View.VISIBLE);

        // Пытаемся найти пользователя в списке
        User currentUser = usersList.stream()
                .filter(u -> u.getId() == currentUserId)
                .findFirst()
                .orElse(null);

        String username = "User";
        String avatarPath = null;

        if (currentUser != null) {
            username = currentUser.getUsername();
            avatarPath = currentUser.getAvatar();
        } else {
            // Если пользователя нет в списке — берём из SharedPreferences
            username = getSharedPreferences("prefs", MODE_PRIVATE)
                    .getString("username", "User");
        }

        if (avatarPath != null && !avatarPath.isEmpty()) {
            // Есть аватар — загружаем
            String avatarUrl = "https://unlimitedpossibilities12.org" +
                    avatarPath + "?t=" + System.currentTimeMillis();

            Glide.with(this)
                    .load(avatarUrl)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .circleCrop()
                    .placeholder(R.drawable.default_messenger_avatar)
                    .error(createAvatarDrawable(username))
                    .into(navProfileAvatar);
        } else {
            // Фото нет — первая буква
            navProfileAvatar.setImageDrawable(createAvatarDrawable(username));
        }
    }

    private android.graphics.drawable.Drawable createAvatarDrawable(String username) {
        String firstLetter = username.isEmpty() ? "?" : username.substring(0, 1).toUpperCase();

        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(28, 28, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        // Фон
        android.graphics.Paint bgPaint = new android.graphics.Paint();
        bgPaint.setColor(0xFF6366F1);
        canvas.drawCircle(14, 14, 14, bgPaint);

        // Буква
        android.graphics.Paint textPaint = new android.graphics.Paint();
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(14f);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);

        canvas.drawText(firstLetter, 14, 19, textPaint);

        return new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
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
                GroupCallRequest groupCallRequest = (GroupCallRequest) intent.getSerializableExtra("groupCallRequest");
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
                                        intent = new Intent(MessengerMainActivity.this, VideoCallActivity.class);
                                        break;
                                    case "translate":
                                        intent = new Intent(MessengerMainActivity.this, VoiceCallWithTranslateActivity.class);
                                        break;
                                    case "voice_secure":  // ← ДОБАВЬ ЭТОТ CASE!
                                        intent = new Intent(MessengerMainActivity.this, SecureVoiceCallActivity.class);
                                        break;
                                    case "voice":
                                    default:
                                        intent = new Intent(MessengerMainActivity.this, VoiceCallActivity.class);
                                        break;
                                }
                                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                                intent.putExtra("recipientId", String.valueOf(incomingCall.getCallerId()));
                                intent.putExtra("roomId", incomingCall.getRoomId());
                                intent.putExtra("isCaller", false);
                                intent.putExtra("callerName", callerName);
                                intent.putExtra("recipientName", incomingCall.getCallerName());
                                startActivity(intent);
                                Toast.makeText(MessengerMainActivity.this,
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

    private void fetchCalls() {
        if (currentUserId == -1) {
            Toast.makeText(MessengerMainActivity.this, "You need to log in to view calls", Toast.LENGTH_SHORT).show();
            return;
        }
        apiService.getCalls(currentUserId).enqueue(new Callback<List<com.example.unlimitedpossibilities12.api.Call>>() {
            @Override
            public void onResponse(retrofit2.Call<List<com.example.unlimitedpossibilities12.api.Call>> call, Response<List<com.example.unlimitedpossibilities12.api.Call>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    callsList.clear();
                    callsList.addAll(response.body());
                    Collections.sort(callsList, (a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
                    callsAdapter.updateCalls(callsList, usersList);
                    Log.d(TAG, "Calls fetched: " + callsList.size());
                } else {
                    Toast.makeText(MessengerMainActivity.this, "Failed to fetch calls", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Failed to fetch calls: code=" + response.code());
                }
            }

            @Override
            public void onFailure(retrofit2.Call<List<com.example.unlimitedpossibilities12.api.Call>> call, Throwable t) {
                Toast.makeText(MessengerMainActivity.this, "Error fetching calls", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Error fetching calls: " + t.getMessage());
            }
        });
    }

    private void deleteCall(long callId) {
        if (currentUserId == -1) {
            Toast.makeText(MessengerMainActivity.this, "You need to log in to delete calls", Toast.LENGTH_SHORT).show();
            return;
        }
        apiService.deleteCall(callId, currentUserId).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    callsList.removeIf(c -> c.getId() == callId);
                    callsAdapter.updateCalls(callsList, usersList);
                    Toast.makeText(MessengerMainActivity.this, "Call deleted successfully", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Call deleted: id=" + callId);
                } else {
                    Toast.makeText(MessengerMainActivity.this, "Failed to delete call", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Failed to delete call: code=" + response.code());
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Toast.makeText(MessengerMainActivity.this, "Error deleting call", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Error deleting call: " + t.getMessage());
            }
        });
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
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(chatUpdatedReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(messageReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(endCallReceiver);
        Log.d(TAG, "Receivers unregistered in onDestroy");
    }

    @Override
    public void onBackPressed() {
        if (callsAdapter.isSelectionMode()) {
            callsAdapter.exitSelectionMode();
        } else {
            super.onBackPressed();
        }
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
    protected void onResume() {
        super.onResume();
        refreshOfflineStrip(); // режим могли включить или выключить на его экране
        if (shouldRefreshChats) {
            fetchChats(); // Обновляем чаты при возвращении, если флаг установлен
            shouldRefreshChats = false; // Сбрасываем флаг после обновления
        } else if (chatsList.isEmpty()) {
            fetchChats(); // в прошлый раз не загрузилось — пробуем снова
        }
        if (callsContainer.getVisibility() == View.VISIBLE) {
            fetchCalls(); // Обновляем звонки, если открыта вкладка Calls
        }

        updateProfileIcon();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }
}