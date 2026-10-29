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
import android.provider.OpenableColumns;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;

import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.Glide;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.CallRequest;
import com.example.unlimitedpossibilities12.api.Chat;
import com.example.unlimitedpossibilities12.api.FileUploadResponse;
import com.example.unlimitedpossibilities12.api.GenerateMessageRequest;
import com.example.unlimitedpossibilities12.api.Message;
import com.example.unlimitedpossibilities12.api.MessageRequest;
import com.example.unlimitedpossibilities12.api.TranslationUpdateRequest;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;
import com.example.unlimitedpossibilities12.messenger.translate_and_spell_check.GroupChatTranslationAndSpellCheckHelper;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import ua.naiksoftware.stomp.StompClient;

import android.widget.LinearLayout;
import android.widget.ProgressBar;
import com.google.android.material.textfield.TextInputLayout;
import android.content.ClipboardManager;
import android.content.ClipData;
import androidx.appcompat.app.AlertDialog;

public class GroupChatsActivity extends AppCompatActivity implements GroupMessageAdapter.OnMessageActionListener {
    private static final String TAG = "GroupChatsActivity";
    private static final String ACTION_SHOW_CALL_DIALOG = "com.example.unlimitedpossibilities12.SHOW_CALL_DIALOG";
    private static final String ACTION_NEW_MESSAGE = "com.example.unlimitedpossibilities12.NEW_MESSAGE";
    private static final String ACTION_NEW_NOTIFICATION = "com.example.unlimitedpossibilities12.NEW_NOTIFICATION";
    private static final String ACTION_CHAT_UPDATED = "com.example.unlimitedpossibilities12.CHAT_UPDATED";
    private static final String ACTION_END_CALL = "com.example.unlimitedpossibilities12.END_CALL";
    private static final int FILE_PICKER_REQUEST_CODE = 1;

    private TextView chatNameTextView;
    private TextView chatStatusTextView; // строка под именем: «Group chat» и включённые режимы
    private EditText inputMessage;
    private RecyclerView messageList;
    private GroupMessageAdapter messageAdapter;
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
    private BroadcastReceiver callReceiver, messageReceiver, notificationReceiver, endCallReceiver, userStatusReceiver;
    private boolean isMessageJustSent = false;
    private LinearLayoutManager layoutManager;
    private boolean isTranslationEnabled;
    private String translationLanguage;
    private TextView translationIndicator;
    private ImageView translationIconView; // Изменено: вместо MenuItem для иконки глобуса
    private TextView spellCheckIndicator; // Добавлено: индикатор орфографии
    private TextView spellCheckIconView; // Добавлено: эмодзи орфографии
    private boolean isSpellCheckEnabled; // Добавлено: флаг орфографии
    private String spellCheckEnabledTimestamp; // Для временной метки орфографии
    private List<Message> translationQueue = new ArrayList<>();
    private boolean isTranslating = false;
    private TranslationSettingsFragment.TranslationMode translationMode;
    // *** ДОБАВЛЕНО: Поля для записи аудио ***
    private static final int AUDIO_PERMISSION_REQUEST_CODE = 100;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private File audioFile;
    private TextView attachedFileTextView; // Для отображения имени прикреплённого файла
    private TextView ttsIndicator; // Индикатор TTS
    private ImageView ttsIconView; // Иконка TTS
    private boolean isTTSEnabled; // Флаг состояния TTS
    // Поля для транскрипции
    private TextView transcriptionIndicator;
    private ImageView transcriptionIconView;
    private boolean isTranscriptionEnabled;
    private TranscriptionSettingsFragment.TranscriptionMode transcriptionMode = TranscriptionSettingsFragment.TranscriptionMode.ALL;
    private List<Message> transcriptionQueue = new ArrayList<>();
    private boolean isTranscribing = false;
    private Drawable aiSparkleDrawable; // Для AI-ассистента генерации сообщений
    // Для иконок ввода (AI, mic, send)
    private ImageView aiAssistIcon;
    private ImageView micIcon;
    private ImageView sendIcon;

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

        apiService = ApiClient.getApiService();
        mediaPlayer = null; // Инициализация отложена до необходимости

        chatNameTextView = findViewById(R.id.chat_name_text_view);
        chatStatusTextView = findViewById(R.id.chat_status_text_view);

        // Разметка общая с личным чатом, но звонить в группе пока нечем:
        // групповой звонок живёт отдельным экраном и собирается из списка людей
        ImageView voiceCallButton = findViewById(R.id.toolbar_voice_call);
        ImageView videoCallButton = findViewById(R.id.toolbar_video_call);
        if (voiceCallButton != null) voiceCallButton.setVisibility(View.GONE);
        if (videoCallButton != null) videoCallButton.setVisibility(View.GONE);
        ImageView overflowButton = findViewById(R.id.toolbar_overflow);
        if (overflowButton != null) overflowButton.setOnClickListener(this::showChatMenu);
        View avatarDot = findViewById(R.id.partner_status_dot);
        if (avatarDot != null) avatarDot.setVisibility(View.GONE);
        inputMessage = findViewById(R.id.input_message);
        messageList = findViewById(R.id.message_list);
        translationIndicator = findViewById(R.id.translation_indicator);
        translationIconView = findViewById(R.id.translation_icon); // Добавлено: инициализация иконки глобуса
        spellCheckIndicator = findViewById(R.id.spell_check_indicator); // Добавлено: инициализация индикатора орфографии
        spellCheckIconView = findViewById(R.id.spell_check_icon); // Добавлено: инициализация эмодзи орфографии
        attachedFileTextView = findViewById(R.id.attached_file_text_view);
        ttsIndicator = findViewById(R.id.tts_indicator); // Инициализация индикатора TTS
        ttsIconView = findViewById(R.id.tts_icon); // Инициализация иконки TTS
        // ДОБАВЛЕНО: Инициализация индикатора и иконки транскрипции
        transcriptionIndicator = findViewById(R.id.transcription_indicator);
        transcriptionIconView = findViewById(R.id.transcription_icon);

        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        messageList.setLayoutManager(layoutManager);
        // Свой номер известен из настроек — иначе первые мгновения все сообщения
        // выглядят чужими и переписка потом перестраивается на глазах
        currentUserId = getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);
        messageAdapter = new GroupMessageAdapter(this, messages, currentUserId, this, isTranslationEnabled, isTTSEnabled, isTranscriptionEnabled);
        messageList.setAdapter(messageAdapter);

        // Инициализация AI-иконки и обновление
        aiSparkleDrawable = ContextCompat.getDrawable(this, R.drawable.ic_ai_sparkle);
        if (aiSparkleDrawable != null) {
            aiSparkleDrawable.setTint(Color.parseColor("#757575"));
        }
        updateInputIcons();  // Обновляем сразу, чтобы иконки появились корректно

        // Иконка ассистента — по центру панели иконок
        ImageView assistantIcon = findViewById(R.id.assistant_icon);
        assistantIcon.setOnClickListener(v -> {
            AssistantChatDialogFragment dialog = new AssistantChatDialogFragment();
            dialog.show(getSupportFragmentManager(), "AssistantChatDialog");
        });

        // Обработка кликов по отдельным иконкам AI, mic и send
        aiAssistIcon = findViewById(R.id.ai_assist_icon);
        micIcon = findViewById(R.id.mic_icon);
        sendIcon = findViewById(R.id.send_icon);

        if (aiAssistIcon != null) {
            aiAssistIcon.setOnClickListener(v -> {
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

        if (sendIcon != null) {
            sendIcon.setOnClickListener(v -> {
                // 1. Анимация нажатия на саму иконку
                v.animate()
                        .scaleX(0.85f)
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

                // 2. Лёгкое подрагивание поля ввода
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

        // Добавлено: Обработчик нажатия на иконки скрепки и микрофона
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

        // Изменено: Обновляем TextWatcher для управления иконками
        inputMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                Log.d(TAG, "Text changed, length=" + s.length() + ", isRecording=" + isRecording + ", selectedFileUri=" + selectedFileUri);
                updateInputIcons();
            }
        });

        // Добавлено: обработка клика на иконку перевода
        if (translationIconView != null) {
            translationIconView.setOnClickListener(v -> {
                TranslationSettingsFragment fragment = new TranslationSettingsFragment((enabled, language, mode) -> {
                    isTranslationEnabled = enabled;
                    translationLanguage = language;
                    translationMode = mode;
                    SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
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
                    GroupChatTranslationAndSpellCheckHelper.updateTranslationIndicator(this, translationIndicator, isTranslationEnabled, translationLanguage);
                    updateHeaderStatus();
                    GroupChatTranslationAndSpellCheckHelper.updateTranslationIcon(this, translationIconView, isTranslationEnabled);
                    messageAdapter.setTranslationEnabled(enabled);
                    if (enabled) {
                        GroupChatTranslationAndSpellCheckHelper.translateExistingMessages(
                                this, messages, isTranslationEnabled, translationLanguage,
                                chatId, translationQueue, messageAdapter, this::translateNextMessage
                        );
                    } else {
                        GroupChatTranslationAndSpellCheckHelper.clearTranslations(this, messages, chatId, translationQueue, messageAdapter);
                    }
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
                SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
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
                GroupChatTranslationAndSpellCheckHelper.updateSpellCheckIndicator(this, spellCheckIndicator, isSpellCheckEnabled);
                updateHeaderStatus();
                GroupChatTranslationAndSpellCheckHelper.updateSpellCheckIcon(this, spellCheckIconView, isSpellCheckEnabled);
                Log.d(TAG, "Spell check toggled: enabled=" + isSpellCheckEnabled);
            });
        } else {
            Log.e(TAG, "spellCheckIconView is null");
        }

        if (ttsIconView != null) {
            ttsIconView.setOnClickListener(v -> {
                isTTSEnabled = !isTTSEnabled;
                SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean("tts_enabled_" + chatId, isTTSEnabled);
                editor.apply();
                updateTTSIndicator();
                updateHeaderStatus();
                updateTTSIcon();
                messageAdapter.setTTSEnabled(isTTSEnabled);
                messageAdapter.notifyDataSetChanged();
                Log.d(TAG, "TTS toggled: enabled=" + isTTSEnabled);
                Toast.makeText(this, "TTS " + (isTTSEnabled ? "enabled" : "disabled"), Toast.LENGTH_SHORT).show();
            });
        } else {
            Log.e(TAG, "ttsIconView is null");
        }

        // ДОБАВЛЕНО: Обработка клика на иконку транскрипции
        if (transcriptionIconView != null) {
            transcriptionIconView.setOnClickListener(v -> {
                TranscriptionSettingsFragment fragment = new TranscriptionSettingsFragment((enabled, mode) -> {
                    isTranscriptionEnabled = enabled;
                    transcriptionMode = mode;
                    SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
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
                    GroupChatTranslationAndSpellCheckHelper.updateTranscriptionIndicator(this, transcriptionIndicator, isTranscriptionEnabled);
                    updateHeaderStatus();
                    GroupChatTranslationAndSpellCheckHelper.updateTranscriptionIcon(this, transcriptionIconView, isTranscriptionEnabled);
                    messageAdapter.setTranscriptionEnabled(isTranscriptionEnabled);
                    if (!enabled) {
                        GroupChatTranslationAndSpellCheckHelper.clearTranscriptions(this, messages, chatId, messageAdapter);
                    } else if (mode != TranscriptionSettingsFragment.TranscriptionMode.SELECTIVELY) {
                        GroupChatTranslationAndSpellCheckHelper.transcribeExistingMessages(
                                this, messages, isTranscriptionEnabled, transcriptionMode, chatId,
                                transcriptionQueue, this::transcribeNextMessage);
                    }
                    messageAdapter.notifyDataSetChanged();
                    Log.d(TAG, "Transcription settings applied: enabled=" + enabled + ", mode=" + mode);
                });
                Bundle args = new Bundle();
                args.putLong("chatId", chatId);
                fragment.show(getSupportFragmentManager(), "TranscriptionSettings");
            });
        } else {
            Log.e(TAG, "transcriptionIconView is null");
        }

        chatId = getIntent().getLongExtra("chatId", -1);
        username = getSharedPreferences("prefs", MODE_PRIVATE).getString("username", "");
        currentUserId = getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);

        // Изменено: загрузка настроек перевода и орфографии
        SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
        isTranslationEnabled = prefs.getBoolean("translation_enabled_" + chatId, false);
        translationLanguage = prefs.getString("translation_language_" + chatId, "auto");
        translationMode = TranslationSettingsFragment.TranslationMode.valueOf(
                prefs.getString("translation_mode_" + chatId, TranslationSettingsFragment.TranslationMode.ALL.name())
        );
        isSpellCheckEnabled = prefs.getBoolean("spell_check_enabled_" + chatId, false);
        spellCheckEnabledTimestamp = prefs.getString("spell_check_enabled_timestamp_" + chatId, null);
        isTTSEnabled = prefs.getBoolean("tts_enabled_" + chatId, false); // Загрузка состояния TTS
        updateTTSIndicator(); // Обновление индикатора TTS
        updateTTSIcon(); // Обновление иконки TTS
        GroupChatTranslationAndSpellCheckHelper.updateTranslationIndicator(this, translationIndicator, isTranslationEnabled, translationLanguage);
        updateHeaderStatus();
        GroupChatTranslationAndSpellCheckHelper.updateTranslationIcon(this, translationIconView, isTranslationEnabled);
        GroupChatTranslationAndSpellCheckHelper.updateSpellCheckIndicator(this, spellCheckIndicator, isSpellCheckEnabled);
        updateHeaderStatus();
        GroupChatTranslationAndSpellCheckHelper.updateSpellCheckIcon(this, spellCheckIconView, isSpellCheckEnabled);

        // ДОБАВЛЕНО: Загрузка настроек транскрипции
        isTranscriptionEnabled = prefs.getBoolean("transcription_enabled_" + chatId, false);
        transcriptionMode = TranscriptionSettingsFragment.TranscriptionMode.valueOf(
                prefs.getString("transcription_mode_" + chatId, TranscriptionSettingsFragment.TranscriptionMode.ALL.name())
        );
        GroupChatTranslationAndSpellCheckHelper.updateTranscriptionIndicator(this, transcriptionIndicator, isTranscriptionEnabled);
        updateHeaderStatus();
        GroupChatTranslationAndSpellCheckHelper.updateTranscriptionIcon(this, transcriptionIconView, isTranscriptionEnabled);

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

        Intent serviceIntent = new Intent(this, CallService.class);
        serviceIntent.putExtra("action", "SUBSCRIBE_CHAT");
        serviceIntent.putExtra("chatId", chatId);
        startService(serviceIntent);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);

        // Слушатель прокрутки для обновления времени посещения
        messageList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                int lastVisiblePosition = layoutManager.findLastCompletelyVisibleItemPosition();
                if (lastVisiblePosition == messages.size() - 1) {
                    updateLastVisitTime();
                }
            }
        });

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
                        Log.d(TAG, "Received message JSON for chatId=" + chatId + ": " + messageJson);
                        try {
                            JSONObject msgJson = new JSONObject(messageJson);
                            Message newMessage = parseMessageFromJson(msgJson);
                            updateMessages(newMessage);
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing received message: " + e.getMessage(), e);
                        }
                    }
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
                    long notificationChatId = -1;
                    if (matcher.find()) {
                        try {
                            notificationChatId = Long.parseLong(matcher.group(1));
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "Error parsing chatId: " + e.getMessage());
                        }
                    }
                    if (notificationChatId != -1 && notificationChatId != chatId) {
                        apiService.getChatById(notificationChatId).enqueue(new Callback<Chat>() {
                            @Override
                            public void onResponse(Call<Chat> call, Response<Chat> response) {
                                if (response.isSuccessful() && response.body() != null) {
                                    Chat chat = response.body();
                                    boolean isPrivateFormat = chat.getName().contains("_");
                                    if (chat.isPrivate() || isPrivateFormat) {
                                        Log.d(TAG, "Private chat notification: " + notification);
                                        Toast.makeText(GroupChatsActivity.this, notification, Toast.LENGTH_LONG).show();
                                    } else {
                                        Log.d(TAG, "Skipping group chat notification: " + notification);
                                    }
                                } else {
                                    Log.e(TAG, "Failed to fetch chat for notification: " + response.code());
                                }
                            }

                            @Override
                            public void onFailure(Call<Chat> call, Throwable t) {
                                Log.e(TAG, "Error fetching chat for notification: " + t.getMessage());
                            }
                        });
                    } else {
                        Log.w(TAG, "No valid chatId or notification for current chat: " + notification);
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
                        Toast.makeText(GroupChatsActivity.this, "The caller cancelled the call.", Toast.LENGTH_LONG).show();
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
                long receivedUserId = intent.getLongExtra("userId", -1);
                boolean online = intent.getBooleanExtra("online", false);
                Log.d(TAG, "Received status update for userId: " + receivedUserId + ", online: " + online);
                if (receivedUserId != -1) {
                    messageAdapter.updateUserStatus(receivedUserId, online);
                }
            }
        };
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
        newMessage.setMessageType(msgJson.optString("messageType", "text"));  // *** ДОБАВЛЕНО: Извлечение messageType из JSON ***
        return newMessage;
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
                    // Пересоздаём только если номер и правда другой: иначе лента
                    // перерисовывается впустую
                    if (messageAdapter == null || messageAdapter.getCurrentUserId() != currentUserId) {
                        messageAdapter = new GroupMessageAdapter(GroupChatsActivity.this, messages, currentUserId, GroupChatsActivity.this, isTranslationEnabled, isTTSEnabled, isTranscriptionEnabled);
                        messageList.setAdapter(messageAdapter);
                    }
                    fetchChatData();
                } else {
                    Log.e(TAG, "Failed to validate user: code=" + response.code());
                    Toast.makeText(GroupChatsActivity.this, "Failed to validate user", Toast.LENGTH_LONG).show();
                    finish();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Log.e(TAG, "Error validating user: " + t.getMessage());
                Toast.makeText(GroupChatsActivity.this, "Error validating user: " + t.getMessage(), Toast.LENGTH_LONG).show();
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
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(messageReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(notificationReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(endCallReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(userStatusReceiver);
        Log.d(TAG, "Receivers unregistered in onStop, including endCallReceiver");
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
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
        messageAdapter.stopCurrentTTS();
        Log.d(TAG, "TTS stopped in onDestroy");
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(callReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(messageReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(notificationReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(endCallReceiver);
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(userStatusReceiver);
        Log.d(TAG, "Receivers unregistered in onDestroy");
        handler.removeCallbacksAndMessages(null);
    }


    /**
     * Меню шапки группового чата. Переключатели режимов переехали сюда с
     * постоянной полосы: нажатие пересылается прежним кнопкам, вся логика
     * осталась в их обработчиках.
     */
    private void showChatMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(
                new android.view.ContextThemeWrapper(this, R.style.CustomPopupTheme), anchor);
        menu.getMenu().add(0, 1, 0, "Translation…");
        menu.getMenu().add(0, 2, 1, "Transcription…");
        menu.getMenu().add(0, 3, 2, isTTSEnabled ? "Read aloud: on" : "Read aloud: off");
        menu.getMenu().add(0, 4, 3, isSpellCheckEnabled ? "Spellcheck: on" : "Spellcheck: off");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: if (translationIconView != null) translationIconView.performClick(); return true;
                case 2: if (transcriptionIconView != null) transcriptionIconView.performClick(); return true;
                case 3: if (ttsIconView != null) ttsIconView.performClick(); return true;
                case 4: if (spellCheckIconView != null) spellCheckIconView.performClick(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    /**
     * Картинка группы в шапке. В списке чатов она уже показывалась, а в самой
     * переписке на её месте было пустое пятно. Если картинки нет (или файл
     * пропал с сервера) — кружок с первой буквой названия, как у людей.
     */
    private void showGroupPicture(String avatarPath, String name) {
        ImageView avatar = findViewById(R.id.partner_avatar);
        if (avatar == null) return;
        String initial = (name == null || name.trim().isEmpty())
                ? "?" : name.trim().substring(0, 1).toUpperCase();
        LetterAvatarDrawable letter = new LetterAvatarDrawable(initial);
        if (avatarPath == null || avatarPath.trim().isEmpty()) {
            avatar.setImageDrawable(letter);
            return;
        }
        Glide.with(this)
                .load("https://unlimitedpossibilities12.org" + avatarPath)
                .apply(new RequestOptions().circleCrop().placeholder(letter).error(letter))
                .into(avatar);
    }

    /** Строка под именем: вид чата и метки включённых режимов. */
    private void updateHeaderStatus() {
        if (chatStatusTextView == null) return;
        StringBuilder line = new StringBuilder("Group chat");
        if (isTranslationEnabled && translationLanguage != null && !translationLanguage.isEmpty()) {
            line.append(" · ").append(translationLanguage.toUpperCase());
        }
        if (isTranscriptionEnabled) line.append(" · TEXT");
        if (isTTSEnabled) line.append(" · AUDIO");
        if (isSpellCheckEnabled) line.append(" · SC");
        chatStatusTextView.setText(line.toString());
        chatStatusTextView.setVisibility(View.VISIBLE);
    }

    private void fetchChatData() {
        Log.d(TAG, "Fetching chat data for chatId=" + chatId);
        // Сохранённый хвост переписки — на экран сразу, иначе первую секунду
        // смотреть не на что. Ответ сервера придёт следом и заменит список.
        if (messages.isEmpty()) {
            List<Message> cached = MessageCache.load(this, chatId);
            if (!cached.isEmpty()) {
                for (Message cachedMessage : cached) {
                    if (cachedMessage.getChat() == null) {
                        cachedMessage.setChat(new Chat(chatId, chatName));
                    }
                }
                messages.addAll(cached);
                messageAdapter.updateMessages(messages);
                jumpToBottom();
                Log.d(TAG, "Показали сохранённые сообщения: " + cached.size());
            }
        }

        apiService.getChatById(chatId).enqueue(new Callback<Chat>() {
            @Override
            public void onResponse(Call<Chat> call, Response<Chat> response) {
                if (response.isSuccessful() && response.body() != null) {
                    chatName = response.body().getName();
                    chatNameTextView.setText(chatName);
                    updateHeaderStatus();
                    showGroupPicture(response.body().getAvatar(), chatName);
                    Log.d(TAG, "Chat fetched: name=" + chatName);
                } else {
                    Log.e(TAG, "Failed to fetch chat: code=" + response.code());
                    Toast.makeText(GroupChatsActivity.this, "Failed to fetch chat data", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<Chat> call, Throwable t) {
                Log.e(TAG, "Error fetching chat: " + t.getMessage());
                Toast.makeText(GroupChatsActivity.this, "Error fetching chat: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        apiService.getMessagesByChatId(chatId).enqueue(new Callback<List<Message>>() {
            @Override
            public void onResponse(Call<List<Message>> call, Response<List<Message>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    messages.clear();
                    messages.addAll(response.body());

                    // Восстановление локальных переводов из SharedPreferences только если перевод включён
                    if (isTranslationEnabled) {
                        SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
                        for (Message message : messages) {
                            String key = "translation_" + chatId + "_" + message.getId();
                            if (prefs.contains(key)) {
                                String savedTranslation = prefs.getString(key, null);
                                String savedLanguage = prefs.getString(key + "_language", null);
                                if (savedTranslation != null && savedLanguage != null) {
                                    message.setTranslatedContent(savedTranslation);
                                    message.setTranslationLanguage(savedLanguage);
                                    Log.d(TAG, "Restored translation for message id=" + message.getId() + ": " + savedTranslation);
                                }
                            }
                        }
                    }

                    // ДОБАВLEНО: Восстановление транскрипций
                    if (isTranscriptionEnabled) {
                        SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
                        for (Message message : messages) {
                            if ("audio".equals(message.getMessageType())) {
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
                    MessageCache.save(getApplicationContext(), chatId, messages);
                    updateLastVisitTime();
                    Log.d(TAG, "Messages fetched: count=" + messages.size());

                    // Запрашиваем статусы всех отправителей сообщений
                    if (isBound && callService != null && callService.getStompClient() != null && callService.getStompClient().isConnected()) {
                        for (Message message : messages) {
                            if (message.getSender() != null && message.getSender().getId() != currentUserId) {
                                callService.requestUserStatus(message.getSender().getId());
                            }
                        }
                    }

                    // Перевод ищет вьюхи по номеру позиции — ждём, пока лента
                    // перерисуется под новый список
                    messageList.post(() -> {
                        if (isTranslationEnabled) {
                            GroupChatTranslationAndSpellCheckHelper.translateExistingMessages(
                                    GroupChatsActivity.this, messages, isTranslationEnabled, translationLanguage,
                                    chatId, translationQueue, messageAdapter, () -> translateNextMessage()
                            );
                        }
                    });
                } else {
                    Log.e(TAG, "Failed to fetch messages: code=" + response.code());
                    Toast.makeText(GroupChatsActivity.this, "Failed to fetch messages", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<List<Message>> call, Throwable t) {
                Log.e(TAG, "Error fetching messages: " + t.getMessage());
                Toast.makeText(GroupChatsActivity.this, "Error fetching messages: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateLastVisitTime() {
        if (messages.isEmpty()) {
            return;
        }
        Message lastMessage = messages.get(messages.size() - 1);
        String timestamp = lastMessage.getTimestamp();
        if (timestamp != null && !timestamp.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
            prefs.edit().putString("last_visit_chat_" + chatId, timestamp).apply();
            Log.d(TAG, "Updated last visit time for chat " + chatId + ": " + timestamp);
            Intent intent = new Intent(ACTION_CHAT_UPDATED);
            intent.putExtra("chatId", chatId);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }

    private void initStompClient() {
        if (!isBound || callService == null) {
            Log.e(TAG, "CallService not bound, cannot initialize StompClient");
            Toast.makeText(this, "Service not available", Toast.LENGTH_LONG).show();
            return;
        }

        stompClient = callService.getStompClient();
        if (stompClient == null || !stompClient.isConnected()) {
            Log.e(TAG, "StompClient not available or not connected");
            Toast.makeText(this, "WebSocket connection not available", Toast.LENGTH_LONG).show();
            return;
        }

        Log.d(TAG, "Using StompClient from CallService");
    }

    private void updateMessages(Message newMessage) {
        runOnUiThread(() -> {
            SharedPreferences prefs = getSharedPreferences("chat_prefs", MODE_PRIVATE);
            isTTSEnabled = prefs.getBoolean("tts_enabled_" + chatId, false);
            messageAdapter.setTTSEnabled(isTTSEnabled);
            updateTTSIndicator();
            updateHeaderStatus();
            updateTTSIcon();
            if (newMessage == null || newMessage.getSender() == null) {
                Log.w(TAG, "Received null message or sender");
                return;
            }
            Log.d(TAG, "Updating message: id=" + newMessage.getId() + ", content=" + newMessage.getContent());
            if ("Message deleted".equals(newMessage.getContent())) {
                messages.removeIf(msg -> msg.getId() == newMessage.getId());
                messageAdapter.updateMessages(messages);
                Toast.makeText(this, "Message deleted!", Toast.LENGTH_SHORT).show();
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
                // Перевод новых или обновлённых сообщений
                if (isTranslationEnabled && newMessage.getTranslatedContent() == null &&
                        !translationLanguage.equals(newMessage.getTranslationLanguage()) &&
                        translationMode != TranslationSettingsFragment.TranslationMode.SELECTIVELY &&
                        ("text".equalsIgnoreCase(newMessage.getMessageType()) ||
                                ("file".equalsIgnoreCase(newMessage.getMessageType()) &&
                                        newMessage.getContent() != null && !newMessage.getContent().isEmpty())) &&
                        GroupChatTranslationAndSpellCheckHelper.isMessageNew(this, newMessage, chatId)) {
                    if (isTranslating) {
                        translationQueue.add(newMessage);
                        Log.d(TAG, "Added message id=" + newMessage.getId() + " to translation queue");
                    } else {
                        isTranslating = true;
                        GroupChatTranslationAndSpellCheckHelper.translateMessage(this, newMessage, translationLanguage, chatId, messageAdapter, () -> {
                            isTranslating = false;
                            translateNextMessage();
                        });
                        Log.d(TAG, "Started translating message id=" + newMessage.getId());
                    }
                }
                // ДОБАВЛЕНО: Автоматическая транскрипция новых аудиосообщений
                if (isTranscriptionEnabled && "audio".equals(newMessage.getMessageType()) &&
                        newMessage.getTranscribedContent() == null &&
                        transcriptionMode != TranscriptionSettingsFragment.TranscriptionMode.SELECTIVELY &&
                        GroupChatTranslationAndSpellCheckHelper.isMessageNewerThanTimestamp(newMessage, prefs.getString("transcription_enabled_timestamp_" + chatId, null))) {
                    if (isTranscribing) {
                        transcriptionQueue.add(newMessage);
                        Log.d(TAG, "Added message id=" + newMessage.getId() + " to transcription queue");
                    } else {
                        isTranscribing = true;
                        GroupChatTranslationAndSpellCheckHelper.transcribeMessage(this, newMessage, chatId, apiService, messageAdapter,
                                isTranslationEnabled, translationLanguage, transcriptionMode, () -> {
                                    isTranscribing = false;
                                    transcribeNextMessage();
                                });
                        Log.d(TAG, "Started transcription for message id=" + newMessage.getId());
                    }
                }
                messageAdapter.updateMessages(messages);
                if (isMessageJustSent || (isNewMessage && newMessage.getSender().getId() != currentUserId)) {
                    scrollToBottom();
                }
                isMessageJustSent = false;
            }
            updateLastVisitTime();
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

    private void translateNextMessage() {
        GroupChatTranslationAndSpellCheckHelper.translateNextMessage(translationQueue, isTranslating, () -> {
            isTranslating = true;
            Message message = translationQueue.remove(0);
            GroupChatTranslationAndSpellCheckHelper.translateMessage(this, message, translationLanguage, chatId, messageAdapter, () -> {
                isTranslating = false;
                translateNextMessage();
            });
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
                updateInputIcons(); // Обновляем иконки (показываем иконку отправки)
                Toast.makeText(this, "File selected: " + fileName, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void sendMessage() {
        String content = inputMessage.getText().toString().trim();
        if (content.isEmpty() && selectedFileUri == null) {
            Toast.makeText(this, "Content is missing", Toast.LENGTH_SHORT).show();
            return;
        }

        // *** ИЗМЕНЕНО: Добавлен messageType для текстовых сообщений ***
        if (isSpellCheckEnabled && spellCheckEnabledTimestamp != null && !content.isEmpty()) {
            GroupChatTranslationAndSpellCheckHelper.spellCheckMessage(this, content, apiService, new Callback<String>() {
                @Override
                public void onResponse(Call<String> call, Response<String> response) {
                    String finalContent = response.isSuccessful() && response.body() != null ?
                            response.body() : content;
                    if (selectedFileUri != null) {
                        uploadFileAndSendMessage(finalContent);
                    } else {
                        sendMessageWithFile(finalContent, null, "text"); // *** ИЗМЕНЕНО: Добавлен messageType "text" ***
                    }
                }

                @Override
                public void onFailure(Call<String> call, Throwable t) {
                    Log.e(TAG, "Spell check failed, sending original content: " + t.getMessage());
                    // Toast уже показан в spellCheckMessage
                    if (selectedFileUri != null) {
                        uploadFileAndSendMessage(content);
                    } else {
                        sendMessageWithFile(content, null, "text"); // *** ИЗМЕНЕНО: Добавлен messageType "text" ***
                    }
                }
            });
        } else {
            if (selectedFileUri != null) {
                uploadFileAndSendMessage(content);
            } else {
                sendMessageWithFile(content, null, "text"); // *** ИЗМЕНЕНО: Добавлен messageType "text" ***
            }
        }
    }

    private void uploadFileAndSendMessage(String content) {
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
                fileName = "uploaded_file_" + System.currentTimeMillis();
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

            Log.d(TAG, "Uploading file: name=" + fileName + ", mimeType=" + mimeType);
            call.enqueue(new Callback<FileUploadResponse>() {
                @Override
                public void onResponse(Call<FileUploadResponse> call, Response<FileUploadResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        String fileUrl = response.body().getFileUrl();
                        Log.d(TAG, "File uploaded successfully, fileUrl=" + fileUrl);
                        sendMessageWithFile(content, fileUrl, "file");
                    } else {
                        Log.e(TAG, "Failed to upload file: code=" + response.code());
                        Toast.makeText(GroupChatsActivity.this, "Failed to upload file", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onFailure(Call<FileUploadResponse> call, Throwable t) {
                    Log.e(TAG, "Error uploading file: " + t.getMessage());
                    Toast.makeText(GroupChatsActivity.this, "Error uploading file: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error preparing file for upload: " + e.getMessage());
            Toast.makeText(this, "Error preparing file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
        message.setMessageType(messageType); // *** ДОБАВЛЕНО: Установка messageType ***

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
                jsonMessage.put("messageType", messageType); // *** ДОБАВЛЕНО: Добавление messageType в JSON ***

                String destination = editingMessageId != null ?
                        "/app/editMessage/" + chatId :
                        "/app/sendMessage/chat/" + chatId;
                Log.d(TAG, "Sending message to: " + destination + ", payload: " + jsonMessage.toString());
                isMessageJustSent = true;
                stompClient.send(destination, jsonMessage.toString())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> {
                                    Log.d(TAG, "Message sent successfully to " + destination);
                                    resetForm();
                                },
                                throwable -> {
                                    Log.e(TAG, "Error sending message: " + throwable.getMessage());
                                    Toast.makeText(GroupChatsActivity.this, "Failed to send message", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "WebSocket not connected", Toast.LENGTH_LONG).show();
            isMessageJustSent = false;
        }
    }

    private void resetForm() {
        inputMessage.setText("");
        selectedFileUri = null;
        editingMessageId = null;
        if (audioFile != null && audioFile.exists()) {
            audioFile.delete();
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
                                        intent = new Intent(GroupChatsActivity.this, VideoCallActivity.class);
                                        break;
                                    case "translate":
                                        intent = new Intent(GroupChatsActivity.this, VoiceCallWithTranslateActivity.class);
                                        break;
                                    case "voice_secure":  // ← ДОБАВЬ ЭТОТ CASE!
                                        intent = new Intent(GroupChatsActivity.this, SecureVoiceCallActivity.class);
                                        break;
                                    case "voice":
                                    default:
                                        intent = new Intent(GroupChatsActivity.this, VoiceCallActivity.class);
                                        break;
                                }
                                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                                intent.putExtra("recipientId", String.valueOf(incomingCall.getCallerId()));
                                intent.putExtra("roomId", incomingCall.getRoomId());
                                intent.putExtra("isCaller", false);
                                intent.putExtra("callerName", callerName);
                                intent.putExtra("recipientName", incomingCall.getCallerName());
                                startActivity(intent);
                                Toast.makeText(GroupChatsActivity.this,
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
        if (isBound && callService != null) {
            handleIntent(intent);
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

    @Override
    protected void onResume() {
        super.onResume();
        Intent serviceIntent = new Intent(this, CallService.class);
        serviceIntent.putExtra("action", "SUBSCRIBE_CHAT");
        serviceIntent.putExtra("chatId", chatId);
        startService(serviceIntent);
        fetchChatData(); // Обновляем сообщения при разворачивании
    }

    private String generateCurrentTimestamp() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Изменено: убрано inflate translation_menu, так как всё в XML
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Изменено: убрана обработка action_translate
        return super.onOptionsItemSelected(item);
    }

    // *** ДОБАВЛЕНО: Проверка и запрос разрешения для записи аудио ***
    private void requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST_CODE);
        } else {
            startRecording();
        }
    }

    // *** ДОБАВЛЕНО: Обработка результата запроса разрешения ***
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

    // *** ДОБАВЛЕНО: Начало записи аудио ***
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
            updateInputIcons(); // Используем updateInputIcons
            Log.d(TAG, "Recording started");
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Error starting recording: " + e.getMessage());
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
            updateInputIcons(); // Используем updateInputIcons
        }
    }

    // *** ДОБАВЛЕНО: Остановка записи аудио ***
    private void stopRecording() {
        if (mediaRecorder != null && isRecording) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                updateInputIcons(); // Используем updateInputIcons
                Log.d(TAG, "Recording stopped");
                Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
                sendAudioMessage();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recording: " + e.getMessage());
                Toast.makeText(this, "Failed to stop recording", Toast.LENGTH_SHORT).show();
                updateInputIcons(); // Используем updateInputIcons
            }
        }
    }

    // *** ДОБАВЛЕНО: Отправка аудиосообщения ***
    private void sendAudioMessage() {
        if (audioFile == null || !audioFile.exists()) {
            Toast.makeText(this, "No audio recorded", Toast.LENGTH_SHORT).show();
            updateInputIcons(); // Используем updateInputIcons
            audioFile = null;
            return;
        }

        try {
            byte[] audioBytes = Files.readAllBytes(audioFile.toPath());
            RequestBody fileBody = RequestBody.create(MediaType.parse("audio/mpeg"), audioBytes);
            MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", audioFile.getName(), fileBody);
            Call<FileUploadResponse> call = apiService.uploadFile(filePart);

            Log.d(TAG, "Uploading audio file: name=" + audioFile.getName());
            call.enqueue(new Callback<FileUploadResponse>() {
                @Override
                public void onResponse(Call<FileUploadResponse> call, Response<FileUploadResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        String fileUrl = response.body().getFileUrl();
                        Log.d(TAG, "Audio uploaded, fileUrl=" + fileUrl);
                        sendMessageWithFile("", fileUrl, "audio");
                        updateInputIcons(); // Используем updateInputIcons
                        if (audioFile != null && audioFile.exists()) {
                            audioFile.delete();
                        }
                        audioFile = null;
                    } else {
                        Log.e(TAG, "Failed to upload audio: code=" + response.code());
                        Toast.makeText(GroupChatsActivity.this, "Failed to upload audio", Toast.LENGTH_SHORT).show();
                        updateInputIcons(); // Используем updateInputIcons
                        audioFile = null;
                    }
                }

                @Override
                public void onFailure(Call<FileUploadResponse> call, Throwable t) {
                    Log.e(TAG, "Error uploading audio: " + t.getMessage());
                    Toast.makeText(GroupChatsActivity.this, "Error uploading audio", Toast.LENGTH_SHORT).show();
                    updateInputIcons(); // Используем updateInputIcons
                    audioFile = null;
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Error reading audio file: " + e.getMessage());
            Toast.makeText(this, "Error reading audio file", Toast.LENGTH_SHORT).show();
            updateInputIcons(); // Используем updateInputIcons
            audioFile = null;
        }
    }

    public String getTranslationLanguage() {
        return translationLanguage;
    }

    public boolean isTranslationEnabled() {
        return isTranslationEnabled;
    }

    public TranslationSettingsFragment.TranslationMode getTranslationMode() {
        return translationMode;
    }

    public void translateMessage(Message message) {
        if (isTranslating) {
            translationQueue.add(message);
            Log.d(TAG, "Added message id=" + message.getId() + " to translation queue (translate icon)");
        } else {
            isTranslating = true;
            GroupChatTranslationAndSpellCheckHelper.translateMessage(this, message, translationLanguage, chatId, messageAdapter, () -> {
                isTranslating = false;
                translateNextMessage();
            });
            Log.d(TAG, "Started translating message id=" + message.getId() + " (translate icon)");
        }
    }

    private void updateTTSIndicator() {
        if (ttsIndicator != null) {
            ttsIndicator.setVisibility(isTTSEnabled ? View.VISIBLE : View.GONE);
            Log.d(TAG, "TTS indicator updated: visibility=" + (isTTSEnabled ? "VISIBLE" : "GONE"));
        } else {
            Log.e(TAG, "ttsIndicator is null");
        }
    }

    private void updateTTSIcon() {
        if (ttsIconView != null) {
            ttsIconView.setColorFilter(ContextCompat.getColor(this, isTTSEnabled ? R.color.tts_accent : R.color.grey_500));
            Log.d(TAG, "TTS icon updated: enabled=" + isTTSEnabled);
        } else {
            Log.e(TAG, "ttsIconView is null");
        }
    }

    public void transcribeMessage(Message message, Runnable onComplete) {
        if (isTranscribing) {
            transcriptionQueue.add(message);
            Log.d(TAG, "Added message id=" + message.getId() + " to transcription queue (transcribeMessage)");
        } else {
            isTranscribing = true;
            GroupChatTranslationAndSpellCheckHelper.transcribeMessage(this, message, chatId, apiService, messageAdapter,
                    isTranslationEnabled, translationLanguage, transcriptionMode, () -> {
                        isTranscribing = false;
                        onComplete.run();
                        transcribeNextMessage();
                    });
            Log.d(TAG, "Started transcribing message id=" + message.getId() + " (transcribeMessage)");
        }
    }

    private void transcribeNextMessage() {
        GroupChatTranslationAndSpellCheckHelper.transcribeNextMessage(transcriptionQueue, isTranscribing, () -> {
            if (!transcriptionQueue.isEmpty()) {
                isTranscribing = true;
                Message message = transcriptionQueue.remove(0);
                GroupChatTranslationAndSpellCheckHelper.transcribeMessage(this, message, chatId, apiService, messageAdapter,
                        isTranslationEnabled, translationLanguage, transcriptionMode, () -> {
                            isTranscribing = false;
                            transcribeNextMessage();
                        });
            } else {
                isTranscribing = false;
                Log.d(TAG, "Transcription queue is empty, stopping transcription");
            }
        });
    }

    private void showAiMessageGeneratorDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AIAssistDialogTheme);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ai_message_generator, null);
        builder.setView(dialogView);

        TextView title = dialogView.findViewById(R.id.dialog_title);
        CheckBox preferenceCheck = dialogView.findViewById(R.id.preference_checkbox);
        EditText preferenceInput = dialogView.findViewById(R.id.preference_input);
        Button generateButton = dialogView.findViewById(R.id.generate_button);
        ProgressBar progressBar = dialogView.findViewById(R.id.progress_bar);
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

                        LinearLayout buttonsContainer = dialogView.findViewById(R.id.buttons_container);
                        Log.d("AI_DEBUG", "buttonsContainer = " + (buttonsContainer != null ? "найден" : "NULL"));

                        if (buttonsContainer != null) {
                            buttonsContainer.setVisibility(View.VISIBLE);
                            Log.d("AI_DEBUG", "buttonsContainer показан");

                            Button copyBtn = buttonsContainer.findViewById(R.id.copy_button);
                            Button editBtn = buttonsContainer.findViewById(R.id.edit_button);
                            Button sendBtn = buttonsContainer.findViewById(R.id.send_button);

                            Log.d("AI_DEBUG", "copy_button = " + (copyBtn != null ? "найден" : "NULL"));
                            Log.d("AI_DEBUG", "edit_button = " + (editBtn != null ? "найден" : "NULL"));
                            Log.d("AI_DEBUG", "send_button = " + (sendBtn != null ? "найден" : "NULL"));

                            if (copyBtn != null) copyBtn.setVisibility(View.VISIBLE);
                            if (editBtn != null) editBtn.setVisibility(View.VISIBLE);
                            if (sendBtn != null) sendBtn.setVisibility(View.VISIBLE);
                        } else {
                            Log.e("AI_DEBUG", "buttons_container == null — кнопки не покажутся");
                            Toast.makeText(GroupChatsActivity.this, "Ошибка: контейнер кнопок не найден", Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(GroupChatsActivity.this, "Failed to generate (code " + response.code() + ")", Toast.LENGTH_LONG).show();
                        Log.e("AI_DEBUG", "Неуспешный ответ: code = " + response.code());
                    }
                }

                @Override
                public void onFailure(Call<String> call, Throwable t) {
                    generateButton.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(GroupChatsActivity.this, "Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });

        copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Generated message", generatedMessage.getText());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(GroupChatsActivity.this, "Copied", Toast.LENGTH_SHORT).show();
        });

        editButton.setOnClickListener(v -> {
            generatedMessage.setEnabled(true);
            generatedMessage.requestFocus();
            generatedMessage.setSelection(generatedMessage.getText().length());
            Toast.makeText(GroupChatsActivity.this, "Edit the text", Toast.LENGTH_SHORT).show();
        });

        sendButton.setOnClickListener(v -> {
            String text = generatedMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                inputMessage.setText(text);
                sendMessage();
                dialog.dismiss();
            } else {
                Toast.makeText(GroupChatsActivity.this, "The message is empty", Toast.LENGTH_SHORT).show();
            }
        });

        dialog.setOnDismissListener(d -> {
            // Можно очистить поля при закрытии, если нужно
        });

        dialog.show();
    }
}