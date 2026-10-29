package com.example.unlimitedpossibilities12.messenger;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.OpenableColumns;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.CallRequest;
import com.example.unlimitedpossibilities12.api.Chat;
import com.example.unlimitedpossibilities12.api.FileUploadResponse;
import com.example.unlimitedpossibilities12.api.Message;
import com.example.unlimitedpossibilities12.api.MessageNotification;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.api.PublicKeyRequest;
import com.example.unlimitedpossibilities12.api.PublicKeyResponse;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;
import com.goterl.lazysodium.SodiumAndroid;
import com.goterl.lazysodium.interfaces.Box;
import com.goterl.lazysodium.interfaces.SecretBox;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import ua.naiksoftware.stomp.StompClient;

public class SecretPrivateChatActivity extends AppCompatActivity implements SecretMessageAdapter.OnMessageActionListener {
    private android.content.BroadcastReceiver socketReadyReceiver;
    private static final String TAG = "SecretPrivateChatActivity";
    private static final String ACTION_SHOW_CALL_DIALOG = "com.example.unlimitedpossibilities12.SHOW_CALL_DIALOG";
    private static final String ACTION_NEW_MESSAGE = "com.example.unlimitedpossibilities12.NEW_MESSAGE";
    private static final String ACTION_END_CALL = "com.example.unlimitedpossibilities12.END_CALL";
    private static final String USER_STATUS = "com.example.unlimitedpossibilities12.USER_STATUS";
    private static final String EXTRA_USER_ID = "userId";
    private static final String EXTRA_ONLINE = "online";
    private static final int FILE_PICKER_REQUEST_CODE = 1;
    private static final int AUDIO_PERMISSION_REQUEST_CODE = 100;
    /** Подписи вместо пустоты, когда расшифровать нечем. Их же узнаёт адаптер. */
    public static final String KEY_GONE_NOTICE = "Encrypted with a key this device no longer has";
    public static final String OWN_TEXT_GONE_NOTICE = "Not stored on this device";

    private long partnerId = -1;
    private String partnerUsername; // нужно для защищённого звонка
    private TextView chatNameTextView;
    private EditText inputMessage;
    private RecyclerView messageList;
    private SecretMessageAdapter messageAdapter;
    private List<Message> messages = new ArrayList<>();
    private ApiService apiService;
    private int currentUserId;
    private String username;
    private long chatId;
    private String chatName;
    private Long editingMessageId;
    private Uri selectedFileUri;
    private StompClient stompClient;
    private Handler handler = new Handler();
    private boolean userInteracted = false;
    private MediaPlayer mediaPlayer;
    private CallService callService;
    private boolean isBound = false;
    private CallRequest incomingCall;
    private BroadcastReceiver callReceiver, messageReceiver, endCallReceiver, userStatusReceiver, typingReceiver;
    private long lastStatusCheckTime = 0;
    private static final long STATUS_CHECK_DEBOUNCE_MS = 5000;
    private boolean isMessageJustSent = false;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private File audioFile;
    private TextView attachedFileTextView;
    private SodiumAndroid sodium = new SodiumAndroid();
    private static final String KEYSTORE_ALIAS = "secret_chat_key";
    private static final String PUBLIC_KEY_PREFS = "public_keys";
    private final Map<Long, String> localMessageContent = new HashMap<>();
    private String currentTypingName = null;
    // Для индикатора "печатает…"
    private long lastTypingSentTime = 0L;
    private final long TYPING_DEBOUNCE_MS = 2400L;          // отправляем не чаще раза в ~2.4 сек
    private final long TYPING_VISIBLE_DURATION_MS = 2500L; // индикатор живёт 2.5 сек

    private final Handler typingHandler = new Handler(Looper.getMainLooper());
    private Runnable typingHideRunnable;

    private TextView tvTyping;

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CallService.LocalBinder binder = (CallService.LocalBinder) service;
            callService = binder.getService();
            isBound = true;
            Log.d(TAG, "Connected to CallService");
            initStompClient();
            handleIntent(getIntent());
            initializeKeys();
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
        // Сервис мог ещё не поднять соединение (приложение разбудил push) —
        // как только оно откроется, перечитываем клиента и подписки
        socketReadyReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
                android.util.Log.d(TAG, "Соединение готово — переинициализируем STOMP");
                // В момент события STOMP-клиент ещё рапортует isConnected()=false,
                // поэтому переинициализацию и отметки «прочитано» делаем с паузой
                // и повторной попыткой — иначе вторая галочка у отправителя не вставала.
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    initStompClient();
                    markAllMessagesAsRead();
                }, 500);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) markAllMessagesAsRead();
                }, 2500);
            }
        };
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).registerReceiver(
                socketReadyReceiver,
                new android.content.IntentFilter(com.example.unlimitedpossibilities12.messenger.notife.CallService.ACTION_SOCKET_READY));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_secret_private_chat);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        ImageView secretIcon = findViewById(R.id.secret_icon);
        if (secretIcon != null) {
            secretIcon.setImageResource(R.drawable.ic_lock);
            secretIcon.setVisibility(View.VISIBLE);
            secretIcon.setOnClickListener(v -> showSafetyNumberDialog()); // отпечаток ключей
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        apiService = ApiClient.getApiService();
        chatNameTextView = findViewById(R.id.chat_name_text_view);
        inputMessage = findViewById(R.id.input_message);
        messageList = findViewById(R.id.message_list);
        attachedFileTextView = findViewById(R.id.attached_file_text_view);
        tvTyping = findViewById(R.id.tv_typing);

        // Иконка ассистента в левом краю тулбара
        ImageView secureCallIcon = findViewById(R.id.secure_call_icon);
        if (secureCallIcon != null) secureCallIcon.setOnClickListener(v -> startSecureCall());

        ImageView translateIcon = findViewById(R.id.translate_icon);
        if (translateIcon != null) {
            translateIcon.setOnClickListener(v -> new SecretTranslationDialogFragment(chatId, (enabled, src, tgt) -> {
                localTranslationCache.clear();
                updateTranslateIconTint();
                messageAdapter.notifyDataSetChanged(); // перерисовать с переводом/без
            }).show(getSupportFragmentManager(), "SecretTranslation"));
        }
        ImageView assistantIcon = findViewById(R.id.assistant_icon);
        if (assistantIcon != null) {
            assistantIcon.setOnClickListener(v -> {
                AssistantChatDialogFragment dialog = new AssistantChatDialogFragment();
                dialog.show(getSupportFragmentManager(), "AssistantChatDialog");
                Log.d(TAG, "Assistant dialog opened from secret chat (left toolbar icon)");
            });
        } else {
            Log.w(TAG, "assistant_icon not found in layout — check activity_secret_private_chat.xml");
        }

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        messageList.setLayoutManager(layoutManager);
        // Без cross-fade при notifyItemChanged: иначе каждое обновление статуса
        // (доставлено/прочитано) «моргает» уже показанной картинкой.
        if (messageList.getItemAnimator() instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
            ((androidx.recyclerview.widget.SimpleItemAnimator) messageList.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        messageAdapter = new SecretMessageAdapter(this, messages, currentUserId, this, apiService);
        messageList.setAdapter(messageAdapter);

        chatId = getIntent().getLongExtra("chatId", -1);
        updateTranslateIconTint(); // только теперь известен chatId — настройки перевода читаются по нему
        username = getSharedPreferences("prefs", MODE_PRIVATE).getString("username", "");
        currentUserId = getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);

        if (chatId == -1 || username.isEmpty()) {
            Log.e(TAG, "Invalid parameters: chatId=" + chatId + ", username=" + username);
            Toast.makeText(this, "Invalid chat or user data", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        validateUserAndFetchData();

        subscribeToPartnerPublicKey();

        findViewById(android.R.id.content).setOnClickListener(v -> userInteracted = true);

        attachedFileTextView.setOnClickListener(v -> {
            if (selectedFileUri != null) {
                selectedFileUri = null;
                attachedFileTextView.setVisibility(View.GONE);
                updateInputIcons();
                Log.d(TAG, "File detached");
                Toast.makeText(this, "File detached", Toast.LENGTH_SHORT).show();
            }
        });

        inputMessage.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN && !event.isShiftPressed()) {
                sendMessage();
                return true;
            }
            return false;
        });

        Drawable micDrawable = ContextCompat.getDrawable(this, R.drawable.ic_mic);
        Drawable attachmentDrawable = ContextCompat.getDrawable(this, R.drawable.ic_attachment);
        if (micDrawable != null) {
            micDrawable.setTint(Color.parseColor("#757575"));
        }
        if (attachmentDrawable != null) {
            attachmentDrawable.setTint(Color.parseColor("#757575"));
        }
        inputMessage.setCompoundDrawablesWithIntrinsicBounds(attachmentDrawable, null, micDrawable, null);

        inputMessage.setOnTouchListener((v, event) -> {
            Drawable currentAttachmentDrawable = inputMessage.getCompoundDrawables()[0];
            Drawable currentRightDrawable = inputMessage.getCompoundDrawables()[2];
            float attachmentWidth = currentAttachmentDrawable != null ? currentAttachmentDrawable.getBounds().width() : 0;
            float rightDrawableWidth = currentRightDrawable != null ? currentRightDrawable.getBounds().width() : 0;

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (currentRightDrawable != null &&
                        event.getRawX() >= (inputMessage.getRight() - rightDrawableWidth - inputMessage.getPaddingEnd())) {
                    if (!isRecording && (inputMessage.getText().length() > 0 || selectedFileUri != null)) {
                        Drawable sendDrawable = ContextCompat.getDrawable(this, R.drawable.ic_send);
                        if (sendDrawable != null) {
                            sendDrawable.setTint(Color.parseColor("#01579B"));
                            inputMessage.setCompoundDrawablesWithIntrinsicBounds(currentAttachmentDrawable, null, sendDrawable, null);
                            inputMessage.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start();
                        }
                        Log.d(TAG, "Send icon pressed (ACTION_DOWN)");
                    }
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                inputMessage.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
                updateInputIcons();
                if (currentAttachmentDrawable != null &&
                        event.getRawX() <= (inputMessage.getLeft() + attachmentWidth + inputMessage.getPaddingStart())) {
                    Log.d(TAG, "Attachment icon clicked");
                    if (!isRecording) {
                        openFilePicker();
                        return true;
                    }
                    return true;
                } else if (currentRightDrawable != null &&
                        event.getRawX() >= (inputMessage.getRight() - rightDrawableWidth - inputMessage.getPaddingEnd())) {
                    Log.d(TAG, "Right icon clicked, isRecording=" + isRecording + ", selectedFileUri=" + selectedFileUri);
                    if (isRecording) {
                        stopRecording();
                    } else if (!isRecording && inputMessage.getText().length() == 0 && selectedFileUri == null) {
                        requestAudioPermission();
                    } else if (!isRecording && (inputMessage.getText().length() > 0 || selectedFileUri != null)) {
                        sendMessage();
                    }
                    return true;
                }
            }
            return false;
        });

        inputMessage.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                Log.d(TAG, "Text changed, length=" + s.length() + ", isRecording=" + isRecording + ", selectedFileUri=" + selectedFileUri);
                updateInputIcons();

                // ← Для логики печатания
                String text = s != null ? s.toString().trim() : "";
                long now = System.currentTimeMillis();

                if (!text.isEmpty() && now - lastTypingSentTime >= TYPING_DEBOUNCE_MS) {
                    sendTypingEvent(true);
                    lastTypingSentTime = now;
                }
            }
        });

        updateInputIcons();

        Intent serviceIntent = new Intent(this, CallService.class);
        serviceIntent.putExtra("action", "SUBSCRIBE_CHAT");
        serviceIntent.putExtra("chatId", chatId);
        startService(serviceIntent);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);

        callReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                CallRequest callRequest = (CallRequest) intent.getSerializableExtra("callRequest");
                if (callRequest != null && !isFinishing() && !isDestroyed()) {
                    incomingCall = callRequest;
                    showIncomingCallDialog(callRequest);
                } else {
                    Log.w(TAG, "Received call broadcast but activity is not in a valid state");
                }
            }
        };

        messageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_NEW_MESSAGE.equals(intent.getAction())) {
                    long receivedChatId = intent.getLongExtra("chatId", -1);
                    if (receivedChatId == chatId) {
                        String messageJson = intent.getStringExtra("messageJson");
                        try {
                            JSONObject msgJson = new JSONObject(messageJson);
                            Message newMessage = parseMessageFromJson(msgJson);
                            Log.d(TAG, "Parsed message from WebSocket: id=" + newMessage.getId() + ", contentType=" + newMessage.getContentType());
                            Log.d(TAG, "Received message via WebSocket: id=" + newMessage.getId() + ", senderId=" + newMessage.getSender().getId() + ", publicKeyId=" + newMessage.getPublicKeyId());

                            // Сервер рассылает сообщение в топик повторно, когда кто-то
                            // отмечает его прочитанным (/app/readMessage → @SendTo в тот же
                            // топик). Для уже известного сообщения это обновление статуса,
                            // а не новое сообщение: расшифровывать, качать файл и
                            // уведомлять заново не нужно — только обновить флаги.
                            if (!"Message deleted".equals(newMessage.getContent()) && isKnownMessage(newMessage.getId())) {
                                Log.d(TAG, "Status update for known message id=" + newMessage.getId()
                                        + " (read=" + newMessage.isRead() + ", delivered=" + newMessage.isDelivered() + ")");
                                updateMessages(newMessage);
                                return;
                            }

                            if (newMessage.getSender().getId() == currentUserId) {
                                String localContent = null;
                                for (Map.Entry<Long, String> entry : localMessageContent.entrySet()) {
                                    long tempId = entry.getKey();
                                    for (Message msg : messages) {
                                        if (msg.getId() == tempId && msg.getEncryptedContent() != null &&
                                                msg.getEncryptedContent().equals(newMessage.getEncryptedContent())) {
                                            localContent = entry.getValue();
                                            saveLocalMessageContent(newMessage.getId(), localContent, newMessage.getNonce());
                                            localMessageContent.remove(tempId);
                                            Log.d(TAG, "Transferred local content from tempId=" + tempId + " to serverId=" + newMessage.getId());
                                            break;
                                        }
                                    }
                                    if (localContent != null) break;
                                }
                                if (localContent == null) {
                                    localContent = getLocalMessageContent(newMessage.getId());
                                }
                                if (localContent != null) {
                                    newMessage.setContent(localContent);
                                    Log.d(TAG, "Restored local content for received own message id=" + newMessage.getId() + ": " + localContent);
                                } else {
                                    Log.w(TAG, "No local content found for received own message id=" + newMessage.getId());
                                }
                            } else if ("text".equals(newMessage.getMessageType()) && newMessage.getEncryptedContent() != null) {
                                String decryptedContent = decryptMessage(
                                        newMessage.getEncryptedContent(),
                                        newMessage.getNonce(),
                                        newMessage.getSender().getId(),
                                        newMessage.getPublicKeyId() // Передаем publicKeyId
                                );
                                if (decryptedContent != null) {
                                    newMessage.setContent(decryptedContent);
                                    saveLocalMessageContent(newMessage.getId(), decryptedContent, newMessage.getNonce());
                                    Log.d(TAG, "Saved decrypted content for message id=" + newMessage.getId() + ": " + decryptedContent);
                                } else {
                                    newMessage.setContent(KEY_GONE_NOTICE);
                                    Log.w(TAG, "Failed to decrypt message id=" + newMessage.getId());
                                }
                                // Кэшируем текущий публичный ключ отправителя для будущих сообщений
                                fetchPublicKey(newMessage.getSender().getId(), new Callback<String>() {
                                    @Override
                                    public void onResponse(Call<String> call, Response<String> response) {
                                        if (response.isSuccessful() && response.body() != null) {
                                            cachePublicKey(newMessage.getSender().getId(), response.body());
                                            Log.d(TAG, "Cached public key for senderId=" + newMessage.getSender().getId() + ", key=" + response.body());
                                        } else {
                                            Log.e(TAG, "Failed to fetch public key for senderId=" + newMessage.getSender().getId() + ": code=" + response.code());
                                        }
                                    }

                                    @Override
                                    public void onFailure(Call<String> call, Throwable t) {
                                        Log.e(TAG, "Error fetching public key for senderId=" + newMessage.getSender().getId() + ": " + t.getMessage());
                                    }
                                });
                            }
                            if (newMessage.getSender().getId() != currentUserId
                                    && ("file".equals(newMessage.getMessageType()) || "audio".equals(newMessage.getMessageType()))) {
                                applyDecryptedFileMetadata(newMessage); // имя, MIME, подпись
                            }
                            updateMessages(newMessage);
                            sendNotification(newMessage);
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing received message: " + e.getMessage(), e);
                        }
                    }
                }
            }
        };

        endCallReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String roomId = intent.getStringExtra("roomId");
                if (roomId == null || incomingCall == null || !roomId.equals(incomingCall.getRoomId())) {
                    return;
                }
                FragmentManager fm = getSupportFragmentManager();
                IncomingCallDialogFragment dialog = (IncomingCallDialogFragment) fm.findFragmentByTag("IncomingCallDialog");
                if (dialog != null && dialog.isAdded()) {
                    dialog.dismissAllowingStateLoss();
                }
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(SecretPrivateChatActivity.this, "The caller cancelled the call.", Toast.LENGTH_LONG).show();
                }
                incomingCall = null;
            }
        };

        userStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (USER_STATUS.equals(intent.getAction())) {
                    long userId = intent.getLongExtra(EXTRA_USER_ID, -1);
                    boolean online = intent.getBooleanExtra(EXTRA_ONLINE, false);
                    Log.d(TAG, "Received USER_STATUS broadcast: userId=" + userId + ", online=" + online);
                    if (userId == partnerId) {
                        messageAdapter.updateUserStatus(userId, online);
                        updatePartnerStatusIndicator();
                        Log.d(TAG, "Updated toolbar status indicator for partnerId=" + partnerId + " → online=" + online);
                    }
                }
            }
        };
    }

    private void initializeKeys() {
        try {
            String alias = KEYSTORE_ALIAS + "_" + currentUserId;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String publicKeyAlias = "public_key_" + currentUserId;
            String publicKeyBase64;

            // Ключ хранится в SharedPreferences (см. savePrivateKeyToKeystore),
            // поэтому и проверять его наличие нужно там же. Раньше проверка шла
            // через AndroidKeyStore.containsAlias(), куда ключ никогда не клался,
            // и пара генерировалась заново при КАЖДОМ входе в чат — всё, что
            // собеседник зашифровал на предыдущий ключ, становилось нечитаемым.
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
                cachePublicKey(currentUserId, publicKeyBase64);
                Log.d(TAG, "Generated new key pair for userId: " + currentUserId + ", publicKey: " + publicKeyBase64 + ", length: " + publicKeyBase64.length());
                sendPublicKeyToServer(publicKeyBase64);
            } else {
                publicKeyBase64 = prefs.getString(publicKeyAlias, null);
                if (publicKeyBase64 == null) {
                    Log.e(TAG, "Public key not found in SharedPreferences for userId: " + currentUserId);
                    throw new IllegalStateException("Public key missing for existing private key");
                }
                // Удаляем = из сохраненного ключа, если он есть
                publicKeyBase64 = publicKeyBase64.replace("=", "");
                cachePublicKey(currentUserId, publicKeyBase64);
                Log.d(TAG, "Retrieved public key for userId: " + currentUserId + ", publicKey: " + publicKeyBase64 + ", length: " + publicKeyBase64.length());
                sendPublicKeyToServer(publicKeyBase64);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing keys: " + e.getMessage());
            Toast.makeText(this, "Failed to initialize encryption keys", Toast.LENGTH_LONG).show();
        }
    }

    private void savePrivateKeyToKeystore(byte[] privateKey, String alias) {
        try {
            byte[] key = new byte[SecretBox.KEYBYTES];
            sodium.randombytes_buf(key, SecretBox.KEYBYTES);
            byte[] nonce = new byte[SecretBox.NONCEBYTES];
            sodium.randombytes_buf(nonce, nonce.length);

            byte[] encryptedPrivateKey = new byte[privateKey.length + SecretBox.MACBYTES];
            int result = sodium.crypto_secretbox_easy(encryptedPrivateKey, privateKey, privateKey.length, nonce, key);
            if (result != 0) {
                throw new RuntimeException("Failed to encrypt private key");
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String encryptedKeyBase64 = Base64.encodeToString(encryptedPrivateKey, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
            String nonceBase64 = Base64.encodeToString(nonce, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
            String secretBoxKeyBase64 = Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");

            prefs.edit()
                    .putString("encrypted_private_key_" + alias, encryptedKeyBase64)
                    .putString("nonce_" + alias, nonceBase64)
                    .putString("secretbox_key_" + alias, secretBoxKeyBase64)
                    .apply();

            Log.d(TAG, "Private key encrypted and saved to SharedPreferences for alias: " + alias);
        } catch (Exception e) {
            Log.e(TAG, "Error saving private key: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(this, "Failed to save private key", Toast.LENGTH_SHORT).show());
        }
    }

    private byte[] getPrivateKeyFromKeystore() {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String alias = KEYSTORE_ALIAS + "_" + currentUserId;
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

    private void sendPublicKeyToServer(String publicKey) {
        PublicKeyRequest request = new PublicKeyRequest((long) currentUserId, publicKey);
        apiService.uploadPublicKey(request).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Public key uploaded successfully for userId: " + currentUserId);
                    // [ADD] Отправка публичного ключа через WebSocket
                    if (stompClient != null && stompClient.isConnected()) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("userId", currentUserId);
                            json.put("publicKey", publicKey);
                            String destination = "/app/publicKey/" + currentUserId;
                            stompClient.send(destination, json.toString())
                                    .subscribeOn(Schedulers.io())
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .subscribe(
                                            () -> Log.d(TAG, "Public key sent via WebSocket for userId: " + currentUserId),
                                            throwable -> Log.e(TAG, "Error sending public key via WebSocket: " + throwable.getMessage())
                                    );
                        } catch (Exception e) {
                            Log.e(TAG, "Error preparing public key JSON for WebSocket: " + e.getMessage());
                        }
                    } else {
                        Log.w(TAG, "StompClient not connected, skipping WebSocket public key update");
                    }
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

    private String getCachedPublicKey(long userId) {
        return getSharedPreferences(PUBLIC_KEY_PREFS, MODE_PRIVATE).getString("public_key_" + userId, null);
    }

    private void cachePublicKey(long userId, String publicKey) {
        if (publicKey == null) return;
        SharedPreferences prefs = getSharedPreferences(PUBLIC_KEY_PREFS, MODE_PRIVATE);
        String previous = prefs.getString("public_key_" + userId, null);
        prefs.edit().putString("public_key_" + userId, publicKey).apply();

        // Ключ собеседника сменился по сравнению с тем, что видели раньше.
        // Молчаливая замена ключа — это то, как выглядит подмена (MITM),
        // поэтому показываем предупреждение, а не просто принимаем новый.
        if (userId == partnerId && previous != null && !previous.equals(publicKey)) {
            Log.w(TAG, "Partner public key CHANGED for partnerId=" + partnerId);
            runOnUiThread(this::showKeyChangedBanner);
        }
    }

    // ===== Отпечаток ключей (safety number) =====

    /**
     * Тот же алгоритм, что в веб-клиенте (SafetyNumber.js): SHA-256 от двух
     * публичных ключей, отсортированных по userId, 12 групп по 4 hex-символа.
     * У обоих собеседников должен совпадать.
     */
    private String computeSafetyNumber() {
        try {
            String myKey = PreferenceManager.getDefaultSharedPreferences(this)
                    .getString("public_key_" + currentUserId, null);
            String partnerKey = getCachedPublicKey(partnerId);
            if (myKey == null || partnerKey == null || partnerId == -1) return null;
            myKey = myKey.replace("=", "");
            partnerKey = partnerKey.replace("=", "");

            long idA = currentUserId, idB = partnerId;
            String keyA = myKey, keyB = partnerKey;
            if (idA > idB) { long t = idA; idA = idB; idB = t; String tk = keyA; keyA = keyB; keyB = tk; }

            String material = idA + ":" + keyA + "|" + idB + ":" + keyB;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02X", b));
            String h = hex.substring(0, 48);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < h.length(); i += 4) {
                if (i > 0) out.append(' ');
                out.append(h, i, i + 4);
            }
            return out.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute safety number: " + e.getMessage());
            return null;
        }
    }

    private void showSafetyNumberDialog() {
        String number = computeSafetyNumber();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Отпечаток ключей шифрования")
                .setMessage((number != null ? number : "Ключи ещё не загружены")
                        + "\n\nДолжен совпадать с отпечатком на устройстве собеседника. "
                        + "Если не совпадает — сообщения могут читать третьи лица.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showKeyChangedBanner() {
        TextView banner = findViewById(R.id.key_changed_banner);
        if (banner == null) return;
        banner.setVisibility(View.VISIBLE);
        banner.setOnClickListener(v -> {
            showSafetyNumberDialog();
            banner.setVisibility(View.GONE);
        });
    }

    private void fetchPublicKey(long userId, Callback<String> callback) {
        fetchPublicKeyWithRetry(userId, callback, 3);
    }

    private void fetchPublicKeyWithRetry(long userId, Callback<String> callback, int retries) {
        Log.d(TAG, "Fetching public key for userId: " + userId + ", retries left: " + retries);
        apiService.getPublicKey(userId).enqueue(new Callback<PublicKeyResponse>() {
            @Override
            public void onResponse(Call<PublicKeyResponse> call, Response<PublicKeyResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String publicKey = response.body().getPublicKey();
                    Log.d(TAG, "Successfully fetched public key for userId: " + userId + ": " + publicKey);
                    cachePublicKey(userId, publicKey);
                    callback.onResponse(null, Response.success(publicKey));
                } else if (retries > 0 && response.code() == 404) {
                    Log.w(TAG, "Failed to fetch public key for userId: " + userId + ", code=" + response.code() + ", retrying...");
                    handler.postDelayed(() -> fetchPublicKeyWithRetry(userId, callback, retries - 1), 2000);
                } else {
                    Log.e(TAG, "Failed to fetch public key for userId: " + userId + ", code=" + response.code());
                    String cachedKey = getCachedPublicKey(userId);
                    if (cachedKey != null) {
                        Log.d(TAG, "Using cached public key for userId: " + userId + ": " + cachedKey);
                        callback.onResponse(null, Response.success(cachedKey));
                    } else {
                        Log.e(TAG, "No cached public key available for userId: " + userId);
                        callback.onResponse(null, Response.error(response.code(), response.errorBody()));
                    }
                }
            }

            @Override
            public void onFailure(Call<PublicKeyResponse> call, Throwable t) {
                if (retries > 0) {
                    Log.w(TAG, "Error fetching public key for userId: " + userId + ", error=" + t.getMessage() + ", retrying...");
                    handler.postDelayed(() -> fetchPublicKeyWithRetry(userId, callback, retries - 1), 2000);
                } else {
                    Log.e(TAG, "Error fetching public key for userId: " + userId + ", error=" + t.getMessage());
                    String cachedKey = getCachedPublicKey(userId);
                    if (cachedKey != null) {
                        Log.d(TAG, "Using cached public key for userId: " + userId + ": " + cachedKey);
                        callback.onResponse(null, Response.success(cachedKey));
                    } else {
                        Log.e(TAG, "No cached public key available for userId: " + userId);
                        callback.onFailure(null, t);
                    }
                }
            }
        });
    }

    private CompletableFuture<String> encryptMessage(String content, long recipientId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            if (content == null || content.isEmpty()) {
                Log.w(TAG, "Attempting to encrypt empty message");
                future.complete(null);
                return future;
            }
            Log.d(TAG, "Encrypting message: content=" + content + ", recipientId=" + recipientId);

            byte[] privateKey = getPrivateKeyFromKeystore();
            if (privateKey == null) {
                Log.e(TAG, "Private key not found for alias: secret_chat_key_" + currentUserId);
                future.completeExceptionally(new Exception("Private key not found"));
                return future;
            }
            // Приватный ключ в лог не пишем.
            Log.d(TAG, "Private key: length=" + privateKey.length);

            fetchPublicKeyAsync(recipientId).whenComplete((publicKeyStr, throwable) -> {
                if (throwable != null) {
                    Log.e(TAG, "Error fetching public key for userId: " + recipientId + ", error=" + throwable.getMessage());
                    future.completeExceptionally(throwable);
                    return;
                }
                if (publicKeyStr == null) {
                    Log.e(TAG, "Public key is null for userId: " + recipientId);
                    future.completeExceptionally(new Exception("Failed to fetch recipient public key"));
                    return;
                }
                Log.d(TAG, "Fetched public key for recipientId=" + recipientId + ", publicKeyBase64=" + publicKeyStr);
                try {
                    byte[] publicKey = decodeBase64(publicKeyStr);
                    String publicKeyHex = bytesToHex(publicKey);
                    Log.d(TAG, "Public key bytes: length=" + publicKey.length + ", hex=" + publicKeyHex);

                    if (!isValidBase64(publicKeyStr)) {
                        throw new Exception("Invalid public key base64: " + publicKeyStr);
                    }
                    if (publicKey.length != Box.PUBLICKEYBYTES) {
                        throw new Exception("Invalid public key length: " + publicKey.length + ", expected=" + Box.PUBLICKEYBYTES);
                    }
                    if (privateKey.length != Box.SECRETKEYBYTES) {
                        throw new Exception("Invalid private key length: " + privateKey.length + ", expected=" + Box.SECRETKEYBYTES);
                    }

                    byte[] nonce = new byte[Box.NONCEBYTES];
                    sodium.randombytes_buf(nonce, nonce.length);
                    String nonceBase64 = Base64.encodeToString(nonce, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
                    String nonceHex = bytesToHex(nonce);
                    Log.d(TAG, "Nonce: length=" + nonce.length + ", base64=" + nonceBase64 + ", hex=" + nonceHex);

                    byte[] messageBytes = content.getBytes(StandardCharsets.UTF_8);
                    Log.d(TAG, "Message bytes: length=" + messageBytes.length + ", hex=" + bytesToHex(messageBytes));
                    byte[] encryptedContent = new byte[messageBytes.length + Box.MACBYTES];
                    int result = sodium.crypto_box_easy(encryptedContent, messageBytes, messageBytes.length, nonce, publicKey, privateKey);
                    if (result != 0) {
                        throw new Exception("Encryption failed, result=" + result);
                    }

                    String encryptedContentBase64 = Base64.encodeToString(encryptedContent, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
                    String encryptedContentHex = bytesToHex(encryptedContent);
                    Log.d(TAG, "Encrypted output: encryptedContentBase64=" + encryptedContentBase64 + ", encryptedContentHex=" + encryptedContentHex +
                            ", nonceBase64=" + nonceBase64 + ", nonceHex=" + nonceHex + ", encryptedBytesLength=" + encryptedContent.length);

                    if (!isValidBase64(encryptedContentBase64) || !isValidBase64(nonceBase64)) {
                        throw new Exception("Invalid base64 output: encryptedContentBase64=" + encryptedContentBase64 + ", nonceBase64=" + nonceBase64);
                    }

                    JSONObject resultJson = new JSONObject();
                    resultJson.put("encryptedContent", encryptedContentBase64);
                    resultJson.put("nonce", nonceBase64);
                    Log.d(TAG, "Encryption successful: resultJson=" + resultJson.toString());
                    future.complete(resultJson.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Error encrypting message: " + e.getMessage(), e);
                    future.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error starting encryption: " + e.getMessage());
            future.completeExceptionally(e);
        }
        return future;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private boolean isValidBase64(String str) {
        try {
            Base64.decode(str, Base64.URL_SAFE | Base64.NO_WRAP);
            return true;
        } catch (IllegalArgumentException e) {
            try {
                Base64.decode(str, Base64.DEFAULT);
                return true;
            } catch (IllegalArgumentException e2) {
                return false;
            }
        }
    }

    public String decryptMessage(String encryptedContent, String nonce, long senderId, Long publicKeyId) {
        try {
            byte[] privateKey = getPrivateKeyFromKeystore();
            if (privateKey == null) {
                throw new Exception("Private key not found");
            }

            String publicKeyStr = getCachedPublicKey(senderId);
            if (publicKeyStr == null && publicKeyId != null) {
                // Кэш пуст, пробуем получить ключ по publicKeyId
                Log.w(TAG, "Public key not found in cache for senderId=" + senderId + ", attempting to fetch by publicKeyId=" + publicKeyId);
                publicKeyStr = getCachedPublicKeyById(publicKeyId);
                if (publicKeyStr == null) {
                    CompletableFuture<String> keyFuture = fetchPublicKeyByIdAsync(publicKeyId);
                    publicKeyStr = keyFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (publicKeyStr == null) {
                        Log.e(TAG, "Failed to fetch public key for publicKeyId=" + publicKeyId);
                        // Пробуем текущий ключ отправителя как запасной вариант
                        CompletableFuture<String> fallbackFuture = fetchPublicKeyAsync(senderId);
                        publicKeyStr = fallbackFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                        if (publicKeyStr == null) {
                            throw new Exception("Failed to fetch public key for senderId=" + senderId);
                        }
                    }
                    // Кэшируем ключ по senderId и publicKeyId
                    cachePublicKey(senderId, publicKeyStr);
                    cachePublicKeyById(publicKeyId, publicKeyStr);
                    Log.d(TAG, "Fetched and cached public key for senderId=" + senderId + ", publicKeyId=" + publicKeyId + ": " + publicKeyStr);
                } else {
                    // Ключ найден в кэше по publicKeyId, кэшируем также по senderId
                    cachePublicKey(senderId, publicKeyStr);
                    Log.d(TAG, "Using cached public key for publicKeyId=" + publicKeyId + ": " + publicKeyStr);
                }
            } else if (publicKeyStr == null) {
                // Кэш пуст, и нет publicKeyId, используем текущую логику
                Log.w(TAG, "Public key not found in cache for senderId=" + senderId + ", attempting to fetch");
                CompletableFuture<String> keyFuture = fetchPublicKeyAsync(senderId);
                publicKeyStr = keyFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (publicKeyStr == null) {
                    throw new Exception("Failed to fetch public key for senderId=" + senderId);
                }
                cachePublicKey(senderId, publicKeyStr);
                Log.d(TAG, "Fetched and cached public key for senderId=" + senderId + ": " + publicKeyStr);
            }

            byte[] publicKey = decodeBase64(publicKeyStr);
            byte[] encryptedBytes = decodeBase64(encryptedContent);
            byte[] nonceBytes = decodeBase64(nonce);
            byte[] decryptedContent = new byte[encryptedBytes.length - Box.MACBYTES];
            int result = sodium.crypto_box_open_easy(decryptedContent, encryptedBytes, encryptedBytes.length, nonceBytes, publicKey, privateKey);
            if (result != 0) {
                throw new Exception("Decryption failed, result=" + result);
            }
            return new String(decryptedContent, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Error decrypting message for senderId=" + senderId + ", publicKeyId=" + publicKeyId + ": " + e.getMessage());
            return null;
        }
    }

    private CompletableFuture<JSONObject> encryptFile(byte[] fileBytes, long recipientId) {
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        Log.d(TAG, "Starting file encryption for recipientId=" + recipientId + ", fileSize=" + fileBytes.length);

        try {
            if (fileBytes == null || fileBytes.length == 0) {
                Log.e(TAG, "Invalid file bytes: null or empty");
                runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Invalid file data", Toast.LENGTH_SHORT).show());
                future.completeExceptionally(new Exception("Invalid file data"));
                return future;
            }

            byte[] privateKey = getPrivateKeyFromKeystore();
            if (privateKey == null || privateKey.length != Box.SECRETKEYBYTES) {
                Log.e(TAG, "Private key invalid for userId: " + currentUserId);
                runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Invalid private key", Toast.LENGTH_SHORT).show());
                future.completeExceptionally(new Exception("Invalid private key"));
                return future;
            }

            fetchPublicKeyAsync(recipientId).whenComplete((publicKeyStr, throwable) -> {
                if (throwable != null || publicKeyStr == null) {
                    Log.e(TAG, "Error fetching public key for userId: " + recipientId + ", error=" + (throwable != null ? throwable.getMessage() : "null key"));
                    runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Public key not found", Toast.LENGTH_SHORT).show());
                    future.completeExceptionally(throwable != null ? throwable : new Exception("Public key not found"));
                    return;
                }

                try {
                    byte[] publicKey = decodeBase64(publicKeyStr);
                    if (publicKey == null || publicKey.length != Box.PUBLICKEYBYTES) {
                        Log.e(TAG, "Invalid public key: " + (publicKey == null ? "null" : "length=" + publicKey.length));
                        runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Invalid public key", Toast.LENGTH_SHORT).show());
                        future.completeExceptionally(new Exception("Invalid public key"));
                        return;
                    }

                    byte[] nonce = new byte[Box.NONCEBYTES];
                    sodium.randombytes_buf(nonce, nonce.length);
                    String nonceBase64 = Base64.encodeToString(nonce, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");

                    byte[] encryptedFile = new byte[fileBytes.length + Box.MACBYTES];
                    int result = sodium.crypto_box_easy(encryptedFile, fileBytes, fileBytes.length, nonce, publicKey, privateKey);
                    if (result != 0) {
                        Log.e(TAG, "File encryption failed, result=" + result);
                        runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Failed to encrypt file", Toast.LENGTH_SHORT).show());
                        future.completeExceptionally(new Exception("File encryption failed, result=" + result));
                        return;
                    }

                    String encryptedFileBase64 = Base64.encodeToString(encryptedFile, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
                    JSONObject resultJson = new JSONObject();
                    resultJson.put("encryptedFile", encryptedFileBase64);
                    resultJson.put("nonce", nonceBase64);
                    resultJson.put("publicKey", publicKeyStr); // [CHANGE] Добавляем для унификации
                    Log.d(TAG, "File encryption successful: nonce=" + nonceBase64 + ", encryptedFileSize=" + encryptedFile.length);
                    future.complete(resultJson);
                } catch (Exception e) {
                    Log.e(TAG, "Error encrypting file: " + e.getMessage(), e);
                    runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Failed to encrypt file", Toast.LENGTH_SHORT).show());
                    future.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error starting file encryption: " + e.getMessage(), e);
            runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Failed to encrypt file", Toast.LENGTH_SHORT).show());
            future.completeExceptionally(e);
        }
        return future;
    }

    public byte[] decryptFile(byte[] encryptedFile, String nonce, long senderId, Long publicKeyId) {
        try {
            Log.d(TAG, "Starting file decryption: senderId=" + senderId + ", publicKeyId=" + publicKeyId + ", nonce=" + nonce + ", encryptedFileSize=" + (encryptedFile != null ? encryptedFile.length : 0));
            byte[] privateKey = getPrivateKeyFromKeystore();
            if (privateKey == null) {
                throw new Exception("Private key not found for userId=" + currentUserId);
            }
            Log.d(TAG, "Private key retrieved: length=" + privateKey.length); // сам ключ в лог не пишем

            String publicKeyStr;
            if (senderId == currentUserId) {
                // Для собственных файлов используем публичный ключ получателя (partnerId)
                if (partnerId == -1) {
                    throw new Exception("Partner ID not available for decrypting own file");
                }
                Log.d(TAG, "Decrypting own file, fetching public key for partnerId=" + partnerId);
                publicKeyStr = getCachedPublicKey(partnerId);
                if (publicKeyStr == null && publicKeyId != null) {
                    Log.d(TAG, "Public key not cached for partnerId=" + partnerId + ", fetching by publicKeyId=" + publicKeyId);
                    publicKeyStr = getCachedPublicKeyById(publicKeyId);
                    if (publicKeyStr == null) {
                        CompletableFuture<String> keyFuture = fetchPublicKeyByIdAsync(publicKeyId);
                        publicKeyStr = keyFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                        if (publicKeyStr == null) {
                            throw new Exception("Failed to fetch public key for publicKeyId=" + publicKeyId);
                        }
                        cachePublicKey(partnerId, publicKeyStr);
                        cachePublicKeyById(publicKeyId, publicKeyStr);
                    }
                } else if (publicKeyStr == null) {
                    CompletableFuture<String> keyFuture = fetchPublicKeyAsync(partnerId);
                    publicKeyStr = keyFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (publicKeyStr == null) {
                        throw new Exception("Failed to fetch public key for partnerId=" + partnerId);
                    }
                    cachePublicKey(partnerId, publicKeyStr);
                }
            } else {
                // Для чужих файлов используем senderId
                Log.d(TAG, "Decrypting received file, fetching public key for senderId=" + senderId);
                publicKeyStr = getCachedPublicKey(senderId);
                if (publicKeyStr == null && publicKeyId != null) {
                    CompletableFuture<String> keyFuture = fetchPublicKeyByIdAsync(publicKeyId);
                    publicKeyStr = keyFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (publicKeyStr == null) {
                        throw new Exception("Failed to fetch public key for publicKeyId=" + publicKeyId);
                    }
                    cachePublicKey(senderId, publicKeyStr);
                    cachePublicKeyById(publicKeyId, publicKeyStr);
                } else if (publicKeyStr == null) {
                    CompletableFuture<String> keyFuture = fetchPublicKeyAsync(senderId);
                    publicKeyStr = keyFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (publicKeyStr == null) {
                        throw new Exception("Public key not found for senderId=" + senderId);
                    }
                    cachePublicKey(senderId, publicKeyStr);
                }
            }

            byte[] publicKey = decodeBase64(publicKeyStr);
            if (publicKey == null || publicKey.length != Box.PUBLICKEYBYTES) {
                throw new Exception("Invalid public key: length=" + (publicKey == null ? 0 : publicKey.length) + ", expected=" + Box.PUBLICKEYBYTES);
            }
            Log.d(TAG, "Public key: length=" + publicKey.length + ", hex=" + bytesToHex(publicKey));

            if (encryptedFile == null || encryptedFile.length <= Box.MACBYTES) {
                throw new Exception("Invalid encrypted file: " + (encryptedFile == null ? "null" : "length=" + encryptedFile.length));
            }
            // Только первые 32 байта: bytesToHex всего файла на 2 МБ строил строку в 4 МБ
            // и занимал секунды — именно из-за этого большие файлы «долго расшифровывались».
            Log.d(TAG, "Encrypted file: length=" + encryptedFile.length + ", head="
                    + bytesToHex(java.util.Arrays.copyOf(encryptedFile, Math.min(32, encryptedFile.length))) + "...");

            byte[] nonceBytes = decodeBase64(nonce);
            if (nonceBytes == null || nonceBytes.length != Box.NONCEBYTES) {
                throw new Exception("Invalid nonce: length=" + (nonceBytes == null ? 0 : nonceBytes.length) + ", expected=" + Box.NONCEBYTES);
            }
            Log.d(TAG, "Nonce: length=" + nonceBytes.length + ", hex=" + bytesToHex(nonceBytes));

            byte[] decryptedFile = new byte[encryptedFile.length - Box.MACBYTES];
            int result = sodium.crypto_box_open_easy(decryptedFile, encryptedFile, encryptedFile.length, nonceBytes, publicKey, privateKey);
            if (result != 0) {
                throw new Exception("File decryption failed, result=" + result);
            }
            Log.d(TAG, "File decrypted successfully: length=" + decryptedFile.length);
            return decryptedFile;
        } catch (Exception e) {
            Log.e(TAG, "Error decrypting file for senderId=" + senderId + ", publicKeyId=" + publicKeyId + ": " + e.getMessage(), e);
            return null;
        }
    }

    private CompletableFuture<String> fetchPublicKeyAsync(long userId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Log.d(TAG, "Fetching public key for userId: " + userId);
        apiService.getPublicKey(userId).enqueue(new Callback<PublicKeyResponse>() {
            @Override
            public void onResponse(Call<PublicKeyResponse> call, Response<PublicKeyResponse> response) {
                Log.d(TAG, "Public key request completed for userId: " + userId + ", responseCode=" + response.code());
                if (response.isSuccessful() && response.body() != null && response.body().getPublicKey() != null) {
                    String publicKey = response.body().getPublicKey();
                    Log.d(TAG, "Successfully fetched public key for userId: " + userId + ", publicKeyBase64=" + publicKey);
                    future.complete(publicKey);
                } else {
                    String errorMsg = "Failed to fetch public key, code=" + (response.errorBody() != null ? response.code() : "unknown");
                    Log.e(TAG, errorMsg);
                    future.completeExceptionally(new Exception(errorMsg));
                }
            }

            @Override
            public void onFailure(Call<PublicKeyResponse> call, Throwable t) {
                Log.e(TAG, "Error fetching public key for userId: " + userId + ", error=" + t.getMessage());
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private void validateUserAndFetchData() {
        apiService.getUserByUsername(username).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    currentUserId = response.body().getId();
                    getSharedPreferences("prefs", MODE_PRIVATE)
                            .edit()
                            .putInt("userId", currentUserId)
                            .putString("username", username)
                            .apply();
                    messageAdapter = new SecretMessageAdapter(SecretPrivateChatActivity.this, messages, currentUserId, SecretPrivateChatActivity.this, apiService);
                    messageList.setAdapter(messageAdapter);
                    fetchChatData();
                } else {
                    Log.e(TAG, "Failed to validate user: code=" + response.code());
                    Toast.makeText(SecretPrivateChatActivity.this, "Failed to validate user", Toast.LENGTH_LONG).show();
                    finish();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Log.e(TAG, "Error validating user: " + t.getMessage());
                Toast.makeText(SecretPrivateChatActivity.this, "Error validating user: " + t.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(callReceiver,
                new IntentFilter(ACTION_SHOW_CALL_DIALOG));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(messageReceiver,
                new IntentFilter(ACTION_NEW_MESSAGE));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(endCallReceiver,
                new IntentFilter(ACTION_END_CALL));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(userStatusReceiver,
                new IntentFilter(USER_STATUS));
        registerTypingReceiver();
        if (partnerId != -1) {
            requestPartnerStatus();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(callReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(messageReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(endCallReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(userStatusReceiver);
        unregisterTypingReceiver();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Override
    protected void onDestroy() {
        if (socketReadyReceiver != null) {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(socketReadyReceiver);
            socketReadyReceiver = null;
        }
        super.onDestroy();
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error releasing MediaPlayer: " + e.getMessage());
        }
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        handler.removeCallbacksAndMessages(null);
        unregisterTypingReceiver();
    }

    private void fetchChatData() {
        apiService.getChatById(chatId).enqueue(new Callback<Chat>() {
            @Override
            public void onResponse(Call<Chat> call, Response<Chat> response) {
                if (response.isSuccessful() && response.body() != null) {
                    if (!response.body().getIsSecret()) {
                        Log.e(TAG, "Chat is not secret: chatId=" + chatId);
                        Toast.makeText(SecretPrivateChatActivity.this, "This is not a secret chat", Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }
                    chatName = response.body().getName();
                    String partnerName = getChatPartnerName(chatName);
                    partnerUsername = partnerName;
                    chatNameTextView.setText(partnerName);
                    apiService.getUserByUsername(partnerName).enqueue(new Callback<User>() {
                        @Override
                        public void onResponse(Call<User> call, Response<User> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                partnerId = response.body().getId();

                                // НАЧАЛО ВСТАВКИ — Аватар и клик по нему в тулбаре (как в обычном чате)
                                String avatarUrl = response.body().getAvatar();
                                ImageView partnerAvatar = findViewById(R.id.partner_avatar);
                                if (partnerAvatar != null) {
                                    if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
                                        Glide.with(SecretPrivateChatActivity.this)
                                                .load("https://unlimitedpossibilities12.org" + avatarUrl)
                                                .apply(new RequestOptions()
                                                        .circleCrop()
                                                        .placeholder(R.drawable.ic_default_avatar)
                                                        .error(R.drawable.ic_default_avatar))
                                                .into(partnerAvatar);
                                    } else {
                                        // Если нет аватара — первая буква имени (как в сообщениях)
                                        String initial = TextUtils.isEmpty(partnerName) ? "?" : partnerName.substring(0, 1).toUpperCase();
                                        LetterAvatarDrawable avatarDrawable = new LetterAvatarDrawable(initial);
                                        partnerAvatar.setImageDrawable(avatarDrawable);
                                    }

                                    // Клик по аватару → открываем профиль собеседника
                                    partnerAvatar.setOnClickListener(v -> {
                                        if (partnerId != -1) {
                                            Intent profileIntent = new Intent(SecretPrivateChatActivity.this, UserProfileActivity.class);
                                            profileIntent.putExtra("userId", (int) partnerId);
                                            startActivity(profileIntent);
                                            Log.d(TAG, "Открыт профиль собеседника по клику на аватар в тулбаре: userId=" + partnerId);
                                        } else {
                                            Log.w(TAG, "partnerId = -1, клик по аватару в тулбаре отключён");
                                        }
                                    });
                                } else {
                                    Log.e(TAG, "View с id=partner_avatar не найден в layout");
                                }
                                // КОНЕЦ ВСТАВКИ — Аватар и клик по нему в тулбаре

                                updatePartnerStatusIndicator();  // ← Индикатор онлайн-статуса в тулбаре

                                subscribeToPartnerPublicKey(); // [ADD] Подписка на ключ партнёра
                                fetchPublicKey(partnerId, new Callback<String>() {
                                    @Override
                                    public void onResponse(Call<String> call, Response<String> response) {
                                        if (response.isSuccessful() && response.body() != null) {
                                            cachePublicKey(partnerId, response.body());
                                            Log.d(TAG, "Cached public key for partnerId: " + partnerId + ", key: " + response.body());
                                        } else {
                                            Log.e(TAG, "Failed to fetch partner public key: code=" + response.code());
                                        }
                                        fetchMessages();
                                    }

                                    @Override
                                    public void onFailure(Call<String> call, Throwable t) {
                                        Log.e(TAG, "Error fetching partner public key: " + t.getMessage());
                                        String cachedKey = getCachedPublicKey(partnerId);
                                        if (cachedKey != null) {
                                            Log.d(TAG, "Using cached public key for partnerId: " + partnerId);
                                        } else {
                                            Log.w(TAG, "No cached public key for partnerId: " + partnerId);
                                        }
                                        fetchMessages();
                                    }
                                });
                                requestPartnerStatus();
                            } else {
                                Log.e(TAG, "Failed to fetch partner: code=" + response.code());
                                Toast.makeText(SecretPrivateChatActivity.this, "Failed to fetch partner data", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<User> call, Throwable t) {
                            Log.e(TAG, "Error fetching partner: " + t.getMessage());
                            Toast.makeText(SecretPrivateChatActivity.this, "Error fetching partner: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Log.e(TAG, "Failed to fetch chat: code=" + response.code());
                    Toast.makeText(SecretPrivateChatActivity.this, "Failed to fetch chat data", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<Chat> call, Throwable t) {
                Log.e(TAG, "Error fetching chat: " + t.getMessage());
                Toast.makeText(SecretPrivateChatActivity.this, "Error fetching chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void fetchMessages() {
        apiService.getMessagesByChatId(chatId).enqueue(new Callback<List<Message>>() {
            @Override
            public void onResponse(Call<List<Message>> call, Response<List<Message>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    messages.clear();
                    for (Message msg : response.body()) {
                        if (msg.getChat() == null) {
                            msg.setChat(new Chat(chatId, chatName));
                        }
                        if (msg.getSender().getId() == currentUserId) {
                            // Для собственных сообщений
                            String localContent = getLocalMessageContent(msg.getId(), msg.getNonce(), true);
                            if (localContent != null) {
                                msg.setContent(localContent);
                                Log.d(TAG, "Restored local content for own message id=" + msg.getId() + ": " + localContent);
                            } else {
                                Log.w(TAG, "No local content found for own message id=" + msg.getId());
                                // Свой текст хранится только здесь, на устройстве: если его нет,
                                // пузырь выходил пустым — с одними галочками и временем
                                msg.setContent(OWN_TEXT_GONE_NOTICE);
                            }
                            if ("file".equals(msg.getMessageType()) || "audio".equals(msg.getMessageType())) {
                                JSONObject localMetadata = getValidLocalFileMetadata(msg);
                                if (localMetadata != null) {
                                    Log.d(TAG, "Restored local file metadata for own message id=" + msg.getId() + ": " + localMetadata.toString());
                                }
                            }
                        } else if ("text".equals(msg.getMessageType()) && msg.getEncryptedContent() != null) {
                            // Для чужих текстовых сообщений
                            String localContent = getLocalMessageContent(msg.getId(), msg.getNonce(), false);
                            if (localContent != null) {
                                msg.setContent(localContent);
                                Log.d(TAG, "Restored local content for received message id=" + msg.getId() + ": " + localContent);
                            } else {
                                String decryptedContent = decryptMessage(
                                        msg.getEncryptedContent(),
                                        msg.getNonce(),
                                        msg.getSender().getId(),
                                        msg.getPublicKeyId()
                                );
                                if (decryptedContent != null) {
                                    msg.setContent(decryptedContent);
                                    saveLocalMessageContent(msg.getId(), decryptedContent, msg.getNonce());
                                    Log.d(TAG, "Decrypted and saved content for message id=" + msg.getId() + ": " + decryptedContent);
                                } else {
                                    // Ключи живут на устройстве: после переустановки или входа
                                    // с другого устройства прежняя пара теряется, и старые
                                    // сообщения не расшифровать ничем. Говорим об этом прямо,
                                    // а не «Unable to decrypt message».
                                    msg.setContent(KEY_GONE_NOTICE);
                                    Log.w(TAG, "Failed to decrypt message id=" + msg.getId());
                                }
                            }
                        } else if ("file".equals(msg.getMessageType()) && msg.getEncryptedContent() != null) {
                            // Для чужих файловых сообщений
                            String localContent = getLocalMessageContent(msg.getId(), msg.getNonce(), false);
                            if (localContent != null) {
                                msg.setContent(localContent);
                                Log.d(TAG, "Restored local content for received file message id=" + msg.getId() + ": " + localContent);
                            }
                            JSONObject localMetadata = getValidLocalFileMetadata(msg);
                            if (localMetadata == null || localContent == null) {
                                // Имя, MIME и подпись — из зашифрованных метаданных
                                applyDecryptedFileMetadata(msg);
                            }
                        }
                        Log.d(TAG, "Processing message from API: id=" + msg.getId() + ", contentType=" + msg.getContentType() + ", messageType=" + msg.getMessageType());
                        messages.add(msg);
                    }
                    messageAdapter.updateMessages(messages);
                    scrollToBottom();
                    markAllMessagesAsRead();
                    scheduleStatusCheck();
                } else {
                    Log.e(TAG, "Failed to fetch messages: code=" + response.code());
                    Toast.makeText(SecretPrivateChatActivity.this, "Failed to fetch messages", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<List<Message>> call, Throwable t) {
                Log.e(TAG, "Error fetching messages: " + t.getMessage());
                Toast.makeText(SecretPrivateChatActivity.this, "Error fetching messages: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Защищённый звонок собеседнику.
     *
     * Стороны подписывают параметры связи ключами Ed25519 и сверяют подписи,
     * поэтому сервер не может подменить сторону незаметно. Звонок с переводом
     * сюда не ставим: перевод идёт через сервер, а это противоречит замыслу
     * секретного чата.
     */
    private void startSecureCall() {
        if (currentUserId == -1 || partnerId == -1 || partnerUsername == null) {
            Toast.makeText(this, "Cannot find who to call", Toast.LENGTH_SHORT).show();
            return;
        }
        String roomId = "secure-" + currentUserId + "-" + partnerId + "-" + System.currentTimeMillis();
        String myName = getSharedPreferences("prefs", MODE_PRIVATE).getString("username", "Unknown");
        CallRequest request = new CallRequest(currentUserId, (int) partnerId, roomId, myName, "voice_secure");
        apiService.initiateCall(request).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (!response.isSuccessful()) {
                    Toast.makeText(SecretPrivateChatActivity.this, "Failed to start the protected call",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(SecretPrivateChatActivity.this, SecureVoiceCallActivity.class);
                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                intent.putExtra("recipientId", String.valueOf(partnerId));
                intent.putExtra("roomId", roomId);
                intent.putExtra("callerName", myName);
                intent.putExtra("recipientName", partnerUsername);
                intent.putExtra("isCaller", true);
                startActivity(intent);
                Toast.makeText(SecretPrivateChatActivity.this,
                        "Calling " + partnerUsername + " — protected call", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Secure call failed: " + t.getMessage());
                Toast.makeText(SecretPrivateChatActivity.this, "Failed to start the protected call",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String getChatPartnerName(String chatName) {
        String[] parts = chatName.split("_");
        if (parts.length == 3 && parts[2].equals("secret")) {
            return parts[0].equals(username) ? parts[1] : parts[0];
        } else if (parts.length == 2) {
            return parts[0].equals(username) ? parts[1] : parts[0];
        }
        return "Unknown";
    }

    private void initStompClient() {
        if (!isBound || callService == null) {
            Log.e(TAG, "CallService not bound, cannot initialize StompClient");
            Toast.makeText(this, "Service not available", Toast.LENGTH_LONG).show();
            return;
        }
        stompClient = callService.getStompClient();
        if (stompClient == null || !stompClient.isConnected()) {
            // Соединение ещё поднимается (приложение разбудил push) — экран
            // переинициализируется сам по ACTION_SOCKET_READY, пугать не нужно
            Log.d(TAG, "StompClient ещё не готов — ждём ACTION_SOCKET_READY");
        }
    }

    /** Есть ли сообщение с таким серверным id уже в списке (не временное). */
    private boolean isKnownMessage(long messageId) {
        for (Message msg : messages) {
            if (msg.getId() == messageId && !msg.isTemporary()) {
                return true;
            }
        }
        return false;
    }

    private void updateMessages(Message newMessage) {
        Log.d(TAG, "Updating message: id=" + (newMessage != null ? newMessage.getId() : "null") +
                ", contentType=" + (newMessage != null ? newMessage.getContentType() : "null"));

        runOnUiThread(() -> {
            if (newMessage == null || newMessage.getSender() == null) {
                Log.w(TAG, "Received null message or sender");
                return;
            }

            if (newMessage.getChat() == null) {
                newMessage.setChat(new Chat(chatId, chatName));
            }

            // Удаление
            if ("Message deleted".equals(newMessage.getContent())) {
                messages.removeIf(msg -> msg.getId() == newMessage.getId());
                clearLocalDataForMessage(newMessage.getId());
                messageAdapter.updateMessages(new ArrayList<>(messages)); // Полный апдейт
                return;
            }

            boolean replacedTemp = false;
            // Позиция элемента, который изменили НА МЕСТЕ (тот же объект). DiffUtil
            // такое изменение не видит — старый и новый список держат один объект, —
            // поэтому после обновления списка перерисовываем эту позицию явно.
            int changedInPlaceIndex = -1;

            // === ФИКС: замена временного своего сообщения на серверное ===
            if (newMessage.getSender().getId() == currentUserId && newMessage.getEncryptedContent() != null) {
                for (int i = 0; i < messages.size(); i++) {
                    Message pending = messages.get(i);

                    if (pending.isTemporary()
                            && pending.getEncryptedContent() != null
                            && pending.getEncryptedContent().equals(newMessage.getEncryptedContent())) {

                        long oldTempId = pending.getId();

                        // Переносим контент
                        String localContent = getLocalMessageContent(oldTempId);
                        if (localContent != null) {
                            saveLocalMessageContent(newMessage.getId(), localContent, newMessage.getNonce());
                            localMessageContent.remove(oldTempId);
                        }

                        // Переносим файлы (если были)
                        if ("file".equals(pending.getMessageType()) || "audio".equals(pending.getMessageType())) {
                            JSONObject meta = getLocalFileMetadata(oldTempId);
                            if (meta != null) {
                                saveLocalFileMetadata(newMessage.getId(),
                                        meta.optString("fileName"),
                                        meta.optString("mimeType"),
                                        newMessage.getNonce());
                            }
                            transferLocalFile(oldTempId, newMessage.getId());
                        }

                        // Заменяем объект
                        pending.setId(newMessage.getId());
                        pending.setTemporary(false);
                        pending.setDelivered(newMessage.isDelivered());
                        pending.setRead(newMessage.isRead());
                        pending.setTimestamp(newMessage.getTimestamp());
                        pending.setFileUrl(newMessage.getFileUrl());
                        pending.setContent(localContent != null ? localContent : pending.getContent());

                        replacedTemp = true;
                        changedInPlaceIndex = i;
                        Log.d(TAG, "Replaced temp message oldId=" + oldTempId + " → serverId=" + newMessage.getId());
                        break;
                    }
                }
            }

            // === Если не было замены — обычная логика ===
            if (!replacedTemp) {
                int existingIndex = -1;
                for (int i = 0; i < messages.size(); i++) {
                    if (messages.get(i).getId() == newMessage.getId()) {
                        existingIndex = i;
                        break;
                    }
                }

                if (existingIndex != -1) {
                    // Повторная доставка уже известного сообщения (после readMessage):
                    // объект НЕ подменяем — иначе теряется расшифрованный текст,
                    // состояние скачанного файла и т.п. Переносим только статусы.
                    Message existing = messages.get(existingIndex);
                    existing.setDelivered(newMessage.isDelivered());
                    existing.setRead(newMessage.isRead());
                    if (newMessage.getTimestamp() != null) {
                        existing.setTimestamp(newMessage.getTimestamp());
                    }
                    if (existing.getFileUrl() == null && newMessage.getFileUrl() != null) {
                        existing.setFileUrl(newMessage.getFileUrl());
                    }
                    Log.d(TAG, "Merged status into existing message id=" + existing.getId()
                            + ": read=" + existing.isRead() + ", delivered=" + existing.isDelivered());
                    changedInPlaceIndex = existingIndex;
                } else {
                    // Восстановление контента для своих и расшифровка для чужих
                    restoreOrDecryptContent(newMessage);
                    messages.add(newMessage);
                }
            }

            // ВАЖНО: всегда обновляем через DiffUtil — это гарантирует отображение
            messageAdapter.updateMessages(new ArrayList<>(messages));
            if (changedInPlaceIndex >= 0 && changedInPlaceIndex < messages.size()) {
                // Объект изменён на месте — DiffUtil этого не заметит, перерисовываем явно
                messageAdapter.notifyItemChanged(changedInPlaceIndex);
            }

            // Скролл и отметка о прочтении
            if (isMessageJustSent || (newMessage.getSender().getId() != currentUserId)) {
                scrollToBottom();
            }
            if (newMessage.getSender().getId() != currentUserId && !newMessage.isRead()) {
                markMessageAsRead(newMessage);
            }

            isMessageJustSent = false;
            Log.d(TAG, "Messages updated, count=" + messages.size() + ", replacedTemp=" + replacedTemp);
        });
    }

    private void clearLocalDataForMessage(long messageId) {
        localMessageContent.remove(messageId);
        getSharedPreferences("local_message_content", MODE_PRIVATE)
                .edit().remove("content_" + messageId).apply();
        getSharedPreferences("local_file_metadata", MODE_PRIVATE)
                .edit().remove("metadata_" + messageId).apply();

        File file = getLocalFile(messageId, "unknown");
        if (file != null && file.exists()) file.delete();
    }

    private void transferLocalFile(long oldId, long newId) {
        File oldFile = getLocalFile(oldId, "unknown");
        if (oldFile == null || !oldFile.exists()) return;

        try {
            byte[] bytes = new byte[(int) oldFile.length()];
            FileInputStream fis = new FileInputStream(oldFile);
            fis.read(bytes);
            fis.close();

            JSONObject meta = getLocalFileMetadata(oldId);
            String fileName = meta != null ? meta.optString("fileName", "unknown") : "unknown";
            saveLocalFileContent(newId, bytes, fileName);
            oldFile.delete();
        } catch (Exception e) {
            Log.e(TAG, "Failed to transfer file from tempId=" + oldId, e);
        }
    }

    /**
     * Расшифровывает метаданные чужого файлового/аудио сообщения (имя, MIME,
     * подпись) и сохраняет их локально. Подпись кладётся в кэш текста и в
     * content сообщения — адаптер показывает её под картинкой.
     * Возвращает true, если метаданные удалось разобрать.
     */
    public boolean applyDecryptedFileMetadata(Message msg) {
        if (msg == null || msg.getSender() == null || msg.getSender().getId() == currentUserId) return false;
        if (msg.getEncryptedContent() == null || msg.getNonce() == null) return false;
        String decryptedMetadata = decryptMessage(
                msg.getEncryptedContent(), msg.getNonce(), msg.getSender().getId(), msg.getPublicKeyId());
        if (decryptedMetadata == null) {
            Log.w(TAG, "Failed to decrypt file metadata for message id=" + msg.getId());
            return false;
        }
        try {
            JSONObject metadata = new JSONObject(decryptedMetadata);
            String fileName = metadata.optString("fileName", "Unknown file");
            String mimeType = metadata.optString("mimeType", metadata.optString("contentType", "application/octet-stream"));
            saveLocalFileMetadata(msg.getId(), fileName, mimeType, msg.getNonce());
            String caption = metadata.optString("caption", "");
            if (!caption.isEmpty()) {
                saveLocalMessageContent(msg.getId(), caption, msg.getNonce());
                msg.setContent(caption);
            }
            Log.d(TAG, "Decrypted and saved file metadata for message id=" + msg.getId()
                    + ": fileName=" + fileName + ", mimeType=" + mimeType + ", caption=" + (caption.isEmpty() ? "-" : "yes"));
            return true;
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing decrypted metadata for message id=" + msg.getId() + ": " + e.getMessage());
            return false;
        }
    }

    // ===== Перевод на устройстве (секретный чат) =====

    private void updateTranslateIconTint() {
        ImageView icon = findViewById(R.id.translate_icon);
        if (icon == null) return;
        boolean on = LocalTranslator.isEnabled(this, chatId) && LocalTranslator.targetLanguage(this, chatId) != null;
        icon.setColorFilter(Color.parseColor(on ? "#67E8F9" : "#94A3B8"));
    }

    /**
     * Исходящий текст: если перевод включён и пакеты на месте — переводим на
     * устройстве и заворачиваем в {"text","translated","lang"}; иначе отдаём
     * текст как есть. Никогда не блокирует отправку: любая ошибка -> оригинал.
     */
    private CompletableFuture<String> prepareOutgoingText(String text) {
        CompletableFuture<String> f = new CompletableFuture<>();
        String tgt = LocalTranslator.targetLanguage(this, chatId);
        if (!LocalTranslator.isEnabled(this, chatId) || tgt == null) {
            f.complete(text);
            return f;
        }
        LocalTranslator.translateToIfNeeded(text, tgt).addOnCompleteListener(t -> {
            if (t.isSuccessful() && t.getResult() != null && !t.getResult().isEmpty()) {
                f.complete(SecretText.wrap(text, t.getResult(), tgt));
            } else {
                if (!t.isSuccessful()) Log.w(TAG, "Local translation failed: " + (t.getException() != null ? t.getException().getMessage() : "?"));
                f.complete(text); // текст уже на целевом языке или перевод недоступен
            }
        });
        return f;
    }

    /**
     * Вызывается адаптером при отрисовке текстового сообщения.
     * Правило: готовый перевод из сообщения — если его язык совпадает с тем,
     * что нужен получателю; иначе переводим сами (если пакеты есть); иначе
     * ничего. Для своих сообщений показываем свой же перевод из обёртки.
     */
    public void bindLocalTranslation(TextView view, Message message, SecretText st, boolean own) {
        if (view == null) return;
        view.setVisibility(View.GONE);
        if (st == null || st.text.isEmpty()) return;
        if (!LocalTranslator.isEnabled(this, chatId)) return;
        String tgt = LocalTranslator.targetLanguage(this, chatId);
        if (tgt == null) return;

        // 1) Готовый перевод из сообщения на нужный язык — показываем сразу
        if (st.translated != null && tgt.equals(st.lang)) {
            show(view, st.translated);
            return;
        }
        // 2) Иначе переводим сами (в том числе историю), результат кэшируем
        String cacheKey = "lt_" + message.getId() + "_" + tgt;
        String cached = localTranslationCache.get(cacheKey);
        if (cached != null) {
            if (!cached.isEmpty()) show(view, cached);
            return;
        }
        final long msgId = message.getId();
        view.setTag(R.id.translated_content_text_view, msgId);
        LocalTranslator.translateToIfNeeded(st.text, tgt).addOnCompleteListener(t -> {
            String result = (t.isSuccessful() && t.getResult() != null) ? t.getResult() : "";
            localTranslationCache.put(cacheKey, result); // "" = уже на целевом языке / нечего переводить
            runOnUiThread(() -> {
                if (currentBoundId(view) != msgId) return;
                if (!result.isEmpty()) show(view, result); else view.setVisibility(View.GONE);
            });
        });
    }

    private final Map<String, String> localTranslationCache = new HashMap<>();

    private long currentBoundId(TextView view) {
        Object tag = view.getTag(R.id.translated_content_text_view);
        return tag instanceof Long ? (Long) tag : -1L;
    }

    private void show(TextView view, String translated) {
        view.setText(translated + "  ·  translated on device");
        view.setVisibility(View.VISIBLE);
    }

    private void restoreOrDecryptContent(Message msg) {
        if (msg.getSender().getId() == currentUserId) {
            String local = getLocalMessageContent(msg.getId());
            if (local != null) msg.setContent(local);
        } else if ("text".equals(msg.getMessageType()) && msg.getEncryptedContent() != null) {
            String local = getLocalMessageContent(msg.getId());
            if (local == null) {
                String decrypted = decryptMessage(msg.getEncryptedContent(), msg.getNonce(),
                        msg.getSender().getId(), msg.getPublicKeyId());
                if (decrypted != null) {
                    msg.setContent(decrypted);
                    saveLocalMessageContent(msg.getId(), decrypted);
                }
            } else {
                msg.setContent(local);
            }
        }
    }

    private void updateInputIcons() {
        Drawable attachmentDrawable = ContextCompat.getDrawable(this, R.drawable.ic_attachment);
        Drawable micDrawable = ContextCompat.getDrawable(this, R.drawable.ic_mic);
        Drawable sendDrawable = ContextCompat.getDrawable(this, R.drawable.ic_send);

        attachmentDrawable = attachmentDrawable.mutate();
        attachmentDrawable.setTint(Color.parseColor("#94A3B8"));

        if (micDrawable != null) {
            micDrawable.setTint(Color.parseColor("#757575"));
        }
        if (sendDrawable != null) {
            sendDrawable.setTint(Color.parseColor("#0288D1"));
        }

        if (isRecording) {
            Drawable recordingDrawable = ContextCompat.getDrawable(this, R.drawable.ic_recording);
            if (recordingDrawable != null) {
                recordingDrawable.setColorFilter(Color.parseColor("#EF4444"), android.graphics.PorterDuff.Mode.SRC_IN);
            }
            inputMessage.setCompoundDrawablesWithIntrinsicBounds(null, null, recordingDrawable, null);
        } else if (inputMessage.getText().length() > 0 || selectedFileUri != null) {
            inputMessage.setCompoundDrawablesWithIntrinsicBounds(attachmentDrawable, null, sendDrawable, null);
        } else {
            inputMessage.setCompoundDrawablesWithIntrinsicBounds(attachmentDrawable, null, micDrawable, null);
        }
    }

    private void markMessageAsRead(Message message) {
        if (stompClient != null && stompClient.isConnected()) {
            stompClient.send("/app/readMessage/" + chatId, String.valueOf(message.getId()))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            () -> {
                                message.setRead(true);
                                messageAdapter.updateMessages(messages);
                            },
                            throwable -> Log.e(TAG, "Error marking message as read: " + throwable.getMessage())
                    );
        }
    }

    private void scheduleStatusCheck() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastStatusCheckTime >= STATUS_CHECK_DEBOUNCE_MS) {
            lastStatusCheckTime = currentTime;
            checkMessagesStatus();
        } else {
            handler.postDelayed(this::checkMessagesStatus, STATUS_CHECK_DEBOUNCE_MS - (currentTime - lastStatusCheckTime));
        }
    }

    private void checkMessagesStatus() {
        for (Message msg : messages) {
            if (msg.getSender().getId() == currentUserId && (!msg.isDelivered() || !msg.isRead())) {
                apiService.getMessageById(msg.getId()).enqueue(new Callback<Message>() {
                    @Override
                    public void onResponse(Call<Message> call, Response<Message> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            Message updatedFromServer = response.body();
                            // <<< ФИКС: НЕ создаём new obj, обновляем только флаги на existing msg, content preserve >>>
                            int index = -1;
                            for (int i = 0; i < messages.size(); i++) {
                                if (messages.get(i).getId() == msg.getId()) {
                                    index = i;
                                    break;
                                }
                            }
                            if (index != -1) {
                                Message existingMsg = messages.get(index);
                                existingMsg.setDelivered(updatedFromServer.isDelivered());
                                existingMsg.setRead(updatedFromServer.isRead());
                                // Restore content if needed (but since existing, already has local)
                                if ("text".equalsIgnoreCase(existingMsg.getMessageType()) && existingMsg.getContent().isEmpty()) {
                                    String localContent = getLocalMessageContent(existingMsg.getId());
                                    if (localContent != null) {
                                        existingMsg.setContent(localContent);
                                        saveLocalMessageContent(existingMsg.getId(), localContent);
                                        Log.d(TAG, "Restored content in polling for id=" + existingMsg.getId());
                                    }
                                }
                                messageAdapter.notifyItemChanged(index);  // Только этот item, no full rebind
                                Log.d(TAG, "Updated status only for message id=" + msg.getId() + " (content preserved)");
                            }
                        }
                    }
                    @Override
                    public void onFailure(Call<Message> call, Throwable t) {
                        Log.e(TAG, "Error fetching status for message " + msg.getId() + ": " + t.getMessage());
                    }
                });
            }
        }
    }

    private void checkMessageStatus(Message message, int retryCount, long tempId, Callback<Message> callback) {
        if (retryCount >= 3) {
            runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Unable to verify message delivery", Toast.LENGTH_SHORT).show());
            if (callback != null) {
                callback.onFailure(null, new Exception("Max retries reached"));
            }
            return;
        }
        apiService.getMessagesByChatId(chatId).enqueue(new Callback<List<Message>>() {
            @Override
            public void onResponse(Call<List<Message>> call, Response<List<Message>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String localContent = getLocalMessageContent(tempId);
                    Log.d(TAG, "Checking message status for tempId=" + tempId + ", localContent=" + localContent);
                    for (Message serverMessage : response.body()) {
                        if (serverMessage.getSender().getId() == currentUserId &&
                                serverMessage.getEncryptedContent() != null &&
                                serverMessage.getEncryptedContent().equals(message.getEncryptedContent())) {
                            Log.d(TAG, "Found matching server message: serverId=" + serverMessage.getId() + ", tempId=" + tempId);
                            if (localContent != null) {
                                saveLocalMessageContent(serverMessage.getId(), localContent);
                                localMessageContent.remove(tempId);
                                Log.d(TAG, "Transferred local content from tempId=" + tempId + " to serverId=" + serverMessage.getId());
                                serverMessage.setContent(localContent);
                            }
                            updateMessages(serverMessage);
                            if (callback != null) {
                                callback.onResponse(null, Response.success(serverMessage));
                            }
                            // Отправка уведомления
                            if (!serverMessage.isRead()) {
                                String[] parts = chatName.split("_");
                                String recipientName = parts.length >= 2 ? (parts[0].equals(username) ? parts[1] : parts[0]) : null;
                                if (recipientName != null) {
                                    apiService.getUserByUsername(recipientName).enqueue(new Callback<User>() {
                                        @Override
                                        public void onResponse(Call<User> call, Response<User> response) {
                                            if (response.isSuccessful() && response.body() != null) {
                                                int recipientId = response.body().getId();
                                                MessageNotification notification = new MessageNotification(
                                                        currentUserId, recipientId, "New secret message", chatId);
                                                apiService.notifyMessage(notification).enqueue(new Callback<Void>() {
                                                    @Override
                                                    public void onResponse(Call<Void> call, Response<Void> response) {
                                                        if (response.isSuccessful()) {
                                                            Log.d(TAG, "Notification sent for message id=" + serverMessage.getId());
                                                        }
                                                    }

                                                    @Override
                                                    public void onFailure(Call<Void> call, Throwable t) {
                                                        Log.e(TAG, "Error sending notification: " + t.getMessage());
                                                    }
                                                });
                                            }
                                        }

                                        @Override
                                        public void onFailure(Call<User> call, Throwable t) {
                                            Log.e(TAG, "Error fetching recipient: " + t.getMessage());
                                        }
                                    });
                                }
                            }
                            return;
                        }
                    }
                    Log.w(TAG, "No matching server message found for tempId=" + tempId + ", retrying...");
                    handler.postDelayed(() -> checkMessageStatus(message, retryCount + 1, tempId, callback), 3000);
                } else {
                    Log.e(TAG, "Failed to fetch messages: code=" + response.code());
                    handler.postDelayed(() -> checkMessageStatus(message, retryCount + 1, tempId, callback), 3000);
                }
            }

            @Override
            public void onFailure(Call<List<Message>> call, Throwable t) {
                Log.e(TAG, "Error fetching messages: " + t.getMessage());
                handler.postDelayed(() -> checkMessageStatus(message, retryCount + 1, tempId, callback), 3000);
            }
        });
    }

    // Перегруженная версия без callback для обратной совместимости
    private void checkMessageStatus(Message message, int retryCount, long tempId) {
        checkMessageStatus(message, retryCount, tempId, null);
    }

    private void sendNotification(Message message) {
        if (message.getSender().getId() != currentUserId) {
            String[] parts = chatName.split("_");
            String recipientName;
            if (parts.length == 3 && parts[2].equals("secret")) {
                recipientName = parts[0].equals(username) ? parts[1] : parts[0];
            } else if (parts.length == 2) {
                recipientName = parts[0].equals(username) ? parts[1] : parts[0];
            } else {
                Log.e(TAG, "Invalid chatName format: " + chatName);
                return;
            }
            apiService.getUserByUsername(recipientName).enqueue(new Callback<User>() {
                @Override
                public void onResponse(Call<User> call, Response<User> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        int recipientId = response.body().getId();
                        MessageNotification notification = new MessageNotification(
                                currentUserId, recipientId, "New secret message", chatId);
                        apiService.notifyMessage(notification).enqueue(new Callback<Void>() {
                            @Override
                            public void onResponse(Call<Void> call, Response<Void> response) {
                                if (response.isSuccessful()) {
                                    Log.d(TAG, "Notification sent successfully");
                                }
                            }

                            @Override
                            public void onFailure(Call<Void> call, Throwable t) {
                                Log.e(TAG, "Error sending notification: " + t.getMessage());
                            }
                        });
                    }
                }

                @Override
                public void onFailure(Call<User> call, Throwable t) {
                    Log.e(TAG, "Error fetching recipient by username: " + t.getMessage());
                }
            });
        }
    }

    private void scrollToBottom() {
        if (messages.size() > 0) {
            messageList.smoothScrollToPosition(messages.size() - 1);
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            selectedFileUri = data.getData();
            if (selectedFileUri != null) {
                String fileName = null;
                try {
                    Cursor cursor = getContentResolver().query(selectedFileUri, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIndex != -1) {
                            fileName = cursor.getString(nameIndex);
                        }
                        cursor.close();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not get file name: " + e.getMessage());
                }
                if (fileName == null) {
                    fileName = "selected_file";
                }
                SpannableString spannable = new SpannableString(fileName + " ✕");
                spannable.setSpan(new ForegroundColorSpan(Color.RED), fileName.length() + 1, spannable.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                attachedFileTextView.setText(spannable);
                attachedFileTextView.setVisibility(View.VISIBLE);
                updateInputIcons();
                Toast.makeText(this, "File selected: " + fileName, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void sendMessage() {
        final String content = inputMessage.getText().toString().trim();
        if (content.isEmpty() && selectedFileUri == null && audioFile == null) {
            Toast.makeText(this, "Content is missing", Toast.LENGTH_SHORT).show();
            return;
        }

        final String messageType;
        if (selectedFileUri != null) {
            String mimeType = getContentResolver().getType(selectedFileUri);
            messageType = (mimeType != null && mimeType.startsWith("audio/")) ? "audio" : "file";
        } else {
            messageType = "text";
        }

        apiService.checkChatExists(currentUserId, chatId).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String result = response.body().string();
                        if (result.equals("Chat exists in user's private chats")) {
                            proceedWithSendingMessage(content, messageType);
                        } else {
                            Log.w(TAG, "Chat does not exist for user: " + currentUserId + ", chatId: " + chatId);
                            Toast.makeText(SecretPrivateChatActivity.this, "Chat not found", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Log.e(TAG, "Failed to check chat: code=" + response.code());
                        Toast.makeText(SecretPrivateChatActivity.this, "Error checking chat", Toast.LENGTH_SHORT).show();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading response: " + e.getMessage());
                    Toast.makeText(SecretPrivateChatActivity.this, "Error reading response: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Log.e(TAG, "Error checking chat: " + t.getMessage());
                Toast.makeText(SecretPrivateChatActivity.this, "Error checking chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void proceedWithSendingMessage(String content, String messageType) {
        if (selectedFileUri != null) {
            try {
                final String finalFileName;
                final String finalMimeType;
                String tempFileName = null;
                String tempMimeType = null;
                try {
                    Cursor cursor = getContentResolver().query(selectedFileUri, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIndex != -1) {
                            tempFileName = cursor.getString(nameIndex);
                        }
                        cursor.close();
                    }
                    tempMimeType = getContentResolver().getType(selectedFileUri);
                    if (tempMimeType == null) {
                        if (tempFileName != null) {
                            String extension = tempFileName.substring(tempFileName.lastIndexOf(".") + 1).toLowerCase();
                            switch (extension) {
                                case "jpg":
                                case "jpeg":
                                    tempMimeType = "image/jpeg";
                                    break;
                                case "png":
                                    tempMimeType = "image/png";
                                    break;
                                case "pdf":
                                    tempMimeType = "application/pdf";
                                    break;
                                case "mp3":
                                    tempMimeType = "audio/mpeg";
                                    break;
                                default:
                                    tempMimeType = "application/octet-stream";
                            }
                        } else {
                            tempMimeType = "application/octet-stream";
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not get file name or MIME type: " + e.getMessage());
                }

                finalFileName = tempFileName != null ? tempFileName : "uploaded_file_" + System.currentTimeMillis() +
                        (tempMimeType != null && tempMimeType.startsWith("image/") ? ".jpg" :
                                tempMimeType != null && tempMimeType.equals("application/pdf") ? ".pdf" :
                                        tempMimeType != null && tempMimeType.startsWith("audio/") ? ".mp3" : ".bin");
                finalMimeType = tempMimeType != null ? tempMimeType : "application/octet-stream";

                InputStream inputStream = getContentResolver().openInputStream(selectedFileUri);
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead);
                }
                inputStream.close();
                final byte[] fileBytes = byteArrayOutputStream.toByteArray();

                apiService.getChatById(chatId).enqueue(new Callback<Chat>() {
                    @Override
                    public void onResponse(Call<Chat> call, Response<Chat> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getIsSecret()) {
                            // Создаем объект сообщения
                            Message message = new Message();
                            long tempId = System.currentTimeMillis();
                            message.setId(tempId);
                            message.setChat(new Chat(chatId, chatName));
                            message.setSender(new User(currentUserId, username, null));
                            message.setMessageType(finalMimeType.startsWith("audio/") ? "audio" : "file");
                            message.setContentType(finalMimeType); // чтобы картинка распозналась как картинка сразу, а не после перезахода
                            message.setContent(content.isEmpty() ? "" : content);
                            message.setDelivered(false);
                            message.setRead(false);
                            message.setTimestamp(generateCurrentTimestamp());
                            message.setTemporary(true);

                            // Сначала отправляем сообщение на сервер
                            if (!content.isEmpty()) {
                                encryptMessage(content, partnerId).whenComplete((textResult, textThrowable) -> {
                                    if (textThrowable != null) {
                                        Log.e(TAG, "Text encryption failed: " + textThrowable.getMessage());
                                        runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Failed to encrypt text", Toast.LENGTH_SHORT).show());
                                        return;
                                    }
                                    try {
                                        JSONObject textJson = new JSONObject(textResult);
                                        message.setEncryptedContent(textJson.getString("encryptedContent"));
                                        message.setNonce(textJson.getString("nonce"));
                                        saveLocalMessageContent(tempId, content);
                                        message.setContent(content);

                                        // Отправляем сообщение на сервер
                                        sendMessageToServerForFile(message, fileBytes, finalFileName, finalMimeType, tempId);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error parsing text encryption result: " + e.getMessage());
                                        runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Failed to encrypt text", Toast.LENGTH_SHORT).show());
                                    }
                                });
                            } else {
                                // Отправляем сообщение без текста
                                sendMessageToServerForFile(message, fileBytes, finalFileName, finalMimeType, tempId);
                            }
                        } else {
                            // Обычный чат: текущая логика
                            RequestBody fileBody = RequestBody.create(MediaType.parse(finalMimeType), fileBytes);
                            MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", finalFileName, fileBody);
                            Call<FileUploadResponse> uploadCall = apiService.uploadFile(filePart);

                            uploadCall.enqueue(new Callback<FileUploadResponse>() {
                                @Override
                                public void onResponse(Call<FileUploadResponse> call, Response<FileUploadResponse> response) {
                                    if (response.isSuccessful() && response.body() != null) {
                                        String fileUrl = response.body().getFileUrl();
                                        sendMessageWithFile(content, fileUrl, messageType);
                                    } else {
                                        runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this,
                                                "Failed to upload file: " + getErrorBody(response), Toast.LENGTH_SHORT).show());
                                    }
                                }

                                @Override
                                public void onFailure(Call<FileUploadResponse> call, Throwable t) {
                                    Log.e(TAG, "Error uploading file: " + t.getMessage());
                                    runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this,
                                            "Error uploading file: " + t.getMessage(), Toast.LENGTH_SHORT).show());
                                }
                            });
                        }
                    }

                    @Override
                    public void onFailure(Call<Chat> call, Throwable t) {
                        Log.e(TAG, "Error checking chat: " + t.getMessage());
                        runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Error checking chat", Toast.LENGTH_SHORT).show());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error preparing file for upload: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "Error preparing file: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        } else {
            sendMessageWithFile(content, null, messageType);
        }
    }

    private void sendMessageToServerForFile(Message message, byte[] fileBytes, String fileName, String mimeType, long tempId) {
        Log.d(TAG, "Preparing to send file message: tempId=" + tempId + ", fileName=" + fileName + ", mimeType=" + mimeType);

        // Сохраняем локальный текст, если он есть
        if (!message.getContent().isEmpty()) {
            saveLocalMessageContent(tempId, message.getContent());
            Log.d(TAG, "Saved local text content for tempId=" + tempId + ": " + message.getContent());
        }

        // Сохраняем метаданные файла
        saveLocalFileMetadata(tempId, fileName, mimeType);
        Log.d(TAG, "Saved local file metadata for tempId=" + tempId + ": fileName=" + fileName + ", mimeType=" + mimeType);

        // Сохраняем исходный (незашифрованный) файл локально
        saveLocalFileContent(tempId, fileBytes, fileName);
        Log.d(TAG, "Saved local file content for tempId=" + tempId + ", fileName=" + fileName);

        // Создаём временное сообщение
        message.setId(tempId);
        message.setSender(new User((int) currentUserId, username, null));
        message.setMessageType(mimeType.startsWith("audio/") ? "audio" : "file");
        message.setContentType(mimeType);
        message.setTemporary(true);
        message.setContent(message.getContent().isEmpty() ? fileName : message.getContent());
        message.setFileUrl("file://" + getLocalFile(tempId, fileName).getAbsolutePath()); // Локальный URI для UI
        runOnUiThread(() -> {
            messages.add(message);
            messageAdapter.notifyItemInserted(messages.size() - 1);
            scrollToBottom();
            Log.d(TAG, "Added temporary message: tempId=" + tempId + ", fileUrl=" + message.getFileUrl());
        });

        // Шифруем метаданные
        // Подпись к файлу едет внутри зашифрованных метаданных — открытого поля
        // content у сообщений секретного чата нет (сервер его обнуляет).
        // Веб-клиент читает и пишет то же поле "caption".
        String captionRaw0 = message.getContent();
        final String captionRaw = (captionRaw0 == null || captionRaw0.equals(fileName)) ? "" : captionRaw0;
        // Подпись к файлу переводим так же, как текст: на устройстве, до шифрования.
        prepareOutgoingText(captionRaw).whenComplete((captionPrepared, capThrowable) -> {
        String captionForMeta = (capThrowable == null && captionPrepared != null) ? captionPrepared : captionRaw;
        if (!captionForMeta.isEmpty() && !captionForMeta.equals(captionRaw)) {
            saveLocalMessageContent(tempId, captionForMeta); // своя подпись с переводом — в кэш
        }
        String metadata;
        try {
            JSONObject metaJson = new JSONObject();
            metaJson.put("fileName", fileName);
            metaJson.put("mimeType", mimeType);
            if (!captionForMeta.isEmpty()) metaJson.put("caption", captionForMeta);
            metadata = metaJson.toString();
        } catch (JSONException e) {
            metadata = "{\"fileName\":\"" + fileName + "\",\"mimeType\":\"" + mimeType + "\"}";
        }
        encryptMessage(metadata, partnerId).whenComplete((metadataJson, metadataThrowable) -> {
            if (metadataThrowable != null || metadataJson == null) {
                Log.e(TAG, "Failed to encrypt file metadata: " + (metadataThrowable != null ? metadataThrowable.getMessage() : "null result"));
                runOnUiThread(() -> {
                    updateTempMessageStatus(tempId, "Failed to encrypt metadata");
                    Toast.makeText(SecretPrivateChatActivity.this, "Failed to encrypt file metadata", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            try {
                JSONObject json = new JSONObject(metadataJson);
                message.setEncryptedContent(json.getString("encryptedContent"));
                message.setNonce(json.getString("nonce"));

                // Отправляем сообщение на сервер
                sendMessageToServer(message, null, message.getMessageType(), tempId, new Callback<Message>() {
                    @Override
                    public void onResponse(Call<Message> call, Response<Message> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            Message serverMessage = response.body();
                            long serverMessageId = serverMessage.getId();
                            Log.d(TAG, "Message sent to server, serverMessageId=" + serverMessageId);

                            // Переносим локальные данные на serverMessageId
                            String localContent = getLocalMessageContent(tempId);
                            if (localContent != null) {
                                saveLocalMessageContent(serverMessageId, localContent);
                                localMessageContent.remove(tempId);
                                serverMessage.setContent(localContent);
                                Log.d(TAG, "Transferred local text content from tempId=" + tempId + " to serverMessageId=" + serverMessageId);
                            }
                            JSONObject localMetadata = getLocalFileMetadata(tempId);
                            if (localMetadata != null) {
                                saveLocalFileMetadata(serverMessageId, localMetadata.optString("fileName", fileName), localMetadata.optString("mimeType", mimeType));
                                Log.d(TAG, "Transferred local file metadata from tempId=" + tempId + " to serverMessageId=" + serverMessageId);
                            }
                            File localFile = getLocalFile(tempId, fileName);
                            if (localFile != null && localFile.exists()) {
                                saveLocalFileContent(serverMessageId, fileBytes, fileName);
                                Log.d(TAG, "Transferred local file content from tempId=" + tempId + " to serverMessageId=" + serverMessageId);
                            }

                            // Шифруем и загружаем файл
                            encryptFile(fileBytes, partnerId).whenComplete((fileResultJson, fileThrowable) -> {
                                if (fileThrowable != null || fileResultJson == null) {
                                    Log.e(TAG, "File encryption failed: " + (fileThrowable != null ? fileThrowable.getMessage() : "null result"));
                                    runOnUiThread(() -> {
                                        updateTempMessageStatus(tempId, "Failed to encrypt file");
                                        Toast.makeText(SecretPrivateChatActivity.this, "Failed to encrypt file", Toast.LENGTH_SHORT).show();
                                    });
                                    return;
                                }

                                try {
                                    String encryptedFileBase64 = fileResultJson.getString("encryptedFile");
                                    String nonce = fileResultJson.getString("nonce");
                                    byte[] encryptedFileBytes = decodeBase64(encryptedFileBase64);

                                    RequestBody fileBody = RequestBody.create(MediaType.parse(mimeType), encryptedFileBytes); // Используем правильный mimeType
                                    MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", fileName, fileBody);
                                    RequestBody nonceBody = RequestBody.create(MediaType.parse("text/plain"), nonce);
                                    RequestBody messageIdBody = RequestBody.create(MediaType.parse("text/plain"), String.valueOf(serverMessageId));

                                    Call<FileUploadResponse> uploadCall = apiService.uploadEncryptedFile(filePart, nonceBody, messageIdBody);
                                    uploadCall.enqueue(new Callback<FileUploadResponse>() {
                                        @Override
                                        public void onResponse(Call<FileUploadResponse> call, Response<FileUploadResponse> response) {
                                            if (response.isSuccessful() && response.body() != null) {
                                                String fileUrl = response.body().getFileUrl();
                                                serverMessage.setFileUrl(fileUrl);
                                                serverMessage.setMessageType(message.getMessageType());
                                                serverMessage.setTemporary(false);
                                                serverMessage.setContent(localContent != null ? localContent : fileName);
                                                runOnUiThread(() -> {
                                                    for (int i = 0; i < messages.size(); i++) {
                                                        if (messages.get(i).getId() == tempId) {
                                                            messages.set(i, serverMessage);
                                                            messageAdapter.notifyItemChanged(i);
                                                            scrollToBottom();
                                                            Log.d(TAG, "Updated message: tempId=" + tempId + " to serverMessageId=" + serverMessageId + ", fileUrl=" + fileUrl);
                                                            break;
                                                        }
                                                    }
                                                    resetForm();

                                                    sendTypingEvent(false);
                                                });
                                            } else {
                                                Log.e(TAG, "Failed to upload encrypted file: code=" + response.code());
                                                runOnUiThread(() -> {
                                                    updateTempMessageStatus(tempId, "Failed to upload file");
                                                    Toast.makeText(SecretPrivateChatActivity.this, "Failed to upload file", Toast.LENGTH_SHORT).show();
                                                });
                                            }
                                        }

                                        @Override
                                        public void onFailure(Call<FileUploadResponse> call, Throwable t) {
                                            Log.e(TAG, "Error uploading encrypted file: " + t.getMessage());
                                            runOnUiThread(() -> {
                                                updateTempMessageStatus(tempId, "Failed to upload file");
                                                Toast.makeText(SecretPrivateChatActivity.this, "Error uploading file", Toast.LENGTH_SHORT).show();
                                            });
                                        }
                                    });
                                } catch (Exception e) {
                                    Log.e(TAG, "Error processing encrypted file: " + e.getMessage());
                                    runOnUiThread(() -> {
                                        updateTempMessageStatus(tempId, "Failed to process file");
                                        Toast.makeText(SecretPrivateChatActivity.this, "Failed to process file", Toast.LENGTH_SHORT).show();
                                    });
                                }
                            });
                        } else {
                            Log.e(TAG, "Failed to send message: code=" + response.code());
                            runOnUiThread(() -> {
                                updateTempMessageStatus(tempId, "Failed to send message");
                                Toast.makeText(SecretPrivateChatActivity.this, "Failed to send message", Toast.LENGTH_SHORT).show();
                            });
                        }
                    }

                    @Override
                    public void onFailure(Call<Message> call, Throwable t) {
                        Log.e(TAG, "Error sending message: " + t.getMessage());
                        runOnUiThread(() -> {
                            updateTempMessageStatus(tempId, "Failed to send message");
                            Toast.makeText(SecretPrivateChatActivity.this, "Failed to send message", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error parsing metadata encryption result: " + e.getMessage());
                runOnUiThread(() -> {
                    updateTempMessageStatus(tempId, "Failed to process metadata");
                    Toast.makeText(SecretPrivateChatActivity.this, "Failed to process file metadata", Toast.LENGTH_SHORT).show();
                });
            }
        });
        }); // prepareOutgoingText(caption)
    }

    // Вспомогательный метод для сохранения локального файла
    public void saveLocalFileContent(long messageId, byte[] fileBytes, String fileName) {
        JSONObject metadata = getLocalFileMetadata(messageId);
        String mimeType = metadata != null ? metadata.optString("mimeType", null) : null;
        String uniqueSuffix = metadata != null ? metadata.optString("uniqueSuffix", String.valueOf(messageId)) : String.valueOf(messageId);
        String extension = getFileExtension(mimeType, fileName);
        String fileNameWithSuffix = "file_" + messageId + "_" + uniqueSuffix + "." + extension;
        File file = new File(getCacheDir(), fileNameWithSuffix);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(fileBytes);
            Log.d(TAG, "Saved local file for messageId=" + messageId + ", fileName=" + fileNameWithSuffix);
        } catch (IOException e) {
            Log.e(TAG, "Error saving local file for messageId=" + messageId + ": " + e.getMessage());
        }
    }

    /**
     * Метаданные локального файла, только если они относятся именно к этому
     * сообщению (совпадает nonce). Временные (ещё не отправленные) сообщения
     * не проверяем — их id уникальны. Устаревшие данные удаляем вместе с файлом.
     */
    public JSONObject getValidLocalFileMetadata(Message message) {
        if (message == null) return null;
        JSONObject metadata = getLocalFileMetadata(message.getId());
        if (metadata == null) return null;
        if (message.isTemporary()) return metadata;

        String storedNonce = metadata.optString("nonce", null);
        String actualNonce = message.getNonce();
        boolean fresh = storedNonce != null && actualNonce != null && storedNonce.equals(actualNonce);
        if (fresh) return metadata;

        Log.w(TAG, "Local file cache for messageId=" + message.getId() + " belongs to another message "
                + "(stored nonce=" + storedNonce + ", actual=" + actualNonce + "), discarding it");
        File stale = getLocalFile(message.getId(), metadata.optString("fileName", null));
        if (stale != null && stale.delete()) {
            Log.d(TAG, "Deleted stale local file: " + stale.getName());
        }
        getSharedPreferences("local_file_metadata", MODE_PRIVATE).edit()
                .remove("metadata_" + message.getId()).apply();
        return null;
    }

    /** Локальный файл сообщения — с проверкой, что он относится к этому сообщению. */
    public File getLocalFile(Message message, String fileName) {
        if (message == null) return null;
        if (getValidLocalFileMetadata(message) == null) return null;
        return getLocalFile(message.getId(), fileName);
    }

    // Вспомогательный метод для получения локального файла
    public File getLocalFile(long messageId, String fileName) {
        JSONObject metadata = getLocalFileMetadata(messageId);
        if (metadata != null) {
            String storedFileName = metadata.optString("fileName", fileName);
            String mimeType = metadata.optString("mimeType", null);
            String uniqueSuffix = metadata.optString("uniqueSuffix", String.valueOf(messageId));
            String extension = getFileExtension(mimeType, storedFileName);
            String fileNameWithSuffix = "file_" + messageId + "_" + uniqueSuffix + "." + extension;
            File file = new File(getCacheDir(), fileNameWithSuffix);
            if (file.exists()) {
                Log.d(TAG, "Retrieved local file for messageId=" + messageId + ", fileName=" + fileNameWithSuffix);
                return file;
            }
            Log.w(TAG, "Local file not found for messageId=" + messageId + ", fileName=" + fileNameWithSuffix);
        } else {
            Log.w(TAG, "No metadata found for messageId=" + messageId);
        }
        return null;
    }

    private String getFileExtension(String mimeType, String fileName) {
        Map<String, String> mimeToExtension = new HashMap<>();
        mimeToExtension.put("image/jpeg", "jpg");
        mimeToExtension.put("image/jpg", "jpg");
        mimeToExtension.put("image/png", "png");
        mimeToExtension.put("image/gif", "gif");
        mimeToExtension.put("image/bmp", "bmp");
        mimeToExtension.put("image/webp", "webp");
        mimeToExtension.put("audio/mpeg", "mp3");
        mimeToExtension.put("audio/wav", "wav");
        mimeToExtension.put("audio/ogg", "ogg");
        mimeToExtension.put("audio/aac", "aac");
        mimeToExtension.put("video/mp4", "mp4");
        mimeToExtension.put("video/mpeg", "mpeg");
        mimeToExtension.put("video/webm", "webm");
        mimeToExtension.put("video/3gpp", "3gp");
        mimeToExtension.put("video/avi", "avi");
        mimeToExtension.put("application/pdf", "pdf");
        mimeToExtension.put("application/msword", "doc");
        mimeToExtension.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        mimeToExtension.put("application/vnd.ms-excel", "xls");
        mimeToExtension.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        mimeToExtension.put("application/vnd.ms-powerpoint", "ppt");
        mimeToExtension.put("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
        mimeToExtension.put("text/plain", "txt");
        mimeToExtension.put("text/csv", "csv");
        mimeToExtension.put("application/zip", "zip");
        mimeToExtension.put("application/x-7z-compressed", "7z");
        mimeToExtension.put("application/x-rar-compressed", "rar");
        mimeToExtension.put("application/json", "json");
        mimeToExtension.put("text/html", "html");
        mimeToExtension.put("image/tiff", "tiff");

        String extension = mimeType != null ? mimeToExtension.get(mimeType.toLowerCase()) : null;
        if (extension != null) {
            return extension;
        }
        if (fileName != null && fileName.contains(".")) {
            try {
                return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
            } catch (Exception e) {
                Log.w(TAG, "Failed to extract extension from fileName=" + fileName + ", defaulting to bin");
            }
        }
        return "bin";
    }

    public void saveLocalFileMetadata(long messageId, String fileName, String mimeType) {
        saveLocalFileMetadata(messageId, fileName, mimeType, null);
    }

    /**
     * Сохраняет метаданные файла вместе с nonce сообщения. По nonce кэш потом
     * проверяется на актуальность: локальные данные ключуются только по id
     * сообщения, а id не уникальны во времени (после обнуления базы или
     * пересоздания чата нумерация идёт заново). Без этой проверки под новым
     * сообщением показывался чужой старый файл — так фото на 2 МБ
     * «превратилось» в mp3 на 26 КБ от давно удалённого голосового.
     */
    public void saveLocalFileMetadata(long messageId, String fileName, String mimeType, String nonce) {
        SharedPreferences prefs = getSharedPreferences("local_file_metadata", MODE_PRIVATE);
        JSONObject metadata = new JSONObject();
        try {
            metadata.put("fileName", fileName);
            metadata.put("mimeType", mimeType);
            // Используем messageId как uniqueSuffix
            String uniqueSuffix = String.valueOf(messageId);
            metadata.put("uniqueSuffix", uniqueSuffix);
            if (nonce != null && !nonce.isEmpty()) {
                metadata.put("nonce", nonce);
            } else {
                // Сохраняем уже известный nonce, если он был записан раньше
                JSONObject previous = getLocalFileMetadata(messageId);
                if (previous != null && previous.has("nonce")) {
                    metadata.put("nonce", previous.optString("nonce"));
                }
            }
            prefs.edit().putString("metadata_" + messageId, metadata.toString()).apply();
            Log.d(TAG, "Saved local file metadata for messageId=" + messageId + ": " + metadata.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error saving local file metadata for messageId=" + messageId + ": " + e.getMessage());
        }
    }

    public JSONObject getLocalFileMetadata(long messageId) {
        SharedPreferences prefs = getSharedPreferences("local_file_metadata", MODE_PRIVATE);
        String metadataJson = prefs.getString("metadata_" + messageId, null);
        if (metadataJson != null) {
            try {
                JSONObject metadata = new JSONObject(metadataJson);
                Log.d(TAG, "Retrieved local file metadata for messageId=" + messageId + ": " + metadata.toString());
                return metadata;
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing local file metadata for messageId=" + messageId + ": " + e.getMessage());
            }
        }
        Log.w(TAG, "No local file metadata found for messageId=" + messageId);
        return null;
    }

    // Вспомогательный метод для обновления статуса временного сообщения
    private void updateTempMessageStatus(long tempId, String status) {
        runOnUiThread(() -> {
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).getId() == tempId) {
                    messages.get(i).setFileUrl("");
                    messages.get(i).setMessageType("error");
                    messages.get(i).setContent(status);
                    messageAdapter.notifyItemChanged(i);
                    break;
                }
            }
        });
    }

    private String getErrorBody(Response<?> response) {
        try {
            return response.errorBody() != null ? response.errorBody().string() : "No error details";
        } catch (IOException e) {
            Log.e(TAG, "Could not read error body: " + e.getMessage());
            return "Error reading response";
        }
    }

    private void sendMessageWithFile(String content, String fileUrl, String messageType) {
        Message message = new Message();
        long tempId = System.currentTimeMillis();
        message.setId(editingMessageId != null ? editingMessageId : tempId);
        message.setContent(""); // Пустое content для сервера
        Log.d(TAG, "Preparing message: tempId=" + tempId + ", messageType=" + messageType + ", content=" + content);

        if ("text".equals(messageType) && !content.isEmpty()) {
            // Перевод на устройстве ДО шифрования: в шифртекст уходит обёртка
            // {"text","translated","lang"}, сервер видит только шифртекст.
            prepareOutgoingText(content).whenComplete((payload, prepThrowable) -> {
            final String contentToEncrypt = (prepThrowable == null && payload != null) ? payload : content;
            saveLocalMessageContent(tempId, contentToEncrypt); // Сохраняем локальный контент (с переводом)
            Log.d(TAG, "Saved local content for tempId=" + tempId + ": " + contentToEncrypt);
            encryptMessage(contentToEncrypt, partnerId).whenComplete((encryptionResult, throwable) -> {
                if (throwable != null) {
                    Log.e(TAG, "Encryption failed: " + throwable.getMessage());
                    runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Failed to encrypt message", Toast.LENGTH_SHORT).show());
                    return;
                }
                if (encryptionResult != null) {
                    try {
                        JSONObject resultJson = new JSONObject(encryptionResult);
                        message.setEncryptedContent(resultJson.getString("encryptedContent"));
                        message.setNonce(resultJson.getString("nonce"));
                        message.setContent(content); // Устанавливаем локальный контент для UI
                        User sender = new User();
                        sender.setId(currentUserId);
                        sender.setUsername(username);
                        sender.setAvatar(getSharedPreferences("prefs", MODE_PRIVATE).getString("avatar", null));
                        message.setSender(sender);
                        message.setChat(new Chat(chatId, chatName));
                        message.setFileUrl(fileUrl);
                        message.setDelivered(false);
                        message.setRead(false);
                        message.setTimestamp(generateCurrentTimestamp());
                        message.setMessageType(messageType);
                        message.setTemporary(true); // Помечаем как временное
                        updateMessages(message); // Добавляем в UI с временным ID
                        Log.d(TAG, "Message added to UI with tempId=" + tempId + ", content=" + content);
                        sendMessageToServer(message, fileUrl, messageType, tempId); // Передаем tempId
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing encryption result: " + e.getMessage());
                        runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Failed to encrypt message", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    Log.e(TAG, "Encryption result is null");
                    runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Encryption failed", Toast.LENGTH_SHORT).show());
                }
            });
            }); // prepareOutgoingText
        } else {
            sendMessageToServer(message, fileUrl, messageType, tempId);
        }
    }

    private void sendMessageToServer(Message message, String fileUrl, String messageType, long tempId, Callback<Message> callback) {
        // Раньше здесь стояло message.setContent("") «для сервера». Но JSON для
        // сервера собирается ниже отдельно (content там и так пустой), а этот
        // объект — тот же, что лежит в списке UI: список биндил его чуть позже
        // и показывал своё сообщение пустой белой плашкой до перезахода в чат.
        User sender = new User();
        sender.setId(currentUserId);
        sender.setUsername(username);
        sender.setAvatar(getSharedPreferences("prefs", MODE_PRIVATE).getString("avatar", null));
        message.setSender(sender);
        Chat chat = new Chat(chatId, chatName);
        message.setChat(chat);
        message.setFileUrl(fileUrl);
        message.setDelivered(false);
        message.setRead(false);
        message.setTimestamp(generateCurrentTimestamp());
        message.setMessageType(messageType);

        if (stompClient != null && stompClient.isConnected()) {
            try {
                JSONObject jsonMessage = new JSONObject();
                jsonMessage.put("id", message.getId());
                jsonMessage.put("content", "");
                if (message.getEncryptedContent() != null) {
                    jsonMessage.put("encryptedContent", message.getEncryptedContent());
                    jsonMessage.put("nonce", message.getNonce());
                }
                JSONObject senderJson = new JSONObject();
                senderJson.put("id", sender.getId());
                senderJson.put("username", sender.getUsername());
                if (sender.getAvatar() != null) {
                    senderJson.put("avatar", sender.getAvatar());
                }
                jsonMessage.put("sender", senderJson);
                JSONObject chatJson = new JSONObject();
                chatJson.put("id", chat.getId());
                jsonMessage.put("chat", chatJson);
                if (fileUrl != null && !fileUrl.trim().isEmpty()) {
                    jsonMessage.put("fileUrl", fileUrl);
                }
                jsonMessage.put("delivered_status", false);
                jsonMessage.put("read_status", false);
                jsonMessage.put("messageType", messageType);
                // MIME-тип файла: по нему собеседник (и мы сами после перезахода)
                // отличаем картинку от прочих файлов. Веб-клиент его шлёт, Android не слал.
                if (message.getContentType() != null && !message.getContentType().isEmpty()) {
                    jsonMessage.put("contentType", message.getContentType());
                }

                String destination = "/app/sendMessage/chat/" + chatId;
                isMessageJustSent = true;
                Log.d(TAG, "Sending message to server: tempId=" + tempId + ", json=" + jsonMessage.toString());
                stompClient.send(destination, jsonMessage.toString())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> {
                                    Log.d(TAG, "Message sent successfully, tempId=" + tempId);
                                    sendTypingEvent(false);
                                    resetForm(); // Очищаем поле ввода и сбрасываем состояние
                                    // Проверяем статус сообщения
                                    checkMessageStatus(message, 0, tempId, callback);
                                },
                                throwable -> {
                                    Log.e(TAG, "Error sending message: " + throwable.getMessage());
                                    runOnUiThread(() -> Toast.makeText(SecretPrivateChatActivity.this, "Failed to send message", Toast.LENGTH_SHORT).show());
                                    isMessageJustSent = false;
                                    if (callback != null) {
                                        callback.onFailure(null, throwable);
                                    }
                                }
                        );
            } catch (Exception e) {
                Log.e(TAG, "Error preparing message JSON: " + e.getMessage());
                Toast.makeText(this, "Error preparing message: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                isMessageJustSent = false;
                if (callback != null) {
                    callback.onFailure(null, e);
                }
            }
        } else {
            Log.w(TAG, "StompClient not connected");
            Toast.makeText(this, "WebSocket not connected", Toast.LENGTH_SHORT).show();
            isMessageJustSent = false;
            if (callback != null) {
                callback.onFailure(null, new Exception("WebSocket not connected"));
            }
        }
    }

    // Перегруженная версия без callback для обратной совместимости
    private void sendMessageToServer(Message message, String fileUrl, String messageType, long tempId) {
        sendMessageToServer(message, fileUrl, messageType, tempId, null);
    }

    private Message parseMessageFromJson(JSONObject msgJson) throws Exception {
        Log.d(TAG, "Received JSON for parsing: " + msgJson.toString());
        Message newMessage = new Message();
        newMessage.setId(msgJson.getLong("id"));
        newMessage.setContent(msgJson.optString("content", null));
        newMessage.setEncryptedContent(msgJson.optString("encryptedContent", null));
        newMessage.setNonce(msgJson.optString("nonce", null));
        if (msgJson.has("publicKeyId") && !msgJson.isNull("publicKeyId")) {
            newMessage.setPublicKeyId(msgJson.getLong("publicKeyId"));
        }
        User sender = new User();
        if (msgJson.has("sender") && !msgJson.isNull("sender")) {
            JSONObject senderJson = msgJson.getJSONObject("sender");
            sender.setId(senderJson.getInt("id"));
            sender.setUsername(senderJson.optString("username", "Unknown"));
            sender.setAvatar(senderJson.optString("avatar", null));
        } else {
            sender.setId(-1);
            sender.setUsername("Unknown");
        }
        newMessage.setSender(sender);
        Chat chat = new Chat(chatId, chatName);
        newMessage.setChat(chat);
        newMessage.setFileUrl(msgJson.optString("fileUrl", null));
        newMessage.setDelivered(msgJson.optBoolean("delivered_status", false));
        newMessage.setRead(msgJson.optBoolean("read_status", false));
        String timestamp = msgJson.optString("timestamp", null);
        newMessage.setTimestamp(timestamp != null && !timestamp.isEmpty() ?
                timestamp : generateCurrentTimestamp());
        newMessage.setMessageType(msgJson.optString("messageType", "text"));
        newMessage.setContentType(msgJson.optString("contentType", null));
        newMessage.setTemporary(false);
        Log.d(TAG, "Parsed message: id=" + newMessage.getId() + ", contentType=" + newMessage.getContentType() + ", messageType=" + newMessage.getMessageType());
        if (sender.getId() == currentUserId && "text".equals(newMessage.getMessageType())) {
            String localContent = getLocalMessageContent(newMessage.getId());
            if (localContent != null) {
                newMessage.setContent(localContent);
                saveLocalMessageContent(newMessage.getId(), localContent, newMessage.getNonce());
                Log.d(TAG, "Restored and saved local content in parseMessageFromJson for message id=" + newMessage.getId() + ": " + localContent);
            } else {
                Log.w(TAG, "No local content found in parseMessageFromJson for message id=" + newMessage.getId());
            }
        }
        return newMessage;
    }

    private void resetForm() {
        inputMessage.setText("");
        selectedFileUri = null;
        editingMessageId = null;
        if (audioFile != null && audioFile.exists()) {
            audioFile.delete();
            audioFile = null;
        }
        attachedFileTextView.setVisibility(View.GONE);
        updateInputIcons();
    }

    @Override
    public void onEdit(Message message) {
        if (message.getSender().getId() == currentUserId) {
            inputMessage.setText(message.getContent());
            editingMessageId = message.getId();
        } else {
            Toast.makeText(this, "You can only edit your own messages", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDelete(Message message) {
        if (message.getSender().getId() == currentUserId) {
            if (stompClient != null && stompClient.isConnected()) {
                stompClient.send("/app/deleteMessage/" + chatId, String.valueOf(message.getId()))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> {
                                    messages.removeIf(msg -> msg.getId() == message.getId());
                                    messageAdapter.updateMessages(messages);
                                    Toast.makeText(this, "Message deleted!", Toast.LENGTH_SHORT).show();
                                },
                                throwable -> {
                                    Log.e(TAG, "Error deleting message: " + throwable.getMessage());
                                    Toast.makeText(this, "Failed to delete message", Toast.LENGTH_SHORT).show();
                                }
                        );
            } else {
                Toast.makeText(this, "WebSocket not connected", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "You can only delete your own messages", Toast.LENGTH_SHORT).show();
        }
    }

    private void markAllMessagesAsRead() {
        if (stompClient == null || !stompClient.isConnected()) {
            Log.d(TAG, "Отметки «прочитано» отложены: соединение не готово");
            return;
        }
        for (Message msg : messages) {
            if (msg.getSender().getId() != currentUserId && !msg.isRead()) {
                markMessageAsRead(msg);
            }
        }
    }

    private void stopCallSoundLocally() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping MediaPlayer: " + e.getMessage());
            }
        }
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("showCallDialog", false)) {
            CallRequest callRequest = (CallRequest) intent.getSerializableExtra("callRequest");
            if (callRequest != null) {
                incomingCall = callRequest;
                showIncomingCallDialog(callRequest);
            }
        }
    }

    private void showIncomingCallDialog(CallRequest callRequest) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved()) {
            return;
        }
        IncomingCallDialogFragment existingDialog = (IncomingCallDialogFragment) fm.findFragmentByTag("IncomingCallDialog");
        if (existingDialog != null && existingDialog.isAdded()) {
            return;
        }

        String callerName = callRequest.getCallerName() != null ? callRequest.getCallerName() : "User " + callRequest.getCallerId();
        String notificationMessage = callRequest.getType().equals("video") ?
                getString(R.string.incoming_video_call, callerName) :
                getString(R.string.incoming_voice_call, callerName);
        Toast.makeText(this, notificationMessage, Toast.LENGTH_SHORT).show();

        IncomingCallDialogFragment dialog = new IncomingCallDialogFragment(callRequest, this::acceptCall, this::rejectCall);
        dialog.show(fm, "IncomingCallDialog");
    }

    private void acceptCall() {
        if (incomingCall == null) {
            return;
        }
        if (isBound && callService != null) {
            callService.stopCallSound();
            callService.sendSignal("acceptCall", incomingCall.getRoomId());
        } else {
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
                                Intent intent = new Intent(SecretPrivateChatActivity.this,
                                        incomingCall.getType().equals("video") ? VideoCallActivity.class : VoiceCallActivity.class);
                                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                                intent.putExtra("recipientId", String.valueOf(incomingCall.getCallerId()));
                                intent.putExtra("roomId", incomingCall.getRoomId());
                                intent.putExtra("isCaller", false);
                                intent.putExtra("callerName", callerName);
                                intent.putExtra("recipientName", incomingCall.getCallerName());
                                startActivity(intent);
                                Toast.makeText(SecretPrivateChatActivity.this,
                                        incomingCall.getType().equals("video") ?
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
            return;
        }
        if (isBound && callService != null) {
            callService.stopCallSound();
            callService.sendSignal("rejectCall", incomingCall.getRoomId());
        } else {
            stopCallSoundLocally();
        }
        incomingCall = null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        long newChatId = intent.getLongExtra("chatId", -1);
        if (newChatId != -1 && newChatId != chatId) {
            chatId = newChatId;
            messages.clear();
            messageAdapter.updateMessages(messages);
            fetchChatData();
        }
        if (isBound && callService != null) {
            handleIntent(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent serviceIntent = new Intent(this, CallService.class);
        serviceIntent.putExtra("action", "SUBSCRIBE_CHAT");
        serviceIntent.putExtra("chatId", chatId);
        startService(serviceIntent);
        scheduleStatusCheck();
        fetchChatData();
    }

    private String generateCurrentTimestamp() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    private void requestPartnerStatus() {
        if (isBound && callService != null && partnerId != -1) {
            callService.requestUserStatus(partnerId);
        }
    }

    private void requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST_CODE);
        } else {
            startRecording();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                Toast.makeText(this, "Audio recording permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startRecording() {
        try {
            audioFile = new File(getCacheDir(), "audio_" + System.currentTimeMillis() + ".mp3");
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            updateInputIcons();
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Error starting recording: " + e.getMessage());
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
            updateInputIcons();
        }
    }

    private void stopRecording() {
        if (mediaRecorder != null && isRecording) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                updateInputIcons();
                Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
                sendAudioMessage();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recording: " + e.getMessage());
                Toast.makeText(this, "Failed to stop recording", Toast.LENGTH_SHORT).show();
                updateInputIcons();
                audioFile = null;
            }
        }
    }

    private void sendAudioMessage() {
        if (audioFile == null || !audioFile.exists()) {
            Toast.makeText(this, "No audio recorded", Toast.LENGTH_SHORT).show();
            updateInputIcons();
            audioFile = null;
            return;
        }
        try {
            // Читаем аудио как байты (аналогично selectedFileUri)
            byte[] audioBytes = Files.readAllBytes(audioFile.toPath());
            String audioFileName = audioFile.getName(); // "audio_XXXX.mp3"
            String audioMimeType = "audio/mpeg"; // Фиксируем для MP3

            // Проверяем secret chat и шифруем как файл (логика из proceedWithSendingMessage)
            apiService.getChatById(chatId).enqueue(new Callback<Chat>() {
                @Override
                public void onResponse(Call<Chat> call, Response<Chat> response) {
                    if (response.isSuccessful() && response.body() != null && response.body().getIsSecret()) {
                        // Создаём tempId и сообщение (пустой content, тип "audio")
                        long tempId = System.currentTimeMillis();
                        Message message = new Message();
                        message.setId(tempId);
                        message.setChat(new Chat(chatId, chatName));
                        message.setSender(new User(currentUserId, username, null));
                        message.setMessageType("audio");
                        message.setContent(""); // Пустой для сервера
                        message.setDelivered(false);
                        message.setRead(false);
                        message.setTimestamp(generateCurrentTimestamp());
                        message.setTemporary(true);

                        // Сохраняем локальные метаданные (имя/тип)
                        saveLocalFileMetadata(tempId, audioFileName, audioMimeType);
                        Log.d(TAG, "Saved local audio metadata for tempId=" + tempId + ": " + audioFileName + ", " + audioMimeType);

                        // Сохраняем незашифрованный файл локально для UI
                        saveLocalFileContent(tempId, audioBytes, audioFileName);
                        message.setFileUrl("file://" + getLocalFile(tempId, audioFileName).getAbsolutePath()); // Локальный URI

                        // Добавляем temp-сообщение в UI
                        runOnUiThread(() -> {
                            messages.add(message);
                            messageAdapter.notifyItemInserted(messages.size() - 1);
                            scrollToBottom();
                            Log.d(TAG, "Added temporary audio message: tempId=" + tempId);
                        });

                        // [ФИКС] Один nonce для metadata + file
                        byte[] privateKeyBytes = getPrivateKeyFromKeystore();
                        if (privateKeyBytes == null) {
                            Log.e(TAG, "Private key not found for audio encryption");
                            runOnUiThread(() -> {
                                updateTempMessageStatus(tempId, "Failed to encrypt audio");
                                Toast.makeText(SecretPrivateChatActivity.this, "Private key not found", Toast.LENGTH_SHORT).show();
                            });
                            return;
                        }
                        fetchPublicKeyAsync(partnerId).whenComplete((publicKeyStr, throwable) -> {
                            if (throwable != null || publicKeyStr == null) {
                                Log.e(TAG, "Error fetching public key for audio: " + (throwable != null ? throwable.getMessage() : "null key"));
                                runOnUiThread(() -> {
                                    updateTempMessageStatus(tempId, "Public key not found");
                                    Toast.makeText(SecretPrivateChatActivity.this, "Public key not found", Toast.LENGTH_SHORT).show();
                                });
                                return;
                            }
                            try {
                                byte[] publicKeyBytes = decodeBase64(publicKeyStr);
                                if (publicKeyBytes == null || publicKeyBytes.length != Box.PUBLICKEYBYTES) {
                                    Log.e(TAG, "Invalid public key for audio");
                                    runOnUiThread(() -> {
                                        updateTempMessageStatus(tempId, "Invalid public key");
                                        Toast.makeText(SecretPrivateChatActivity.this, "Invalid public key", Toast.LENGTH_SHORT).show();
                                    });
                                    return;
                                }

                                byte[] nonceBytes = new byte[Box.NONCEBYTES];
                                sodium.randombytes_buf(nonceBytes, nonceBytes.length);
                                String nonceB64 = Base64.encodeToString(nonceBytes, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
                                Log.d(TAG, "Generated metadata nonce for audio: " + nonceB64);
                                // Для самого файла — ДРУГОЙ nonce. Один nonce на два открытых
                                // текста под одной парой ключей раскрывает XOR текстов и ломает
                                // аутентификацию; файл и метаданные шифруем разными.
                                byte[] fileNonceBytes = new byte[Box.NONCEBYTES];
                                sodium.randombytes_buf(fileNonceBytes, fileNonceBytes.length);
                                String fileNonceB64 = Base64.encodeToString(fileNonceBytes, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");

                                // Шифруем metadata (как байты, с одним nonce)
                                String metadataJson = "{\"fileName\":\"" + audioFileName + "\",\"mimeType\":\"" + audioMimeType + "\"}";
                                byte[] metaBytes = metadataJson.getBytes(StandardCharsets.UTF_8);
                                byte[] encryptedMeta = new byte[metaBytes.length + Box.MACBYTES];
                                int metaResult = sodium.crypto_box_easy(encryptedMeta, metaBytes, metaBytes.length, nonceBytes, publicKeyBytes, privateKeyBytes);
                                if (metaResult != 0) {
                                    Log.e(TAG, "Metadata encryption failed, result=" + metaResult);
                                    runOnUiThread(() -> {
                                        updateTempMessageStatus(tempId, "Failed to encrypt metadata");
                                        Toast.makeText(SecretPrivateChatActivity.this, "Failed to encrypt metadata", Toast.LENGTH_SHORT).show();
                                    });
                                    return;
                                }
                                String encryptedMetaB64 = Base64.encodeToString(encryptedMeta, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");

                                // Шифруем file (с тем же nonce)
                                byte[] encryptedFileBytes = new byte[audioBytes.length + Box.MACBYTES];
                                int fileResult = sodium.crypto_box_easy(encryptedFileBytes, audioBytes, audioBytes.length, fileNonceBytes, publicKeyBytes, privateKeyBytes);
                                if (fileResult != 0) {
                                    Log.e(TAG, "File encryption failed, result=" + fileResult);
                                    runOnUiThread(() -> {
                                        updateTempMessageStatus(tempId, "Failed to encrypt file");
                                        Toast.makeText(SecretPrivateChatActivity.this, "Failed to encrypt file", Toast.LENGTH_SHORT).show();
                                    });
                                    return;
                                }
                                String encryptedFileB64 = Base64.encodeToString(encryptedFileBytes, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");

                                // Устанавливаем в message (для WS)
                                message.setEncryptedContent(encryptedMetaB64);
                                message.setNonce(nonceB64);

                                // Отправляем на сервер (с nonce для metadata)
                                sendMessageToServer(message, null, "audio", tempId, new Callback<Message>() {
                                    @Override
                                    public void onResponse(Call<Message> call, Response<Message> response) {
                                        if (response.isSuccessful() && response.body() != null) {
                                            Message serverMessage = response.body();
                                            long serverMessageId = serverMessage.getId();
                                            Log.d(TAG, "Audio message sent to server, serverMessageId=" + serverMessageId + ", shared nonce=" + nonceB64);

                                            // Переносим локальные данные
                                            JSONObject localMetadata = getLocalFileMetadata(tempId);
                                            if (localMetadata != null) {
                                                saveLocalFileMetadata(serverMessageId, localMetadata.optString("fileName", audioFileName), localMetadata.optString("mimeType", audioMimeType));
                                                Log.d(TAG, "Transferred audio metadata to serverMessageId=" + serverMessageId);
                                            }
                                            saveLocalFileContent(serverMessageId, audioBytes, audioFileName); // Переносим файл
                                            serverMessage.setContent(audioFileName); // Для UI

                                            // Upload encrypted file (с тем же nonce)
                                            RequestBody fileBody = RequestBody.create(MediaType.parse(audioMimeType), encryptedFileBytes);
                                            MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", audioFileName, fileBody);
                                            RequestBody nonceBody = RequestBody.create(MediaType.parse("text/plain"), fileNonceB64); // nonce файла -> file_metadata
                                            RequestBody messageIdBody = RequestBody.create(MediaType.parse("text/plain"), String.valueOf(serverMessageId));

                                            Call<FileUploadResponse> uploadCall = apiService.uploadEncryptedFile(filePart, nonceBody, messageIdBody);
                                            uploadCall.enqueue(new Callback<FileUploadResponse>() {
                                                @Override
                                                public void onResponse(Call<FileUploadResponse> call, Response<FileUploadResponse> response) {
                                                    if (response.isSuccessful() && response.body() != null) {
                                                        String fileUrl = response.body().getFileUrl();
                                                        serverMessage.setFileUrl(fileUrl);
                                                        serverMessage.setTemporary(false);
                                                        runOnUiThread(() -> {
                                                            for (int i = 0; i < messages.size(); i++) {
                                                                if (messages.get(i).getId() == tempId) {
                                                                    messages.set(i, serverMessage);
                                                                    messageAdapter.notifyItemChanged(i);
                                                                    scrollToBottom();
                                                                    Log.d(TAG, "Updated audio: tempId=" + tempId + " → serverId=" + serverMessageId + ", fileUrl=" + fileUrl + ", nonce=" + nonceB64);
                                                                    break;
                                                                }
                                                            }
                                                            resetForm();
                                                        });
                                                    } else {
                                                        Log.e(TAG, "Failed upload encrypted audio: code=" + response.code());
                                                        runOnUiThread(() -> {
                                                            updateTempMessageStatus(tempId, "Failed to upload audio");
                                                            Toast.makeText(SecretPrivateChatActivity.this, "Failed to upload audio", Toast.LENGTH_SHORT).show();
                                                        });
                                                    }
                                                }

                                                @Override
                                                public void onFailure(Call<FileUploadResponse> call, Throwable t) {
                                                    Log.e(TAG, "Error upload encrypted audio: " + t.getMessage());
                                                    runOnUiThread(() -> {
                                                        updateTempMessageStatus(tempId, "Failed to upload audio");
                                                        Toast.makeText(SecretPrivateChatActivity.this, "Error uploading audio", Toast.LENGTH_SHORT).show();
                                                    });
                                                }
                                            });
                                        } else {
                                            Log.e(TAG, "Failed to send audio message: code=" + response.code());
                                            runOnUiThread(() -> {
                                                updateTempMessageStatus(tempId, "Failed to send message");
                                                Toast.makeText(SecretPrivateChatActivity.this, "Failed to send audio message", Toast.LENGTH_SHORT).show();
                                            });
                                        }
                                    }

                                    @Override
                                    public void onFailure(Call<Message> call, Throwable t) {
                                        Log.e(TAG, "Error sending audio message: " + t.getMessage());
                                        runOnUiThread(() -> {
                                            updateTempMessageStatus(tempId, "Failed to send message");
                                            Toast.makeText(SecretPrivateChatActivity.this, "Failed to send audio message", Toast.LENGTH_SHORT).show();
                                        });
                                    }
                                });
                            } catch (Exception e) {
                                Log.e(TAG, "Error in audio encryption: " + e.getMessage());
                                runOnUiThread(() -> {
                                    updateTempMessageStatus(tempId, "Failed to process audio");
                                    Toast.makeText(SecretPrivateChatActivity.this, "Failed to process audio", Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
                    } else {
                        // Fallback: не secret — обычная загрузка (но для теста оставь, если нужно)
                        Log.w(TAG, "Chat is not secret, using non-encrypted upload");
                        RequestBody fileBody = RequestBody.create(MediaType.parse(audioMimeType), audioBytes);
                        MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", audioFileName, fileBody);
                        Call<FileUploadResponse> uploadCall = apiService.uploadFile(filePart);
                        uploadCall.enqueue(new Callback<FileUploadResponse>() {
                            @Override
                            public void onResponse(Call<FileUploadResponse> call, Response<FileUploadResponse> response) {
                                if (response.isSuccessful() && response.body() != null) {
                                    String fileUrl = response.body().getFileUrl();
                                    sendMessageWithFile("", fileUrl, "audio");
                                    if (audioFile != null && audioFile.exists()) {
                                        audioFile.delete();
                                    }
                                    audioFile = null;
                                    updateInputIcons();
                                } else {
                                    Toast.makeText(SecretPrivateChatActivity.this, "Failed to upload audio", Toast.LENGTH_SHORT).show();
                                    audioFile = null;
                                    updateInputIcons();
                                }
                            }

                            @Override
                            public void onFailure(Call<FileUploadResponse> call, Throwable t) {
                                Toast.makeText(SecretPrivateChatActivity.this, "Error uploading audio", Toast.LENGTH_SHORT).show();
                                audioFile = null;
                                updateInputIcons();
                            }
                        });
                    }
                }

                @Override
                public void onFailure(Call<Chat> call, Throwable t) {
                    Log.e(TAG, "Error checking chat for audio: " + t.getMessage());
                    Toast.makeText(SecretPrivateChatActivity.this, "Error checking chat", Toast.LENGTH_SHORT).show();
                    audioFile = null;
                    updateInputIcons();
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Error reading audio file: " + e.getMessage());
            Toast.makeText(this, "Error reading audio file", Toast.LENGTH_SHORT).show();
            audioFile = null;
            updateInputIcons();
        }
    }

    public void saveLocalMessageContent(long messageId, String content) {
        saveLocalMessageContent(messageId, content, null);
    }

    /** Сохраняет расшифрованный текст вместе с nonce сообщения (см. saveLocalFileMetadata). */
    public void saveLocalMessageContent(long messageId, String content, String nonce) {
        localMessageContent.put(messageId, content);
        SharedPreferences prefs = getSharedPreferences("local_message_content", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit().putString("content_" + messageId, content);
        if (nonce != null && !nonce.isEmpty()) {
            editor.putString("content_nonce_" + messageId, nonce);
        }
        editor.apply();
        Log.d(TAG, "Saved local content for messageId=" + messageId + ": " + content);
    }

    /**
     * Кэшированный текст, только если он относится к этому сообщению.
     * Если записанный nonce не совпадает — запись от другого сообщения с тем же
     * id, выбрасываем. Если nonce не записан (кэш до этой правки): для чужих
     * сообщений возвращаем null, чтобы текст расшифровался заново (это дёшево);
     * для своих — доверяем, у них другого источника текста нет.
     */
    public String getLocalMessageContent(long messageId, String expectedNonce, boolean ownMessage) {
        SharedPreferences prefs = getSharedPreferences("local_message_content", MODE_PRIVATE);
        String storedNonce = prefs.getString("content_nonce_" + messageId, null);

        if (storedNonce != null) {
            if (expectedNonce != null && !storedNonce.equals(expectedNonce)) {
                Log.w(TAG, "Local content for messageId=" + messageId + " belongs to another message, discarding");
                localMessageContent.remove(messageId);
                prefs.edit().remove("content_" + messageId).remove("content_nonce_" + messageId).apply();
                return null;
            }
            return getLocalMessageContent(messageId);
        }

        if (!ownMessage) {
            return null; // без отпечатка чужому кэшу не верим — перерасшифруем
        }
        return getLocalMessageContent(messageId);
    }

    public String getLocalMessageContent(long messageId) {
        String content = localMessageContent.getOrDefault(messageId, null);
        if (content == null) {
            SharedPreferences prefs = getSharedPreferences("local_message_content", MODE_PRIVATE);
            content = prefs.getString("content_" + messageId, null);
            if (content != null) {
                localMessageContent.put(messageId, content);
                Log.d(TAG, "Restored local content from SharedPreferences for messageId=" + messageId + ": " + content);
            }
        }
        Log.d(TAG, "Retrieved local content for messageId=" + messageId + ": " + content);
        return content;
    }

    private void subscribeToPartnerPublicKey() {
        if (!isBound || callService == null || stompClient == null || !stompClient.isConnected()) {
            Log.w(TAG, "Cannot subscribe to partner public key: StompClient not ready or not bound");
            return;
        }
        if (partnerId == -1) {
            Log.w(TAG, "Partner ID not available, subscription to public key deferred");
            return;
        }
        String topic = "/topic/publicKey/" + partnerId;
        stompClient.topic(topic).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(
                message -> {
                    try {
                        JSONObject json = new JSONObject(new String(message.getPayload()));
                        String updatedPublicKey = json.getString("publicKey");
                        cachePublicKey(partnerId, updatedPublicKey);
                        Log.d(TAG, "Received updated public key for partnerId=" + partnerId + ": " + updatedPublicKey);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing public key update: " + e.getMessage());
                    }
                },
                throwable -> Log.e(TAG, "Error subscribing to public key for partnerId=" + partnerId + ": " + throwable.getMessage())
        );
        Log.d(TAG, "Subscribed to public key updates for partnerId=" + partnerId);
    }

    private CompletableFuture<String> fetchPublicKeyByIdAsync(long publicKeyId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Log.d(TAG, "Fetching public key by publicKeyId: " + publicKeyId);
        apiService.getPublicKeyById(publicKeyId).enqueue(new Callback<PublicKeyResponse>() {
            @Override
            public void onResponse(Call<PublicKeyResponse> call, Response<PublicKeyResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().getPublicKey() != null) {
                    String publicKey = response.body().getPublicKey();
                    Log.d(TAG, "Successfully fetched public key for publicKeyId: " + publicKeyId + ", publicKeyBase64=" + publicKey);
                    // Кэшируем ключ по publicKeyId и senderId
                    cachePublicKeyById(publicKeyId, publicKey);
                    future.complete(publicKey);
                } else {
                    String errorMsg = "Failed to fetch public key for publicKeyId: " + publicKeyId + ", code=" + response.code();
                    Log.e(TAG, errorMsg);
                    future.completeExceptionally(new Exception(errorMsg));
                }
            }

            @Override
            public void onFailure(Call<PublicKeyResponse> call, Throwable t) {
                Log.e(TAG, "Error fetching public key for publicKeyId: " + publicKeyId + ", error=" + t.getMessage());
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private void cachePublicKeyById(long publicKeyId, String publicKey) {
        getSharedPreferences(PUBLIC_KEY_PREFS, MODE_PRIVATE).edit()
                .putString("public_key_by_id_" + publicKeyId, publicKey)
                .apply();
        Log.d(TAG, "Cached public key for publicKeyId=" + publicKeyId + ": " + publicKey);
    }

    private String getCachedPublicKeyById(long publicKeyId) {
        String key = getSharedPreferences(PUBLIC_KEY_PREFS, MODE_PRIVATE)
                .getString("public_key_by_id_" + publicKeyId, null);
        if (key != null) {
            Log.d(TAG, "Retrieved cached public key for publicKeyId=" + publicKeyId + ": " + key);
        }
        return key;
    }

    private void sendTypingEvent(boolean isTyping) {
        if (stompClient == null || !stompClient.isConnected()) {
            Log.w(TAG, "Cannot send typing: Stomp not connected");
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("messageType", isTyping ? "TYPING" : "STOP_TYPING");
            payload.put("sender", new JSONObject()
                    .put("id", currentUserId)
                    .put("username", username));
            payload.put("chat", new JSONObject()
                    .put("id", chatId));

            String destination = "/app/typing/" + chatId;

            stompClient.send(destination, payload.toString())
                    .subscribe(
                            () -> Log.d(TAG, "Typing sent: " + (isTyping ? "TYPING" : "STOP_TYPING")),
                            throwable -> Log.e(TAG, "Failed to send typing", throwable)
                    );
        } catch (Exception e) {
            Log.e(TAG, "Error preparing typing JSON", e);
        }
    }

    private void showTypingIndicator(String name) {
        currentTypingName = name;
        if (tvTyping != null) {
            tvTyping.setText(name + " печатает…");
            tvTyping.setVisibility(View.VISIBLE);
            Log.d(TAG, "Showing typing: " + name);
        }
    }

    private void hideTypingIndicator() {
        if (tvTyping != null) {
            tvTyping.setVisibility(View.GONE);
        }
        currentTypingName = null;
        Log.d(TAG, "Hiding typing indicator");
    }

    private void registerTypingReceiver() {
        if (typingReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(typingReceiver);
        }

        typingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.example.unlimitedpossibilities12.TYPING_EVENT".equals(intent.getAction())) {
                    long receivedChatId = intent.getLongExtra("chatId", -1);
                    Log.d(TAG, "Typing event received for chatId=" + receivedChatId + ", current chatId=" + chatId);
                    if (receivedChatId == chatId) {
                        String type = intent.getStringExtra("type");
                        String name = intent.getStringExtra("username");
                        int senderId = intent.getIntExtra("senderId", -1);

                        if (senderId != currentUserId) {
                            if ("TYPING".equals(type)) {
                                showTypingIndicator(name != null ? name : "Собеседник");
                                if (typingHideRunnable != null) {
                                    typingHandler.removeCallbacks(typingHideRunnable);
                                }
                                typingHideRunnable = SecretPrivateChatActivity.this::hideTypingIndicator;
                                typingHandler.postDelayed(typingHideRunnable, TYPING_VISIBLE_DURATION_MS);
                            } else if ("STOP_TYPING".equals(type)) {
                                hideTypingIndicator();
                            }
                        }
                    }
                }
            }
        };

        LocalBroadcastManager.getInstance(this).registerReceiver(
                typingReceiver,
                new IntentFilter("com.example.unlimitedpossibilities12.TYPING_EVENT")
        );
        Log.d(TAG, "Typing receiver registered for chatId=" + chatId);
    }

    private void unregisterTypingReceiver() {
        if (typingReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(typingReceiver);
            typingReceiver = null;
            Log.d(TAG, "Typing receiver unregistered");
        }
    }

    private void updatePartnerStatusIndicator() {
        View statusIndicator = findViewById(R.id.partner_status_indicator);
        if (statusIndicator != null) {
            Boolean isOnline = messageAdapter.getUserOnlineStatus(partnerId);
            boolean visible = isOnline != null && isOnline;
            statusIndicator.setVisibility(visible ? View.VISIBLE : View.GONE);
            Log.d(TAG, "Updated partner status indicator in toolbar: partnerId=" + partnerId + ", online=" + visible);
        }
    }
}