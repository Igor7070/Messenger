package com.example.unlimitedpossibilities12.messenger;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.CallRequest;
import com.example.unlimitedpossibilities12.api.Chat;
import com.example.unlimitedpossibilities12.api.GroupCallRequest;
import com.example.unlimitedpossibilities12.api.Message;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class PersonalChatsActivity extends AppCompatActivity {
    private static final String TAG = "PersonalChatsActivity";
    private static final String ACTION_SHOW_CALL_DIALOG = "com.example.unlimitedpossibilities12.SHOW_CALL_DIALOG";
    private static final String ACTION_NEW_NOTIFICATION = "com.example.unlimitedpossibilities12.NEW_NOTIFICATION";
    private static final String ACTION_END_CALL = "com.example.unlimitedpossibilities12.END_CALL";
    private TextView titleTextView;
    private RecyclerView chatsRecyclerView;
    private androidx.appcompat.widget.SearchView searchView;
    private PrivateChatsAdapter chatsAdapter;
    private ApiService apiService;
    private int currentUserId;
    private String currentUserName;
    private List<Chat> privateChats;
    private Map<Long, User> chatPartners;
    private MediaPlayer mediaPlayer;
    private CallRequest incomingCall;
    private boolean userInteracted = false;
    private CallService callService;
    private GroupCallRequest incomingGroupCall;
    private boolean isBound = false;
    private BroadcastReceiver callReceiver, notificationReceiver, endCallReceiver, userStatusReceiver;
    private boolean shouldRefreshChats = true; // Флаг для управления обновлением

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
        setContentView(R.layout.activity_personal_chats);

        // Запрещаем поворот экрана
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        apiService = ApiClient.getApiService();
        mediaPlayer = null; // Инициализация отложена до необходимости

        // Инициализация компонентов
        titleTextView = findViewById(R.id.title_text_view);
        chatsRecyclerView = findViewById(R.id.chats_recycler_view);
        searchView = findViewById(R.id.search_view);

        // Получение параметров
        currentUserId = getIntent().getIntExtra("currentUserId", -1);
        currentUserName = getSharedPreferences("prefs", MODE_PRIVATE).getString("username", "User");
        Log.d(TAG, "Current user: id=" + currentUserId + ", username=" + currentUserName);

        // Инициализация данных
        privateChats = new ArrayList<>();
        chatPartners = new HashMap<>();

        // Настройка RecyclerView
        chatsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        chatsAdapter = new PrivateChatsAdapter(this, privateChats, chatPartners, currentUserName,
                this::navigateToPrivateChat, this::showContextMenu);
        chatsRecyclerView.setAdapter(chatsAdapter);

        // Загрузка данных
        titleTextView.setText(currentUserName + "'s private chats");
        fetchPrivateChats();

        // Настройка SearchView
        setupSearchView();

        // Обработка взаимодействия пользователя
        findViewById(android.R.id.content).setOnClickListener(v -> userInteracted = true);

        // Привязка к CallService
        Intent serviceIntent = new Intent(this, CallService.class);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);

        // Иконка ассистента в нижнем правом углу
        ImageView assistantIcon = findViewById(R.id.assistant_icon);
        assistantIcon.setOnClickListener(v -> {
            AssistantChatDialogFragment dialog = new AssistantChatDialogFragment();
            dialog.show(getSupportFragmentManager(), "AssistantChatDialog");
        });
        assistantIcon.bringToFront();

        // Анимация увеличения при нажатии (как в главной активности)
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

        setupDarkSearchView();

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
                        Toast.makeText(PersonalChatsActivity.this, notification, Toast.LENGTH_LONG).show();
                        // Обновляем чаты при получении уведомления
                        shouldRefreshChats = true;
                        fetchPrivateChats();
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
                Log.d(TAG, "END_CALL for roomId: " + roomId + ", incomingCall: " + (incomingCall != null ? incomingCall.getRoomId() : "null") + ", incomingGroupCall: " + (incomingGroupCall != null ? incomingGroupCall.getRoomId() : "null"));
                if (roomId == null) {
                    Log.e(TAG, "roomId is null in END_CALL broadcast");
                    return;
                }
                FragmentManager fm = getSupportFragmentManager();
                if (incomingCall != null && roomId.equals(incomingCall.getRoomId())) {
                    Log.d(TAG, "Processing endCall for matching roomId: " + roomId);
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
                    incomingCall = null;
                    Log.d(TAG, "incomingCall reset for roomId: " + roomId);
                } else if (incomingGroupCall != null && roomId.equals(incomingGroupCall.getRoomId())) {
                    Log.d(TAG, "Processing endCall for group call roomId: " + roomId);
                    IncomingCallDialogFragment dialog = (IncomingCallDialogFragment) fm.findFragmentByTag("IncomingGroupCallDialog");
                    if (dialog != null && dialog.isAdded()) {
                        try {
                            dialog.dismissAllowingStateLoss();
                            Log.d(TAG, "Incoming group call dialog dismissed for roomId: " + roomId);
                        } catch (Exception e) {
                            Log.e(TAG, "Error dismissing group call dialog: " + e.getMessage());
                        }
                    } else {
                        Log.d(TAG, "No active dialog found for IncomingGroupCallDialog");
                    }
                    incomingGroupCall = null;
                    Log.d(TAG, "incomingGroupCall reset for roomId: " + roomId);
                } else {
                    Log.w(TAG, "No matching call for roomId: " + roomId + ", current incomingCall: " + (incomingCall != null ? incomingCall.getRoomId() : "null") + ", incomingGroupCall: " + (incomingGroupCall != null ? incomingGroupCall.getRoomId() : "null"));
                }
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(PersonalChatsActivity.this, "The call was cancelled.", Toast.LENGTH_LONG).show();
                    Log.d(TAG, "Toast shown for roomId: " + roomId);
                }
            }
        };

        userStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long userId = intent.getLongExtra("userId", -1); // Изменено с getIntExtra на getLongExtra
                boolean isOnline = intent.getBooleanExtra("online", false);
                Log.d(TAG, "Received user status update: userId=" + userId + ", isOnline=" + isOnline + ", intent action=" + intent.getAction());
                if (userId != -1) {
                    Log.d(TAG, "Calling updatePartnerStatus for userId=" + userId + ", chatPartners size=" + chatPartners.size());
                    chatsAdapter.updatePartnerStatus(userId, isOnline);
                } else {
                    Log.w(TAG, "Invalid userId in status update");
                }
            }
        };
    }

    private void setupSearchView() {
        searchView.setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false; // Не обрабатываем отправку запроса
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterChats(newText);
                return true;
            }
        });
    }

    public void filterChats(String query) {
        List<Chat> filteredChats = new ArrayList<>();
        String lowercaseQuery = query.trim().toLowerCase();

        for (Chat chat : privateChats) {
            String partnerName = getChatPartnerName(chat.getName()).toLowerCase();
            if (partnerName.contains(lowercaseQuery)) {
                filteredChats.add(chat);
            }
        }

        chatsAdapter.updateChats(filteredChats);
        Log.d(TAG, "Filtered chats: " + filteredChats.size() + " for query: " + query);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (shouldRefreshChats) {
            fetchPrivateChats();
        }
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

    private void fetchPrivateChats() {
        apiService.getPrivateChats(currentUserId).enqueue(new Callback<List<Chat>>() {
            @Override
            public void onResponse(Call<List<Chat>> call, Response<List<Chat>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<Chat> fetchedChats = response.body();
                    Log.d(TAG, "Fetched " + fetchedChats.size() + " private chats");
                    for (Chat chat : fetchedChats) {
                        Log.d(TAG, "Chat: id=" + chat.getId() + ", name=" + chat.getName());
                    }
                    runOnUiThread(() -> {
                        privateChats.clear();
                        privateChats.addAll(fetchedChats);
                        Log.d(TAG, "privateChats updated with " + privateChats.size() + " chats");
                        // Сбрасываем поиск, чтобы показать полный список
                        if (!searchView.getQuery().toString().isEmpty()) {
                            filterChats(searchView.getQuery().toString());
                        } else {
                            chatsAdapter.updateChats(new ArrayList<>(privateChats));
                        }
                        fetchChatPartners();
                        fetchLastMessages();
                        shouldRefreshChats = false; // Сбрасываем флаг после обновления
                    });
                } else {
                    Log.e(TAG, "Failed to fetch private chats: HTTP " + response.code());
                    Toast.makeText(PersonalChatsActivity.this, "Failed to fetch private chats", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<List<Chat>> call, Throwable t) {
                Log.e(TAG, "Error fetching private chats: " + t.getMessage());
                Toast.makeText(PersonalChatsActivity.this, "Error fetching chats: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void fetchChatPartners() {
        if (privateChats.isEmpty()) {
            Log.d(TAG, "No private chats to fetch partners for");
            return;
        }

        Map<Long, User> partners = new HashMap<>();
        AtomicInteger pendingRequests = new AtomicInteger(privateChats.size());

        for (Chat chat : privateChats) {
            String partnerName = getChatPartnerName(chat.getName());
            if (partnerName.isEmpty()) {
                Log.w(TAG, "Empty partner name for chat " + chat.getId());
                pendingRequests.decrementAndGet();
                continue;
            }

            apiService.getUserByUsername(partnerName).enqueue(new Callback<User>() {
                @Override
                public void onResponse(Call<User> call, Response<User> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        User partner = response.body();
                        partners.put(chat.getId(), response.body());
                        Log.d(TAG, "Fetched partner " + partnerName + " for chat " + chat.getId());
                        if (isBound && callService != null && callService.getStompClient() != null && callService.getStompClient().isConnected()) {
                            callService.requestUserStatus(partner.getId());
                            Log.d(TAG, "Requested status for userId=" + partner.getId());
                        }
                    } else {
                        Log.e(TAG, "Failed to fetch partner " + partnerName + ": HTTP " + response.code());
                    }

                    if (pendingRequests.decrementAndGet() == 0) {
                        Log.d(TAG, "All partners fetched, updating adapter with " + partners.size() + " partners");
                        runOnUiThread(() -> chatsAdapter.updateChatPartners(partners));
                    }
                }

                @Override
                public void onFailure(Call<User> call, Throwable t) {
                    Log.e(TAG, "Error fetching partner " + partnerName + ": " + t.getMessage());
                    if (pendingRequests.decrementAndGet() == 0) {
                        Log.d(TAG, "All partners fetched (with errors), updating adapter with " + partners.size() + " partners");
                        runOnUiThread(() -> chatsAdapter.updateChatPartners(partners));
                    }
                }
            });
        }
    }

    private void fetchLastMessages() {
        AtomicInteger pendingRequests = new AtomicInteger(privateChats.size());
        for (Chat chat : privateChats) {
            apiService.getMessagesByChatId(chat.getId()).enqueue(new Callback<List<Message>>() {
                @Override
                public void onResponse(Call<List<Message>> call, Response<List<Message>> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        if (!response.body().isEmpty()) {
                            Message lastMessage = response.body().get(response.body().size() - 1);
                            chat.setLastMessage(lastMessage.getContent());
                            chat.setTimestamp(lastMessage.getTimestamp());
                            chat.setUnreadCount(calculateUnreadCount(response.body()));
                        } else {
                            chat.setLastMessage("No messages yet");
                            chat.setTimestamp("");
                            chat.setUnreadCount(0);
                        }
                    }
                    if (pendingRequests.decrementAndGet() == 0) {
                        runOnUiThread(() -> {
                            // Повторно применяем фильтр, если поиск активен
                            if (!searchView.getQuery().toString().isEmpty()) {
                                filterChats(searchView.getQuery().toString());
                            } else {
                                chatsAdapter.notifyDataSetChanged();
                            }
                        });
                    }
                }

                @Override
                public void onFailure(Call<List<Message>> call, Throwable t) {
                    Log.e(TAG, "Error fetching messages for chat " + chat.getId() + ": " + t.getMessage());
                    if (pendingRequests.decrementAndGet() == 0) {
                        runOnUiThread(() -> {
                            // Повторно применяем фильтр, если поиск активен
                            if (!searchView.getQuery().toString().isEmpty()) {
                                filterChats(searchView.getQuery().toString());
                            } else {
                                chatsAdapter.notifyDataSetChanged();
                            }
                        });
                    }
                }
            });
        }
    }

    private int calculateUnreadCount(List<Message> messages) {
        int unreadCount = 0;
        for (Message message : messages) {
            if (!message.isRead() && message.getSender() != null && message.getSender().getId() != currentUserId) {
                unreadCount++;
            }
        }
        return unreadCount;
    }

    private String getChatPartnerName(String chatName) {
        String[] parts = chatName.split("_");
        if (parts.length == 3 && parts[2].equals("secret")) {
            return parts[0].equals(currentUserName) ? parts[1] : parts[0];
        } else if (parts.length == 2) {
            return parts[0].equals(currentUserName) ? parts[1] : parts[0];
        }
        Log.w(TAG, "No partner name found in chatName: " + chatName);
        return "";
    }

    private void navigateToPrivateChat(long chatId) {
        for (Chat chat : privateChats) {
            if (chat.getId() == chatId) {
                Intent intent;
                if (chat.getIsSecret()) {
                    intent = new Intent(this, SecretPrivateChatActivity.class);
                } else {
                    intent = new Intent(this, PrivateChatActivity.class);
                }
                intent.putExtra("chatId", chatId);
                shouldRefreshChats = true; // Устанавливаем флаг перед переходом в чат
                startActivity(intent);
                return;
            }
        }
        Log.w(TAG, "Chat with id " + chatId + " not found in privateChats");
        Toast.makeText(this, "Chat not found", Toast.LENGTH_SHORT).show();
    }

    private void showContextMenu(long chatId, View view) {
        PopupMenu popup = new PopupMenu(this, view);
        popup.getMenu().add("Delete chat");
        popup.setOnMenuItemClickListener(item -> {
            if ("Delete chat".equals(item.getTitle())) {
                showDeleteChatDialog(chatId);
            }
            return true;
        });
        popup.show();
    }

    private void showDeleteChatDialog(long chatId) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete Chat")
                .setMessage("Are you sure you want to delete this chat?")
                .setPositiveButton("Delete", (dialog, which) -> deleteChat(chatId))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteChat(long chatId) {
        apiService.deleteChatFromUser(currentUserId, chatId).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    privateChats.removeIf(chat -> chat.getId() == chatId);
                    // Повторно применяем фильтр, если поиск активен
                    if (!searchView.getQuery().toString().isEmpty()) {
                        filterChats(searchView.getQuery().toString());
                    } else {
                        chatsAdapter.updateChats(new ArrayList<>(privateChats));
                    }
                    apiService.checkAndDeleteChat(chatId).enqueue(new Callback<ResponseBody>() {
                        @Override
                        public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                            try {
                                Log.d(TAG, "Check and delete chat response: " + (response.body() != null ? response.body().string() : ""));
                            } catch (Exception e) {
                                Log.e(TAG, "Error reading check response: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onFailure(Call<ResponseBody> call, Throwable t) {
                            Log.e(TAG, "Error checking chat: " + t.getMessage());
                        }
                    });
                    Toast.makeText(PersonalChatsActivity.this, "Chat deleted successfully!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(PersonalChatsActivity.this, "Failed to delete chat: " + response.code(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Toast.makeText(PersonalChatsActivity.this, "Error deleting chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
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

        if (isBound && callService != null && userInteracted) {
            callService.playCallSound();
            Log.d(TAG, "Call sound triggered via CallService for roomId: " + callRequest.getRoomId());
        }

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
                                        intent = new Intent(PersonalChatsActivity.this, VideoCallActivity.class);
                                        break;
                                    case "translate":
                                        intent = new Intent(PersonalChatsActivity.this, VoiceCallWithTranslateActivity.class);
                                        break;
                                    case "voice_secure":  // ← ДОБАВЬ ЭТОТ CASE!
                                        intent = new Intent(PersonalChatsActivity.this, SecureVoiceCallActivity.class);
                                        break;
                                    case "voice":
                                    default:
                                        intent = new Intent(PersonalChatsActivity.this, VoiceCallActivity.class);
                                        break;
                                }
                                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                                intent.putExtra("recipientId", String.valueOf(incomingCall.getCallerId()));
                                intent.putExtra("roomId", incomingCall.getRoomId());
                                intent.putExtra("isCaller", false);
                                intent.putExtra("callerName", callerName);
                                intent.putExtra("recipientName", incomingCall.getCallerName());
                                startActivity(intent);
                                Toast.makeText(PersonalChatsActivity.this,
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
        Toast.makeText(this, getString(R.string.call_rejected), Toast.LENGTH_SHORT).show();
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

    private void setupDarkSearchView() {
        // Основной фон
        searchView.setBackgroundResource(R.drawable.search_view_background_dark);

        // Внутренний контейнер
        View searchPlate = searchView.findViewById(androidx.appcompat.R.id.search_plate);
        if (searchPlate != null) {
            searchPlate.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }

        // Поле ввода
        EditText searchEditText = searchView.findViewById(androidx.appcompat.R.id.search_src_text);
        if (searchEditText != null) {
            searchEditText.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            searchEditText.setTextColor(Color.parseColor("#E2E8F0"));
            searchEditText.setHintTextColor(Color.parseColor("#94A3B8"));

            // Принудительно устанавливаем hint ещё раз
            searchEditText.setHint("Search chats...");
        }

        // Иконки
        ImageView searchIcon = searchView.findViewById(androidx.appcompat.R.id.search_mag_icon);
        if (searchIcon != null) {
            searchIcon.setColorFilter(Color.parseColor("#94A3B8"));
        }

        ImageView closeButton = searchView.findViewById(androidx.appcompat.R.id.search_close_btn);
        if (closeButton != null) {
            closeButton.setColorFilter(Color.parseColor("#94A3B8"));
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
}