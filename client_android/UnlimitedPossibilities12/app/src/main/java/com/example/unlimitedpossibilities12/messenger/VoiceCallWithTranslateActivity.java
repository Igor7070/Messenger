package com.example.unlimitedpossibilities12.messenger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
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
    private static final double BACKGROUND_VOLUME = 0.3;
    /** На время самой озвучки оригинал убираем ещё тише, но не в тишину. */
    private static final double DUCK_VOLUME = 0.12;
    /** Произносится ли перевод прямо сейчас. */
    private boolean translationSpeaking;
    private AudioManager audioManager;
    private MediaPlayer ringtonePlayer;
    private boolean isWaiting = false;
    private String userName;
    private boolean terminationInitiator = false;
    private Button endCallButton;
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
    private TextView startTime;
    private TextView currentTime;
    private CallWaveformView waveformView;
    private Handler timerHandler;
    private Runnable timerRunnable;
    private Handler timeHandler;
    private Runnable timeRunnable;
    private long callStartTime;
    private Handler currentTimeHandler;
    private Runnable currentTimeRunnable;

    private AudioRecord audioRecord;
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
        startTime = findViewById(R.id.start_time);
        currentTime = findViewById(R.id.current_time);
        waveformView = findViewById(R.id.waveform_view);
        endCallButton = findViewById(R.id.end_call_button);
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

        callTitle.setText("Voice communication with " + recipientName);

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

        timeHandler = new Handler(Looper.getMainLooper());
        final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

        currentTimeHandler = new Handler(Looper.getMainLooper());
        currentTimeRunnable = new Runnable() {
            @Override
            public void run() {
                currentTime.setText("Current time: " + sdf.format(new Date()));
                currentTimeHandler.postDelayed(this, 1000);
            }
        };
        currentTimeHandler.postDelayed(currentTimeRunnable, 1000);

        timeRunnable = new Runnable() {
            @Override
            public void run() {
                if (startTime.getText().toString().startsWith("Start: --")) {
                    startTime.setText("Start: " + sdf.format(new Date(callStartTime)));
                }
                waveformView.updateAmplitude();
                timeHandler.postDelayed(this, 5000);
            }
        };
        timeHandler.post(timeRunnable);

        endCallButton.setOnClickListener(v -> {
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction(() -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                endCall();
            }).start();
        });

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);
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
                friendTranscriptionTextView.setText("Friend's Transcription: Waiting..."); // [ДОБАВЛЕНО] Сброс текста
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
            Log.d(TAG, "Caller waiting for 'ready' or 'acceptCall' signal...");
        }
    }

    private void startAudioCapture() {
        // Запись запускается из двух мест — сразу и по событию соединения WebRTC.
        // Без этой проверки при неудачном порядке событий создавались два потока
        // с одного микрофона: сервер получал звук дважды, каждая фраза дважды
        // распознавалась, переводилась и озвучивалась — двойная цена и эхо.
        if (isRecording || audioRecord != null) {
            Log.d(TAG, "Audio capture already running — skipping second start");
            return;
        }
        if (isRecording) return;
        int sampleRate = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        // Минимальный буфер — около сотни миллисекунд. Если поток чтения на миг
        // задержится, микрофон затрёт несчитанное, и речь дойдёт с дырами.
        // Берём запас на секунду: памяти это 32 КБ, зато терять нечего.
        int bufferSize = Math.max(
                AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 4,
                sampleRate * 2);
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
            );
            audioRecord.startRecording();
            isRecording = true;
            new Thread(() -> {
                byte[] buffer = new byte[1600];
                while (isRecording) {
                    int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    if (onDeviceMode && vosk != null && vosk.isReady()) {
                        // Бесплатный режим: тот же поток звука отдаём распознавателю
                        // на устройстве — на сервер не уходит ничего
                        if (bytesRead > 0) vosk.accept(buffer, bytesRead);
                        continue;
                    }
                    // Модель ещё качается или ждёт согласия — пока идём прежним
                    // путём через сервер, иначе собеседник не услышал бы ничего
                    // и не понял почему
                    if (bytesRead > 0 && stompClient != null && stompClient.isConnected()) {
                        String base64Audio = android.util.Base64.encodeToString(buffer, 0, bytesRead, android.util.Base64.NO_WRAP);
                        JSONObject audioMessage = new JSONObject();
                        try {
                            audioMessage.put("audio", base64Audio);
                            audioMessage.put("sessionId", sessionId);
                            // Распознаванию сообщаем язык пользователя: угадывание по
                            // коротким фразам ошибается (английская речь шла кириллицей)
                            audioMessage.put("spokenLanguage", selectedLanguageCode != null ? selectedLanguageCode : "auto");
                        } catch (JSONException e) {
                            Log.e(TAG, "Error creating audio JSON: " + e.getMessage());
                        }
                        Log.d(TAG, "Encoded Base64 audio length: " + base64Audio.length());
                        stompClient.send("/app/audio-transcription/" + roomId, audioMessage.toString())
                                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                                .subscribe(
                                        () -> Log.d(TAG, "Sent audio buffer: " + bytesRead + " bytes, sessionId: " + sessionId),
                                        throwable -> Log.e(TAG, "Error sending audio: " + throwable.getMessage())
                                );
                    }
                }
            }).start();
            Log.d(TAG, "Audio capture started");
        } else {
            Log.e(TAG, "No RECORD_AUDIO permission");
        }
    }

    private void stopAudioCapture() {
        if (isRecording) {
            isRecording = false;
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
                Log.d(TAG, "Audio capture stopped");
            }
        }
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
                                        if (currentText.equals("Transcription: Waiting...") || currentText.startsWith("Error")) {
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
        if (currentText.equals("Transcription: Waiting...") || currentText.startsWith("Error")) {
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
                                            updateFriendTranscriptionTextView(displayText, scrollView);
                                        });
                                    } else {
                                        // Отображаем оригинальный текст
                                        updateFriendTranscriptionTextView(transcription, scrollView);
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
     * с озвучкой — фоновая, а на время самой озвучки ещё тише.
     */
    private void applyRemoteVolume() {
        if (remoteAudioTrack == null) return;
        boolean voiceTranslation = ttsSwitch != null && ttsSwitch.isChecked()
                && translationSwitch != null && translationSwitch.isChecked();
        double volume = !voiceTranslation ? 1.0
                : (translationSpeaking ? DUCK_VOLUME : BACKGROUND_VOLUME);
        try {
            remoteAudioTrack.setVolume(volume);
            Log.d(TAG, "Remote voice volume: " + volume);
        } catch (Exception e) {
            Log.w(TAG, "Не удалось изменить громкость входящего голоса: " + e.getMessage());
        }
    }

    /** Озвучку выключили посреди фразы — замолчать, а не договаривать. */
    private void stopSpeaking() {
        if (onDeviceSpeech != null) onDeviceSpeech.silence();
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

    private void updateFriendTranscriptionTextView(String displayText, ScrollView scrollView) {
        boolean wasAtBottom = isScrolledToBottom(scrollView);
        String currentText = friendTranscriptionTextView.getText().toString();
        String[] lines = currentText.split("\n");
        StringBuilder newText = new StringBuilder();
        if (currentText.equals("Friend's Transcription: Waiting...") || currentText.startsWith("Error")) {
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
        friendTranscriptionTextView.setText(newText.toString());
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
                                    // Создаём временный файл для MediaPlayer
                                    File tempFile = File.createTempFile("tts_audio", ".mp3", getCacheDir());
                                    FileOutputStream fos = new FileOutputStream(tempFile);
                                    fos.write(audioData);
                                    fos.close();

                                    // Инициализируем новый MediaPlayer для TTS
                                    if (ttsMediaPlayer != null) {
                                        ttsMediaPlayer.release();
                                    }
                                    ttsMediaPlayer = new MediaPlayer();
                                    ttsMediaPlayer.setDataSource(tempFile.getAbsolutePath());
                                    ttsMediaPlayer.prepare();

                                    // Приглушаем голос собеседника: иначе оригинал и
                                    // перевод звучат одновременно
                                    duckRemoteVoice(true);
                                    // Микрофон тоже выключаем, иначе он поймает перевод
                                    // из динамика и вернёт его собеседнику
                                    if (localAudioTrack != null) {
                                        localAudioTrack.setEnabled(false);
                                        Log.d(TAG, "Muted own mic during TTS playback");
                                    }

                                    ttsMediaPlayer.start();
                                    Log.d(TAG, "Started TTS audio playback");

                                    // Восстанавливаем WebRTC-аудио после завершения
                                    ttsMediaPlayer.setOnCompletionListener(mp -> {
                                        Log.d(TAG, "TTS audio playback completed");
                                        duckRemoteVoice(false);
                                        if (localAudioTrack != null) {
                                            localAudioTrack.setEnabled(true);
                                            Log.d(TAG, "Restored own mic");
                                        }
                                        ttsMediaPlayer.release();
                                        ttsMediaPlayer = null;
                                        tempFile.delete();
                                    });
                                    // Сорвалось воспроизведение — звук не должен
                                    // остаться приглушённым до конца разговора
                                    ttsMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                                        Log.w(TAG, "TTS playback error: " + what);
                                        duckRemoteVoice(false);
                                        if (localAudioTrack != null) localAudioTrack.setEnabled(true);
                                        return false;
                                    });
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
        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory();

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
                    runOnUiThread(VoiceCallWithTranslateActivity.this::applyRemoteVolume);
                    Log.d(TAG, "Remote audio track from receiver: " + remoteAudioTrack.id());
                } else if (mediaStreams.length > 0 && !mediaStreams[0].audioTracks.isEmpty()) {
                    remoteAudioTrack = mediaStreams[0].audioTracks.get(0);
                    remoteAudioTrack.setEnabled(true);
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
                    if (isCaller && peerConnection != null) {
                        String sdp = signal.getString("sdp");
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
}