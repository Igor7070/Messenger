package com.example.unlimitedpossibilities12.messenger;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextWatcher;
import android.media.MediaRecorder;
import android.content.pm.PackageManager;
import android.Manifest;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.CallRequest;
import com.example.unlimitedpossibilities12.api.Chat;
import com.example.unlimitedpossibilities12.api.FileUploadResponse;
import com.example.unlimitedpossibilities12.api.GenerateMessageRequest;
import com.example.unlimitedpossibilities12.api.Message;
import com.example.unlimitedpossibilities12.api.MessageNotification;
import com.example.unlimitedpossibilities12.api.MessageRequest;
import com.example.unlimitedpossibilities12.api.TranslationUpdateRequest;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;
import com.example.unlimitedpossibilities12.messenger.translate_and_spell_check.PrivateChatTranslationAndSpellCheckHelper;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.appcompat.app.AlertDialog;
import android.content.ClipboardManager;

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

public class PrivateChatActivity extends AppCompatActivity implements MessageAdapter.OnMessageActionListener {
    private android.content.BroadcastReceiver socketReadyReceiver;
    private static final String TAG = "PrivateChatActivity";
    private static final String ACTION_SHOW_CALL_DIALOG = "com.example.unlimitedpossibilities12.SHOW_CALL_DIALOG";
    private static final String ACTION_NEW_MESSAGE = "com.example.unlimitedpossibilities12.NEW_MESSAGE";
    private static final String ACTION_END_CALL = "com.example.unlimitedpossibilities12.END_CALL";
    private static final String USER_STATUS = "com.example.unlimitedpossibilities12.USER_STATUS";
    private static final String EXTRA_USER_ID = "userId";
    private static final String EXTRA_ONLINE = "online";
    private static final int FILE_PICKER_REQUEST_CODE = 1;
    private long partnerId = -1; // ID собеседника
    private TextView chatNameTextView;
    private TextView chatStatusTextView; // строка под именем: online / печатает / режимы
    private String partnerUsername; // нужно для звонка из шапки
    private EditText inputMessage;
    private RecyclerView messageList;
    private MessageAdapter messageAdapter;
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
    private static final long STATUS_CHECK_DEBOUNCE_MS = 5000; // 5 секунд
    private boolean isMessageJustSent = false; // Флаг для прокрутки после отправки
    // Добавлено: Переменные для записи аудио
    private static final int AUDIO_PERMISSION_REQUEST_CODE = 100;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private File audioFile;
    // Добавлено: Переменные для перевода
    private TextView translationTextView; // TextView для индикатора языка
    private ImageView translationIconView; // ImageView для иконки глобуса
    private boolean isTranslationEnabled;
    private String translationLanguage;
    private TextView spellCheckIndicator;
    private boolean isSpellCheckEnabled;
    private TextView spellCheckIconView; // Новый TextView для эмодзи ✍️
    private String spellCheckEnabledTimestamp; // Добавлено: временная метка включения орфографии
    private List<Message> translationQueue = new ArrayList<>();
    private boolean isTranslating = false;
    // ДОБАВЛЕНО: Переменные для TTS
    private TextView ttsIndicator;
    private ImageView ttsIconView;
    private boolean isTTSEnabled;
    private TextView attachedFileTextView; // Для отображения имени прикреплённого файла
    private TranslationSettingsFragment.TranslationMode translationMode = TranslationSettingsFragment.TranslationMode.ALL;
    // ДОБАВЛЕНО: Переменные для транскрипции
    private TextView transcriptionIndicator;
    private ImageView transcriptionIconView;
    private boolean isTranscriptionEnabled;
    private TranscriptionSettingsFragment.TranscriptionMode transcriptionMode = TranscriptionSettingsFragment.TranscriptionMode.ALL;
    private List<Message> transcriptionQueue = new ArrayList<>();
    private boolean isTranscribing = false;
    // Для индикатора "печатает…"
    private long lastTypingSentTime = 0L;
    private final long TYPING_DEBOUNCE_MS = 2400L;          // отправляем не чаще раза в ~2.4 сек
    private final long TYPING_VISIBLE_DURATION_MS = 2500L; // индикатор живёт 2.5 сек

    private final Handler typingHandler = new Handler(Looper.getMainLooper());
    private Runnable typingHideRunnable;

    private String currentTypingName = null;  // имя того, кто печатает
    private TextView tvTyping;
    private Drawable aiSparkleDrawable;

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CallService.LocalBinder binder = (CallService.LocalBinder) service;
            callService = binder.getService();
            isBound = true;
            Log.d(TAG, "Connected to CallService");
            initStompClient();
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
        setContentView(R.layout.activity_private_chat);

        // Настройка Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        // Отключить стандартный заголовок Toolbar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        // Запрещаем поворот экрана
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);

        apiService = ApiClient.getApiService();
        chatNameTextView = findViewById(R.id.chat_name_text_view);
        inputMessage = findViewById(R.id.input_message);
        messageList = findViewById(R.id.message_list);
        attachedFileTextView = findViewById(R.id.attached_file_text_view);

        translationTextView = findViewById(R.id.translation_indicator);
        translationIconView = findViewById(R.id.translation_icon);
        spellCheckIndicator = findViewById(R.id.spell_check_indicator);
        spellCheckIconView = findViewById(R.id.spell_check_icon); // Новый TextView для эмодзи
        // ДОБАВЛЕНО: Инициализация иконки TTS и индикатора
        ttsIndicator = findViewById(R.id.tts_indicator);
        ttsIconView = findViewById(R.id.tts_icon);
        // ДОБАВЛЕНО: Инициализация иконки транскрипции и индикатора
        transcriptionIndicator = findViewById(R.id.transcription_indicator);
        transcriptionIconView = findViewById(R.id.transcription_icon);

        tvTyping = findViewById(R.id.tv_typing);
        chatStatusTextView = findViewById(R.id.chat_status_text_view);

        // Звонки прямо из переписки: раньше позвонить можно было только со
        // страницы человека, в самом чате кнопок не было вовсе
        ImageView voiceCallButton = findViewById(R.id.toolbar_voice_call);
        ImageView videoCallButton = findViewById(R.id.toolbar_video_call);
        ImageView overflowButton = findViewById(R.id.toolbar_overflow);
        if (voiceCallButton != null) voiceCallButton.setOnClickListener(v -> startCallFromHeader("voice"));
        if (videoCallButton != null) videoCallButton.setOnClickListener(v -> startCallFromHeader("video"));
        if (overflowButton != null) overflowButton.setOnClickListener(this::showChatMenu);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        messageList.setLayoutManager(layoutManager);
        messageAdapter = new MessageAdapter(this, messages, -1, this, isTranslationEnabled, isTranscriptionEnabled);
        messageList.setAdapter(messageAdapter);

        // Иконка ассистента — по центру панели иконок
        ImageView assistantIcon = findViewById(R.id.assistant_icon);
        assistantIcon.setOnClickListener(v -> {
            AssistantChatDialogFragment dialog = new AssistantChatDialogFragment();
            dialog.show(getSupportFragmentManager(), "AssistantChatDialog");
        });

        // Обработка клика на иконку перевода
        if (translationIconView != null) {
            translationIconView.setOnClickListener(v -> {
                TranslationSettingsFragment fragment = new TranslationSettingsFragment((enabled, language, mode) -> {
                    isTranslationEnabled = enabled;
                    translationLanguage = language;
                    translationMode = mode;
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean("translation_enabled_" + chatId, enabled);
                    editor.putString("translation_language_" + chatId, language);
                    editor.putString("translation_mode_" + chatId, mode.name());
                    if (enabled) {
                        String enabledTimestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                        editor.putString("translation_enabled_timestamp_" + chatId, enabledTimestamp);
                        Log.d(TAG, "Saved translation enabled timestamp: " + enabledTimestamp);
                    } else {
                        editor.remove("translation_enabled_timestamp_" + chatId);
                        Log.d(TAG, "Removed translation enabled timestamp");
                    }
                    editor.apply();
                    PrivateChatTranslationAndSpellCheckHelper.updateTranslationIndicator(this, translationTextView, isTranslationEnabled, translationLanguage);
        updateHeaderStatus();
                    PrivateChatTranslationAndSpellCheckHelper.updateTranslationIcon(this, translationIconView, isTranslationEnabled);
                    messageAdapter.setTranslationEnabled(enabled);
                    if (!enabled) {
                        // Очищаем переводы только если перевод полностью отключен
                        PrivateChatTranslationAndSpellCheckHelper.clearTranslations(this, messages, chatId, translationQueue, messageAdapter);
                    } else if (mode != TranslationSettingsFragment.TranslationMode.SELECTIVELY) {
                        // Переводим сообщения только в режимах ALL или NEW_ONLY
                        PrivateChatTranslationAndSpellCheckHelper.translateExistingMessages(
                                this, messages, isTranslationEnabled, translationLanguage,
                                mode == TranslationSettingsFragment.TranslationMode.NEW_ONLY,
                                chatId, translationQueue, messageAdapter, () -> translateNextMessage()
                        );
                    }
                    // **ДОБАВЛЕНО**: Обновляем видимость иконок перевода для всех сообщений
                    messageAdapter.notifyDataSetChanged();
                    Log.d(TAG, "Translation settings applied: enabled=" + enabled + ", language=" + language + ", mode=" + mode);
                });
                Bundle args = new Bundle();
                args.putLong("chatId", chatId);
                fragment.setArguments(args);
                fragment.show(getSupportFragmentManager(), "TranslationSettings");
            });
        } else {
            Log.e(TAG, "translationIconView is null");
        }

        // Изменено: добавлено сохранение временной метки для орфографии
        if (spellCheckIconView != null) {
            spellCheckIconView.setOnClickListener(v -> {
                isSpellCheckEnabled = !isSpellCheckEnabled;
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean("spell_check_enabled_" + chatId, isSpellCheckEnabled);
                if (isSpellCheckEnabled) {
                    spellCheckEnabledTimestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                    editor.putString("spell_check_enabled_timestamp_" + chatId, spellCheckEnabledTimestamp);
                    Log.d(TAG, "Saved spell check enabled timestamp: " + spellCheckEnabledTimestamp);
                } else {
                    spellCheckEnabledTimestamp = null;
                    editor.remove("spell_check_enabled_timestamp_" + chatId);
                    Log.d(TAG, "Removed spell check enabled timestamp");
                }
                editor.apply();
                PrivateChatTranslationAndSpellCheckHelper.updateSpellCheckIndicator(this, spellCheckIndicator, isSpellCheckEnabled);
        updateHeaderStatus();
                PrivateChatTranslationAndSpellCheckHelper.updateSpellCheckIcon(this, spellCheckIconView, isSpellCheckEnabled);
                Log.d(TAG, "Spell check toggled: enabled=" + isSpellCheckEnabled);
            });
        } else {
            Log.e(TAG, "spellCheckIconView is null");
        }

        // Обработка клика на иконку TTS
        if (ttsIconView != null) {
            ttsIconView.setOnClickListener(v -> {
                isTTSEnabled = !isTTSEnabled;
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean("tts_enabled_" + chatId, isTTSEnabled);
                editor.apply();
                updateTTSIndicator();
                updateTTSIcon();
                updateHeaderStatus();
                messageAdapter.setTTSEnabled(isTTSEnabled); // Обновляем адаптер
                messageAdapter.notifyDataSetChanged(); // Обновляем отображение иконок TTS
                Log.d(TAG, "TTS toggled: enabled=" + isTTSEnabled);
            });
        } else {
            Log.e(TAG, "ttsIconView is null");
        }

        // Обработка клика на иконку транскрипции
        if (transcriptionIconView != null) {
            transcriptionIconView.setOnClickListener(v -> {
                TranscriptionSettingsFragment fragment = new TranscriptionSettingsFragment((enabled, mode) -> {
                    isTranscriptionEnabled = enabled;
                    transcriptionMode = mode;
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean("transcription_enabled_" + chatId, enabled);
                    editor.putString("transcription_mode_" + chatId, mode.name());
                    if (enabled) {
                        String enabledTimestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
                        editor.putString("transcription_enabled_timestamp_" + chatId, enabledTimestamp);
                        Log.d(TAG, "Saved transcription enabled timestamp: " + enabledTimestamp);
                    } else {
                        editor.remove("transcription_enabled_timestamp_" + chatId);
                        Log.d(TAG, "Removed transcription enabled timestamp");
                    }
                    editor.apply();
                    PrivateChatTranslationAndSpellCheckHelper.updateTranscriptionIndicator(this, transcriptionIndicator, isTranscriptionEnabled);
        updateHeaderStatus();
                    PrivateChatTranslationAndSpellCheckHelper.updateTranscriptionIcon(this, transcriptionIconView, isTranscriptionEnabled);
                    messageAdapter.setTranscriptionEnabled(isTranscriptionEnabled);
                    if (!enabled) {
                        // Очищаем транскрипции, если функция отключена
                        PrivateChatTranslationAndSpellCheckHelper.clearTranscriptions(this, messages, chatId, messageAdapter);
                    } else if (mode != TranscriptionSettingsFragment.TranscriptionMode.SELECTIVELY) {
                        // Транскрибируем существующие аудиосообщения в режимах ALL или NEW_ONLY
                        PrivateChatTranslationAndSpellCheckHelper.transcribeExistingMessages(
                                this, messages, isTranscriptionEnabled, transcriptionMode, chatId, transcriptionQueue, messageAdapter, () -> transcribeNextMessage()
                        );
                    }
                    messageAdapter.notifyDataSetChanged();
                    Log.d(TAG, "Transcription settings applied: enabled=" + enabled + ", mode=" + mode);
                });
                Bundle args = new Bundle();
                args.putLong("chatId", chatId);
                fragment.setArguments(args);
                fragment.show(getSupportFragmentManager(), "TranscriptionSettings");
            });
        } else {
            Log.e(TAG, "transcriptionIconView is null");
        }

        chatId = getIntent().getLongExtra("chatId", -1);
        username = getSharedPreferences("prefs", MODE_PRIVATE).getString("username", "");
        currentUserId = getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);

        // Загрузка настроек перевода
        isTranslationEnabled = prefs.getBoolean("translation_enabled_" + chatId, false);
        translationLanguage = prefs.getString("translation_language_" + chatId, "auto");
        translationMode = TranslationSettingsFragment.TranslationMode.valueOf(
                prefs.getString("translation_mode_" + chatId, TranslationSettingsFragment.TranslationMode.ALL.name())
        );
        PrivateChatTranslationAndSpellCheckHelper.updateTranslationIndicator(this, translationTextView, isTranslationEnabled, translationLanguage);
        updateHeaderStatus();
        PrivateChatTranslationAndSpellCheckHelper.updateTranslationIcon(this, translationIconView, isTranslationEnabled);

        // Загрузка настроек орфографии
        isSpellCheckEnabled = prefs.getBoolean("spell_check_enabled_" + chatId, false);
        spellCheckEnabledTimestamp = prefs.getString("spell_check_enabled_timestamp_" + chatId, null);
        PrivateChatTranslationAndSpellCheckHelper.updateSpellCheckIndicator(this, spellCheckIndicator, isSpellCheckEnabled);
        updateHeaderStatus();
        PrivateChatTranslationAndSpellCheckHelper.updateSpellCheckIcon(this, spellCheckIconView, isSpellCheckEnabled);

        // Загрузка настроек TTS
        isTTSEnabled = prefs.getBoolean("tts_enabled_" + chatId, false);
        updateTTSIndicator();
        updateTTSIcon();

        // Загрузка настроек транскрипции
        isTranscriptionEnabled = prefs.getBoolean("transcription_enabled_" + chatId, false);
        transcriptionMode = TranscriptionSettingsFragment.TranscriptionMode.valueOf(
                prefs.getString("transcription_mode_" + chatId, TranscriptionSettingsFragment.TranscriptionMode.ALL.name())
        );
        PrivateChatTranslationAndSpellCheckHelper.updateTranscriptionIndicator(this, transcriptionIndicator, isTranscriptionEnabled);
        updateHeaderStatus();
        PrivateChatTranslationAndSpellCheckHelper.updateTranscriptionIcon(this, transcriptionIconView, isTranscriptionEnabled);

        if (chatId == -1 || username.isEmpty()) {
            Log.e(TAG, "Invalid parameters: chatId=" + chatId + ", username=" + username);
            Toast.makeText(this, "Invalid chat or user data", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        validateUserAndFetchData();

        findViewById(android.R.id.content).setOnClickListener(v -> userInteracted = true);

        attachedFileTextView.setOnClickListener(v -> {
            if (selectedFileUri != null) {
                selectedFileUri = null;
                attachedFileTextView.setVisibility(View.GONE);
                updateInputIcons(); // Обновляем иконки после открепления
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

        // Инициализация AI-иконки и обновление
        aiSparkleDrawable = ContextCompat.getDrawable(this, R.drawable.ic_ai_sparkle);
        if (aiSparkleDrawable != null) {
            aiSparkleDrawable.setTint(Color.parseColor("#757575"));
        }
        updateInputIcons();  // Обновляем сразу, чтобы AI появилась при запуске

        // Добавлено: Обработчик нажатия на иконку скрепки
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
                            sendDrawable.setTint(Color.parseColor("#01579B")); // Более тёмный оттенок синего
                            inputMessage.setCompoundDrawablesWithIntrinsicBounds(currentAttachmentDrawable, null, sendDrawable, null);
                            inputMessage.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start(); // Добавляем анимацию масштабирования
                        }
                        Log.d(TAG, "Send icon pressed (ACTION_DOWN)");
                    }
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                inputMessage.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start(); // Восстанавливаем масштаб
                updateInputIcons(); // Восстанавливаем иконки
                if (currentAttachmentDrawable != null &&
                        event.getRawX() <= (inputMessage.getLeft() + attachmentWidth + inputMessage.getPaddingStart())) {
                    Log.d(TAG, "Attachment icon clicked");
                    if (!isRecording) {
                        openFilePicker();
                        return true;
                    }
                    return true;
                }
            }
            return false;
        });

        // Изменено: Обновляем TextWatcher для управления только иконкой микрофона
        inputMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                Log.d(TAG, "Text changed, length=" + s.length() + ", isRecording=" + isRecording + ", selectedFileUri=" + selectedFileUri);
                updateInputIcons();

                // ← Добавляем только этот блок в конец метода
                String text = s != null ? s.toString().trim() : "";
                long now = System.currentTimeMillis();

                if (!text.isEmpty() && now - lastTypingSentTime >= TYPING_DEBOUNCE_MS) {
                    sendTypingEvent(true);
                    lastTypingSentTime = now;
                }
            }
        });

        // Обработка кликов по отдельным иконкам AI и mic
        ImageView aiIcon = findViewById(R.id.ai_assist_icon);
        ImageView micIcon = findViewById(R.id.mic_icon);

        if (aiIcon != null) {
            aiIcon.setOnClickListener(v -> {
                showAiMessageGeneratorDialog();
                Log.d(TAG, "AI assist icon clicked");
            });
        }

        if (micIcon != null) {
            micIcon.setOnClickListener(v -> {
                if (isRecording) {
                    stopRecording();
                } else {
                    requestAudioPermission();
                }
                Log.d(TAG, "Mic icon clicked");
            });
        }

        ImageView sendIcon = findViewById(R.id.send_icon);
        if (sendIcon != null) {
            sendIcon.setOnClickListener(v -> {
                // 1. Анимация нажатия на саму иконку (как было раньше)
                v.animate()
                        .scaleX(0.85f)  // чуть сильнее уменьшение для заметности
                        .scaleY(0.85f)
                        .setDuration(80)
                        .withEndAction(() -> {
                            v.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(100)
                                    .start();
                        })
                        .start();

                // 2. Лёгкое "подрагивание" поля ввода (очень ненавязчиво)
                inputMessage.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(60)
                        .withEndAction(() -> {
                            inputMessage.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(80)
                                    .start();
                        })
                        .start();

                // 3. Отправка сообщения
                sendMessage();
                Log.d(TAG, "Send icon clicked");
            });
        }

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
                    Log.d(TAG, "Received message broadcast: receivedChatId=" + receivedChatId + ", current chatId=" + chatId);
                    if (receivedChatId == chatId) {
                        String messageJson = intent.getStringExtra("messageJson");
                        Log.d(TAG, "Received message JSON for chatId=" + chatId + ": " + messageJson);
                        try {
                            JSONObject msgJson = new JSONObject(messageJson);
                            Log.d(TAG, "Parsed JSON fields: id=" + msgJson.optLong("id") +
                                    ", content=" + msgJson.optString("content", "<null>") +
                                    ", delivered_status=" + msgJson.optBoolean("delivered_status", false) +
                                    ", read_status=" + msgJson.optBoolean("read_status", false) +
                                    ", fileUrl=" + msgJson.optString("fileUrl", "<null>") +
                                    ", sender=" + (msgJson.has("sender") && !msgJson.isNull("sender") ?
                                    msgJson.getJSONObject("sender").toString() : "<null>") +
                                    ", timestamp=" + msgJson.optString("timestamp", "<null>"));

                            Message newMessage = new Message();
                            newMessage.setId(msgJson.getLong("id"));
                            newMessage.setContent(msgJson.optString("content", ""));

                            User sender = new User();
                            if (msgJson.has("sender") && !msgJson.isNull("sender")) {
                                JSONObject senderJson = msgJson.getJSONObject("sender");
                                sender.setId(senderJson.getInt("id"));
                                sender.setUsername(senderJson.optString("username", "Unknown"));
                                sender.setAvatar(senderJson.optString("avatar", null));
                            } else {
                                sender.setId(-1);
                                sender.setUsername("Unknown");
                                Log.w(TAG, "Sender is null for message id=" + newMessage.getId());
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

                            String messageType = msgJson.optString("messageType", null); // **ДОБАВЛЕНО**: Извлечение messageType
                            if (messageType == null || messageType.isEmpty()) {
                                String fileUrl = msgJson.optString("fileUrl", null);
                                messageType = (fileUrl != null && fileUrl.endsWith(".webm")) ? "audio" : "text"; // **ДОБАВЛЕНО**: Определение типа по fileUrl
                                Log.w(TAG, "messageType is null/empty for id=" + newMessage.getId() + ", set to: " + messageType); // **ДОБАВЛЕНО**: Логирование
                            }
                            newMessage.setMessageType(messageType); // **ДОБАВЛЕНО**: Установка messageType
                            Log.d(TAG, "Parsed message: id=" + newMessage.getId() + ", messageType=" + messageType + ", fileUrl=" + newMessage.getFileUrl()); // **ДОБАВЛЕНО**: Логирование

                            updateMessages(newMessage);
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
                Log.d(TAG, "Received END_CALL broadcast with intent: " + intent.toString());
                String roomId = intent.getStringExtra("roomId");
                Log.d(TAG, "END_CALL for roomId: " + roomId + ", incomingCall: " + (incomingCall != null ? incomingCall.getRoomId() : "null"));
                if (roomId == null) {
                    Log.e(TAG, "roomId is null in END_CALL broadcast");
                    return;
                }
                if (incomingCall != null && roomId.equals(incomingCall.getRoomId())) {
                    Log.d(TAG, "Processing endCall for matching roomId: " + roomId);
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
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(PrivateChatActivity.this, "The caller cancelled the call.", Toast.LENGTH_LONG).show();
                        Log.d(TAG, "Toast shown for roomId: " + roomId);
                    }
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
                if (USER_STATUS.equals(intent.getAction())) {
                    long userId = intent.getLongExtra(EXTRA_USER_ID, -1);
                    boolean online = intent.getBooleanExtra(EXTRA_ONLINE, false);
                    Log.d(TAG, "Received USER_STATUS broadcast: userId=" + userId + ", online=" + online);
                    if (userId == partnerId) {
                        messageAdapter.updateUserStatus(userId, online);
                        showPartnerOnline(online); // та же точка, но у аватара в шапке
                        Log.d(TAG, "Updating adapter status for partnerId=" + userId);
                    }
                }
            }
        };
    }

    /** Зелёная точка у аватара в шапке: собеседник в сети. */
    private void showPartnerOnline(boolean online) {
        View dot = findViewById(R.id.partner_status_dot);
        if (dot != null) dot.setVisibility(online ? View.VISIBLE : View.GONE);
    }

    private void requestPartnerStatus() {
        if (isBound && callService != null && partnerId != -1) {
            Log.d(TAG, "Requesting status for partnerId=" + partnerId);
            callService.requestUserStatus(partnerId);
        } else {
            Log.w(TAG, "Cannot request partner status: isBound=" + isBound + ", partnerId=" + partnerId);
        }
    }

    private void validateUserAndFetchData() {
        apiService.getUserByUsername(username).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    currentUserId = response.body().getId();
                    Log.d(TAG, "Validated user: id=" + currentUserId + ", username=" + username);
                    getSharedPreferences("prefs", MODE_PRIVATE)
                            .edit()
                            .putInt("userId", currentUserId)
                            .putString("username", username)
                            .apply();
                    // Исправлено: Синхронизация isTranslationEnabled перед созданием адаптера
                    SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
                    isTranslationEnabled = prefs.getBoolean("translation_enabled_" + chatId, false);
                    messageAdapter = new MessageAdapter(PrivateChatActivity.this, messages, currentUserId, PrivateChatActivity.this, isTranslationEnabled, isTranscriptionEnabled);
                    messageList.setAdapter(messageAdapter);
                    fetchChatData();
                } else {
                    Log.e(TAG, "Failed to validate user: code=" + response.code());
                    Toast.makeText(PrivateChatActivity.this, "Failed to validate user", Toast.LENGTH_LONG).show();
                    finish();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Log.e(TAG, "Error validating user: " + t.getMessage());
                Toast.makeText(PrivateChatActivity.this, "Error validating user: " + t.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // Существующие регистрации ресиверов (не меняем)
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(callReceiver,
                new IntentFilter(ACTION_SHOW_CALL_DIALOG));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(messageReceiver,
                new IntentFilter(ACTION_NEW_MESSAGE));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(endCallReceiver,
                new IntentFilter(ACTION_END_CALL));
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(userStatusReceiver,
                new IntentFilter(USER_STATUS));

        // ← Перерегистрируем typing-ресивер с текущим chatId
        registerTypingReceiver();

        Log.d(TAG, "Receivers registered in onStart, including endCallReceiver for " + ACTION_END_CALL +
                " and TYPING_EVENT");  // ← НОВОЕ: добавлено в лог для удобства отладки

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

        // ← Отменяем typing-ресивер
        unregisterTypingReceiver();

        Log.d(TAG, "Receivers unregistered in onStop, including endCallReceiver");
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
                Log.d(TAG, "MediaPlayer released in onDestroy");
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error releasing MediaPlayer in onDestroy: " + e.getMessage());
        }
        messageAdapter.stopCurrentTTS();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(callReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(messageReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(endCallReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(userStatusReceiver);
        if (typingReceiver != null) {
            LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(typingReceiver);
            typingReceiver = null;
        }
        Log.d(TAG, "Receivers unregistered in onDestroy");
        handler.removeCallbacksAndMessages(null);
    }


    /**
     * Звонок собеседнику прямо из переписки.
     *
     * Вид звонка определяет и экран, и то, что уходит на сервер: "voice",
     * "video" или "translate" — последний с переводом речи.
     */
    private void startCallFromHeader(String type) {
        if (currentUserId == -1 || partnerId == -1 || partnerUsername == null) {
            Toast.makeText(this, "Cannot find who to call", Toast.LENGTH_SHORT).show();
            return;
        }
        String roomId = "room-" + currentUserId + "-" + partnerId;
        CallRequest request = new CallRequest((int) currentUserId, (int) partnerId, roomId,
                getSharedPreferences("prefs", MODE_PRIVATE).getString("username", "Unknown"), type);
        apiService.initiateCall(request).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (!response.isSuccessful()) {
                    Toast.makeText(PrivateChatActivity.this, "Failed to start the call", Toast.LENGTH_SHORT).show();
                    return;
                }
                Class<?> screen = "video".equals(type) ? VideoCallActivity.class
                        : "translate".equals(type) ? VoiceCallWithTranslateActivity.class
                        : VoiceCallActivity.class;
                Intent intent = new Intent(PrivateChatActivity.this, screen);
                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                intent.putExtra("recipientId", String.valueOf(partnerId));
                intent.putExtra("roomId", roomId);
                intent.putExtra("callerName", request.getCallerName());
                intent.putExtra("recipientName", partnerUsername);
                intent.putExtra("isCaller", true);
                startActivity(intent);
                Toast.makeText(PrivateChatActivity.this,
                        "translate".equals(type) ? "Calling " + partnerUsername + " with translation..."
                                : "Calling " + partnerUsername + "...",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Call failed: " + t.getMessage());
                Toast.makeText(PrivateChatActivity.this, "Failed to start the call", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Меню шапки. Переключатели режимов переехали сюда с постоянной полосы:
     * их трогают редко, а место они занимали всегда. Нажатие пересылается
     * прежним вьюхам — вся логика осталась в их обработчиках, здесь ничего
     * не продублировано.
     */
    private void showChatMenu(View anchor) {
        PopupMenu menu = new PopupMenu(
                new android.view.ContextThemeWrapper(this, R.style.CustomPopupTheme), anchor);
        menu.getMenu().add(0, 1, 0, "Call with translation");
        menu.getMenu().add(0, 2, 1, "Translation…");
        menu.getMenu().add(0, 3, 2, "Transcription…");
        menu.getMenu().add(0, 4, 3, isTTSEnabled ? "Read aloud: on" : "Read aloud: off");
        menu.getMenu().add(0, 5, 4, isSpellCheckEnabled ? "Spellcheck: on" : "Spellcheck: off");
        menu.getMenu().add(0, 6, 5, "View profile");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: startCallFromHeader("translate"); return true;
                case 2: if (translationIconView != null) translationIconView.performClick(); return true;
                case 3: if (transcriptionIconView != null) transcriptionIconView.performClick(); return true;
                case 4: if (ttsIconView != null) ttsIconView.performClick(); return true;
                case 5: if (spellCheckIconView != null) spellCheckIconView.performClick(); return true;
                case 6:
                    if (partnerId != -1) {
                        Intent profile = new Intent(PrivateChatActivity.this, UserProfileActivity.class);
                        profile.putExtra("userId", String.valueOf(partnerId));
                        startActivity(profile);
                    }
                    return true;
                default: return false;
            }
        });
        menu.show();
    }

    /**
     * Строка под именем: сначала состояние собеседника, затем метки включённых
     * режимов. Прежде включённое показывала полоса во всю ширину экрана.
     */
    private void updateHeaderStatus() {
        if (chatStatusTextView == null) return;
        StringBuilder line = new StringBuilder();
        if (isTranslationEnabled && translationLanguage != null && !translationLanguage.isEmpty()) {
            line.append(translationLanguage.toUpperCase());
        }
        if (isTranscriptionEnabled) {
            if (line.length() > 0) line.append(" · ");
            line.append("TEXT");
        }
        if (isTTSEnabled) {
            if (line.length() > 0) line.append(" · ");
            line.append("AUDIO");
        }
        if (isSpellCheckEnabled) {
            if (line.length() > 0) line.append(" · ");
            line.append("SC");
        }
        if (line.length() == 0) {
            chatStatusTextView.setVisibility(View.GONE);
        } else {
            chatStatusTextView.setText(line.toString());
            chatStatusTextView.setVisibility(View.VISIBLE);
        }
    }

    private void fetchChatData() {
        Log.d(TAG, "Fetching chat data for chatId=" + chatId);
        apiService.getChatById(chatId).enqueue(new Callback<Chat>() {
            @Override
            public void onResponse(Call<Chat> call, Response<Chat> response) {
                if (response.isSuccessful() && response.body() != null) {
                    chatName = response.body().getName();
                    String partnerName = getChatPartnerName(chatName);
                    partnerUsername = partnerName;
                    chatNameTextView.setText(partnerName);
                    Log.d(TAG, "Chat fetched: name=" + chatName + ", displayed as: Chat with " + partnerName);
                    // Получить ID собеседника
                    apiService.getUserByUsername(partnerName).enqueue(new Callback<User>() {
                        @Override
                        public void onResponse(Call<User> call, Response<User> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                partnerId = response.body().getId();
                                Log.d(TAG, "Partner fetched: id=" + partnerId + ", username=" + partnerName);

                                // ────────────────────────────────────────────────
                                // Загрузка аватара партнёра в заголовок (точно как в MessageAdapter)
                                String avatarUrl = response.body().getAvatar();
                                ImageView partnerAvatar = findViewById(R.id.partner_avatar);
                                if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
                                    Glide.with(PrivateChatActivity.this)
                                            .load("https://unlimitedpossibilities12.org" + avatarUrl)
                                            .apply(new RequestOptions().circleCrop()
                                                    .placeholder(new LetterAvatarDrawable(
                                                            partnerName.isEmpty() ? "?" : partnerName.substring(0, 1).toUpperCase()))
                                                    .error(new LetterAvatarDrawable(
                                                            partnerName.isEmpty() ? "?" : partnerName.substring(0, 1).toUpperCase())))
                                            .into(partnerAvatar);
                                } else {
                                    // Если аватара нет — первая буква (как в адаптере)
                                    String initial = partnerName.substring(0, 1).toUpperCase();
                                    LetterAvatarDrawable avatarDrawable = new LetterAvatarDrawable(initial);
                                    partnerAvatar.setImageDrawable(avatarDrawable);
                                }
                                // Клик по аватару в заголовке → переход в профиль собеседника (как в сообщениях)
                                if (partnerId != -1) {
                                    partnerAvatar.setOnClickListener(v -> {
                                        int userIdForProfile = (int) partnerId;

                                        Intent profileIntent = new Intent(PrivateChatActivity.this, UserProfileActivity.class);
                                        profileIntent.putExtra("userId", userIdForProfile);
                                        startActivity(profileIntent);
                                        Log.d(TAG, "Открыт профиль по клику на аватар в заголовке: userId(long)=" + partnerId + ", передан как int=" + userIdForProfile);
                                    });
                                } else {
                                    Log.w(TAG, "partnerId = -1, клик по аватару в заголовке отключён");
                                }
                                // ────────────────────────────────────────────────

                                // Запросить начальный статус собеседника
                                requestPartnerStatus();
                            } else {
                                Log.e(TAG, "Failed to fetch partner: code=" + response.code());
                            }
                        }

                        @Override
                        public void onFailure(Call<User> call, Throwable t) {
                            Log.e(TAG, "Error fetching partner: " + t.getMessage());
                        }
                    });
                } else {
                    Log.e(TAG, "Failed to fetch chat: code=" + response.code() + ", message=" + response.message());
                    Toast.makeText(PrivateChatActivity.this, "Failed to fetch chat data", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<Chat> call, Throwable t) {
                Log.e(TAG, "Error fetching chat: " + t.getMessage());
                Toast.makeText(PrivateChatActivity.this, "Error fetching chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        apiService.getMessagesByChatId(chatId).enqueue(new Callback<List<Message>>() {
            @Override
            public void onResponse(Call<List<Message>> call, Response<List<Message>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Log.d(TAG, "Messages onResponse: isTranslationEnabled=" + isTranslationEnabled + ", translationMode=" + translationMode);
                    messages.clear();
                    for (Message msg : response.body()) {
                        // Добавлено: Установка Chat для сообщений в fetchChatData
                        if (msg.getChat() == null) {
                            msg.setChat(new Chat(chatId, chatName));
                            Log.d(TAG, "Set chat for message id=" + msg.getId() + ": chatId=" + chatId);
                        }
                        Log.d(TAG, "Fetched message id=" + msg.getId() + ", delivered=" + msg.isDelivered() +
                                ", read=" + msg.isRead() + ", senderId=" + msg.getSender().getId() +
                                ", timestamp=" + msg.getTimestamp());
                        messages.add(msg);
                    }
                    // Восстановление локальных переводов только если перевод включён
                    if (isTranslationEnabled || isTranscriptionEnabled) {
                        SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
                        for (Message message : messages) {
                            // Восстановление переводов
                            if (isTranslationEnabled) {
                                String translationKey = "translation_" + chatId + "_" + message.getId();
                                if (prefs.contains(translationKey)) {
                                    String savedTranslation = prefs.getString(translationKey, null);
                                    String savedLanguage = prefs.getString(translationKey + "_language", null);
                                    if (savedTranslation != null && savedLanguage != null) {
                                        message.setTranslatedContent(savedTranslation);
                                        message.setTranslationLanguage(savedLanguage);
                                        Log.d(TAG, "Restored translation for message id=" + message.getId() + ": " + savedTranslation);
                                    }
                                }
                            }
                            // ДОБАВЛЕНО: Восстановление транскрипций
                            if (isTranscriptionEnabled && "audio".equals(message.getMessageType())) {
                                String transcriptionKey = "transcription_" + chatId + "_" + message.getId();
                                if (prefs.contains(transcriptionKey)) {
                                    String savedTranscription = prefs.getString(transcriptionKey, null);
                                    if (savedTranscription != null) {
                                        message.setTranscribedContent(savedTranscription);
                                        Log.d(TAG, "Restored transcription for message id=" + message.getId() + ": " + savedTranscription);
                                    }
                                }
                            }
                        }
                    }
                    messageAdapter.updateMessages(messages);
                    jumpToBottom();
                    markAllMessagesAsRead();
                    scheduleStatusCheck();
                    Log.d(TAG, "Messages fetched: count=" + messages.size());
                    // Добавлено: Запуск перевода при включённом isTranslationEnabled
                    if (isTranslationEnabled && translationMode != TranslationSettingsFragment.TranslationMode.SELECTIVELY) {
                        List<Message> translatedMessages = new ArrayList<>();
                        PrivateChatTranslationAndSpellCheckHelper.translateExistingMessages(
                                PrivateChatActivity.this, messages, isTranslationEnabled, translationLanguage,
                                translationMode == TranslationSettingsFragment.TranslationMode.NEW_ONLY,
                                chatId, translationQueue, messageAdapter, () ->
                                        PrivateChatTranslationAndSpellCheckHelper.translateNextMessage(
                                                translationQueue, isTranslating, () -> translateNextMessage(),
                                                messageAdapter, messages, PrivateChatActivity.this, translatedMessages
                                        )
                        );
                    }
                } else {
                    Log.e(TAG, "Failed to fetch messages: code=" + response.code() + ", message=" + response.message());
                    Toast.makeText(PrivateChatActivity.this, "Failed to fetch messages", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<List<Message>> call, Throwable t) {
                Log.e(TAG, "Error fetching messages: " + t.getMessage());
                Toast.makeText(PrivateChatActivity.this, "Error fetching messages: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String getChatPartnerName(String chatName) {
        String[] names = chatName.split("_");
        for (String name : names) {
            if (!name.equals(username)) {
                return name;
            }
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
            return;
        }

        Log.d(TAG, "Using StompClient from CallService");
    }

    private void updateMessages(Message newMessage) {
        runOnUiThread(() -> {
            if (newMessage == null || newMessage.getSender() == null) {
                Log.w(TAG, "Received null message or sender");
                return;
            }
            if (newMessage.getChat() == null) {
                newMessage.setChat(new Chat(chatId, chatName));
                Log.d(TAG, "Set chat for message id=" + newMessage.getId() + ": chatId=" + chatId);
            }
            Log.d(TAG, "Updating message: id=" + newMessage.getId() + ", content=" +
                    (newMessage.getContent() != null ? newMessage.getContent() : "<null>") +
                    ", delivered=" + newMessage.isDelivered() + ", read=" + newMessage.isRead() +
                    ", timestamp=" + newMessage.getTimestamp() +
                    ", senderId=" + newMessage.getSender().getId() +
                    ", messageType=" + newMessage.getMessageType() +
                    ", isDeleted=" + "Message deleted".equals(newMessage.getContent()));
            if ("Message deleted".equals(newMessage.getContent())) {
                boolean removed = messages.removeIf(msg -> msg.getId() == newMessage.getId());
                Log.d(TAG, "Message with id=" + newMessage.getId() + " removed (Message deleted), success=" + removed);
                messageAdapter.updateMessages(messages);
            } else {
                int existingIndex = -1;
                for (int i = 0; i < messages.size(); i++) {
                    if (messages.get(i).getId() == newMessage.getId()) {
                        existingIndex = i;
                        break;
                    }
                }
                boolean isNewMessage = existingIndex == -1;
                if (existingIndex != -1) {
                    messages.set(existingIndex, newMessage);
                    Log.d(TAG, "Updated message with id=" + newMessage.getId());
                } else {
                    messages.add(newMessage);
                    Log.d(TAG, "Added new message with id=" + newMessage.getId());
                }
                // Синхронизация настроек с SharedPreferences
                SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
                isTTSEnabled = prefs.getBoolean("tts_enabled_" + chatId, false);
                isTranslationEnabled = prefs.getBoolean("translation_enabled_" + chatId, false);
                isTranscriptionEnabled = prefs.getBoolean("transcription_enabled_" + chatId, false);
                translationLanguage = prefs.getString("translation_language_" + chatId, "auto");
                String translationModeStr = prefs.getString("translation_mode_" + chatId, "ALL");
                try {
                    translationMode = TranslationSettingsFragment.TranslationMode.valueOf(translationModeStr);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid translation mode: " + translationModeStr + ", defaulting to ALL");
                    translationMode = TranslationSettingsFragment.TranslationMode.ALL;
                }
                messageAdapter.setTTSEnabled(isTTSEnabled);
                messageAdapter.setTranslationEnabled(isTranslationEnabled);
                messageAdapter.setTranscriptionEnabled(isTranscriptionEnabled);
                Log.d(TAG, "Synced settings: isTTSEnabled=" + isTTSEnabled +
                        ", isTranslationEnabled=" + isTranslationEnabled +
                        ", isTranscriptionEnabled=" + isTranscriptionEnabled +
                        ", translationLanguage=" + translationLanguage +
                        ", translationMode=" + translationMode +
                        " for message id=" + newMessage.getId());

                // Очистка перевода для аудиосообщений
                if ("audio".equals(newMessage.getMessageType())) {
                    newMessage.setTranslatedContent(null);
                    newMessage.setTranslationLanguage(null);
                    Log.d(TAG, "Cleared translatedContent and translationLanguage for audio message id=" + newMessage.getId());
                }

                // Перевод нового или обновлённого сообщения
                if (isTranslationEnabled && newMessage.getTranslatedContent() == null &&
                        translationMode != TranslationSettingsFragment.TranslationMode.SELECTIVELY &&
                        PrivateChatTranslationAndSpellCheckHelper.isMessageNew(this, newMessage, translationMode == TranslationSettingsFragment.TranslationMode.NEW_ONLY, chatId)) {
                    if (isTranslating) {
                        translationQueue.add(newMessage);
                        Log.d(TAG, "Added message id=" + newMessage.getId() + " to translation queue");
                    } else {
                        isTranslating = true;
                        PrivateChatTranslationAndSpellCheckHelper.translateMessage(
                                PrivateChatActivity.this,
                                newMessage,
                                translationLanguage,
                                chatId,
                                messageAdapter,
                                messages,
                                new ArrayList<>(),
                                () -> {
                                    isTranslating = false;
                                    translateNextMessage();
                                }
                        );
                        Log.d(TAG, "Started translation for message id=" + newMessage.getId() + " to language=" + translationLanguage);
                    }
                }
                // Транскрипция нового или обновлённого аудиосообщения
                if (isTranscriptionEnabled && "audio".equals(newMessage.getMessageType()) &&
                        newMessage.getTranscribedContent() == null &&
                        transcriptionMode != TranscriptionSettingsFragment.TranscriptionMode.SELECTIVELY &&
                        PrivateChatTranslationAndSpellCheckHelper.isMessageNewerThanTimestamp(newMessage, prefs.getString("transcription_enabled_timestamp_" + chatId, null))) {
                    if (isTranscribing) {
                        transcriptionQueue.add(newMessage);
                        Log.d(TAG, "Added message id=" + newMessage.getId() + " to transcription queue");
                    } else {
                        isTranscribing = true;
                        PrivateChatTranslationAndSpellCheckHelper.transcribeMessage(
                                this, newMessage, chatId, messageAdapter, messages, apiService, () -> {
                                    isTranscribing = false;
                                    transcribeNextMessage();
                                }
                        );
                        Log.d(TAG, "Started transcription for message id=" + newMessage.getId());
                    }
                }
                messageAdapter.updateMessages(messages);
                messageAdapter.notifyDataSetChanged();
                if (isMessageJustSent || (isNewMessage && newMessage.getSender().getId() != currentUserId)) {
                    Log.d(TAG, "Scrolling to bottom: isMessageJustSent=" + isMessageJustSent +
                            ", isNewMessage=" + isNewMessage + ", senderId=" + newMessage.getSender().getId());
                    scrollToBottom();
                } else {
                    Log.d(TAG, "Skipping scroll: isMessageJustSent=" + isMessageJustSent +
                            ", isNewMessage=" + isNewMessage + ", senderId=" + newMessage.getSender().getId());
                }
                if (newMessage.getSender().getId() != currentUserId && !newMessage.isRead()) {
                    markMessageAsRead(newMessage);
                }
                isMessageJustSent = false;
            }
        });
    }

    private void updateInputIcons() {
        ImageView aiIcon = findViewById(R.id.ai_assist_icon);
        ImageView micIcon = findViewById(R.id.mic_icon);
        ImageView sendIcon = findViewById(R.id.send_icon);

        if (aiIcon == null || micIcon == null || sendIcon == null) {
            Log.w(TAG, "AI, mic or send icon not found");
            return;
        }

        if (isRecording) {
            // Запись: только красный кружок, AI и send скрыты
            micIcon.setImageResource(R.drawable.ic_recording);
            micIcon.setColorFilter(Color.parseColor("#EF4444")); // ← Ярко-красный
            micIcon.setVisibility(View.VISIBLE);
            aiIcon.setVisibility(View.GONE);
            sendIcon.setVisibility(View.GONE);
        } else if (inputMessage.getText().length() > 0 || selectedFileUri != null) {
            // Есть текст или файл: показываем send, скрываем AI и mic
            aiIcon.setVisibility(View.GONE);
            micIcon.setVisibility(View.GONE);
            sendIcon.setVisibility(View.VISIBLE);
        } else {
            // Пустое поле: AI + mic, send скрыт
            aiIcon.setVisibility(View.VISIBLE);
            micIcon.setVisibility(View.VISIBLE);
            sendIcon.setVisibility(View.GONE);
            micIcon.setImageResource(R.drawable.ic_mic);
            micIcon.setColorFilter(Color.parseColor("#757575"));
        }
    }

    private void markMessageAsRead(Message message) {
        if (stompClient != null && stompClient.isConnected()) {
            stompClient.send("/app/readMessage/" + chatId, String.valueOf(message.getId()))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            () -> {
                                Log.d(TAG, "Read message sent for id=" + message.getId());
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
            Log.d(TAG, "Skipping status check due to debounce");
            handler.postDelayed(this::checkMessagesStatus, STATUS_CHECK_DEBOUNCE_MS - (currentTime - lastStatusCheckTime));
        }
    }

    private void checkMessagesStatus() {
        Log.d(TAG, "Checking status for messages of user: " + currentUserId);
        for (Message msg : messages) {
            if (msg.getSender().getId() == currentUserId && (!msg.isDelivered() || !msg.isRead())) {
                apiService.getMessageById(msg.getId()).enqueue(new Callback<Message>() {
                    @Override
                    public void onResponse(Call<Message> call, Response<Message> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            Message updatedMessage = response.body();
                            Log.d(TAG, "Message " + updatedMessage.getId() + ": delivered=" + updatedMessage.isDelivered() +
                                    ", read=" + updatedMessage.isRead() + ", timestamp=" + updatedMessage.getTimestamp());
                            updateMessages(updatedMessage);
                        } else {
                            Log.w(TAG, "Failed to fetch status for message " + msg.getId() + ": code=" + response.code());
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

    private void checkMessageStatus(Message message, int retryCount) {
        if (retryCount >= 3) {
            Log.e(TAG, "Max retries reached for message: " + message.getContent());
            runOnUiThread(() -> Toast.makeText(PrivateChatActivity.this, "Unable to verify message delivery", Toast.LENGTH_SHORT).show());
            return;
        }
        Log.d(TAG, "Checking message status for content: " + message.getContent() + ", tempId: " + message.getId() + ", attempt: " + (retryCount + 1));
        apiService.searchMessage(message.getContent(), currentUserId, chatId).enqueue(new Callback<Message>() {
            @Override
            public void onResponse(Call<Message> call, Response<Message> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Message serverMessage = response.body();
                    long serverMessageId = serverMessage.getId();
                    Log.d(TAG, "Found message ID: " + serverMessageId);
                    apiService.getMessageById(serverMessageId).enqueue(new Callback<Message>() {
                        @Override
                        public void onResponse(Call<Message> call, Response<Message> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                Message updatedMessage = response.body();
                                // Исправлено: Устанавливаем объект Chat
                                if (updatedMessage.getChat() == null) {
                                    updatedMessage.setChat(new Chat(chatId, chatName));
                                    Log.d(TAG, "Set chat for updated message id=" + updatedMessage.getId() + ": chatId=" + chatId);
                                }
                                Log.d(TAG, "Message " + updatedMessage.getId() + ": delivered=" + updatedMessage.isDelivered() +
                                        ", read=" + updatedMessage.isRead() + ", timestamp=" + updatedMessage.getTimestamp());
                                updateMessages(updatedMessage);
                                if (!updatedMessage.isRead()) {
                                    Log.d(TAG, "Message not read, fetching recipient info");
                                    String[] names = chatName.split("_");
                                    String recipientName = names[0].equals(username) ? names[1] : names[0];
                                    Log.d(TAG, "Recipient name: " + recipientName);
                                    apiService.getUserByUsername(recipientName).enqueue(new Callback<User>() {
                                        @Override
                                        public void onResponse(Call<User> call, Response<User> response) {
                                            if (response.isSuccessful() && response.body() != null) {
                                                int recipientId = response.body().getId();
                                                Log.d(TAG, "Recipient ID: " + recipientId);
                                                MessageNotification notification = new MessageNotification(
                                                        currentUserId, recipientId, message.getContent(), chatId);
                                                apiService.notifyMessage(notification).enqueue(new Callback<Void>() {
                                                    @Override
                                                    public void onResponse(Call<Void> call, Response<Void> response) {
                                                        if (response.isSuccessful()) {
                                                            Log.d(TAG, "Notification sent successfully");
                                                        } else {
                                                            Log.e(TAG, "Failed to send notification: code=" + response.code());
                                                        }
                                                    }

                                                    @Override
                                                    public void onFailure(Call<Void> call, Throwable t) {
                                                        Log.e(TAG, "Error sending notification: " + t.getMessage());
                                                    }
                                                });
                                            } else {
                                                Log.e(TAG, "Failed to fetch recipient: code=" + response.code());
                                            }
                                        }

                                        @Override
                                        public void onFailure(Call<User> call, Throwable t) {
                                            Log.e(TAG, "Error fetching recipient by username: " + t.getMessage());
                                        }
                                    });
                                } else {
                                    Log.d(TAG, "Message already read, skipping notification");
                                }
                            } else {
                                Log.e(TAG, "Failed to fetch message status: code=" + response.code());
                            }
                        }

                        @Override
                        public void onFailure(Call<Message> call, Throwable t) {
                            Log.e(TAG, "Error checking message status: " + t.getMessage());
                            handler.postDelayed(() -> checkMessageStatus(message, retryCount + 1), 3000);
                        }
                    });
                } else {
                    Log.w(TAG, "Message not found, retrying after delay: code=" + response.code());
                    handler.postDelayed(() -> checkMessageStatus(message, retryCount + 1), 3000);
                }
            }

            @Override
            public void onFailure(Call<Message> call, Throwable t) {
                Log.e(TAG, "Error searching message: " + t.getMessage());
                handler.postDelayed(() -> checkMessageStatus(message, retryCount + 1), 3000);
            }
        });
    }

    /**
     * Сразу в конец переписки — так открывается чат. Плавная прокрутка через
     * весь список выглядит рывком: лента успевает промелькнуть серединой.
     */
    private void jumpToBottom() {
        if (messages.size() > 0) {
            messageList.scrollToPosition(messages.size() - 1);
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
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
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
                // Отображаем имя файла с крестиком
                SpannableString spannable = new SpannableString(fileName + " ✕");
                spannable.setSpan(new ForegroundColorSpan(Color.RED), fileName.length() + 1, spannable.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                attachedFileTextView.setText(spannable);
                attachedFileTextView.setVisibility(View.VISIBLE);
                updateInputIcons(); // Обновляем иконки (показываем иконку отправки)
                Toast.makeText(this, "File selected: " + fileName, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void sendMessage() {
        final String content = inputMessage.getText().toString().trim(); // Изменено: добавлено final
        if (content.isEmpty() && selectedFileUri == null && audioFile == null) {
            Toast.makeText(this, "Content is missing", Toast.LENGTH_SHORT).show();
            return;
        }

        // Добавлено: Проверка орфографии и определение типа сообщения
        final String messageType; // Изменено: объявляем как final
        if (selectedFileUri != null) {
            String mimeType = getContentResolver().getType(selectedFileUri);
            if (mimeType != null && mimeType.startsWith("audio/")) {
                messageType = "audio";
            } else {
                messageType = "file";
            }
        } else {
            messageType = "text";
        }

        // Изменено: Проверка орфографии только для текстовых сообщений
        if (isSpellCheckEnabled && spellCheckEnabledTimestamp != null && !content.isEmpty() && messageType.equals("text")) {
            PrivateChatTranslationAndSpellCheckHelper.spellCheckMessage(this, content, apiService, new Callback<String>() {
                @Override
                public void onResponse(Call<String> call, Response<String> response) {
                    final String finalContent = response.isSuccessful() && response.body() != null ?
                            response.body() : content; // Изменено: добавлено final
                    apiService.checkChatExists(currentUserId, chatId).enqueue(new Callback<ResponseBody>() {
                        @Override
                        public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                            try {
                                if (response.isSuccessful() && response.body() != null) {
                                    String result = response.body().string();
                                    Log.d(TAG, "Check chat response: " + result);
                                    if (result.equals("Chat exists in user's private chats")) {
                                        proceedWithSendingMessage(finalContent, messageType); // Используем finalContent
                                    } else {
                                        Log.w(TAG, "Chat does not exist for user: " + currentUserId + ", chatId: " + chatId);
                                        Toast.makeText(PrivateChatActivity.this, "Chat not found", Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    Log.e(TAG, "Failed to check chat: code=" + response.code() + ", message=" + response.message());
                                    Toast.makeText(PrivateChatActivity.this, "Error checking chat", Toast.LENGTH_SHORT).show();
                                }
                            } catch (IOException e) {
                                Log.e(TAG, "Error reading response: " + e.getMessage());
                                Toast.makeText(PrivateChatActivity.this, "Error reading response: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<ResponseBody> call, Throwable t) {
                            Log.e(TAG, "Error checking chat: " + t.getMessage());
                            Toast.makeText(PrivateChatActivity.this, "Error checking chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onFailure(Call<String> call, Throwable t) {
                    Log.e(TAG, "Spell check failed, sending original content: " + t.getMessage());
                    apiService.checkChatExists(currentUserId, chatId).enqueue(new Callback<ResponseBody>() {
                        @Override
                        public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                            try {
                                if (response.isSuccessful() && response.body() != null) {
                                    String result = response.body().string();
                                    Log.d(TAG, "Check chat response: " + result);
                                    if (result.equals("Chat exists in user's private chats")) {
                                        proceedWithSendingMessage(content, messageType); // Используем content
                                    } else {
                                        Log.w(TAG, "Chat does not exist for user: " + currentUserId + ", chatId: " + chatId);
                                        Toast.makeText(PrivateChatActivity.this, "Chat not found", Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    Log.e(TAG, "Failed to check chat: code=" + response.code() + ", message=" + response.message());
                                    Toast.makeText(PrivateChatActivity.this, "Error checking chat", Toast.LENGTH_SHORT).show();
                                }
                            } catch (IOException e) {
                                Log.e(TAG, "Error reading response: " + e.getMessage());
                                Toast.makeText(PrivateChatActivity.this, "Error reading response: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<ResponseBody> call, Throwable t) {
                            Log.e(TAG, "Error checking chat: " + t.getMessage());
                            Toast.makeText(PrivateChatActivity.this, "Error checking chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        } else {
            apiService.checkChatExists(currentUserId, chatId).enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                    try {
                        if (response.isSuccessful() && response.body() != null) {
                            String result = response.body().string();
                            Log.d(TAG, "Check chat response: " + result);
                            if (result.equals("Chat exists in user's private chats")) {
                                proceedWithSendingMessage(content, messageType); // Используем content
                            } else {
                                Log.w(TAG, "Chat does not exist for user: " + currentUserId + ", chatId: " + chatId);
                                Toast.makeText(PrivateChatActivity.this, "Chat not found", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Log.e(TAG, "Failed to check chat: code=" + response.code() + ", message=" + response.message());
                            Toast.makeText(PrivateChatActivity.this, "Error checking chat", Toast.LENGTH_SHORT).show();
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading response: " + e.getMessage());
                        Toast.makeText(PrivateChatActivity.this, "Error reading response: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onFailure(Call<ResponseBody> call, Throwable t) {
                    Log.e(TAG, "Error checking chat: " + t.getMessage());
                    Toast.makeText(PrivateChatActivity.this, "Error checking chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void proceedWithSendingMessage(String content, String messageType) {
        if (selectedFileUri != null) {
            try {
                String fileName = null;
                String mimeType = null;
                try {
                    Cursor cursor = getContentResolver().query(selectedFileUri, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIndex != -1) {
                            fileName = cursor.getString(nameIndex);
                        }
                        cursor.close();
                    }
                    mimeType = getContentResolver().getType(selectedFileUri);
                    if (mimeType == null) {
                        if (fileName != null) {
                            String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
                            switch (extension) {
                                case "jpg":
                                case "jpeg":
                                    mimeType = "image/jpeg";
                                    break;
                                case "png":
                                    mimeType = "image/png";
                                    break;
                                case "pdf":
                                    mimeType = "application/pdf";
                                    break;
                                case "mp3": // Изменено: Поддержка .mp3 вместо .webm
                                    mimeType = "audio/mpeg";
                                    break;
                                default:
                                    mimeType = "application/octet-stream";
                            }
                        } else {
                            mimeType = "application/octet-stream";
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not get file name or MIME type: " + e.getMessage());
                }

                if (fileName == null) {
                    fileName = "uploaded_file_" + System.currentTimeMillis() +
                            (mimeType.startsWith("image/") ? ".jpg" :
                                    mimeType.equals("application/pdf") ? ".pdf" :
                                            mimeType.startsWith("audio/") ? ".mp3" : ".bin"); // Изменено: Поддержка .webm
                }

                InputStream inputStream = getContentResolver().openInputStream(selectedFileUri);
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead);
                }
                inputStream.close();
                byte[] bytes = byteArrayOutputStream.toByteArray();

                RequestBody fileBody = RequestBody.create(MediaType.parse(mimeType), bytes);
                MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", fileName, fileBody);
                Call<FileUploadResponse> call = apiService.uploadFile(filePart);

                Log.d(TAG, "Uploading file: name=" + fileName + ", mimeType=" + mimeType + ", size=" + bytes.length);

                call.enqueue(new Callback<FileUploadResponse>() {
                    @Override
                    public void onResponse(Call<FileUploadResponse> call, Response<FileUploadResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            String fileUrl = response.body().getFileUrl();
                            Log.d(TAG, "File uploaded successfully, fileUrl=" + fileUrl);
                            sendMessageWithFile(content, fileUrl, messageType); // Изменено: Передаём messageType
                        } else {
                            final String errorBody = getErrorBody(response);
                            Log.e(TAG, "Failed to upload file: code=" + response.code() +
                                    ", message=" + response.message() + ", errorBody=" + errorBody);
                            runOnUiThread(() -> Toast.makeText(PrivateChatActivity.this,
                                    "Failed to upload file: " + errorBody,
                                    Toast.LENGTH_SHORT).show());
                        }
                    }

                    @Override
                    public void onFailure(Call<FileUploadResponse> call, Throwable t) {
                        Log.e(TAG, "Error uploading file: " + t.getMessage(), t);
                        runOnUiThread(() -> Toast.makeText(PrivateChatActivity.this,
                                "Error uploading file: " + t.getMessage(),
                                Toast.LENGTH_SHORT).show());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error preparing file for upload: " + e.getMessage(), e);
                runOnUiThread(() -> Toast.makeText(this, "Error preparing file: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        } else {
            sendMessageWithFile(content, null, messageType); // Изменено: Передаём messageType
        }
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
        message.setContent(content);
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
        message.setMessageType(messageType); // Добавлено: Установка типа сообщения

        if (stompClient != null && stompClient.isConnected()) {
            try {
                JSONObject jsonMessage = new JSONObject();
                jsonMessage.put("id", message.getId());
                jsonMessage.put("content", message.getContent());
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
                jsonMessage.put("messageType", messageType); // Добавлено: Добавление messageType в JSON

                String destination = "/app/sendMessage/chat/" + chatId;
                Log.d(TAG, "Sending message to: " + destination + ", payload: " + jsonMessage.toString());
                isMessageJustSent = true;
                stompClient.send(destination, jsonMessage.toString())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> {
                                    Log.d(TAG, "Message sent successfully to " + destination);
                                    handler.postDelayed(() -> checkMessageStatus(message, 0), 3000);
                                    resetForm();
                                    sendTypingEvent(false); //Отправка события что печатание окончено, так как отправлено сообщение
                                },
                                throwable -> {
                                    Log.e(TAG, "Error sending message: " + throwable.getMessage());
                                    runOnUiThread(() -> Toast.makeText(PrivateChatActivity.this, "Failed to send message", Toast.LENGTH_SHORT).show());
                                    isMessageJustSent = false;
                                }
                        );
            } catch (Exception e) {
                Log.e(TAG, "Error preparing message JSON: " + e.getMessage());
                Toast.makeText(this, "Error preparing message: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                isMessageJustSent = false;
            }
        } else {
            Log.w(TAG, "StompClient not connected");
            Toast.makeText(this, "WebSocket not connected", Toast.LENGTH_SHORT).show();
            isMessageJustSent = false;
        }
    }

    private Message parseMessageFromJson(JSONObject msgJson) throws Exception {
        Message newMessage = new Message();
        newMessage.setId(msgJson.getLong("id"));
        newMessage.setContent(msgJson.optString("content", ""));
        User sender = new User();
        if (msgJson.has("sender") && !msgJson.isNull("sender")) {
            JSONObject senderJson = msgJson.getJSONObject("sender");
            sender.setId(senderJson.getInt("id"));
            sender.setUsername(senderJson.optString("username", "Unknown"));
            sender.setAvatar(senderJson.optString("avatar", null));
        } else {
            sender.setId(-1);
            sender.setUsername("Unknown");
            Log.w(TAG, "Sender is null for message id=" + newMessage.getId());
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
        newMessage.setMessageType(msgJson.optString("messageType", "text")); // Добавлено: Извлечение messageType
        return newMessage;
    }

    private void resetForm() {
        inputMessage.setText("");
        selectedFileUri = null;
        editingMessageId = null;
        if (audioFile != null && audioFile.exists()) {
            audioFile.delete();
            audioFile = null; // ДОБАВЛЕНО: Сбрасываем audioFile
        }
        attachedFileTextView.setVisibility(View.GONE); // Скрываем индикатор файла
        updateInputIcons(); // Восстанавливаем иконки
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
                                    Log.d(TAG, "Delete message sent for id=" + message.getId());
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
                Log.w(TAG, "StompClient not connected for delete");
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
                if (stompClient != null && stompClient.isConnected()) {
                    stompClient.send("/app/readMessage/" + chatId, String.valueOf(msg.getId()))
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(
                                    () -> {
                                        Log.d(TAG, "Read message sent for id=" + msg.getId());
                                        msg.setRead(true);
                                        messageAdapter.updateMessages(messages);
                                    },
                                    throwable -> Log.e(TAG, "Error marking message as read: " + throwable.getMessage())
                            );
                }
            }
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
                                        intent = new Intent(PrivateChatActivity.this, VideoCallActivity.class);
                                        break;
                                    case "translate":
                                        intent = new Intent(PrivateChatActivity.this, VoiceCallWithTranslateActivity.class);
                                        break;
                                    case "voice_secure":  // ← ДОБАВЬ ЭТОТ CASE!
                                        intent = new Intent(PrivateChatActivity.this, SecureVoiceCallActivity.class);
                                        break;
                                    case "voice":
                                    default:
                                        intent = new Intent(PrivateChatActivity.this, VoiceCallActivity.class);
                                        break;
                                }
                                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                                intent.putExtra("recipientId", String.valueOf(incomingCall.getCallerId()));
                                intent.putExtra("roomId", incomingCall.getRoomId());
                                intent.putExtra("isCaller", false);
                                intent.putExtra("callerName", callerName);
                                intent.putExtra("recipientName", incomingCall.getCallerName());
                                startActivity(intent);
                                Toast.makeText(PrivateChatActivity.this,
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Добавлено: проверка chatId в новом Intent
        long newChatId = intent.getLongExtra("chatId", -1);
        Log.d(TAG, "onNewIntent: received chatId=" + newChatId + ", current chatId=" + chatId);
        if (newChatId != -1 && newChatId != chatId) {
            chatId = newChatId;
            messages.clear();
            messageAdapter.updateMessages(messages);
            fetchChatData();
            Log.d(TAG, "Switched to new chatId=" + chatId);
        }
        // Существующая логика для вызовов
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
        fetchChatData(); // Обновляем сообщения при разворачивании
        // ← Перерегистрируем typing-ресивер КАЖДЫЙ раз при показе активности
        registerTypingReceiver();
        Log.d(TAG, "Typing receiver перерегистрирован в onResume для chatId=" + chatId);
    }

    private String generateCurrentTimestamp() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    // Добавлено: Создание меню перевода
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    private void translateNextMessage() {
        // Добавлено: Инициализация списка переведённых сообщений
        List<Message> translatedMessages = new ArrayList<>();
        PrivateChatTranslationAndSpellCheckHelper.translateNextMessage(translationQueue, isTranslating, () -> {
            isTranslating = true;
            if (!translationQueue.isEmpty()) {
                Message message = translationQueue.remove(0);
                // Изменено: Передаём translatedMessages
                PrivateChatTranslationAndSpellCheckHelper.translateMessage(this, message, translationLanguage, chatId, messageAdapter, messages, translatedMessages, () -> {
                    isTranslating = false;
                    translateNextMessage();
                });
            } else {
                isTranslating = false;
            }
        }, messageAdapter, messages, this, translatedMessages);
    }

    // Добавлено: Проверка и запрос разрешения для записи аудио
    private void requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST_CODE);
        } else {
            startRecording();
        }
    }

    // Добавлено: Обработка результата запроса разрешения
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                Toast.makeText(this, "Audio recording permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // Добавлено: Начало записи аудио
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
            updateInputIcons(); // Изменено: Используем updateInputIcons
            Log.d(TAG, "Recording started");
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Error starting recording: " + e.getMessage());
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
            updateInputIcons(); // Изменено: Используем updateInputIcons
        }
    }

    // Добавлено: Остановка записи аудио
    private void stopRecording() {
        if (mediaRecorder != null && isRecording) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                updateInputIcons(); // Изменено: Используем updateInputIcons
                Log.d(TAG, "Recording stopped");
                Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
                sendAudioMessage();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recording: " + e.getMessage());
                Toast.makeText(this, "Failed to stop recording", Toast.LENGTH_SHORT).show();
                updateInputIcons(); // Изменено: Используем updateInputIcons
                audioFile = null;
            }
        }
    }

    // Добавлено: Отправка аудиосообщения
    private void sendAudioMessage() {
        if (audioFile == null || !audioFile.exists()) {
            Toast.makeText(this, "No audio recorded", Toast.LENGTH_SHORT).show();
            updateInputIcons(); // Изменено: Используем updateInputIcons
            audioFile = null;
            return;
        }

        try {
            byte[] audioBytes = Files.readAllBytes(audioFile.toPath());
            RequestBody fileBody = RequestBody.create(MediaType.parse("audio/mpeg"), audioBytes);
            MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", audioFile.getName(), fileBody);
            Call<FileUploadResponse> call = apiService.uploadFile(filePart);
            Log.d(TAG, "Uploading audio file: " + audioFile.getName());
            call.enqueue(new Callback<FileUploadResponse>() {
                @Override
                public void onResponse(Call<FileUploadResponse> call, Response<FileUploadResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        String fileUrl = response.body().getFileUrl();
                        Log.d(TAG, "Audio uploaded, fileUrl=" + fileUrl);
                        sendMessageWithFile("", fileUrl, "audio");
                        updateInputIcons(); // Изменено: Используем updateInputIcons
                        if (audioFile != null && audioFile.exists()) {
                            audioFile.delete();
                        }
                        audioFile = null;
                    } else {
                        Log.e(TAG, "Failed to upload audio: code=" + response.code());
                        Toast.makeText(PrivateChatActivity.this, "Failed to upload audio", Toast.LENGTH_SHORT).show();
                        updateInputIcons(); // Изменено: Используем updateInputIcons
                        audioFile = null;
                    }
                }

                @Override
                public void onFailure(Call<FileUploadResponse> call, Throwable t) {
                    Log.e(TAG, "Error uploading audio: " + t.getMessage());
                    Toast.makeText(PrivateChatActivity.this, "Error uploading audio", Toast.LENGTH_SHORT).show();
                    updateInputIcons(); // Изменено: Используем updateInputIcons
                    audioFile = null;
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Error reading audio file: " + e.getMessage());
            Toast.makeText(this, "Error reading audio file", Toast.LENGTH_SHORT).show();
            updateInputIcons(); // Изменено: Используем updateInputIcons
            audioFile = null;
        }
    }

    // Обновление индикатора TTS
    private void updateTTSIndicator() {
        if (ttsIndicator != null) {
            ttsIndicator.setVisibility(isTTSEnabled ? View.VISIBLE : View.GONE);
            Log.d(TAG, "TTS indicator updated: visibility=" + (isTTSEnabled ? "VISIBLE" : "GONE"));
        }
    }

    // Обновление иконки TTS
    private void updateTTSIcon() {
        if (ttsIconView != null) {
            ttsIconView.setImageResource(isTTSEnabled ? R.drawable.ic_tts : R.drawable.ic_tts);
            ttsIconView.setColorFilter(ContextCompat.getColor(this, isTTSEnabled ? R.color.tts_accent : R.color.grey_500));
            Log.d(TAG, "TTS icon updated: enabled=" + isTTSEnabled);
        }
    }

    private void transcribeNextMessage() {
        PrivateChatTranslationAndSpellCheckHelper.transcribeNextMessage(
                transcriptionQueue,
                isTranscribing,
                () -> {
                    if (!transcriptionQueue.isEmpty()) {
                        isTranscribing = true;
                        Message message = transcriptionQueue.remove(0);
                        PrivateChatTranslationAndSpellCheckHelper.transcribeMessage(
                                this, message, chatId, messageAdapter, messages, apiService, () -> {
                                    isTranscribing = false;
                                    transcribeNextMessage();
                                }
                        );
                    } else {
                        isTranscribing = false;
                        Log.d(TAG, "Transcription queue is empty, stopping transcription");
                    }
                },
                messageAdapter
        );
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
            tvTyping.setText(name + " is typing…");
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
                    if (receivedChatId == chatId) {  // проверка по текущему chatId
                        String type = intent.getStringExtra("type");
                        String name = intent.getStringExtra("username");
                        int senderId = intent.getIntExtra("senderId", -1);

                        if (senderId != currentUserId) {
                            if ("TYPING".equals(type)) {
                                showTypingIndicator(name != null ? name : "Кто-то");
                                if (typingHideRunnable != null) {
                                    typingHandler.removeCallbacks(typingHideRunnable);
                                }
                                typingHideRunnable = PrivateChatActivity.this::hideTypingIndicator;
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
        Log.d(TAG, "Typing receiver (re)registered for current chatId=" + chatId);
    }

    private void unregisterTypingReceiver() {
        if (typingReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(typingReceiver);
            typingReceiver = null;
            Log.d(TAG, "Typing receiver unregistered");
        }
    }

    private void showAiMessageGeneratorDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AIAssistDialogTheme);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ai_message_generator, null);
        builder.setView(dialogView);

        TextView title = dialogView.findViewById(R.id.dialog_title);
        CheckBox preferenceCheck = dialogView.findViewById(R.id.preference_checkbox);
        EditText preferenceInput = dialogView.findViewById(R.id.preference_input);
        Button generateButton = dialogView.findViewById(R.id.generate_button);
        ProgressBar progressBar = dialogView.findViewById(R.id.progress_bar); // добавим в layout
        EditText generatedMessage = dialogView.findViewById(R.id.generated_message);
        Button copyButton = dialogView.findViewById(R.id.copy_button);
        Button editButton = dialogView.findViewById(R.id.edit_button);
        Button sendButton = dialogView.findViewById(R.id.send_button);

        title.setText("AI message generation");
        preferenceCheck.setText("Add a wish to the message");
        preferenceInput.setEnabled(false);
        generatedMessage.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
        editButton.setVisibility(View.GONE);
        sendButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);

        preferenceCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferenceInput.setEnabled(isChecked);
            if (isChecked) preferenceInput.requestFocus();
        });

        AlertDialog dialog = builder.create();

        generateButton.setOnClickListener(v -> {
            generateButton.setEnabled(false);
            progressBar.setVisibility(View.VISIBLE);

            List<Message> history = new ArrayList<>();
            int start = Math.max(0, messages.size() - 20);
            for (int i = start; i < messages.size(); i++) {
                history.add(messages.get(i));
            }

            String preference = preferenceCheck.isChecked() ? preferenceInput.getText().toString().trim() : null;

            GenerateMessageRequest request = new GenerateMessageRequest(history, preference);

            apiService.generateMessage(request).enqueue(new Callback<String>() {
                @Override
                public void onResponse(Call<String> call, Response<String> response) {
                    generateButton.setEnabled(true);
                    progressBar.setVisibility(View.GONE);

                    Log.d("AI_DEBUG", "onResponse: code = " + response.code() + ", successful = " + response.isSuccessful());
                    Log.d("AI_DEBUG", "response.body() = " + response.body());

                    if (response.isSuccessful() && response.body() != null) {
                        String text = response.body().trim();
                        Log.d("AI_DEBUG", "Текст от сервера: " + text);

                        // 1. Поле сообщения (уже работает, но оставляем для полноты)
                        TextInputLayout generatedContainer = dialogView.findViewById(R.id.generated_container);
                        if (generatedContainer != null) {
                            EditText generatedMessageInner = generatedContainer.findViewById(R.id.generated_message);
                            if (generatedMessageInner != null) {
                                generatedMessageInner.setText(text);
                                generatedMessageInner.setVisibility(View.VISIBLE);
                                generatedContainer.setVisibility(View.VISIBLE);
                                Log.d("AI_DEBUG", "generatedContainer показан + текст установлен");
                            } else {
                                Log.e("AI_DEBUG", "generated_message внутри контейнера == null");
                            }
                        } else {
                            Log.e("AI_DEBUG", "generated_container == null");
                        }

                        // 2. Кнопки — показываем контейнер + проверяем каждую кнопку
                        LinearLayout buttonsContainer = dialogView.findViewById(R.id.buttons_container);
                        Log.d("AI_DEBUG", "buttonsContainer = " + (buttonsContainer != null ? "найден" : "NULL"));

                        if (buttonsContainer != null) {
                            buttonsContainer.setVisibility(View.VISIBLE);
                            Log.d("AI_DEBUG", "buttonsContainer показан");

                            // Проверяем каждую кнопку внутри
                            Button copyBtn = buttonsContainer.findViewById(R.id.copy_button);
                            Button editBtn = buttonsContainer.findViewById(R.id.edit_button);
                            Button sendBtn = buttonsContainer.findViewById(R.id.send_button);

                            Log.d("AI_DEBUG", "copy_button = " + (copyBtn != null ? "найден" : "NULL"));
                            Log.d("AI_DEBUG", "edit_button = " + (editBtn != null ? "найден" : "NULL"));
                            Log.d("AI_DEBUG", "send_button = " + (sendBtn != null ? "найден" : "NULL"));

                            // Принудительно показываем кнопки (на случай, если у них visibility gone)
                            if (copyBtn != null) copyBtn.setVisibility(View.VISIBLE);
                            if (editBtn != null) editBtn.setVisibility(View.VISIBLE);
                            if (sendBtn != null) sendBtn.setVisibility(View.VISIBLE);
                        } else {
                            Log.e("AI_DEBUG", "buttons_container == null — кнопки не покажутся");
                            Toast.makeText(PrivateChatActivity.this, "Error: Button container not found", Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(PrivateChatActivity.this, "Failed to generate (code " + response.code() + ")", Toast.LENGTH_LONG).show();
                        Log.e("AI_DEBUG", "Неуспешный ответ: code = " + response.code());
                    }
                }

                @Override
                public void onFailure(Call<String> call, Throwable t) {
                    generateButton.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(PrivateChatActivity.this, "Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });

        copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Generated message", generatedMessage.getText());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(PrivateChatActivity.this, "Copied", Toast.LENGTH_SHORT).show();
        });

        editButton.setOnClickListener(v -> {
            generatedMessage.setEnabled(true);
            generatedMessage.requestFocus();
            generatedMessage.setSelection(generatedMessage.getText().length());
            Toast.makeText(PrivateChatActivity.this, "Edit the text", Toast.LENGTH_SHORT).show();
        });

        sendButton.setOnClickListener(v -> {
            String text = generatedMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                inputMessage.setText(text);
                sendMessage();
                dialog.dismiss();
            } else {
                Toast.makeText(PrivateChatActivity.this, "The message is empty", Toast.LENGTH_SHORT).show();
            }
        });

        dialog.setOnDismissListener(d -> {
            // Можно очистить поля при закрытии, если нужно
        });

        dialog.show();
    }
}