package com.example.unlimitedpossibilities12.messenger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.MessageRequest;
import com.example.unlimitedpossibilities12.api.Settings;
import com.example.unlimitedpossibilities12.messenger.language.Language;
import com.example.unlimitedpossibilities12.messenger.language.LanguageAdapter;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import io.reactivex.disposables.Disposable;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Request;
import okio.ByteString;
import java.util.function.Consumer;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import ua.naiksoftware.stomp.StompClient;

public class VoiceCallWithTranslateActivity extends AppCompatActivity {
    private static final String TAG = "VoiceCallWithTranslateActivity";
    private StompClient stompClient;
    private String currentUserId;
    private String recipientId;
    private String roomId;
    private String sessionId;
    private boolean isCaller;
    private String callerName;
    private String recipientName;
    private PeerConnection peerConnection;
    private PeerConnectionFactory peerConnectionFactory;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    /** Входящий голос собеседника: его приглушаем, пока звучит перевод. */
    private AudioTrack remoteAudioTrack;
    /**
     * Громкость живого голоса собеседника, когда озвучка перевода включена.
     * Перевод звучит после фразы — значит, фразу всё равно слышно дважды.
     * Чтобы разговор не удваивался в полный голос, оригинал держим фоном:
     * по нему слышно, что человек говорит, и какая у него интонация, а
     * главным голосом становится перевод.
     */
    private static final double BACKGROUND_VOLUME = 0.15;
    /** На время самой озвучки оригинал убираем ещё тише, но не в тишину. */
    // Пока звучит перевод, оригинал молчит: иначе две речи идут внахлёст —
    // перевод отстаёт на фразу, и человек слышит сразу обе
    private static final double DUCK_VOLUME = 0.0;
    /** Произносится ли перевод прямо сейчас. */
    private boolean translationSpeaking;
    private AudioManager audioManager;
    private MediaPlayer ringtonePlayer;
    private boolean isWaiting = false;
    private String userName;
    private boolean terminationInitiator = false;
    private View endCallButton;
    private CallService callService;
    private boolean isBound = false;
    private Handler statsHandler;
    private Runnable statsRunnable;
    private boolean isCallStarted = false;
    private boolean isCallEnded = false;
    private boolean isAcceptCallSent = false;
    private boolean hasSavedCall = false; // Флаг для предотвращения дублирования сохранения звонка
    private boolean isOfferSent = false;
    private Disposable roomSubscription;
    private Disposable transcriptionSubscription;

    private TextView callTitle;
    private TextView callTimer;
    // Снимок собеседника и состояние разговора — как на остальных экранах звонка
    private ImageView callAvatarImage;
    private TextView callAvatarText;
    private ImageView muteButton;
    private ImageView speakerButton;
    private View translationPanel;
    private boolean micMuted = false;
    private boolean speakerOn = true;
    private com.example.unlimitedpossibilities12.messenger.notife.CallGuard callGuard;
    private Handler timerHandler;
    private Runnable timerRunnable;
    private Handler timeHandler;
    private Runnable timeRunnable;
    private long callStartTime;
    private Handler currentTimeHandler;
    private Runnable currentTimeRunnable;

    private boolean isRecording = false;
    private TextView transcriptionTextView;
    private Disposable friendSubscription;

    // NEW: Поля для Switch, Spinner и языка
    private Switch translationSwitch;
    private Spinner languageSpinner;
    /**
     * Выбранный язык описывает самого пользователя: на нём он говорит и на него
     * ему переводят речь собеседника. Отдельный список «на каком языке говорю»
     * был лишним — это тот же факт, записанный дважды.
     */
    private LanguageAdapter languageAdapter;
    private String selectedLanguageCode;
    /** Бесплатный режим: распознаёт и переводит сам телефон, звук на сервер не уходит. */
    private boolean onDeviceMode;
    private Switch onDeviceSwitch;
    private OnDeviceSpeech onDeviceSpeech; // озвучка перевода голосом телефона
    /** Распознавание на устройстве: модель скачивает само приложение. */
    private VoskRecognizer vosk;
    private Disposable peerLanguageSubscription;
    /** Предложение скачать пакет показываем один раз за звонок, не на каждую фразу. */
    private boolean packPromptShown;
    private TextView friendTranscriptionTextView;
    // [ДОБАВЛЕНО] Поля для TTS
    private Switch ttsSwitch;
    private Disposable ttsSubscription;
    private MediaPlayer ttsMediaPlayer;

    private final ApiService apiService = ApiClient.getApiService(); // [ДОБАВЛЕНО] Экземпляр ApiService для вызовов API

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CallService.LocalBinder binder = (CallService.LocalBinder) service;
            callService = binder.getService();
            isBound = true;
            Log.d(TAG, "Connected to CallService");
            stompClient = callService.getStompClient();
            if (stompClient != null && stompClient.isConnected()) {
                Log.d(TAG, "Using STOMP client from CallService");
                subscribeToRoom();
                subscribeToTranscription();
                subscribeToFriendTranscription();
                subscribeToTts(); // [ДОБАВЛЕНО] Подписка на TTS
                registerRecipient(); // NEW: Добавляем вызов здесь
                // Сервер хранит настройки в памяти: после его перезапуска или
                // нашего переподключения их нужно сообщить заново
                if (translationSwitch != null && ttsSwitch != null) sendSettingsToServer();
                if (!isCaller && !isAcceptCallSent) {
                    sendSignal(createSignalJson("acceptCall", null));
                    Log.d(TAG, "Sent acceptCall signal after binding");
                    isAcceptCallSent = true;
                    sendReadySignal();
                }
            } else {
                Log.e(TAG, "STOMP client not available from CallService");
                finish();
            }
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
        com.example.unlimitedpossibilities12.messenger.notife.CallService.setCallInProgress(true); // идёт звонок — соединение держим
        setContentView(R.layout.activity_voice_call_with_translate);

        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        currentUserId = getIntent().getStringExtra("currentUserId");
        recipientId = getIntent().getStringExtra("recipientId");
        roomId = getIntent().getStringExtra("roomId");
        isCaller = getIntent().getBooleanExtra("isCaller", false);
        callerName = getIntent().getStringExtra("callerName");
        recipientName = getIntent().getStringExtra("recipientName");
        rememberOngoingCall(recipientName != null ? recipientName : callerName);

        // NEW: Проверка currentUserId на null или пустую строку
        if (currentUserId == null || currentUserId.isEmpty()) {
            Log.e(TAG, "currentUserId is null or empty! Intent extras: " + getIntent().getExtras());
            Toast.makeText(this, "User ID is missing", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        sessionId = currentUserId + "_" + System.currentTimeMillis();
        Log.d(TAG, "onCreate: currentUserId=" + currentUserId + ", recipientId=" + recipientId +
                ", roomId=" + roomId + ", isCaller=" + isCaller + ", sessionId=" + sessionId);

        // [ДОБАВЛЕНО] Инициализация SharedPreferences в начале метода
        SharedPreferences prefs = getSharedPreferences("call_prefs", MODE_PRIVATE);

        callTitle = findViewById(R.id.call_title);
        callTimer = findViewById(R.id.call_timer);
        endCallButton = findViewById(R.id.end_call_button);
        callAvatarImage = findViewById(R.id.call_avatar_image);
        callAvatarText = findViewById(R.id.call_avatar_text);
        muteButton = findViewById(R.id.mute_button);
        speakerButton = findViewById(R.id.speaker_button);
        translationPanel = findViewById(R.id.translation_panel);

        muteButton.setOnClickListener(v -> toggleMic());
        speakerButton.setOnClickListener(v -> toggleSpeaker());
        findViewById(R.id.minimize_button).setOnClickListener(v -> onBackPressed());
        // Настройки перевода открываются кнопкой: прежде они занимали треть экрана
        findViewById(R.id.translate_button).setOnClickListener(v ->
                translationPanel.setVisibility(
                        translationPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        findViewById(R.id.call_security).setOnClickListener(v -> showFingerprint());
        transcriptionTextView = findViewById(R.id.transcription_text_view);
        // NEW: Инициализация новых элементов UI
        translationSwitch = findViewById(R.id.translation_switch);
        languageSpinner = findViewById(R.id.language_spinner);
        onDeviceSwitch = findViewById(R.id.on_device_switch);
        onDeviceMode = callPref("on_device_translate", false);
        friendTranscriptionTextView = findViewById(R.id.friend_transcription_text_view); // NEW

        // [ДОБАВЛЕНО] Инициализация ttsSwitch
        ttsSwitch = findViewById(R.id.tts_switch);
        boolean isTtsEnabled = callPref("tts_enabled", false);
        ttsSwitch.setChecked(isTtsEnabled);
        // Видимость выставляем ниже, вместе с самим переключателем перевода:
        // здесь его состояние ещё не прочитано из настроек, и переключатель
        // озвучки оставался спрятанным до первого щелчка руками
        ttsSwitch.setVisibility(View.GONE);

        // [ДОБАВЛЕНО] Обработчик ttsSwitch
        ttsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d(TAG, "TTS switch toggled: " + isChecked);
            saveCallPref("tts_enabled", isChecked);
            applyRemoteVolume(); // с озвучкой оригинал уходит фоном, без неё возвращается
            if (!isChecked) stopSpeaking();
            sendSettingsToServer(); // [ДОБАВЛЕНО] Отправка настроек
        });

        String partner = recipientName != null && !recipientName.isEmpty() ? recipientName : "?";
        callTitle.setText(partner);
        showPartnerAvatar(partner);
        setupCallGuard();

        timerHandler = new Handler(Looper.getMainLooper());
        callStartTime = System.currentTimeMillis();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long duration = System.currentTimeMillis() - callStartTime;
                int minutes = (int) (duration / 1000) / 60;
                int seconds = (int) (duration / 1000) % 60;
                callTimer.setText(String.format("%02d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);

        // Часы «Start»/«Current time» и полоса графика убраны: это была отладка

        endCallButton.setOnClickListener(v -> {
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction(() -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                endCall();
            }).start();
        });

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);
        speakerOn = true;
        paintButton(speakerButton, true);
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
        Log.d(TAG, "VOICE_CALL volume set to max: " + maxVolume);

        ringtonePlayer = new MediaPlayer();
        try {
            ringtonePlayer.setDataSource("https://igor7070.github.io/Messenger/incoming_dial_tone.mp3");
            ringtonePlayer.setLooping(true);
            ringtonePlayer.prepareAsync();
            ringtonePlayer.setOnPreparedListener(mp -> {
                if (isCaller && !isWaiting) {
                    isWaiting = true;
                    ringtonePlayer.start();
                    Log.d(TAG, "Ringtone started playing");
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Error setting up ringtone: " + e.getMessage());
        }

        Intent serviceIntent = new Intent(this, CallService.class);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);

        // NEW: Настройка Spinner и загрузка сохранённых настроек
        setupLanguageSpinner();
        // [ИЗМЕНЕНО] Удалено повторное объявление prefs, используем существующую
        boolean isTranslationEnabled = callPref("translation_enabled", false);
        String savedLanguage = callPref("translation_language", "en");
        translationSwitch.setChecked(isTranslationEnabled);
        languageSpinner.setVisibility(isTranslationEnabled ? View.VISIBLE : View.GONE);
        View languageHint = findViewById(R.id.language_hint);
        if (languageHint != null) languageHint.setVisibility(isTranslationEnabled ? View.VISIBLE : View.GONE);
        setupOnDeviceSwitch(isTranslationEnabled);
        ttsSwitch.setVisibility(isTranslationEnabled ? View.VISIBLE : View.GONE);
        setSpinnerSelection(savedLanguage);
        // Сервер решает, кому синтезировать речь, по настройкам слушателя.
        // Без этой отправки он не знал сохранённого выбора, и озвучка включалась
        // только после того, как человек тронет любой переключатель.
        sendSettingsToServer();

        // [ИЗМЕНЕНО] Обработчик Switch с управлением видимостью ttsSwitch
        translationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            languageSpinner.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            View hint = findViewById(R.id.language_hint);
            if (hint != null) hint.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (onDeviceSwitch != null) onDeviceSwitch.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            ttsSwitch.setVisibility(isChecked ? View.VISIBLE : View.GONE); // [ДОБАВЛЕНО]
            SharedPreferences.Editor editor = prefs.edit(); // [ИЗМЕНЕНО] Используем существующую prefs
            saveCallPref("translation_enabled", isChecked);
            applyRemoteVolume();
            if (isChecked) {
                selectedLanguageCode = languageAdapter.getLanguageCode(languageSpinner.getSelectedItemPosition());
                saveCallPref("translation_language", selectedLanguageCode);
                Log.d(TAG, "Translation enabled, selected language code: " + selectedLanguageCode);
            } else {
                selectedLanguageCode = null;
                editor.remove("translation_language_" + roomId);
                friendTranscriptionTextView.setText(getString(R.string.call_translate_listening)); // [ДОБАВЛЕНО] Сброс текста
                Log.d(TAG, "Translation disabled");
            }
            editor.apply();
            sendSettingsToServer(); // [ДОБАВЛЕНО] Отправка настроек
        });

        // [ИЗМЕНЕНО] Обработчик выбора языка с отправкой настроек
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (translationSwitch.isChecked()) {
                    selectedLanguageCode = languageAdapter.getLanguageCode(position);
                    if (onDeviceMode) {
                        if (vosk != null) {
                            stopOnDeviceSpeech();
                            startOnDeviceSpeech(); // другая модель под новый язык
                        }
                        // Сменился свой язык — пара для перевода тоже другая
                        if (peerLanguageCode != null) {
                            ensureTranslationPair(peerLanguageCode, selectedLanguageCode);
                        }
                    }
                    SharedPreferences.Editor editor = prefs.edit(); // [ИЗМЕНЕНО] Используем существующую prefs
                    saveCallPref("translation_language", selectedLanguageCode);
                    editor.apply();
                    Log.d(TAG, "Selected language code: " + selectedLanguageCode);
                    sendSettingsToServer(); // [ДОБАВЛЕНО] Отправка настроек
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedLanguageCode = null;
                Log.d(TAG, "No language selected");
                sendSettingsToServer(); // [ДОБАВЛЕНО] Отправка настроек
            }
        });

        initializeWebRTC();
        if (isCaller) {
            // Пока собеседник не ответил, честнее «звоним», чем «соединяем»:
            // соединять ещё нечего, вызов только ушёл
            showStatus(R.string.call_calling);
            Log.d(TAG, "Caller waiting for 'ready' or 'acceptCall' signal...");
        }
    }

    /**
     * Включить расшифровку своей речи.
     *
     * Раньше экран открывал микрофон второй раз, своим AudioRecord. Первым его
     * уже держал сам звонок, и на телефоне второй захват получал придушенный
     * сигнал или тишину — кому что достанется, зависело от того, кто успел
     * раньше. Отсюда и была расшифровка «через раз»: человек говорил, а до
     * сервера доходила тишина, он не находил ни одной фразы и молчал.
     *
     * Теперь микрофон один: звук берём тот же, что уходит собеседнику, —
     * WebRTC отдаёт свои отсчёты через {@link #onMicSamples}.
     */
    private void startAudioCapture() {
        if (isRecording) {
            Log.d(TAG, "Расшифровка своей речи уже включена");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "No RECORD_AUDIO permission");
            return;
        }
        isRecording = true;
        audioSeq = 0;
        micQueue.clear();
        startMicSender();
        Log.d(TAG, "Расшифровка своей речи включена (звук берём у звонка)");
    }

    private void stopAudioCapture() {
        if (!isRecording) return;
        isRecording = false;
        micRemainder = null;
        micQueue.clear();
        Log.d(TAG, "Расшифровка своей речи выключена");
    }

    /** Остаток от прошлой порции: отсчёты делим на куски по 50 мс. */
    private byte[] micRemainder;
    /** Номер куска по порядку — по нему сервер собирает речь как она звучала. */
    private int audioSeq;

    /** Насколько громкой была речь в последние секунды — по ней и выравниваем. */
    private int recentPeak;
    private long lastPeakReset;
    private double gain = 1.0;

    /**
     * Выровнять громкость.
     *
     * Звонок отдаёт запись уже обработанной: эхо убрано, шум подавлен, а
     * громкость рассчитана на передачу собеседнику — усиление делает уже
     * принимающая сторона. Нам же эта запись идёт на распознавание, и обычная
     * речь приходит на уровне около шести процентов шкалы. Нарезка фраз на
     * сервере считает такую речь тишиной, и большая часть сказанного просто не
     * доходила до распознавания.
     *
     * Поэтому подтягиваем уровень сами: смотрим, насколько громкой была речь в
     * последние секунды, и приводим её к удобному для распознавания уровню.
     * Усиление ограничено, тишина остаётся тишиной, а громкий голос не
     * захлёстывает — отсчёты не выходят за пределы шкалы.
     */
    private byte[] normalize(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = Math.abs((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)));
            if (sample > peak) peak = sample;
        }
        long now = System.currentTimeMillis();
        if (peak > recentPeak) recentPeak = peak;
        // Раз в три секунды забываем прежний пик: иначе случайный громкий звук
        // навсегда прижал бы усиление
        if (now - lastPeakReset > 3000) {
            lastPeakReset = now;
            recentPeak = Math.max(peak, recentPeak / 2);
        }
        if (recentPeak < 200) return pcm; // тишина — усиливать нечего

        // Целимся примерно в треть шкалы: громко для распознавания и далеко
        // от предела, за которым звук захлёстывает
        // Усиление меняем плавно: скачок между соседними порциями ломает
        // форму волны, и распознавание слышит дребезг вместо речи
        double target = 11000.0 / recentPeak;
        if (target < 1.0) target = 1.0;
        if (target > 8.0) target = 8.0;
        gain += (target - gain) * 0.02;
        if (gain <= 1.05) return pcm;

        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int louder = (int) Math.round(sample * gain);
            if (louder > 32767) louder = 32767;
            if (louder < -32768) louder = -32768;
            pcm[i] = (byte) (louder & 0xFF);
            pcm[i + 1] = (byte) ((louder >> 8) & 0xFF);
        }
        return pcm;
    }

    /**
     * Звук микрофона, уже записанный звонком.
     *
     * Приходит короткими порциями (10 мс). Собираем их в куски по 50 мс — в
     * таком виде звук и уходил на сервер раньше, и серверная нарезка фраз
     * рассчитана на него.
     */
    private void onMicSamples(org.webrtc.audio.JavaAudioDeviceModule.AudioSamples samples) {
        if (!isRecording) return;
        byte[] pcm = toMono16k(samples);
        if (pcm == null || pcm.length == 0) return;

        if (micRemainder != null && micRemainder.length > 0) {
            byte[] joined = new byte[micRemainder.length + pcm.length];
            System.arraycopy(micRemainder, 0, joined, 0, micRemainder.length);
            System.arraycopy(pcm, 0, joined, micRemainder.length, pcm.length);
            pcm = joined;
        }
        pcm = normalize(pcm);

        int chunk = 1600; // 50 мс при 16 кГц, 16 бит, моно
        int offset = 0;
        while (pcm.length - offset >= chunk) {
            byte[] piece = new byte[chunk];
            System.arraycopy(pcm, offset, piece, 0, chunk);
            offset += chunk;
            // В очередь, а не напрямую: звуковой поток звонка держать нельзя
            if (!micQueue.offer(piece)) {
                Log.w(TAG, "Очередь звука переполнена — кусок пропущен");
            }
        }
        int left = pcm.length - offset;
        micRemainder = new byte[left];
        System.arraycopy(pcm, offset, micRemainder, 0, left);
    }

    /** Приводим отсчёты к тому, чего ждёт распознавание: моно, 16 кГц, 16 бит. */
    private byte[] toMono16k(org.webrtc.audio.JavaAudioDeviceModule.AudioSamples samples) {
        byte[] data = samples.getData();
        int channels = samples.getChannelCount();
        int rate = samples.getSampleRate();
        if (data == null || data.length < 2) return null;

        short[] mono = new short[data.length / 2 / Math.max(1, channels)];
        for (int i = 0, j = 0; j < mono.length; i += 2 * channels, j++) {
            // Из стерео берём левый канал: для речи этого достаточно
            mono[j] = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
        }

        if (rate != 16000 && rate > 16000 && rate % 16000 == 0) {
            int factor = rate / 16000;
            short[] down = new short[mono.length / factor];
            for (int j = 0; j < down.length; j++) {
                int sum = 0;
                for (int k = 0; k < factor; k++) sum += mono[j * factor + k];
                down[j] = (short) (sum / factor);
            }
            mono = down;
        }

        byte[] out = new byte[mono.length * 2];
        for (int j = 0; j < mono.length; j++) {
            out[j * 2] = (byte) (mono[j] & 0xFF);
            out[j * 2 + 1] = (byte) ((mono[j] >> 8) & 0xFF);
        }
        return out;
    }

    /**
     * Очередь кусков звука.
     *
     * Отсчёты приходят из звукового потока звонка, и задерживать его нельзя:
     * пока мы кодируем в Base64 и отправляем, запись стоит, и часть речи
     * теряется. Поэтому кладём кусок в очередь, а отправляет отдельный поток.
     */
    private final java.util.concurrent.BlockingQueue<byte[]> micQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(200);
    private Thread micSender;

    private void startMicSender() {
        if (micSender != null && micSender.isAlive()) return;
        micSender = new Thread(() -> {
            while (isRecording) {
                try {
                    byte[] piece = micQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (piece != null) sendAudioChunk(piece, piece.length);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Кусок звука не ушёл: " + e.getMessage());
                }
            }
        }, "mic-sender");
        micSender.start();
    }

    /** Отправка куска: на устройство или на сервер — как и раньше. */
    private void sendAudioChunk(byte[] buffer, int bytesRead) {
        if (onDeviceMode && vosk != null && vosk.isReady()) {
            // Бесплатный режим: распознаём у себя, на сервер не уходит ничего
            vosk.accept(buffer, bytesRead);
            return;
        }
        if (stompClient == null || !stompClient.isConnected()) return;
        String base64Audio = android.util.Base64.encodeToString(buffer, 0, bytesRead, android.util.Base64.NO_WRAP);
        JSONObject audioMessage = new JSONObject();
        try {
            audioMessage.put("audio", base64Audio);
            audioMessage.put("sessionId", sessionId);
            // Номер куска: на сервере их разбирает несколько потоков сразу,
            // и без номера речь склеивается в переставленном порядке
            audioMessage.put("seq", audioSeq++);
            // Распознаванию сообщаем язык пользователя: угадывание по коротким
            // фразам ошибается (английская речь шла кириллицей)
            audioMessage.put("spokenLanguage", selectedLanguageCode != null ? selectedLanguageCode : "auto");
        } catch (JSONException e) {
            Log.e(TAG, "Error creating audio JSON: " + e.getMessage());
            return;
        }
        stompClient.send("/app/audio-transcription/" + roomId, audioMessage.toString())
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        () -> { },
                        throwable -> Log.e(TAG, "Error sending audio: " + throwable.getMessage())
                );
    }

    /**
     * Звуковое устройство звонка. Просим у него 16 кГц — ровно то, что нужно
     * распознаванию, — и забираем записанные отсчёты себе.
     */
    private org.webrtc.audio.AudioDeviceModule createAudioDeviceModule() {
        return org.webrtc.audio.JavaAudioDeviceModule.builder(getApplicationContext())
                .setInputSampleRate(16000)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(false)
                .setSamplesReadyCallback(this::onMicSamples)
                .createAudioDeviceModule();
    }


    private void subscribeToTranscription() {
        // Программно устанавливаем высоту ScrollView
        ScrollView scrollView = findViewById(R.id.transcription_scroll_view);
        TextView textView = findViewById(R.id.transcription_text_view);
        scrollView.post(() -> {
            float lineHeight = textView.getLineHeight();
            // Высоту не задаём: в разметке у области стоит 0dp с весом, и она
            // сама берёт столько места, сколько осталось. Жёсткие «5 строк»
            // делали экран выше телефона, и нижние элементы заезжали на кнопку.
            scrollView.requestLayout();
            Log.d(TAG, "Actual transcription_scroll_view height: " + scrollView.getHeight() + "px");
        });

        transcriptionSubscription = stompClient.topic("/topic/transcription/" + roomId)
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        message -> {
                            String payload = message.getPayload();
                            Log.d(TAG, "Received transcription message: " + payload);
                            try {
                                JSONObject json = new JSONObject(payload);
                                if (json.has("error")) {
                                    String error = json.getString("error");
                                    Log.e(TAG, "Transcription error: " + error);
                                    transcriptionTextView.setText("Error: " + error);
                                    scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                                } else if (json.has("transcription")) {
                                    String transcription = json.getString("transcription");
                                    String sessionId = json.getString("sessionId");
                                    if (sessionId.equals(this.sessionId)) {
                                        boolean wasAtBottom = isScrolledToBottom(scrollView);
                                        String currentText = transcriptionTextView.getText().toString();
                                        String[] lines = currentText.split("\n");
                                        StringBuilder newText = new StringBuilder();
                                        if (isPlaceholder(currentText) || currentText.startsWith("Error")) {
                                            newText.append(transcription);
                                        } else {
                                            if (lines.length >= 50) {
                                                for (int i = lines.length - 49; i < lines.length; i++) {
                                                    newText.append(lines[i]).append("\n");
                                                }
                                            } else {
                                                newText.append(currentText).append("\n");
                                            }
                                            newText.append(transcription);
                                        }
                                        transcriptionTextView.setText(newText.toString());
                                        if (wasAtBottom) {
                                            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                                        }
                                        Log.d(TAG, "Updated transcriptionTextView with: " + transcription);
                                    }
                                }
                            } catch (JSONException e) {
                                Log.e(TAG, "Error parsing transcription JSON: " + e.getMessage());
                            }
                        },
                        throwable -> {
                            Log.e(TAG, "Transcription subscription error: " + throwable.getMessage());
                            transcriptionTextView.setText("Subscription error: " + throwable.getMessage());
                            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                        }
                );
        Log.d(TAG, "Subscribed to transcription topic: /topic/transcription/" + roomId);
    }

    /*private void subscribeToTranscription() {
        // Программно устанавливаем высоту ScrollView
        ScrollView scrollView = findViewById(R.id.transcription_scroll_view);
        TextView textView = findViewById(R.id.transcription_text_view);
        scrollView.post(() -> {
            float lineHeight = textView.getLineHeight();
            // Высоту не задаём: в разметке у области стоит 0dp с весом, и она
            // сама берёт столько места, сколько осталось. Жёсткие «5 строк»
            // делали экран выше телефона, и нижние элементы заезжали на кнопку.
            scrollView.requestLayout();
            Log.d(TAG, "Actual transcription_scroll_view height: " + scrollView.getHeight() + "px");
        });

        transcriptionSubscription = stompClient.topic("/topic/transcription/" + roomId)
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        message -> {
                            String payload = message.getPayload();
                            Log.d(TAG, "Received transcription message: " + payload);
                            try {
                                JSONObject json = new JSONObject(payload);
                                if (json.has("error")) {
                                    String error = json.getString("error");
                                    Log.e(TAG, "Transcription error: " + error);
                                    transcriptionTextView.setText("Error: " + error);
                                    scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                                } else if (json.has("transcription")) {
                                    String transcription = json.getString("transcription");
                                    String sessionId = json.getString("sessionId");
                                    if (sessionId.equals(this.sessionId)) {
                                        // [ДОБАВЛЕНО] Проверяем, включён ли перевод
                                        if (translationSwitch.isChecked() && selectedLanguageCode != null && !selectedLanguageCode.equals("auto")) {
                                            // Выполняем перевод через /api/translate асинхронно
                                            translateTranscription(transcription, selectedLanguageCode, translatedText -> {
                                                String displayText = translatedText != null ? translatedText : transcription;
                                                updateTranscriptionTextView(displayText, scrollView);
                                            });
                                        } else {
                                            // Отображаем оригинальный текст
                                            updateTranscriptionTextView(transcription, scrollView);
                                        }
                                    }
                                }
                            } catch (JSONException e) {
                                Log.e(TAG, "Error parsing transcription JSON: " + e.getMessage());
                            }
                        },
                        throwable -> {
                            Log.e(TAG, "Transcription subscription error: " + throwable.getMessage());
                            transcriptionTextView.setText("Subscription error: " + throwable.getMessage());
                            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                        }
                );
        Log.d(TAG, "Subscribed to transcription topic: /topic/transcription/" + roomId);
    }

    private void updateTranscriptionTextView(String displayText, ScrollView scrollView) {
        String currentText = transcriptionTextView.getText().toString();
        String[] lines = currentText.split("\n");
        StringBuilder newText = new StringBuilder();
        if (isPlaceholder(currentText) || currentText.startsWith("Error")) {
            newText.append(displayText);
        } else {
            if (lines.length >= 50) {
                for (int i = lines.length - 49; i < lines.length; i++) {
                    newText.append(lines[i]).append("\n");
                }
            } else {
                newText.append(currentText).append("\n");
            }
            newText.append(displayText);
        }
        transcriptionTextView.setText(newText.toString());
        scrollView.post(() -> {
            if (scrollView.getScrollY() >= scrollView.getChildAt(0).getHeight() - scrollView.getHeight() - 50) {
                scrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
        Log.d(TAG, "Updated transcription_text_view with: " + displayText);
    }*/

    private void subscribeToFriendTranscription() {
        // Программно устанавливаем высоту ScrollView
        ScrollView scrollView = findViewById(R.id.friend_transcription_scroll_view);
        TextView textView = findViewById(R.id.friend_transcription_text_view);
        scrollView.post(() -> {
            float lineHeight = textView.getLineHeight();
            // Высоту не задаём: в разметке у области стоит 0dp с весом, и она
            // сама берёт столько места, сколько осталось. Жёсткие «5 строк»
            // делали экран выше телефона, и нижние элементы заезжали на кнопку.
            scrollView.requestLayout();
            Log.d(TAG, "Actual friend_transcription_scroll_view height: " + scrollView.getHeight() + "px");
        });

        friendSubscription = stompClient.topic("/topic/friend-transcription/" + roomId + "/" + currentUserId)
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        message -> {
                            String payload = message.getPayload();
                            Log.d(TAG, "Received friend transcription: " + payload);
                            try {
                                JSONObject json = new JSONObject(payload);
                                if (json.has("error")) {
                                    String error = json.getString("error");
                                    Log.e(TAG, "Friend transcription error: " + error);
                                    friendTranscriptionTextView.setText("Error: " + error);
                                    scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                                } else if (json.has("transcription")) {
                                    String transcription = json.getString("transcription");
                                    // [ДОБАВЛЕНО] Проверяем, включён ли перевод
                                    if (translationSwitch.isChecked() && selectedLanguageCode != null && !selectedLanguageCode.equals("auto")) {
                                        // Выполняем перевод через /api/translate асинхронно
                                        translateTranscription(transcription, selectedLanguageCode, translatedText -> {
                                            String displayText = translatedText != null ? translatedText : transcription;
                                            // Оригинал показываем под переводом: когда перевод звучит
                                            // странно, только по нему и понять, что было сказано
                                            String original = translatedText != null ? transcription : null;
                                            updateFriendTranscriptionTextView(displayText, original, scrollView);
                                        });
                                    } else {
                                        // Отображаем оригинальный текст
                                        updateFriendTranscriptionTextView(transcription, null, scrollView);
                                    }
                                }
                            } catch (JSONException e) {
                                Log.e(TAG, "Error parsing friend transcription JSON: " + e.getMessage());
                            }
                        },
                        throwable -> {
                            Log.e(TAG, "Friend transcription subscription error: " + throwable.getMessage());
                            friendTranscriptionTextView.setText("Subscription error: " + throwable.getMessage());
                            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                        }
                );
        Log.d(TAG, "Subscribed to friend transcription topic: /topic/friend-transcription/" + roomId + "/" + currentUserId);
    }

    /** Язык собеседника, чтобы заранее приготовить пару для перевода. */
    private String peerLanguageCode;
    /**
     * Последняя пара, про которую уже спросили. Нужна только затем, чтобы два
     * источника одного и того же языка (ответ сервера и рассылка выбора) не дали
     * два окна подряд. Любое новое переключение — своё или чужое — спрашивает
     * снова, пока пакета нет: иначе отказавшийся один раз остался бы без
     * перевода до конца звонка и без возможности передумать.
     */
    private String lastAskedPair;
    /** Окно про пакет: при смене языка прежнее уже неактуально. */
    private androidx.appcompat.app.AlertDialog packDialog;

    /**
     * Готовим перевод до начала разговора: узнаём язык собеседника у сервера и,
     * если пары нет, предлагаем скачать сразу. Иначе загрузка начиналась бы
     * посреди первой же фразы, и её пришлось бы ждать.
     */
    private void prepareTranslationForPeer() {
        prepareTranslationForPeer(0);
    }

    /**
     * Сервер держит настройки в памяти и узнаёт язык собеседника только когда
     * тот войдёт в звонок и пришлёт их. Если спросить раньше, ответом будет
     * «auto», и пакет никто не предложит — так и выходило: предложение
     * появлялось лишь после того, как собеседник переключит язык руками.
     * Поэтому спрашиваем ещё пару раз с задержкой.
     */
    private void prepareTranslationForPeer(int attempt) {
        if (recipientId == null || selectedLanguageCode == null) return;
        new Thread(() -> {
            try {
                okhttp3.OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url("https://unlimitedpossibilities12.org/api/settings/language?roomId=" + roomId
                                + "&userId=" + recipientId)
                        .build();
                try (okhttp3.Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) return;
                    String lang = response.body().string().trim();
                    if (lang.isEmpty() || "auto".equals(lang)) {
                        if (attempt < 3 && onDeviceMode) {
                            Log.d(TAG, "Язык собеседника пока неизвестен, попытка " + (attempt + 1));
                            new Handler(Looper.getMainLooper()).postDelayed(
                                    () -> prepareTranslationForPeer(attempt + 1), 3000);
                        }
                        return;
                    }
                    if (lang.equals(selectedLanguageCode)) return;
                    peerLanguageCode = lang;
                    Log.d(TAG, "Peer language: " + lang);
                    ensureTranslationPair(lang, selectedLanguageCode);
                }
            } catch (Exception e) {
                Log.w(TAG, "Не удалось узнать язык собеседника: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Собеседник мог ещё не выбрать язык, и сохранённое на сервере значение
     * бывает прошлым или умолчательным. Поэтому слушаем момент, когда он
     * действительно выбирает, — и готовим пару именно тогда.
     */
    private void subscribeToPeerLanguage() {
        if (stompClient == null || peerLanguageSubscription != null) return;
        peerLanguageSubscription = stompClient.topic("/topic/call-language/" + roomId)
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(message -> {
                    try {
                        JSONObject json = new JSONObject(message.getPayload());
                        String userId = json.optString("userId", "");
                        String language = json.optString("language", "");
                        if (userId.equals(String.valueOf(currentUserId))) return; // своё эхо
                        if (language.isEmpty() || "auto".equals(language)) return;
                        peerLanguageCode = language;
                        Log.d(TAG, "Peer chose language: " + language);
                        if (onDeviceMode && selectedLanguageCode != null) {
                            ensureTranslationPair(language, selectedLanguageCode);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing peer language: " + e.getMessage());
                    }
                }, t -> Log.e(TAG, "Peer language subscription error: " + t.getMessage()));
        Log.d(TAG, "Subscribed to peer language topic");
    }

    /** Если пары языков нет на устройстве — предложить скачать до начала разговора. */
    private void ensureTranslationPair(String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        String pair = from + ">" + to;
        if (pair.equals(lastAskedPair)) return; // то же самое прямо сейчас — одного окна хватит
        lastAskedPair = pair;
        LocalTranslator.isPairReady(from, to).addOnCompleteListener(ready -> {
            if (ready.isSuccessful() && Boolean.TRUE.equals(ready.getResult())) return;
            java.util.Set<String> needed = LocalTranslator.packsNeeded(from, to);
            int sizeMb = needed.size() * LocalTranslator.PACK_SIZE_MB;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (packDialog != null && packDialog.isShowing()) packDialog.dismiss();
                packDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Prepare translation")
                        .setMessage("Your friend speaks " + LocalTranslator.englishLabel(from)
                                + ". To translate it on your phone a language pack is needed — about "
                                + sizeMb + " MB, once. Download it now, before the talk starts?"
                                + System.lineSeparator() + System.lineSeparator()
                                + "If the language changes later, you will be offered the new pack.")
                        .setPositiveButton("Download", (d, w) -> {
                            Toast.makeText(this, "Downloading language pack…", Toast.LENGTH_SHORT).show();
                            LocalTranslator.downloadPacks(needed, false).addOnCompleteListener(t -> runOnUiThread(() ->
                                    Toast.makeText(this, t.isSuccessful()
                                            ? "Translation is ready" : "Could not download the pack",
                                            Toast.LENGTH_LONG).show()));
                        })
                        .setNegativeButton("Not now", null)
                        .show();
                // Отказ не запоминаем: передумать можно, просто переключив язык
            });
        });
    }

    /** Переключатель «перевод на устройстве»: виден вместе с языком перевода. */
    private void setupOnDeviceSwitch(boolean isTranslationEnabled) {
        if (onDeviceSwitch == null) return;
        onDeviceSwitch.setVisibility(isTranslationEnabled ? View.VISIBLE : View.GONE);
        onDeviceSwitch.setChecked(onDeviceMode);
        onDeviceSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && !VoskRecognizer.isLanguageSupported(selectedLanguageCode)) {
                Toast.makeText(this, "No on-device recognition for "
                        + VoskRecognizer.label(selectedLanguageCode)
                        + " yet — the server mode stays on", Toast.LENGTH_LONG).show();
                onDeviceSwitch.setChecked(false);
                return;
            }
            onDeviceMode = checked;
            saveCallPref("on_device_translate", checked);
            if (checked) {
                startOnDeviceSpeech();
                subscribeToPeerLanguage();
                prepareTranslationForPeer();
                sendSettingsToServer(); // кто озвучивает перевод — решает слушатель
                Toast.makeText(this, "On-device translation: free, and the audio never leaves your phone",
                        Toast.LENGTH_SHORT).show();
            } else {
                stopOnDeviceSpeech();
                sendSettingsToServer();
                Toast.makeText(this, "Translation through the server", Toast.LENGTH_SHORT).show();
            }
        });
        if (onDeviceMode && isTranslationEnabled) {
            startOnDeviceSpeech();
            subscribeToPeerLanguage();
            prepareTranslationForPeer();
        }
    }

    /** Распознавание своей речи на устройстве: по сети уходит только текст. */
    private void startOnDeviceSpeech() {
        if (onDeviceSpeech == null) {
            // Для озвучки перевода голосом телефона — распознавание делает Vosk
            onDeviceSpeech = new OnDeviceSpeech(this, new OnDeviceSpeech.Listener() {
                @Override public void onPhrase(String text) {}
                @Override public void onUnavailable(String reason) {}
            });
        }
        // start() мы не вызываем — микрофон занят общим захватом, но синтез
        // речи создаётся именно там, поэтому готовим его отдельно
        onDeviceSpeech.prepareTts();
        // Пока телефон произносит перевод, живой голос собеседника приглушаем
        onDeviceSpeech.setSpeakingListener(speaking -> runOnUiThread(() -> duckRemoteVoice(speaking)));
        if (vosk != null) return;
        // Захват микрофона не останавливаем: Vosk принимает тот же поток, что
        // мы уже читаем во время звонка, и за микрофон ни с кем не спорит
        if (!isRecording) startAudioCapture();
        vosk = new VoskRecognizer(this, new VoskRecognizer.Listener() {
            @Override
            public void onPhrase(String text) {
                // На экран фразу не пишем: сервер рассылает её обоим участникам,
                // и подписка на /topic/transcription покажет её сама — иначе
                // собственная реплика появлялась дважды
                if (stompClient == null || !stompClient.isConnected()) return;
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("sessionId", sessionId);
                    payload.put("text", text);
                    stompClient.send("/app/local-transcription/" + roomId, payload.toString())
                            .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                            .subscribe(() -> Log.d(TAG, "Sent on-device transcription: " + text),
                                    t -> Log.e(TAG, "Error sending on-device transcription: " + t.getMessage()));
                } catch (JSONException e) {
                    Log.e(TAG, "Error building local transcription JSON: " + e.getMessage());
                }
            }

            @Override
            public void onModelMissing(String languageCode, int sizeMb) {
                runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(VoiceCallWithTranslateActivity.this)
                        .setTitle("Speech model needed")
                        .setMessage("To recognise speech on your phone, a model for "
                                + VoskRecognizer.label(languageCode) + " has to be downloaded — about "
                                + sizeMb + " MB, once. After that translation is free and works offline.")
                        .setPositiveButton("Download", (d, w) -> {
                            Toast.makeText(VoiceCallWithTranslateActivity.this,
                                    "Downloading the model…", Toast.LENGTH_SHORT).show();
                            vosk.downloadAndPrepare(languageCode);
                        })
                        .setNegativeButton("Not now", (d, w) -> {
                            onDeviceMode = false;
                            if (onDeviceSwitch != null) onDeviceSwitch.setChecked(false);
                            stopOnDeviceSpeech();
                        })
                        .show());
            }

            @Override
            public void onDownloadProgress(int percent) {
                // Одна обновляемая строка, а не новая на каждые 5% — иначе окно
                // транскрипции забивалось десятком одинаковых сообщений
                runOnUiThread(() -> showModelProgress(percent));
            }

            @Override
            public void onReady() {
                runOnUiThread(() -> {
                    if (transcriptionTextView != null) {
                        String text = transcriptionTextView.getText().toString();
                        int at = text.lastIndexOf("Downloading speech model: ");
                        if (at >= 0) transcriptionTextView.setText(text.substring(0, at).trim());
                    }
                    Toast.makeText(VoiceCallWithTranslateActivity.this,
                            "On-device recognition is ready", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(VoiceCallWithTranslateActivity.this, message, Toast.LENGTH_LONG).show();
                    onDeviceMode = false;
                    if (onDeviceSwitch != null) onDeviceSwitch.setChecked(false);
                    stopOnDeviceSpeech();
                });
            }
        });
        vosk.prepare(selectedLanguageCode);
        Log.d(TAG, "On-device recognition starting, language=" + selectedLanguageCode);
    }

    /**
     * Настройки разговора хранились только для конкретной комнаты, поэтому с
     * каждым новым собеседником язык и озвучка начинались заново — выглядело
     * как «работает нестабильно». Теперь выбор помнится и как общий: для
     * знакомой комнаты берётся её значение, для новой — последнее, что человек
     * выбрал вообще.
     */
    private boolean callPref(String name, boolean fallback) {
        SharedPreferences prefs = getSharedPreferences("call_prefs", MODE_PRIVATE);
        if (prefs.contains(name + "_" + roomId)) {
            return prefs.getBoolean(name + "_" + roomId, fallback);
        }
        return prefs.getBoolean(name + "_default", fallback);
    }

    private String callPref(String name, String fallback) {
        SharedPreferences prefs = getSharedPreferences("call_prefs", MODE_PRIVATE);
        if (prefs.contains(name + "_" + roomId)) {
            return prefs.getString(name + "_" + roomId, fallback);
        }
        return prefs.getString(name + "_default", fallback);
    }

    /** Пишем и для комнаты, и в общее значение — чтобы перенеслось на нового собеседника. */
    private void saveCallPref(String name, boolean value) {
        getSharedPreferences("call_prefs", MODE_PRIVATE).edit()
                .putBoolean(name + "_" + roomId, value)
                .putBoolean(name + "_default", value)
                .apply();
    }

    private void saveCallPref(String name, String value) {
        getSharedPreferences("call_prefs", MODE_PRIVATE).edit()
                .putString(name + "_" + roomId, value)
                .putString(name + "_default", value)
                .apply();
    }

    /**
     * Приглушить живой голос собеседника, пока звучит перевод.
     *
     * Без этого оба голоса идут вместе и не разобрать ни один. Полностью не
     * выключаем: по тихому оригиналу слышно, говорит человек или уже закончил.
     */
    private void duckRemoteVoice(boolean duck) {
        translationSpeaking = duck;
        applyRemoteVolume();
    }

    /**
     * Громкость входящего голоса по текущему состоянию: без озвучки — полная,
     * с озвучкой — фоновая, а на время самой озвучки тишина.
     *
     * Вызывать нужно при каждом изменении состояния и сразу, как появилась
     * дорожка собеседника: иначе первая его фраза звучит в полный голос, а
     * фоновой громкость становится только после первого же перевода.
     */
    /**
     * Держать громкость входящего голоса.
     *
     * Выставить её один раз мало: пока по дорожке не пошёл звук, значение не
     * прилипает — дорожка остаётся в полный голос, и первая фраза собеседника
     * звучала громко, а тихой становилась только после первого перевода.
     * Поэтому подтверждаем значение каждые две секунды, пока идёт разговор.
     */
    private Runnable volumeKeeper;
    private double lastRemoteVolume = -1;

    private void keepRemoteVolume() {
        if (volumeKeeper != null) return;
        Handler handler = new Handler(Looper.getMainLooper());
        volumeKeeper = new Runnable() {
            @Override
            public void run() {
                applyRemoteVolume();
                handler.postDelayed(this, 2000);
            }
        };
        handler.post(volumeKeeper);
    }

    private void stopKeepingRemoteVolume() {
        if (volumeKeeper == null) return;
        new Handler(Looper.getMainLooper()).removeCallbacks(volumeKeeper);
        volumeKeeper = null;
    }

    private void applyRemoteVolume() {
        if (remoteAudioTrack == null) return;
        boolean voiceTranslation = ttsSwitch != null && ttsSwitch.isChecked()
                && translationSwitch != null && translationSwitch.isChecked();
        double volume = !voiceTranslation ? 1.0
                : (translationSpeaking ? DUCK_VOLUME : BACKGROUND_VOLUME);
        try {
            remoteAudioTrack.setVolume(volume);
            if (volume != lastRemoteVolume) {
                lastRemoteVolume = volume;
                Log.d(TAG, "Remote voice volume: " + volume);
            }
        } catch (Exception e) {
            Log.w(TAG, "Не удалось изменить громкость входящего голоса: " + e.getMessage());
        }
    }

    /** Озвучку выключили посреди фразы — замолчать, а не договаривать. */
    private void stopSpeaking() {
        if (onDeviceSpeech != null) onDeviceSpeech.silence();
        stopKeepingRemoteVolume();
        clearTtsQueue();
        if (ttsMediaPlayer != null) {
            try {
                ttsMediaPlayer.stop();
                ttsMediaPlayer.release();
            } catch (Exception ignored) {
            }
            ttsMediaPlayer = null;
        }
        duckRemoteVoice(false);
    }

    /** Ход загрузки модели — одной строкой, которая обновляется на месте. */
    private void showModelProgress(int percent) {
        if (transcriptionTextView == null) return;
        String current = transcriptionTextView.getText().toString();
        String prefix = "Downloading speech model: ";
        int at = current.lastIndexOf(prefix);
        String head = at >= 0 ? current.substring(0, at) : (current.isEmpty() ? "" : current + System.lineSeparator());
        transcriptionTextView.setText(head + prefix + percent + "%");
    }

    /** Показать собственную распознанную фразу в окне транскрипции. */
    private void appendOwnTranscription(String text) {
        if (transcriptionTextView == null || text == null || text.isEmpty()) return;
        String current = transcriptionTextView.getText().toString();
        transcriptionTextView.setText(current.isEmpty() ? text : current + System.lineSeparator() + text);
        ScrollView scroll = findViewById(R.id.transcription_scroll_view);
        if (scroll != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * Собеседник заговорил на языке, пакета для которого нет: предлагаем скачать.
     * Без этого его фразы молча оставались бы без перевода.
     */
    private void offerMissingPack(String text, String targetLanguage) {
        if (packPromptShown) return;
        LocalTranslator.missingPacksFor(text, targetLanguage).addOnCompleteListener(t -> {
            if (!t.isSuccessful() || t.getResult() == null || t.getResult().isEmpty()) return;
            java.util.Set<String> missing = t.getResult();
            packPromptShown = true;
            StringBuilder names = new StringBuilder();
            for (String code : missing) {
                if (names.length() > 0) names.append(", ");
                names.append(LocalTranslator.englishLabel(code));
            }
            int sizeMb = missing.size() * LocalTranslator.PACK_SIZE_MB;
            runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Language pack needed")
                    .setMessage("Your friend speaks another language. To translate it on your phone, "
                            + "these have to be downloaded: " + names + " (~" + sizeMb + " MB, once).")
                    .setPositiveButton("Download", (d, w) -> {
                        Toast.makeText(this, "Downloading language pack…", Toast.LENGTH_SHORT).show();
                        LocalTranslator.downloadPacks(missing, false).addOnCompleteListener(dl -> runOnUiThread(() ->
                                Toast.makeText(this, dl.isSuccessful()
                                        ? "Pack is ready — translation starts with the next phrase"
                                        : "Could not download the pack", Toast.LENGTH_LONG).show()));
                    })
                    .setNegativeButton("Not now", null)
                    .show());
        });
    }

    private void stopOnDeviceSpeech() {
        if (vosk != null) {
            vosk.flush();
            vosk.close();
            vosk = null;
        }
        if (onDeviceSpeech != null) {
            onDeviceSpeech.stop();
            onDeviceSpeech = null;
            // Вернулись к серверному режиму — снова шлём звук на распознавание
            if (getSharedPreferences("call_prefs", MODE_PRIVATE)
                    .getBoolean("translation_enabled_" + roomId, false)) {
                startAudioCapture();
            }
        }
    }

    // Метод для перевода транскрипции через /api/translate с использованием Retrofit
    // и с фильтрацией нежелательных переводов
    private void translateTranscription(String text, String targetLanguage, Consumer<String> callback) {
        if (onDeviceMode) {
            // Бесплатный путь: перевод делает ML Kit прямо здесь, текст никуда не уходит
            LocalTranslator.translateToIfNeeded(text, targetLanguage).addOnCompleteListener(task -> {
                String translated = (task.isSuccessful() && task.getResult() != null) ? task.getResult() : null;
                callback.accept(translated != null ? translated : text);
                if (translated != null) {
                    if (onDeviceSpeech != null && ttsSwitch != null && ttsSwitch.isChecked()) {
                        onDeviceSpeech.speak(translated, targetLanguage);
                    }
                } else {
                    // Перевода нет — возможно, не хватает языкового пакета собеседника
                    offerMissingPack(text, targetLanguage);
                }
            });
            return;
        }
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("prompt", text);
            requestBody.put("targetLanguage", targetLanguage);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating translation JSON: " + e.getMessage());
            callback.accept(null); // Возвращаем null в случае ошибки JSON
            return;
        }

        MessageRequest messageRequest = new MessageRequest(text, targetLanguage);
        Call<String> call = apiService.translateMessage(messageRequest);
        call.enqueue(new Callback<String>() {
            @Override
            public void onResponse(Call<String> call, Response<String> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String translatedText = response.body();
                    // [ДОБАВЛЕНО] Фильтрация нежелательных переводов
                    String normalized = translatedText.toLowerCase().replaceAll("\\s+", " ").trim();
                    if (translatedText.contains("\"") && (normalized.contains("translat") || normalized.contains("phrase") || normalized.contains("word"))) {
                        Log.e(TAG, "Filtered out invalid translation: " + translatedText);
                        callback.accept(null); // Возвращаем null, чтобы использовать оригинальный текст
                        return;
                    }
                    // [ДОБАВЛЕНО] Дополнительная проверка на нежелательные фразы
                    if (normalized.contains("the translation") || normalized.contains("translated") || normalized.contains("phrase is")) {
                        Log.e(TAG, "Filtered out invalid translation with translation/phrase: " + translatedText);
                        callback.accept(null); // Возвращаем null для строк вроде "The translation phrase is ..."
                        return;
                    }
                    // [ИЗМЕНЕНО] Удаляем кавычки, если они есть, как в ChatGPTController
                    if (translatedText.startsWith("\"") && translatedText.endsWith("\"")) {
                        translatedText = translatedText.substring(1, translatedText.length() - 1);
                    }
                    Log.d(TAG, "Translated transcription to " + targetLanguage + ": " + translatedText);
                    callback.accept(translatedText);
                } else {
                    Log.e(TAG, "Translation API error: " + response.code() + ", " + (response.errorBody() != null ? response.errorBody().toString() : "No response body"));
                    callback.accept(null); // Возвращаем null в случае ошибки
                }
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                Log.e(TAG, "Error during translation request: " + t.getMessage());
                callback.accept(null); // Возвращаем null в случае ошибки
            }
        });
    }

    // [ДОБАВЛЕНО] Метод для обновления текста в friend_transcription_text_view
    /**
     * Стоял ли человек у нижнего края до того, как добавилась новая строка.
     *
     * Спрашивать об этом после вставки текста бессмысленно: высота содержимого
     * уже новая, а положение прокрутки ещё старое, и разница сразу больше любого
     * допуска — проверка «мы внизу?» навсегда становится ложной, и последние
     * фразы приходится доставать прокруткой вручную.
     */
    private static boolean isScrolledToBottom(ScrollView scrollView) {
        android.view.View child = scrollView.getChildAt(0);
        if (child == null) return true;
        int bottomEdge = child.getHeight() - scrollView.getHeight();
        // Содержимое короче окошка — значит, мы и так внизу
        return bottomEdge <= 0 || scrollView.getScrollY() >= bottomEdge - 50;
    }

    /**
     * Добавить фразу собеседника.
     *
     * @param original исходные слова: показываем их мельче и тусклее под
     *                 переводом. Перевод иногда получается странным, и тогда
     *                 понять собеседника можно только по тому, что он сказал
     *                 на самом деле.
     */
    private void updateFriendTranscriptionTextView(String displayText, String original, ScrollView scrollView) {
        boolean wasAtBottom = isScrolledToBottom(scrollView);
        CharSequence current = friendTranscriptionTextView.getText();
        String plain = current.toString();
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
        if (!isPlaceholder(plain) && !plain.startsWith("Error")) {
            text.append(current).append("\n");
        }
        text.append(displayText);
        if (original != null && !original.trim().equalsIgnoreCase(displayText.trim())) {
            int from = text.length();
            text.append("\n").append(original);
            text.setSpan(new android.text.style.RelativeSizeSpan(0.82f), from, text.length(),
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.ForegroundColorSpan(0xFF7C8BA1), from, text.length(),
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        friendTranscriptionTextView.setText(text);
        // Прокручиваем, только если человек сам не ушёл читать выше
        if (wasAtBottom) scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        Log.d(TAG, "Updated friend_transcription_text_view with: " + displayText);
    }

    // [ДОБАВЛЕНО] Подписка на TTS-аудио
    private void subscribeToTts() {
        ttsSubscription = stompClient.topic("/topic/tts/" + roomId + "/" + currentUserId)
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        message -> {
                            String payload = message.getPayload();
                            Log.d(TAG, "Received TTS audio: " + payload);
                            try {
                                JSONObject json = new JSONObject(payload);
                                if (json.has("audio") && json.has("sessionId")) {
                                    String base64Audio = json.getString("audio");
                                    byte[] audioData = android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT);
                                    File tempFile = File.createTempFile("tts_audio", ".mp3", getCacheDir());
                                    FileOutputStream fos = new FileOutputStream(tempFile);
                                    fos.write(audioData);
                                    fos.close();
                                    enqueueTts(tempFile);
                                }
                            } catch (JSONException | IOException e) {
                                Log.e(TAG, "Error processing TTS audio: " + e.getMessage());
                            }
                        },
                        throwable -> Log.e(TAG, "TTS subscription error: " + throwable.getMessage())
                );
        Log.d(TAG, "Subscribed to TTS topic: /topic/tts/" + roomId + "/" + currentUserId);
    }

    // NEW: Регистрация recipientId
    private void registerRecipient() {
        if (stompClient != null && stompClient.isConnected()) {
            JSONObject registerMessage = new JSONObject();
            try {
                registerMessage.put("sessionId", sessionId);
                registerMessage.put("recipientId", recipientId); // NEW: Используем recipientId напрямую
            } catch (JSONException e) {
                Log.e(TAG, "Error creating register JSON: " + e.getMessage());
            }
            stompClient.send("/app/register-recipient/" + roomId, registerMessage.toString())
                    .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                    .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                    .subscribe(
                            () -> Log.d(TAG, "Sent register recipient: sessionId=" + sessionId + ", recipientId=" + registerMessage.optString("recipientId")),
                            throwable -> Log.e(TAG, "Error sending register recipient: " + throwable.getMessage())
                    );
        } else {
            Log.e(TAG, "STOMP client not connected, cannot register recipient");
        }
    }


    private void subscribeToRoom() {
        roomSubscription = stompClient.topic("/topic/room/" + roomId)
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(
                        message -> {
                            String payload = message.getPayload();
                            Log.d(TAG, "Received raw message: " + payload);
                            try {
                                JSONObject json = new JSONObject(payload);
                                handleSignal(json);
                            } catch (JSONException e) {
                                Log.e(TAG, "Error parsing message: " + e.getMessage());
                            }
                        },
                        throwable -> Log.e(TAG, "Subscription error: " + throwable.getMessage())
                );
    }

    private void initializeWebRTC() {
        PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(this)
                .createInitializationOptions();
        PeerConnectionFactory.initialize(options);
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(createAudioDeviceModule())
                .createPeerConnectionFactory();

        if (currentUserId == null) {
            Log.e(TAG, "currentUserId is undefined. Check the Intent extras.");
            finish();
        }
    }

    private void checkPermissionsAndStartCall() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
        } else if (!isCallStarted) {
            startCall();
        } else {
            Log.d(TAG, "Call already started, ignoring checkPermissionsAndStartCall");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (!isCallStarted) {
                startCall();
            }
        } else {
            Toast.makeText(this, "Permission to use microphone denied", Toast.LENGTH_LONG).show();
            endCall();
        }
    }

    private void startCall() {
        if (isCallStarted || isOfferSent) {
            Log.d(TAG, "Call already started or offer sent, ignoring duplicate startCall");
            return;
        }
        isCallStarted = true;
        isOfferSent = true;
        Log.d(TAG, "startCall working...");
        userName = recipientName;
        Log.d(TAG, "userName: " + userName);

        initializePeerConnection(true);

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "Local description set: " + sdp.description);
                        sendSignal(createSignalJson("offer", sdp));
                    }

                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}

                    @Override
                    public void onSetFailure(String error) {
                        Log.e(TAG, "Failed to set local description: " + error);
                    }

                    @Override
                    public void onCreateFailure(String error) {}
                }, sdp);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onCreateFailure(String error) {
                Log.e(TAG, "Failed to create offer: " + error);
            }

            @Override
            public void onSetFailure(String error) {}
        }, new MediaConstraints());

        statsHandler = new Handler(Looper.getMainLooper());
        statsRunnable = () -> peerConnection.getStats(statsReport -> {
            RTCStatsReport report = statsReport;
            for (RTCStats stats : report.getStatsMap().values()) {
                if (stats.getType().equals("outbound-rtp") && stats.getId().contains("audio")) {
                    Log.d(TAG, "Outbound RTP: bytesSent=" + stats.getMembers().get("bytesSent"));
                }
            }
            statsHandler.postDelayed(statsRunnable, 5000);
        });
        statsHandler.postDelayed(statsRunnable, 5000);

        startAudioCapture();
    }

    private void initializePeerConnection(boolean isCaller) {
        if (peerConnection != null) {
            if (peerConnection.signalingState() != PeerConnection.SignalingState.CLOSED) {
                Log.d(TAG, "PeerConnection already initialized and not closed, reusing it.");
                return;
            } else {
                Log.d(TAG, "Closing and disposing existing PeerConnection.");
                peerConnection.close();
                peerConnection.dispose();
                peerConnection = null;
            }
        }

        if (peerConnectionFactory == null) {
            Log.d(TAG, "PeerConnectionFactory is null, initializing...");
            PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                    .setNativeLibraryName("jingle_peerconnection_so")
                    .createInitializationOptions();
            PeerConnectionFactory.initialize(options);
            PeerConnectionFactory.Options factoryOptions = new PeerConnectionFactory.Options();
            peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(factoryOptions)
                    .setAudioDeviceModule(createAudioDeviceModule())
                    .createPeerConnectionFactory();
        }

        MediaConstraints audioConstraints = new MediaConstraints();
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
        Log.d(TAG, "AudioSource created: " + audioSource);
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);
        Log.d(TAG, "LocalAudioTrack created and enabled: " + localAudioTrack);

        MediaStream mediaStream = peerConnectionFactory.createLocalMediaStream("stream0");
        mediaStream.addTrack(localAudioTrack);
        Log.d(TAG, "MediaStream created and audio track added: " + mediaStream);

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Arrays.asList(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("turn:13.49.238.121:3478?transport=tcp")
                        .setUsername(isCaller ? "username1" : "username2")
                        .setPassword(isCaller ? "key1" : "key2")
                        .createIceServer()
        ));
        config.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = peerConnectionFactory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState newState) {
                Log.d(TAG, "SignalingState: " + newState);
            }

            @Override
            public void onIceCandidate(IceCandidate candidate) {
                Log.d(TAG, "onIceCandidate event fired: " + candidate);
                sendSignal(createSignalJson("candidate", candidate));
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {
                Log.d(TAG, "onIceCandidatesRemoved: " + Arrays.toString(candidates));
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
                Log.d(TAG, "IceConnectionState: " + newState);
                if (newState == PeerConnection.IceConnectionState.CONNECTED || newState == PeerConnection.IceConnectionState.COMPLETED) {
                    Log.d(TAG, "WebRTC connection established, starting audio capture");
                    runOnUiThread(() -> {
                        if (!isRecording) {
                            startAudioCapture();
                        }
                    });
                    peerConnection.getStats(statsReport -> {
                        RTCStatsReport report = statsReport;
                        for (RTCStats stats : report.getStatsMap().values()) {
                            if (stats.getType().equals("candidate-pair") && stats.getMembers().get("state").equals("succeeded")) {
                                Log.d(TAG, "Selected ICE pair: " + stats.toString());
                            }
                        }
                    });
                } else if (newState == PeerConnection.IceConnectionState.FAILED || newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    Log.w(TAG, "ICE connection failed or disconnected, waiting for endCall signal...");
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {
                Log.d(TAG, "IceConnectionReceivingChange: " + receiving);
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                Log.d(TAG, "PeerConnectionState: " + newState);
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "DataChannel created: " + dataChannel.label());
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed.");
            }

            @Override
            public void onAddStream(MediaStream stream) {
                Log.d(TAG, "Remote stream added: " + stream);
            }

            @Override
            public void onRemoveStream(MediaStream stream) {
                Log.d(TAG, "Remote stream removed: " + stream);
            }

            @Override
            public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
                Log.d(TAG, "Remote track added: " + receiver.id());
                // Дорожку берём у самого получателя: массив потоков в Unified Plan
                // приходит пустым, и тогда приглушать было бы нечего
                org.webrtc.MediaStreamTrack track = receiver.track();
                if (track instanceof AudioTrack) {
                    remoteAudioTrack = (AudioTrack) track;
                    remoteAudioTrack.setEnabled(true);
                    runOnUiThread(VoiceCallWithTranslateActivity.this::keepRemoteVolume);
                    Log.d(TAG, "Remote audio track from receiver: " + remoteAudioTrack.id());
                } else if (mediaStreams.length > 0 && !mediaStreams[0].audioTracks.isEmpty()) {
                    remoteAudioTrack = mediaStreams[0].audioTracks.get(0);
                    remoteAudioTrack.setEnabled(true);
                    // Громкость выставляем и здесь: иначе первая фраза
                    // собеседника звучала в полный голос, а все следующие —
                    // приглушённо, потому что фон включался только после
                    // первой озвучки перевода
                    runOnUiThread(VoiceCallWithTranslateActivity.this::keepRemoteVolume);
                    Log.d(TAG, "Remote audio track enabled: " + remoteAudioTrack.id());
                }
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
                Log.d(TAG, "IceGatheringState: " + newState);
            }
        });

        if (peerConnection == null) {
            Log.e(TAG, "Failed to initialize RTCPeerConnection!");
            return;
        }

        Log.d(TAG, "RTCPeerConnection initialized: " + peerConnection);
        peerConnection.addTrack(localAudioTrack, Arrays.asList("stream0"));
        Log.d(TAG, "Local audio track added to peer connection, enabled: " + localAudioTrack.enabled() + ", state: " + localAudioTrack.state());
    }

    private void handleSignal(JSONObject signalMessage) {
        Log.d(TAG, "Received signal message: " + signalMessage);
        try {
            String from = signalMessage.getString("from");
            if (from.equals(currentUserId)) {
                Log.d(TAG, "Ignoring own message from " + from);
                return;
            }

            JSONObject signal = signalMessage.getJSONObject("signal");
            String type = signal.getString("type");
            Log.d(TAG, "Processing signal type: " + type);

            switch (type) {
                case "offer":
                    if (!isCaller) {
                        if (peerConnection == null || peerConnection.signalingState() == PeerConnection.SignalingState.CLOSED) {
                            Log.d(TAG, "PeerConnection is null or closed when handling offer, initializing...");
                            initializePeerConnection(false);
                        } else {
                            Log.d(TAG, "Reusing existing PeerConnection for offer");
                        }
                        String sdp = signal.getString("sdp");
                        Log.d(TAG, "Received offer SDP: " + sdp);
                        if (checkSignature(sdp, signal)) return;
                        peerConnection.setRemoteDescription(new SdpObserver() {
                            @Override
                            public void onSetSuccess() {
                                Log.d(TAG, "Remote offer set successfully");
                                peerConnection.createAnswer(new SdpObserver() {
                                    @Override
                                    public void onCreateSuccess(SessionDescription answer) {
                                        Log.d(TAG, "Answer created: " + answer.description);
                                        peerConnection.setLocalDescription(new SdpObserver() {
                                            @Override
                                            public void onSetSuccess() {
                                                Log.d(TAG, "Local answer set successfully");
                                                sendSignal(createSignalJson("answer", answer));
                                            }

                                            @Override
                                            public void onSetFailure(String error) {
                                                Log.e(TAG, "Failed to set local answer: " + error);
                                            }

                                            @Override
                                            public void onCreateSuccess(SessionDescription sdp) {}

                                            @Override
                                            public void onCreateFailure(String error) {}
                                        }, answer);
                                    }

                                    @Override
                                    public void onCreateFailure(String error) {
                                        Log.e(TAG, "Failed to create answer: " + error);
                                    }

                                    @Override
                                    public void onSetSuccess() {}

                                    @Override
                                    public void onSetFailure(String error) {}
                                }, new MediaConstraints());
                            }

                            @Override
                            public void onSetFailure(String error) {
                                Log.e(TAG, "Failed to set remote offer: " + error);
                            }

                            @Override
                            public void onCreateSuccess(SessionDescription sdp) {}

                            @Override
                            public void onCreateFailure(String error) {}
                        }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
                    }
                    break;

                case "answer":
                    showStatus(R.string.call_connecting);
                    if (isCaller && peerConnection != null) {
                        String sdp = signal.getString("sdp");
                        if (checkSignature(sdp, signal)) return;
                        peerConnection.setRemoteDescription(new SdpObserver() {
                            @Override
                            public void onSetSuccess() {
                                Log.d(TAG, "Remote answer set");
                            }

                            @Override
                            public void onCreateSuccess(SessionDescription sdp) {}

                            @Override
                            public void onSetFailure(String error) {
                                Log.e(TAG, "Failed to set remote description: " + error);
                            }

                            @Override
                            public void onCreateFailure(String error) {}
                        }, new SessionDescription(SessionDescription.Type.ANSWER, sdp));
                    }
                    break;

                case "candidate":
                    JSONObject iceCandidate = signal.getJSONObject("iceCandidate");
                    String candidate = iceCandidate.getString("candidate");
                    String sdpMid = iceCandidate.getString("sdpMid");
                    int sdpMLineIndex = iceCandidate.getInt("sdpMLineIndex");
                    IceCandidate iceCandidateToAdd = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
                    if (peerConnection != null) {
                        peerConnection.addIceCandidate(iceCandidateToAdd);
                        Log.d(TAG, "ICE candidate added: " + iceCandidateToAdd);
                    }
                    break;

                case "ready":
                    if (isCaller) {
                        Log.d(TAG, "Received ready signal from the other party.");
                        isWaiting = false;
                        if (ringtonePlayer != null && ringtonePlayer.isPlaying()) {
                            ringtonePlayer.pause();
                            ringtonePlayer.seekTo(0);
                            Log.d(TAG, "Ringtone paused");
                        }
                        checkPermissionsAndStartCall();
                    }
                    break;

                case "acceptCall":
                    if (isCaller) {
                        Log.d(TAG, "Received acceptCall signal.");
                        Toast.makeText(this, recipientName + " accepted the call.", Toast.LENGTH_LONG).show();
                    }
                    break;

                case "rejectCall":
                    if (isCaller) {
                        Log.d(TAG, "Received rejectCall signal.");
                        isWaiting = false;
                        if (ringtonePlayer != null && ringtonePlayer.isPlaying()) {
                            ringtonePlayer.pause();
                            ringtonePlayer.seekTo(0);
                        }
                        Toast.makeText(this, recipientName + " rejected the call.", Toast.LENGTH_LONG).show();
                        saveCall("rejected");
                        endCall();
                    }
                    break;

                case "endCall":
                    Log.d(TAG, "Call ended by the other party.");
                    String notificationMessage = userName != null
                            ? userName + " interrupted the conversation."
                            : "User with ID " + from + " interrupted the conversation.";
                    Toast.makeText(this, notificationMessage, Toast.LENGTH_LONG).show();
                    terminationInitiator = true;
                    endCall();
                    break;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing signal message: " + e.getMessage());
        }
    }

    private JSONObject createSignalJson(String type, Object data) {
        JSONObject message = new JSONObject();
        JSONObject signal = new JSONObject();
        try {
            signal.put("type", type);
            if (data instanceof SessionDescription) {
                SessionDescription sdp = (SessionDescription) data;
                signal.put("sdp", sdp.description);
                // Подписываем описание: по подписи собеседник узнаёт, что это мы
                String signature = callGuard != null ? callGuard.sign(sdp.description) : null;
                if (signature != null) {
                    signal.put("signature", signature);
                    signal.put("deviceId", callGuard.deviceId());
                }
            } else if (data instanceof IceCandidate) {
                IceCandidate candidate = (IceCandidate) data;
                JSONObject iceCandidate = new JSONObject();
                iceCandidate.put("candidate", candidate.sdp);
                iceCandidate.put("sdpMid", candidate.sdpMid);
                iceCandidate.put("sdpMLineIndex", candidate.sdpMLineIndex);
                signal.put("iceCandidate", iceCandidate);
            }
            message.put("from", currentUserId);
            message.put("signal", signal);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating signal JSON: " + e.getMessage());
        }
        return message;
    }

    private void sendSignal(JSONObject signalMessage) {
        if (stompClient != null && stompClient.isConnected()) {
            String jsonBody = signalMessage.toString();
            Log.d(TAG, "Attempting to send signal: " + jsonBody);
            stompClient.send("/app/signal/" + roomId, jsonBody)
                    .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                    .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                    .subscribe(
                            () -> Log.d(TAG, "Signal sent successfully: " + jsonBody),
                            throwable -> Log.e(TAG, "Error sending signal: " + throwable.getMessage())
                    );
        } else {
            Log.e(TAG, "STOMP client not connected, cannot send signal");
            runOnUiThread(() -> Toast.makeText(this, "Connection lost, please try again", Toast.LENGTH_SHORT).show());
        }
    }

    private void sendReadySignal() {
        Log.d(TAG, "Preparing to send ready signal");
        initializePeerConnection(false);
        sendSignal(createSignalJson("ready", null));
    }

    private void endCall() {
        if (isCallEnded) {
            Log.d(TAG, "Call already ended, skipping...");
            return;
        }
        Log.d(TAG, "Ending call...");
        isCallEnded = true;

        stopAudioCapture();

        if (transcriptionSubscription != null && !transcriptionSubscription.isDisposed()) {
            transcriptionSubscription.dispose();
            Log.d(TAG, "Unsubscribed from transcription topic: /topic/transcription/" + roomId);
        }

        // Отправляем сигнал закрытия WebSocket
        if (stompClient != null && stompClient.isConnected()) {
            JSONObject closeSignal = new JSONObject();
            try {
                closeSignal.put("type", "closeWebSocket");
                closeSignal.put("roomId", roomId);
                stompClient.send("/app/close-websocket", closeSignal.toString())
                        .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                        .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                        .subscribe(
                                () -> Log.d(TAG, "Sent closeWebSocket signal for roomId: " + roomId),
                                throwable -> Log.e(TAG, "Error sending closeWebSocket signal: " + throwable.getMessage())
                        );
            } catch (JSONException e) {
                Log.e(TAG, "Error creating closeWebSocket JSON: " + e.getMessage());
            }
        }

        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerHandler = null;
            timerRunnable = null;
            Log.d(TAG, "Timer stopped.");
        }
        if (timeHandler != null && timeRunnable != null) {
            timeHandler.removeCallbacks(timeRunnable);
            timeHandler = null;
            timeRunnable = null;
            Log.d(TAG, "Time updates stopped.");
        }
        if (currentTimeHandler != null && currentTimeRunnable != null) {
            currentTimeHandler.removeCallbacks(currentTimeRunnable);
            currentTimeHandler = null;
            currentTimeRunnable = null;
            Log.d(TAG, "Current time updates stopped.");
        }

        if (!terminationInitiator && stompClient != null && stompClient.isConnected()) {
            sendSignal(createSignalJson("endCall", null));
            Log.d(TAG, "Sent endCall signal to /app/signal/" + roomId);

            if (isCaller && recipientId != null) {
                JSONObject endCallMessage = new JSONObject();
                try {
                    endCallMessage.put("type", "endCall");
                    endCallMessage.put("roomId", roomId);
                    endCallMessage.put("callerId", currentUserId);
                    endCallMessage.put("recipientId", recipientId);
                    Log.d(TAG, "Sending endCall to /app/call/" + recipientId + ": " + endCallMessage.toString());
                    stompClient.send("/app/call/" + recipientId, endCallMessage.toString())
                            .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                            .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                            .subscribe(
                                    () -> Log.d(TAG, "endCall signal sent successfully to /app/call/" + recipientId),
                                    throwable -> Log.e(TAG, "Error sending endCall signal to /app/call/" + recipientId + ": " + throwable.getMessage())
                            );
                } catch (JSONException e) {
                    Log.e(TAG, "Error creating endCall JSON for /app/call/" + recipientId + ": " + e.getMessage());
                }
            } else {
                Log.d(TAG, "Not sending endCall to /app/call: isCaller=" + isCaller + ", recipientId=" + recipientId);
            }
        } else {
            Log.d(TAG, "Not sending endCall signals: terminationInitiator=" + terminationInitiator + ", stompClient=" + (stompClient != null ? stompClient.isConnected() : "null"));
        }

        if (roomSubscription != null && !roomSubscription.isDisposed()) {
            roomSubscription.dispose();
            Log.d(TAG, "Unsubscribed from room topic: /topic/room/" + roomId);
        }

        if (statsHandler != null && statsRunnable != null) {
            statsHandler.removeCallbacks(statsRunnable);
            statsHandler = null;
            statsRunnable = null;
            Log.d(TAG, "Stats collection task cancelled.");
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            peerConnection = null;
            Log.d(TAG, "Peer connection closed and disposed.");
        }
        if (localAudioTrack != null) {
            localAudioTrack.dispose();
            localAudioTrack = null;
            Log.d(TAG, "Local audio track disposed.");
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
            Log.d(TAG, "Audio source disposed.");
        }
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
            Log.d(TAG, "PeerConnectionFactory disposed.");
        }

        if (ringtonePlayer != null) {
            if (ringtonePlayer.isPlaying()) {
                ringtonePlayer.stop();
                Log.d(TAG, "Ringtone stopped.");
            }
            ringtonePlayer.release();
            ringtonePlayer = null;
            Log.d(TAG, "Ringtone player released.");
        }

        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.setSpeakerphoneOn(false);
        Log.d(TAG, "Audio mode set to normal, speakerphone off.");

        saveCall("completed");
        finish();
    }

    private void saveCall(String status) {
        if (hasSavedCall) {
            Log.d(TAG, "Call already saved, skipping saveCall");
            return;
        }

        OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        JSONObject callData = new JSONObject();
        try {
            callData.put("caller", isCaller ? currentUserId : recipientId);
            callData.put("receiver", isCaller ? recipientId : currentUserId);
            callData.put("direction", isCaller ? "outgoing" : "incoming");
            callData.put("status", status);
            callData.put("callType", "voice_with_translate");
            callData.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date()));
            callData.put("roomId", roomId);
            // Сколько длился разговор: в списке звонков видно было только
            // время начала, а главное — сколько говорили — нигде
            if ("completed".equals(status) && callStartTime > 0) {
                callData.put("durationSeconds",
                        (int) ((System.currentTimeMillis() - callStartTime) / 1000));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error creating call JSON: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(this, "Failed to save call", Toast.LENGTH_SHORT).show());
            return;
        }

        RequestBody body = RequestBody.create(callData.toString(), JSON);
        Request request = new Request.Builder()
                .url("https://unlimitedpossibilities12.org/api/calls?userId=" + currentUserId)
                .post(body)
                .build();

        new Thread(() -> {
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Voice call with translation saved to server with status: " + status);
                    hasSavedCall = true;
                } else {
                    Log.e(TAG, "Failed to save call, response code: " + response.code());
                    runOnUiThread(() -> Toast.makeText(this, "Failed to save call", Toast.LENGTH_SHORT).show());
                }
            } catch (IOException e) {
                Log.e(TAG, "Error saving voice call with translation: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "Failed to save call", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    protected void onDestroy() {
        com.example.unlimitedpossibilities12.messenger.notife.OngoingCall.clear();
        if (peerLanguageSubscription != null && !peerLanguageSubscription.isDisposed()) {
            peerLanguageSubscription.dispose();
            peerLanguageSubscription = null;
        }
        stopOnDeviceSpeech();
        com.example.unlimitedpossibilities12.messenger.notife.CallService.setCallInProgress(false);
        super.onDestroy();
        if (!isCallEnded) {
            endCall();
        }
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        if (friendSubscription != null && !friendSubscription.isDisposed()) {
            friendSubscription.dispose();
            Log.d(TAG, "Unsubscribed from friend transcription topic");
        }
        // [ДОБАВЛЕНО] Отписка от TTS
        if (ttsSubscription != null && !ttsSubscription.isDisposed()) {
            ttsSubscription.dispose();
            Log.d(TAG, "Unsubscribed from TTS topic");
        }
        // [ДОБАВЛЕНО] Очистка ttsMediaPlayer
        stopKeepingRemoteVolume();
        clearTtsQueue();
        if (ttsMediaPlayer != null) {
            ttsMediaPlayer.release();
            ttsMediaPlayer = null;
            Log.d(TAG, "TTS MediaPlayer released");
        }
        // [ОСТАВЛЕНО] Очистка selectedLanguageCode
        selectedLanguageCode = null;
        // [ДОБАВЛЕНО] Очистка настроек TTS
        SharedPreferences prefs = getSharedPreferences("call_prefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove("tts_enabled_" + roomId);
        editor.apply();
    }

    // NEW: Настройка Spinner с языками
    private void setupLanguageSpinner() {
        List<Language> languages = getLanguageList();
        languageAdapter = new LanguageAdapter(this, R.layout.spinner_item_translate, languages);
        languageAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_translate);
        languageSpinner.setAdapter(languageAdapter);
        SpinnerStyle.roundDropdown(languageSpinner);
    }


    // NEW: Установка выбранного языка в Spinner
    private void setSpinnerSelection(String languageCode) {
        for (int i = 0; i < languageAdapter.getCount(); i++) {
            if (languageAdapter.getLanguageCode(i).equals(languageCode)) {
                languageSpinner.setSelection(i);
                selectedLanguageCode = languageCode;
                break;
            }
        }
    }

    // NEW: Получение списка языков
    private List<Language> getLanguageList() {
        List<Language> languages = new ArrayList<>();
        // Добавляем "Auto" первым
        languages.add(new Language("Auto", "auto", "🌐"));

        // Список остальных языков
        List<Language> otherLanguages = new ArrayList<>();
        otherLanguages.add(new Language("Afrikaans", "af", "🇿🇦"));
        otherLanguages.add(new Language("Albanian", "sq", "🇦🇱"));
        otherLanguages.add(new Language("Amharic", "am", "🇪🇹"));
        otherLanguages.add(new Language("Arabic", "ar", "🇸🇦"));
        otherLanguages.add(new Language("Armenian", "hy", "🇦🇲"));
        otherLanguages.add(new Language("Azerbaijani", "az", "🇦🇿"));
        otherLanguages.add(new Language("Basque", "eu", "🇪🇸"));
        otherLanguages.add(new Language("Belarusian", "be", "🇧🇾"));
        otherLanguages.add(new Language("Bengali", "bn", "🇧🇩"));
        otherLanguages.add(new Language("Bosnian", "bs", "🇧🇦"));
        otherLanguages.add(new Language("Bulgarian", "bg", "🇧🇬"));
        otherLanguages.add(new Language("Catalan", "ca", "🇪🇸"));
        otherLanguages.add(new Language("Cebuano", "ceb", "🇵🇭"));
        otherLanguages.add(new Language("Chichewa", "ny", "🇲🇼"));
        otherLanguages.add(new Language("Chinese (Simplified)", "zh", "🇨🇳"));
        otherLanguages.add(new Language("Chinese (Traditional)", "zh-TW", "🇹🇼"));
        otherLanguages.add(new Language("Corsican", "co", "🇫🇷"));
        otherLanguages.add(new Language("Croatian", "hr", "🇭🇷"));
        otherLanguages.add(new Language("Czech", "cs", "🇨🇿"));
        otherLanguages.add(new Language("Danish", "da", "🇩🇰"));
        otherLanguages.add(new Language("Dutch", "nl", "🇳🇱"));
        otherLanguages.add(new Language("English", "en", "🇬🇧"));
        otherLanguages.add(new Language("Esperanto", "eo", "🏳️"));
        otherLanguages.add(new Language("Estonian", "et", "🇪🇪"));
        otherLanguages.add(new Language("Filipino", "tl", "🇵🇭"));
        otherLanguages.add(new Language("Finnish", "fi", "🇫🇮"));
        otherLanguages.add(new Language("French", "fr", "🇫🇷"));
        otherLanguages.add(new Language("Frisian", "fy", "🇳🇱"));
        otherLanguages.add(new Language("Galician", "gl", "🇪🇸"));
        otherLanguages.add(new Language("Georgian", "ka", "🇬🇪"));
        otherLanguages.add(new Language("German", "de", "🇩🇪"));
        otherLanguages.add(new Language("Greek", "el", "🇬🇷"));
        otherLanguages.add(new Language("Gujarati", "gu", "🇮🇳"));
        otherLanguages.add(new Language("Haitian Creole", "ht", "🇭🇹"));
        otherLanguages.add(new Language("Hausa", "ha", "🇳🇬"));
        otherLanguages.add(new Language("Hawaiian", "haw", "🇺🇸"));
        otherLanguages.add(new Language("Hebrew", "he", "🇮🇱"));
        otherLanguages.add(new Language("Hindi", "hi", "🇮🇳"));
        otherLanguages.add(new Language("Hmong", "hmn", "🇨🇳"));
        otherLanguages.add(new Language("Hungarian", "hu", "🇭🇺"));
        otherLanguages.add(new Language("Icelandic", "is", "🇮🇸"));
        otherLanguages.add(new Language("Igbo", "ig", "🇳🇬"));
        otherLanguages.add(new Language("Indonesian", "id", "🇮🇩"));
        otherLanguages.add(new Language("Irish", "ga", "🇮🇪"));
        otherLanguages.add(new Language("Italian", "it", "🇮🇹"));
        otherLanguages.add(new Language("Japanese", "ja", "🇯🇵"));
        otherLanguages.add(new Language("Javanese", "jv", "🇮🇩"));
        otherLanguages.add(new Language("Kannada", "kn", "🇮🇳"));
        otherLanguages.add(new Language("Kazakh", "kk", "🇰🇿"));
        otherLanguages.add(new Language("Khmer", "km", "🇰🇭"));
        otherLanguages.add(new Language("Kinyarwanda", "rw", "🇷🇼"));
        otherLanguages.add(new Language("Korean", "ko", "🇰🇷"));
        otherLanguages.add(new Language("Kurdish", "ku", "🇮🇶"));
        otherLanguages.add(new Language("Kyrgyz", "ky", "🇰🇬"));
        otherLanguages.add(new Language("Lao", "lo", "🇱🇦"));
        otherLanguages.add(new Language("Latin", "la", "🏳️"));
        otherLanguages.add(new Language("Latvian", "lv", "🇱🇻"));
        otherLanguages.add(new Language("Lithuanian", "lt", "🇱🇹"));
        otherLanguages.add(new Language("Luxembourgish", "lb", "🇱🇺"));
        otherLanguages.add(new Language("Macedonian", "mk", "🇲🇰"));
        otherLanguages.add(new Language("Malagasy", "mg", "🇲🇬"));
        otherLanguages.add(new Language("Malay", "ms", "🇲🇾"));
        otherLanguages.add(new Language("Malayalam", "ml", "🇮🇳"));
        otherLanguages.add(new Language("Maltese", "mt", "🇲🇹"));
        otherLanguages.add(new Language("Maori", "mi", "🇳🇿"));
        otherLanguages.add(new Language("Marathi", "mr", "🇮🇳"));
        otherLanguages.add(new Language("Mongolian", "mn", "🇲🇳"));
        otherLanguages.add(new Language("Myanmar (Burmese)", "my", "🇲🇲"));
        otherLanguages.add(new Language("Nepali", "ne", "🇳🇵"));
        otherLanguages.add(new Language("Norwegian", "no", "🇳🇴"));
        otherLanguages.add(new Language("Odia", "or", "🇮🇳"));
        otherLanguages.add(new Language("Pashto", "ps", "🇦🇫"));
        otherLanguages.add(new Language("Persian", "fa", "🇮🇷"));
        otherLanguages.add(new Language("Polish", "pl", "🇵🇱"));
        otherLanguages.add(new Language("Portuguese", "pt", "🇵🇹"));
        otherLanguages.add(new Language("Punjabi", "pa", "🇮🇳"));
        otherLanguages.add(new Language("Romanian", "ro", "🇷🇴"));
        otherLanguages.add(new Language("Russian", "ru", "🇷🇺"));
        otherLanguages.add(new Language("Samoan", "sm", "🇼🇸"));
        otherLanguages.add(new Language("Scots Gaelic", "gd", "🏴󠁧󠁢󠁳󠁣󠁴󠁿"));
        otherLanguages.add(new Language("Serbian", "sr", "🇷🇸"));
        otherLanguages.add(new Language("Sesotho", "st", "🇱🇸"));
        otherLanguages.add(new Language("Shona", "sn", "🇿🇼"));
        otherLanguages.add(new Language("Sindhi", "sd", "🇵🇰"));
        otherLanguages.add(new Language("Sinhala", "si", "🇱🇰"));
        otherLanguages.add(new Language("Slovak", "sk", "🇸🇰"));
        otherLanguages.add(new Language("Slovenian", "sl", "🇸🇮"));
        otherLanguages.add(new Language("Somali", "so", "🇸🇴"));
        otherLanguages.add(new Language("Spanish", "es", "🇪🇸"));
        otherLanguages.add(new Language("Sundanese", "su", "🇮🇩"));
        otherLanguages.add(new Language("Swahili", "sw", "🇹🇿"));
        otherLanguages.add(new Language("Swedish", "sv", "🇸🇪"));
        otherLanguages.add(new Language("Tajik", "tg", "🇹🇯"));
        otherLanguages.add(new Language("Tamil", "ta", "🇮🇳"));
        otherLanguages.add(new Language("Tatar", "tt", "🇷🇺"));
        otherLanguages.add(new Language("Telugu", "te", "🇮🇳"));
        otherLanguages.add(new Language("Thai", "th", "🇹🇭"));
        otherLanguages.add(new Language("Turkish", "tr", "🇹🇷"));
        otherLanguages.add(new Language("Turkmen", "tk", "🇹🇲"));
        otherLanguages.add(new Language("Ukrainian", "uk", "🇺🇦"));
        otherLanguages.add(new Language("Urdu", "ur", "🇵🇰"));
        otherLanguages.add(new Language("Uyghur", "ug", "🇨🇳"));
        otherLanguages.add(new Language("Uzbek", "uz", "🇺🇿"));
        otherLanguages.add(new Language("Vietnamese", "vi", "🇻🇳"));
        otherLanguages.add(new Language("Welsh", "cy", "🏴󠁧󠁢󠁷󠁬󠁳󠁿"));
        otherLanguages.add(new Language("Xhosa", "xh", "🇿🇦"));
        otherLanguages.add(new Language("Yiddish", "yi", "🇮🇱"));
        otherLanguages.add(new Language("Yoruba", "yo", "🇳🇬"));
        otherLanguages.add(new Language("Zulu", "zu", "🇿🇦"));

        // Сортировка остальных языков по алфавиту
        Collections.sort(otherLanguages, new Comparator<Language>() {
            @Override
            public int compare(Language l1, Language l2) {
                return l1.name.compareTo(l2.name);
            }
        });

        // Добавляем отсортированные языки после "Auto"
        languages.addAll(otherLanguages);

        return languages;
    }

    // [ДОБАВЛЕНО] Отправка настроек на сервер
    private void sendSettingsToServer() {
        if (translationSwitch == null || ttsSwitch == null) {
            Log.d(TAG, "Settings not sent yet: screen is not ready");
            return;
        }
        Log.d(TAG, "Sending settings to server: roomId=" + roomId + ", userId=" + currentUserId +
                ", translationEnabled=" + translationSwitch.isChecked() +
                ", translationLanguage=" + selectedLanguageCode +
                ", ttsEnabled=" + ttsSwitch.isChecked());

        Settings settings = new Settings(
                roomId,
                currentUserId,
                translationSwitch.isChecked(),
                selectedLanguageCode != null ? selectedLanguageCode : "auto",
                ttsSwitch.isChecked(),
                // В бесплатном режиме перевод произносит сам телефон: сообщаем это
                // серверу, иначе он синтезировал бы речь второй раз и за деньги
                onDeviceMode
        );

        Call<Void> call = apiService.saveSettings(settings);
        call.enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Settings sent successfully: " + settings.toString());
                } else {
                    Log.e(TAG, "Failed to send settings: HTTP " + response.code());
                    runOnUiThread(() -> Toast.makeText(VoiceCallWithTranslateActivity.this,
                            "Failed to save settings", Toast.LENGTH_SHORT).show());
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Error sending settings: " + t.getMessage());
                runOnUiThread(() -> Toast.makeText(VoiceCallWithTranslateActivity.this,
                        "Error saving settings", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** Запомнить разговор и экран возврата — для полоски поверх приложения. */
    private void rememberOngoingCall(String partner) {
        com.example.unlimitedpossibilities12.messenger.notife.OngoingCall
                .start(VoiceCallWithTranslateActivity.class, partner);
    }

    /**
     * «Назад» сворачивает разговор, а не кладёт трубку: экран остаётся жить
     * позади, а сверху едет полоска, по которой в него возвращаются.
     */
    @Override
    public void onBackPressed() {
        if (!com.example.unlimitedpossibilities12.messenger.notife.OngoingCall.isActive()) {
            super.onBackPressed();
            return;
        }
        // Разговор продолжается в своей стопке экранов, а она уходит назад
        moveTaskToBack(true);
    }

    // ---------- управление разговором ----------

    /** Снимок собеседника, а если его нет — буква имени. */
    private void showPartnerAvatar(String name) {
        callAvatarText.setText(name.substring(0, 1).toUpperCase(Locale.getDefault()));
        callAvatarText.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(colorForName(name)));
        int id;
        try {
            id = Integer.parseInt(recipientId);
        } catch (Exception e) {
            return;
        }
        com.example.unlimitedpossibilities12.api.ApiClient.getApiService().getUserById(id)
                .enqueue(new retrofit2.Callback<com.example.unlimitedpossibilities12.api.User>() {
                    @Override
                    public void onResponse(retrofit2.Call<com.example.unlimitedpossibilities12.api.User> call,
                                           retrofit2.Response<com.example.unlimitedpossibilities12.api.User> response) {
                        if (isFinishing() || isDestroyed()) return;
                        if (!response.isSuccessful() || response.body() == null) return;
                        String avatar = response.body().getAvatar();
                        if (avatar == null || avatar.isEmpty()) return;
                        com.bumptech.glide.Glide.with(VoiceCallWithTranslateActivity.this)
                                .load("https://unlimitedpossibilities12.org" + avatar)
                                .circleCrop()
                                .into(callAvatarImage);
                        callAvatarText.setVisibility(View.GONE);
                    }

                    @Override
                    public void onFailure(retrofit2.Call<com.example.unlimitedpossibilities12.api.User> call, Throwable tr) {
                        Log.e(TAG, "Could not load partner avatar: " + tr.getMessage());
                    }
                });
    }

    private int colorForName(String name) {
        int[] colors = {0xFF6366F1, 0xFF0D9488, 0xFF8B5CF6, 0xFF0EA5E9, 0xFFDB2777, 0xFF475569};
        int hash = 0;
        for (int i = 0; i < name.length(); i++) hash = hash * 31 + name.charAt(i);
        return colors[Math.abs(hash) % colors.length];
    }

    private void toggleMic() {
        micMuted = !micMuted;
        if (localAudioTrack != null) localAudioTrack.setEnabled(!micMuted);
        muteButton.setImageResource(micMuted ? R.drawable.ic_mic_off_offline : R.drawable.ic_mic_white);
        paintButton(muteButton, micMuted);
    }

    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        if (audioManager != null) audioManager.setSpeakerphoneOn(speakerOn);
        speakerButton.setImageResource(speakerOn ? R.drawable.ic_volume_up : R.drawable.ic_volume_off_offline);
        paintButton(speakerButton, speakerOn);
    }

    /** Включённая кнопка — светлая, обычная — тёмная. */
    private void paintButton(ImageView button, boolean active) {
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(active ? 0xFFE2E8F0 : 0xFF1E293B));
        button.setColorFilter(active ? 0xFF0F172A : 0xFFE2E8F0);
    }

    // ---------- подтверждение собеседника ----------

    private void setupCallGuard() {
        callGuard = new com.example.unlimitedpossibilities12.messenger.notife.CallGuard(this, currentUserId);
        // recipientId здесь всегда собеседник — и когда звоним мы, и когда нам
        callGuard.prepare(recipientId, () -> runOnUiThread(this::updateSecurityLine));
        updateSecurityLine();
    }

    private void updateSecurityLine() {
        TextView text = findViewById(R.id.call_security_text);
        ImageView icon = findViewById(R.id.call_security_icon);
        if (text == null || icon == null || callGuard == null) return;
        int color;
        int label;
        switch (callGuard.state()) {
            case CONFIRMED:
                label = R.string.call_security_confirmed;
                color = 0xFF34D399;
                break;
            case VERIFIED:
                label = R.string.call_security_verified;
                color = 0xFF34D399;
                break;
            case NEW_DEVICE:
                label = R.string.call_security_new_device;
                color = 0xFF94A3B8;
                break;
            case CHANGED:
                label = R.string.call_security_changed;
                color = 0xFFFBBF24;
                break;
            case FAILED:
                label = R.string.call_security_failed;
                color = 0xFFF87171;
                break;
            case ENCRYPTED:
                label = R.string.call_security_encrypted;
                color = 0xFF94A3B8;
                break;
            default:
                label = R.string.call_security_checking;
                color = 0xFF94A3B8;
        }
        text.setText(label);
        text.setTextColor(color);
        icon.setColorFilter(color);
    }

    /** Код разговора и то, что с ним делать. */
    private void showFingerprint() {
        if (callGuard == null) return;
        String code = callGuard.fingerprint();
        String digits = callGuard.fingerprintDigits();
        com.example.unlimitedpossibilities12.messenger.notife.CallGuard.State state = callGuard.state();

        StringBuilder message = new StringBuilder();
        if (state == com.example.unlimitedpossibilities12.messenger.notife.CallGuard.State.CHANGED) {
            message.append(getString(R.string.call_security_changed_note)).append("\n\n");
        } else if (state == com.example.unlimitedpossibilities12.messenger.notife.CallGuard.State.CONFIRMED) {
            message.append(getString(R.string.call_security_confirmed_note)).append("\n\n");
        } else if (state == com.example.unlimitedpossibilities12.messenger.notife.CallGuard.State.NEW_DEVICE) {
            message.append(getString(R.string.call_security_new_device_note)).append("\n\n");
        } else {
            message.append(getString(R.string.call_security_explained)).append("\n\n");
        }
        message.append(code != null ? code + "\n" + digits : getString(R.string.call_security_no_code));
        if (state == com.example.unlimitedpossibilities12.messenger.notife.CallGuard.State.ENCRYPTED) {
            message.append("\n\n").append(getString(R.string.call_security_unverified_note));
        }

        androidx.appcompat.app.AlertDialog.Builder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                        .setTitle(R.string.call_security_title)
                        .setMessage(message.toString())
                        .setNegativeButton(android.R.string.ok, null);
        if (code != null) {
            builder.setPositiveButton(R.string.call_security_match, (dialog, which) -> {
                if (state == com.example.unlimitedpossibilities12.messenger.notife.CallGuard.State.CHANGED) {
                    callGuard.acceptChangedKey();
                }
                callGuard.confirmByCode();
                updateSecurityLine();
            });
        }
        builder.show();
    }

    /**
     * Проверить подпись под описанием соединения.
     *
     * @return true, если разговор надо прервать — подпись не сошлась.
     */
    private boolean checkSignature(String sdp, JSONObject signal) {
        if (callGuard == null) return false;
        String signature = signal.optString("signature", null);
        String senderDevice = signal.optString("deviceId", null);
        com.example.unlimitedpossibilities12.messenger.notife.CallGuard.State state =
                callGuard.verify(sdp, signature, senderDevice);
        runOnUiThread(this::updateSecurityLine);
        if (state == com.example.unlimitedpossibilities12.messenger.notife.CallGuard.State.FAILED) {
            Log.e(TAG, "Подпись собеседника не сошлась — прекращаем разговор");
            runOnUiThread(() -> {
                updateSecurityLine();
                new Handler(Looper.getMainLooper()).postDelayed(this::endCall, 2500);
            });
            return true;
        }
        return false;
    }

    /** Пустое поле расшифровки: туда пишем первую фразу, а не приписываем снизу. */
    private boolean isPlaceholder(String text) {
        return text == null
                || text.isEmpty()
                || text.equals(getString(R.string.call_translate_listening))
                || text.equals("Transcription: Waiting...")
                || text.equals("Friend's Transcription: Waiting...");
    }

    // ---------- озвучка перевода ----------

    /**
     * Очередь озвучки.
     *
     * Перевод каждой фразы приходит отдельным куском. Раньше новый кусок
     * освобождал проигрыватель и начинал играть сам — если собеседник говорил
     * без долгих пауз, следующая фраза обрывала предыдущую на полуслове, и
     * вслух пропадало целое предложение, хотя на экране оно было.
     */
    private final java.util.ArrayDeque<File> ttsQueue = new java.util.ArrayDeque<>();
    private boolean ttsPlaying;

    private void enqueueTts(File audio) {
        ttsQueue.add(audio);
        // Далеко отстать тоже нельзя: разговор уйдёт вперёд, а мы будем
        // договаривать сказанное минуту назад
        while (ttsQueue.size() > 4) {
            File dropped = ttsQueue.poll();
            if (dropped != null) dropped.delete();
            Log.w(TAG, "Озвучка отстала — пропускаем самую старую фразу");
        }
        if (!ttsPlaying) playNextTts();
    }

    private void playNextTts() {
        File next = ttsQueue.poll();
        if (next == null) {
            ttsPlaying = false;
            duckRemoteVoice(false);
            return;
        }
        try {
            if (!ttsPlaying) {
                // Приглушаем голос собеседника: иначе оригинал и перевод звучат
                // одновременно
                duckRemoteVoice(true);
            }
            ttsPlaying = true;
            ttsMediaPlayer = new MediaPlayer();
            // Перевод пускаем по голосовому тракту звонка. Прежде на время
            // озвучки выключался микрофон — чтобы перевод из динамика не ушёл
            // обратно собеседнику. Но перевод длинной фразы звучит секунд
            // двадцать, и всё сказанное человеком за это время пропадало: он
            // говорил, а на экране не появлялось ничего. По голосовому тракту
            // эхо гасит сам телефон, и микрофон можно не трогать.
            ttsMediaPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            ttsMediaPlayer.setDataSource(next.getAbsolutePath());
            ttsMediaPlayer.prepare();
            ttsMediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                ttsMediaPlayer = null;
                next.delete();
                playNextTts();
            });
            ttsMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "TTS playback error: " + what);
                mp.release();
                ttsMediaPlayer = null;
                next.delete();
                playNextTts();
                return true;
            });
            ttsMediaPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Не удалось проиграть перевод: " + e.getMessage());
            next.delete();
            playNextTts();
        }
    }

    /** Конец разговора — очередь не должна доигрывать в пустоту. */
    private void clearTtsQueue() {
        ttsPlaying = false;
        File left;
        while ((left = ttsQueue.poll()) != null) left.delete();
    }

    /**
     * Надпись о состоянии, пока разговор не начался.
     *
     * Прежде и у звонящего, и у отвечающего стояло «Connecting…», хотя пока
     * собеседник не ответил, соединять ещё нечего.
     */
    private void showStatus(int textRes) {
        runOnUiThread(() -> {
            android.widget.TextView v0 = findViewById(R.id.call_timer);
            if (v0 != null) v0.setText(textRes);
        });
    }
}