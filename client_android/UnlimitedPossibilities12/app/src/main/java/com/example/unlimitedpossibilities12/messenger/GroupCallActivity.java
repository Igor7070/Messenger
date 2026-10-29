package com.example.unlimitedpossibilities12.messenger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import org.webrtc.RendererCommon;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoProcessor;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;
import org.webrtc.VideoSource;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import io.reactivex.disposables.Disposable;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import ua.naiksoftware.stomp.StompClient;

public class GroupCallActivity extends AppCompatActivity {
    private static final String TAG = "GroupCallActivity";
    private StompClient stompClient;
    private CallService callService;
    private boolean isBound = false;
    private String currentUserId;
    private int initiatorId;
    private String initiatorName;
    private ArrayList<User> participants;
    private String roomId;
    private Map<String, PeerConnection> peerConnections;
    private PeerConnectionFactory peerConnectionFactory;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private AudioManager audioManager;
    private MediaPlayer ringtonePlayer;
    private boolean isWaiting = false;
    private boolean isCallStarted = false;
    private boolean isCallEnded = false;
    private boolean hasSavedCall = false; // Флаг для предотвращения дублирования сохранения звонка
    private boolean isInitiator = false;
    private Set<String> readyUsers;
    private Disposable roomSubscription;
    private boolean isStompConnected = false;
    private final List<Runnable> pendingInvites = new ArrayList<>();
    // [ДОБАВЛЕНО]: Поля для периодической проверки соединений
    private Handler connectionCheckHandler;
    private Runnable connectionCheckRunnable;

    // Для отслеживания состояния сигнализации каждого PeerConnection
    private Map<String, PeerConnection.SignalingState> signalingStates = new HashMap<>();
    // [ДОБАВЛЕНО]: Отслеживание отправленных offer для каждого пользователя
    private final Set<String> sentOffers = new HashSet<>();
    private Handler statsHandler;
    private Runnable statsRunnable;

    // UI элементы
    private TextView callTitle;
    private TextView callTimer;
    private TextView startTime;
    private TextView currentTime;
    private CallWaveformView waveformView;
    private Button endCallButton;
    private RecyclerView participantsRecyclerView;
    private ParticipantsAdapter participantsAdapter;
    private Handler timerHandler;
    private Runnable timerRunnable;
    private Handler timeHandler;
    private Runnable timeRunnable;
    private long callStartTime;
    private Handler currentTimeHandler;
    private Runnable currentTimeRunnable;

    // [ДОБАВЛЕНО]: Поля для управления видео
    private boolean isVideoMode = false; // Текущий режим (аудио/видео)
    private boolean isCameraOn = false; // Состояние камеры текущего пользователя
    private VideoSource videoSource; // Источник видеопотока
    private VideoTrack localVideoTrack; // Локальный видеотрек
    private Map<String, VideoTrack> remoteVideoTracks; // Входящие видеотреки
    private VideoParticipantsAdapter videoParticipantsAdapter; // Адаптер для видеовкладки
    private EglBase localEglBase; // Для localVideoView
    private EglBase remoteEglBase; // Для video_grid
    private Camera1Enumerator cameraEnumerator; // Для доступа к камере
    private CameraVideoCapturer videoCapturer; // Для захвата видео
    private SurfaceTextureHelper surfaceTextureHelper; // Для обработки текстур
    private Map<String, Boolean> cameraStates; // Состояние камеры участников
    private AtomicBoolean isVideoTrackReady = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SurfaceViewRenderer localVideoView;
    private Button toggleCameraButton;
    private TextView localNoVideoText; // [ДОБАВЛЕНО]
    private Map<String, RtpSender> videoTrackSenders = new HashMap<>();

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CallService.LocalBinder binder = (CallService.LocalBinder) service;
            callService = binder.getService();
            isBound = true;
            Log.d(TAG, "Connected to CallService");
            stompClient = callService.getStompClient();
            Log.d(TAG, "STOMP client: " + (stompClient != null ? "not null" : "null") + ", connected: " + (stompClient != null && stompClient.isConnected()));
            if (stompClient != null && stompClient.isConnected()) {
                isStompConnected = true;
                Log.d(TAG, "Using STOMP client from CallService");
                subscribeToRoomWithRetry(5, 1000);
                if (!isInitiator) {
                    sendSignal(createSignalJson("acceptCall", null, String.valueOf(initiatorId)));
                    Log.d(TAG, "Sent acceptCall signal after binding");
                    sendReadySignal();
                } else {
                    Log.d(TAG, "Initiator, executing pending invites");
                    for (Runnable invite : pendingInvites) {
                        invite.run();
                    }
                    pendingInvites.clear();
                }
            } else {
                Log.w(TAG, "STOMP client not available or not connected, waiting for connection");
                // Не завершаем активность, ждем подключения через subscribeToRoomWithRetry
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
        setContentView(R.layout.activity_group_call);

        // Запрещаем поворот экрана
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // Получение данных из Intent
        initiatorId = getIntent().getIntExtra("initiatorId", -1);
        initiatorName = getIntent().getStringExtra("initiatorName");
        isInitiator = getIntent().getBooleanExtra("isInitiator", false);
        roomId = getIntent().getStringExtra("roomId");
        if (isInitiator && roomId == null) {
            roomId = UUID.randomUUID().toString();
        }
        currentUserId = String.valueOf(getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", initiatorId));
        if (currentUserId.equals(String.valueOf(-1)) && !isInitiator) {
            Log.e(TAG, "currentUserId is not set and user is not initiator. Finishing activity.");
            Toast.makeText(this, "Please log in to join the call", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        peerConnections = new HashMap<>();
        readyUsers = new HashSet<>();
        participants = new ArrayList<>();
        signalingStates = new HashMap<>();
        cameraStates = new HashMap<>();
        remoteVideoTracks = new HashMap<>();
        videoTrackSenders = new HashMap<>();

        // Получение списка участников
        ArrayList<String> recipientIds = getIntent().getStringArrayListExtra("selectedUsersIds");
        if (recipientIds == null || recipientIds.size() > 4) {
            Toast.makeText(this, "Invalid number of participants (max 4 + initiator)", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Инициализация UI
        callTitle = findViewById(R.id.call_title);
        callTimer = findViewById(R.id.call_timer);
        startTime = findViewById(R.id.start_time);
        currentTime = findViewById(R.id.current_time);
        waveformView = findViewById(R.id.waveform_view);
        endCallButton = findViewById(R.id.end_call_button);
        participantsRecyclerView = findViewById(R.id.participants_recycler_view);

        callTitle.setText("Group Call with " + initiatorName);
        participantsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        participantsAdapter = new ParticipantsAdapter(participants, userId -> {
            if (!isVideoMode) {
                toggleMode();
            }
        });
        participantsRecyclerView.setAdapter(participantsAdapter);

        // Инициализация видеоадаптера и GridLayoutManager
        localEglBase = EglBase.create(); // Для localVideoView
        // remoteEglBase ОБЯЗАН разделять контекст с localEglBase.
        // Аппаратный видеодекодер отдаёт кадры как OES-текстуры, созданные в том
        // контексте, который передан в DefaultVideoDecoderFactory — а туда уходит
        // localEglBase. Прочитать такую текстуру можно только из контекста,
        // разделяющего с ним ресурсы. Раньше здесь был независимый EglBase.create(),
        // и рендереры сетки рисовали чужую, недоступную им текстуру: кадры
        // приходили, swapBuffers отрабатывал, счётчик Rendered рос — а в буфер
        // попадала чернота. Локальное видео работало, потому что localVideoView
        // инициализируется именно localEglBase.
        remoteEglBase = EglBase.create(localEglBase.getEglBaseContext(), EglBase.CONFIG_PLAIN); // Для video_grid
        RecyclerView videoGrid = findViewById(R.id.video_grid);
        videoGrid.setLayoutManager(new GridLayoutManager(this, 2));
        videoParticipantsAdapter = new VideoParticipantsAdapter(participants, currentUserId, remoteEglBase, enable -> toggleCamera());
        videoGrid.setAdapter(videoParticipantsAdapter);
        videoGrid.setItemViewCacheSize(participants.size());
        videoGrid.setItemAnimator(null);

        // Инициализация localVideoView и toggleCameraButton
        localVideoView = findViewById(R.id.local_video_view);
        localNoVideoText = findViewById(R.id.local_no_video_text);
        toggleCameraButton = findViewById(R.id.toggle_camera_button);

        // Добавляем SurfaceHolder.Callback для отслеживания готовности поверхности
        AtomicBoolean isSurfaceReady = new AtomicBoolean(false);
        localVideoView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.d(TAG, "localVideoView: Surface created, isValid=" + holder.getSurface().isValid());
                isSurfaceReady.set(true);
                runOnUiThread(() -> {
                    localVideoView.requestLayout();
                    localVideoView.invalidate();
                    toggleCameraButton.setVisibility(View.VISIBLE);
                    toggleCameraButton.bringToFront();
                    Log.d(TAG, "localVideoView: Surface created, UI updated, toggleCameraButton visible");
                });
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Log.d(TAG, "localVideoView: Surface changed, format=" + format + ", width=" + width + ", height=" + height);
                runOnUiThread(() -> {
                    toggleCameraButton.setVisibility(View.VISIBLE);
                    toggleCameraButton.bringToFront();
                });
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.d(TAG, "localVideoView: Surface destroyed");
                isSurfaceReady.set(false);
                runOnUiThread(() -> {
                    toggleCameraButton.setVisibility(View.VISIBLE);
                    toggleCameraButton.bringToFront();
                });
            }
        });

        localVideoView.init(localEglBase.getEglBaseContext(), null);
        localVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        localVideoView.setEnableHardwareScaler(false); // Для совместимости
        localVideoView.setMirror(true);
        localVideoView.setZOrderMediaOverlay(true); // Оставляем только это
        localVideoView.setVisibility(View.VISIBLE);
        localNoVideoText.setVisibility(View.VISIBLE);
        toggleCameraButton.setVisibility(View.VISIBLE);
        toggleCameraButton.bringToFront();
        Log.d(TAG, "Initialized localVideoView, surface valid=" + localVideoView.getHolder().getSurface().isValid());

        localVideoView.post(() -> {
            Log.d(TAG, "localVideoView size: width=" + localVideoView.getWidth() + ", height=" + localVideoView.getHeight());
            Log.d(TAG, "localVideoView surface state: isValid=" + localVideoView.getHolder().getSurface().isValid());
            toggleCameraButton.setVisibility(View.VISIBLE);
            toggleCameraButton.bringToFront();
        });

        toggleCameraButton.setOnClickListener(v -> toggleCamera());
        Log.d(TAG, "Initialized toggleCameraButton");

        // Обработчик кнопки переключения режима
        ImageButton toggleModeButton = findViewById(R.id.toggle_mode_button);
        toggleModeButton.setOnClickListener(v -> toggleMode());

        // Форма плиток участников: восстанавливаем выбор с прошлого звонка
        // и вешаем переключение по кругу Auto → Wide → Tall.
        videoParticipantsAdapter.setTileMode(loadSavedTileMode());
        Button tileModeButton = findViewById(R.id.tile_mode_button);
        updateTileModeButton();
        tileModeButton.setOnClickListener(v -> cycleTileMode());

        // Настройка рингтона
        ringtonePlayer = new MediaPlayer();
        try {
            ringtonePlayer.setDataSource("https://igor7070.github.io/Messenger/incoming_dial_tone.mp3");
            ringtonePlayer.setLooping(true);
            ringtonePlayer.prepareAsync();
            ringtonePlayer.setOnPreparedListener(mp -> {
                if (isInitiator && !isWaiting) {
                    isWaiting = true;
                    ringtonePlayer.start();
                    Log.d(TAG, "Ringtone started playing");
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Error setting up ringtone: " + e.getMessage());
        }

        // Загружаем участников и инициализируем WebRTC/аудио после
        loadParticipants(recipientIds);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (participants.size() != recipientIds.size() + 1) {
                Log.e(TAG, "Not all participants loaded, expected: " + (recipientIds.size() + 1) + ", got: " + participants.size());
                Toast.makeText(this, "Failed to load all participants", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            Log.d(TAG, "All participants loaded: " + participants.stream().map(u -> u.getId() + ":" + u.getUsername()).collect(Collectors.joining(", ")));
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
            } else {
                initializeWebRTC();
                initializeAudioTrack();
                if (!isInitiator) {
                    sendReadySignal();
                }
            }
        }, 2000);

        // Привязка к сервису
        Intent serviceIntent = new Intent(this, CallService.class);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);

        // Инициализация таймера
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

        // Инициализация времени
        timeHandler = new Handler(Looper.getMainLooper());
        final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        currentTimeHandler = new Handler(Looper.getMainLooper());
        currentTimeRunnable = new Runnable() {
            @Override
            public void run() {
                currentTime.setText("Current: " + sdf.format(new Date()));
                currentTimeHandler.postDelayed(this, 1000);
            }
        };
        currentTimeHandler.post(currentTimeRunnable);

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

        // [ДОБАВЛЕНО]: Периодическая проверка соединений
        connectionCheckHandler = new Handler(Looper.getMainLooper());
        final int[] checkCount = {0}; // Счётчик для первых трёх проверок
        connectionCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isCallStarted || isCallEnded) {
                    Log.d(TAG, "Connection check skipped: isCallStarted=" + isCallStarted + ", isCallEnded=" + isCallEnded);
                    return;
                }
                Log.d(TAG, "Periodic connection check triggered, checkCount=" + checkCount[0]);
                boolean hasFailedConnections = false;
                Set<String> checkedUsers = new HashSet<>();

                // Проверяем существующие PeerConnections
                for (Map.Entry<String, PeerConnection> entry : peerConnections.entrySet()) {
                    String userId = entry.getKey();
                    PeerConnection pc = entry.getValue();
                    checkedUsers.add(userId);
                    if (
                            pc.iceConnectionState() != PeerConnection.IceConnectionState.CONNECTED &&
                                    pc.iceConnectionState() != PeerConnection.IceConnectionState.COMPLETED ||
                                    pc.connectionState() == PeerConnection.PeerConnectionState.NEW ||
                                    pc.connectionState() == PeerConnection.PeerConnectionState.CONNECTING ||
                                    pc.connectionState() == PeerConnection.PeerConnectionState.FAILED
                    ) {
                        hasFailedConnections = true;
                        Log.d(TAG, "Connection with user " + userId + " not established, iceConnectionState: " + pc.iceConnectionState() + ", connectionState: " + pc.connectionState());
                    } else {
                        Log.d(TAG, "Connection with user " + userId + " established, iceConnectionState: " + pc.iceConnectionState() + ", connectionState: " + pc.connectionState());
                    }
                }

                // Проверяем участников без PeerConnections
                for (User user : participants) {
                    String userId = String.valueOf(user.getId());
                    if (!userId.equals(currentUserId) && !checkedUsers.contains(userId)) {
                        hasFailedConnections = true;
                        Log.d(TAG, "No PeerConnection for user " + userId + ", marking as failed");
                    }
                }

                if (hasFailedConnections) {
                    Log.d(TAG, "Initiating retry for failed or missing connections");
                    retryFailedConnections();
                } else {
                    Log.d(TAG, "All connections established, no retry needed");
                }

                // Увеличиваем счётчик проверок
                checkCount[0]++;
                // Первые три проверки с интервалом 4 секунды, затем каждые 10 секунд
                long nextDelay = checkCount[0] < 3 ? 4000 : 10000;
                connectionCheckHandler.postDelayed(this, nextDelay);
            }
        };
        connectionCheckHandler.postDelayed(connectionCheckRunnable, 4000); // Первая проверка через 4 секунды

        // Анимация кнопки завершения
        endCallButton.setOnClickListener(v -> {
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction(() -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                endCall();
            }).start();
        });
    }

    private void loadParticipants(ArrayList<String> recipientIds) {
        int expectedParticipants = recipientIds.size() + 1; // +1 для инициатора
        List<String> allUserIds = new ArrayList<>(recipientIds);
        if (!allUserIds.contains(String.valueOf(initiatorId))) {
            allUserIds.add(String.valueOf(initiatorId));
        }

        for (String userId : allUserIds) {
            ApiClient.getApiService().getUserById(Integer.parseInt(userId)).enqueue(new Callback<User>() {
                @Override
                public void onResponse(Call<User> call, Response<User> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        User user = response.body();
                        int existingIndex = -1;
                        for (int i = 0; i < participants.size(); i++) {
                            if (participants.get(i).getId() == user.getId()) {
                                existingIndex = i;
                                break;
                            }
                        }
                        if (existingIndex >= 0) {
                            participants.set(existingIndex, user);
                            participantsAdapter.notifyItemChanged(existingIndex); // Точечное обновление
                            videoParticipantsAdapter.notifyItemChanged(existingIndex); // Точечное обновление для видеоадаптера
                        } else {
                            participants.add(user);
                            cameraStates.put(String.valueOf(user.getId()), false); // Инициализация состояния камеры
                            participantsAdapter.notifyItemInserted(participants.size() - 1); // Точечное добавление
                            videoParticipantsAdapter.notifyItemInserted(participants.size() - 1);
                        }
                        Log.d(TAG, "Added/Updated participant: " + user.getUsername() + ", current size: " + participants.size());
                        if (participants.size() == expectedParticipants && isInitiator) {
                            Log.d(TAG, "All participants loaded, preparing to send group call invite");
                            if (isStompConnected) {
                                sendGroupCallInvite();
                            } else {
                                Log.d(TAG, "STOMP client not connected yet, queueing invite");
                                pendingInvites.add(() -> sendGroupCallInvite());
                            }
                        }
                    } else {
                        Log.e(TAG, "Failed to fetch user " + userId + ": HTTP " + response.code());
                        if (userId.equals(String.valueOf(initiatorId))) {
                            User initiator = new User();
                            initiator.setId(initiatorId);
                            initiator.setUsername(initiatorName);
                            initiator.setAvatar(null);
                            initiator.setOnline(false);
                            int existingIndex = -1;
                            for (int i = 0; i < participants.size(); i++) {
                                if (participants.get(i).getId() == initiatorId) {
                                    existingIndex = i;
                                    break;
                                }
                            }
                            if (existingIndex >= 0) {
                                participants.set(existingIndex, initiator);
                                participantsAdapter.notifyItemChanged(existingIndex);
                                videoParticipantsAdapter.notifyItemChanged(existingIndex);
                            } else {
                                participants.add(initiator);
                                cameraStates.put(String.valueOf(initiator.getId()), false);
                                participantsAdapter.notifyItemInserted(participants.size() - 1);
                                videoParticipantsAdapter.notifyItemInserted(participants.size() - 1);
                            }
                            Log.d(TAG, "Added initiator with default data: " + initiator.getUsername() + ", current size: " + participants.size());
                        }
                    }
                }

                @Override
                public void onFailure(Call<User> call, Throwable t) {
                    Log.e(TAG, "Error fetching user " + userId + ": " + t.getMessage());
                    if (userId.equals(String.valueOf(initiatorId))) {
                        User initiator = new User();
                        initiator.setId(initiatorId);
                        initiator.setUsername(initiatorName);
                        initiator.setAvatar(null);
                        initiator.setOnline(false);
                        int existingIndex = -1;
                        for (int i = 0; i < participants.size(); i++) {
                            if (participants.get(i).getId() == initiatorId) {
                                existingIndex = i;
                                break;
                            }
                        }
                        if (existingIndex >= 0) {
                            participants.set(existingIndex, initiator);
                            participantsAdapter.notifyItemChanged(existingIndex);
                            videoParticipantsAdapter.notifyItemChanged(existingIndex);
                        } else {
                            participants.add(initiator);
                            cameraStates.put(String.valueOf(initiator.getId()), false);
                            participantsAdapter.notifyItemInserted(participants.size() - 1);
                            videoParticipantsAdapter.notifyItemInserted(participants.size() - 1);
                        }
                        Log.d(TAG, "Added initiator with default data on failure: " + initiator.getUsername() + ", current size: " + participants.size());
                    }
                }
            });
        }
    }

    private void initializeWebRTC() {
        try {
            PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(this)
                    .createInitializationOptions();
            PeerConnectionFactory.initialize(options);
            PeerConnectionFactory.Options factoryOptions = new PeerConnectionFactory.Options();
            peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(factoryOptions)
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(localEglBase.getEglBaseContext(), true, true))
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(localEglBase.getEglBaseContext()))
                    .createPeerConnectionFactory();
            Log.d(TAG, "PeerConnectionFactory initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing WebRTC: " + e.getMessage(), e);
            runOnUiThread(() -> Toast.makeText(this, "Failed to initialize WebRTC", Toast.LENGTH_LONG).show());
            finish();
        }

        if (currentUserId == null) {
            Log.e(TAG, "currentUserId is undefined. Check the Intent extras.");
            finish();
        }
    }

    private void initializeAudioTrack() {
        if (localAudioTrack != null || audioSource != null) {
            Log.d(TAG, "localAudioTrack or audioSource already initialized");
            return;
        }

        // Проверка разрешения RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted");
            runOnUiThread(() -> Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show());
            endCall();
            return;
        }

        // Проверка AudioManager
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
                Log.d(TAG, "AudioManager initialized for VOICE_CALL, max volume: " + maxVolume);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing AudioManager: " + e.getMessage(), e);
            runOnUiThread(() -> Toast.makeText(this, "Audio manager initialization error", Toast.LENGTH_LONG).show());
            endCall();
            return;
        }

        try {
            // Пустые MediaConstraints для минимизации конфликтов
            MediaConstraints audioConstraints = new MediaConstraints();
            Log.d(TAG, "Creating AudioSource with empty constraints");
            audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
            if (audioSource == null) {
                Log.e(TAG, "Failed to create AudioSource");
                runOnUiThread(() -> Toast.makeText(this, "Failed to initialize audio", Toast.LENGTH_LONG).show());
                endCall();
                return;
            }
            Log.d(TAG, "AudioSource created successfully");

            localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource);
            if (localAudioTrack == null) {
                Log.e(TAG, "Failed to create AudioTrack");
                runOnUiThread(() -> Toast.makeText(this, "Failed to initialize audio track", Toast.LENGTH_LONG).show());
                audioSource.dispose();
                audioSource = null;
                endCall();
                return;
            }
            localAudioTrack.setEnabled(true);
            Log.d(TAG, "AudioTrack created successfully: audio0");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing audio: " + e.getMessage(), e);
            runOnUiThread(() -> Toast.makeText(this, "Audio initialization error", Toast.LENGTH_LONG).show());
            if (audioSource != null) {
                audioSource.dispose();
                audioSource = null;
            }
            endCall();
        }
    }

    private void subscribeToRoomWithRetry(int maxRetries, long delay) {
        if (stompClient != null && stompClient.isConnected()) {
            isStompConnected = true;
            Log.d(TAG, "STOMP client connected, subscribing to /topic/room/" + roomId);
            subscribeToRoom();
            if (isInitiator && !pendingInvites.isEmpty()) {
                Log.d(TAG, "STOMP client connected, executing pending invites");
                for (Runnable invite : pendingInvites) {
                    invite.run();
                }
                pendingInvites.clear();
            }
        } else if (maxRetries > 0) {
            Log.w(TAG, "STOMP client not connected, retrying in " + delay + "ms, attempts left: " + maxRetries);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                subscribeToRoomWithRetry(maxRetries - 1, delay);
            }, delay);
        } else {
            Log.e(TAG, "Failed to connect to STOMP client after retries");
            Toast.makeText(this, "Failed to connect to call server", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void subscribeToRoom() {
        Log.d(TAG, "Subscribing to /topic/room/" + roomId);
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

    private void checkPermissionsAndStartCall() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
        } else if (!isCallStarted) {
            startCall();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeWebRTC();
            initializeAudioTrack();
            if (!isInitiator) {
                sendReadySignal();
            } else if (!isCallStarted) {
                startCall();
            }
        } else if (requestCode == 2 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // [ДОБАВЛЕНО]: Повторный вызов toggleCamera после получения разрешения
            toggleCamera();
        } else {
            Log.e(TAG, "Permission denied for request code " + requestCode);
            Toast.makeText(this, requestCode == 1 ? "Permission to use microphone denied" : "Permission to use camera denied", Toast.LENGTH_LONG).show();
            if (requestCode == 1) {
                endCall();
            }
        }
    }

    private void sendGroupCallInvite() {
        if (roomId == null) {
            Log.e(TAG, "Cannot send group call invite: roomId is null");
            Toast.makeText(this, "Failed to initiate call: roomId is missing", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (stompClient == null || !stompClient.isConnected()) {
            Log.w(TAG, "Cannot send group call invite: STOMP client is null or not connected");
            Toast.makeText(this, "Connecting to call server, please wait...", Toast.LENGTH_LONG).show();
            pendingInvites.add(() -> sendGroupCallInvite());
            return;
        }
        JSONObject groupCallRequest = new JSONObject();
        try {
            groupCallRequest.put("type", "group");
            groupCallRequest.put("initiatorId", String.valueOf(initiatorId));
            groupCallRequest.put("initiatorName", initiatorName);
            groupCallRequest.put("roomId", roomId);
            JSONArray recipientIds = new JSONArray();
            for (User user : participants) {
                if (user.getId() != initiatorId) {
                    recipientIds.put(String.valueOf(user.getId()));
                }
            }
            groupCallRequest.put("recipientIds", recipientIds);
            Log.d(TAG, "Sending group call invite with roomId: " + roomId + ", to users: " + recipientIds.toString());

            for (User user : participants) {
                if (user.getId() != initiatorId) {
                    stompClient.send("/app/groupCall/" + user.getId(), groupCallRequest.toString())
                            .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                            .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                            .subscribe(
                                    () -> Log.d(TAG, "Group call invite sent to user " + user.getId() + " with roomId: " + roomId),
                                    throwable -> Log.e(TAG, "Error sending invite to user " + user.getId() + ": " + throwable.getMessage())
                            );
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error creating group call invite JSON: " + e.getMessage());
            Toast.makeText(this, "Failed to create call invite", Toast.LENGTH_LONG).show();
        }
    }

    private void startCall() {
        if (isCallStarted) {
            Log.d(TAG, "Call already started, ignoring");
            return;
        }
        if (peerConnectionFactory == null || audioSource == null || localAudioTrack == null) {
            Log.e(TAG, "Cannot start call: WebRTC or audio not initialized");
            runOnUiThread(() -> Toast.makeText(this, "Failed to start call: audio not initialized", Toast.LENGTH_LONG).show());
            endCall();
            return;
        }
        if (participants.size() < 2) {
            Log.e(TAG, "Not enough participants to start call: " + participants.size());
            runOnUiThread(() -> Toast.makeText(this, "Not enough participants", Toast.LENGTH_LONG).show());
            endCall();
            return;
        }
        isCallStarted = true;

        Log.d(TAG, "Starting call for room: " + roomId + ", participants: " + participants.stream().map(u -> u.getId() + ":" + u.getUsername()).collect(Collectors.joining(", ")));

        // Задержка для обеспечения подписки STOMP и загрузки участников
        long delayMs = isInitiator ? 500 : 1000; // 500 мс для инициатора, 1 сек для неинициаторов
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // Создание PeerConnection для каждого участника
            for (User user : participants) {
                String userId = String.valueOf(user.getId());
                if (!userId.equals(currentUserId) && !peerConnections.containsKey(userId)) {
                    initializePeerConnection(userId, true); // Всегда isOfferer = true
                    Log.d(TAG, "Initialized PeerConnection for user: " + userId);
                }
            }

            // Отправка offer для всех участников
            for (User user : participants) {
                String userId = String.valueOf(user.getId());
                if (!userId.equals(currentUserId)) {
                    PeerConnection pc = peerConnections.get(userId);
                    if (pc != null) {
                        if (pc.iceConnectionState() != PeerConnection.IceConnectionState.CONNECTED &&
                                pc.iceConnectionState() != PeerConnection.IceConnectionState.COMPLETED) {
                            Log.d(TAG, "Creating offer for user: " + userId);
                            createAndSendOffer(userId, pc);
                        } else {
                            Log.d(TAG, "Skipping offer for user: " + userId + ", already connected, iceConnectionState=" + pc.iceConnectionState());
                        }
                    } else {
                        Log.e(TAG, "PeerConnection is null for user: " + userId);
                        runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Connection not initialized for user " + userId, Toast.LENGTH_SHORT).show());
                    }
                }
            }

            // Статистика
            statsHandler = new Handler(Looper.getMainLooper());
            statsRunnable = () -> {
                for (Map.Entry<String, PeerConnection> entry : peerConnections.entrySet()) {
                    String userId = entry.getKey();
                    PeerConnection pc = entry.getValue();
                    if (pc != null) {
                        pc.getStats(statsReport -> {
                            for (RTCStats stats : statsReport.getStatsMap().values()) {
                                if (stats.getType().equals("outbound-rtp") && stats.getId().contains("audio")) {
                                    Log.d(TAG, "Outbound RTP for user " + userId + ": bytesSent=" + stats.getMembers().get("bytesSent"));
                                }
                            }
                        });
                    } else {
                        Log.w(TAG, "No PeerConnection for user " + userId + " during stats collection");
                    }
                }
                statsHandler.postDelayed(statsRunnable, 5000);
            };
            statsHandler.postDelayed(statsRunnable, 5000);
        }, delayMs);
    }

    // Метод для повторной попытки соединений
    private void retryFailedConnections() {
        Log.d(TAG, "Checking connection states for all participants");
        try {
            for (User user : participants) {
                String userId = String.valueOf(user.getId());
                if (!userId.equals(currentUserId)) {
                    PeerConnection peerConnection = peerConnections.get(userId);
                    if (peerConnection != null) {
                        if (
                                peerConnection.iceConnectionState() != PeerConnection.IceConnectionState.CONNECTED &&
                                        peerConnection.iceConnectionState() != PeerConnection.IceConnectionState.COMPLETED ||
                                        peerConnection.connectionState() == PeerConnection.PeerConnectionState.FAILED ||
                                        peerConnection.connectionState() == PeerConnection.PeerConnectionState.CONNECTING ||
                                        peerConnection.connectionState() == PeerConnection.PeerConnectionState.NEW
                        ) {
                            Log.d(TAG, "Retrying connection for user " + userId + ", iceConnectionState: " + peerConnection.iceConnectionState() + ", connectionState: " + peerConnection.connectionState() + ", signalingState: " + peerConnection.signalingState());
                            // Закрываем старое соединение
                            peerConnection.close();
                            peerConnections.remove(userId);
                            signalingStates.remove(userId);
                            sentOffers.remove(userId); // Очищаем sentOffers
                            // Инициализируем новое соединение
                            initializePeerConnection(userId, true); // isOfferer = true
                            PeerConnection newPeerConnection = peerConnections.get(userId);
                            if (newPeerConnection != null) {
                                Log.d(TAG, "Creating retry offer for user " + userId);
                                createAndSendOffer(userId, newPeerConnection);
                            } else {
                                Log.e(TAG, "Failed to initialize PeerConnection for user " + userId + " on retry");
                                runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to reconnect with user " + userId, Toast.LENGTH_SHORT).show());
                            }
                        } else {
                            Log.d(TAG, "Connection with user " + userId + " is already established, iceConnectionState: " + peerConnection.iceConnectionState() + ", connectionState: " + peerConnection.connectionState());
                        }
                    } else {
                        Log.w(TAG, "No PeerConnection for user " + userId + ", initializing new one");
                        initializePeerConnection(userId, true); // isOfferer = true
                        PeerConnection newPeerConnection = peerConnections.get(userId);
                        if (newPeerConnection != null) {
                            Log.d(TAG, "Creating initial offer for user " + userId + " after initializing new connection");
                            createAndSendOffer(userId, newPeerConnection);
                        } else {
                            Log.e(TAG, "Failed to initialize PeerConnection for user " + userId);
                            runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to initialize connection for user " + userId, Toast.LENGTH_SHORT).show());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error retrying failed connections: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to retry connections", Toast.LENGTH_SHORT).show());
        }
    }

    // [Изменено] Метод initializePeerConnection для добавления отслеживания SignalingState
    private void initializePeerConnection(String userId, boolean isOfferer) {
        if (localAudioTrack == null) {
            Log.e(TAG, "localAudioTrack is null, cannot initialize PeerConnection for user " + userId);
            runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Audio track not initialized for user " + userId, Toast.LENGTH_SHORT).show());
            return;
        }

        // Создаем MediaStream и добавляем аудиотрек
        MediaStream mediaStream = peerConnectionFactory.createLocalMediaStream("stream0");
        if (mediaStream == null) {
            Log.e(TAG, "Failed to create MediaStream for user " + userId);
            runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to create media stream for user " + userId, Toast.LENGTH_SHORT).show());
            return;
        }
        mediaStream.addTrack(localAudioTrack);
        Log.d(TAG, "Added local audio track to MediaStream for user " + userId);

        // Настраиваем конфигурацию для PeerConnection
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Arrays.asList(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("turn:16.170.203.109:3478?transport=tcp")
                        .setUsername(isOfferer ? "username1" : "username2")
                        .setPassword(isOfferer ? "key1" : "key2")
                        .createIceServer()
        ));
        config.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        // Создаем PeerConnection
        PeerConnection peerConnection = peerConnectionFactory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState newState) {
                Log.d(TAG, "SignalingState for user " + userId + ": " + newState);
                signalingStates.put(userId, newState);
            }

            @Override
            public void onIceCandidate(IceCandidate candidate) {
                Log.d(TAG, "ICE candidate for user " + userId + ": " + candidate);
                sendSignal(createSignalJson("candidate", candidate, userId));
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {
                Log.d(TAG, "ICE candidates removed for user " + userId + ": " + Arrays.toString(candidates));
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
                Log.d(TAG, "IceConnectionState for user " + userId + ": " + newState);
                if (newState == PeerConnection.IceConnectionState.FAILED || newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    Log.w(TAG, "ICE connection failed for user " + userId);
                    runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Connection failed for user " + userId, Toast.LENGTH_SHORT).show());
                } else if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                    Log.d(TAG, "ICE connection established for user " + userId);
                    readyUsers.add(userId); // [ДОБАВЛЕНО]: Добавляем пользователя в readyUsers
                    runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Connected to user " + userId, Toast.LENGTH_SHORT).show());
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {
                Log.d(TAG, "onIceConnectionReceivingChange for user " + userId + ": " + receiving);
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                Log.d(TAG, "onConnectionChange for user " + userId + ": " + newState);
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "onDataChannel for user " + userId);
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded for user " + userId);
            }

            @Override
            public void onAddStream(MediaStream stream) {
                Log.d(TAG, "onAddStream for user " + userId);
            }

            @Override
            public void onRemoveStream(MediaStream stream) {
                Log.d(TAG, "onRemoveStream for user " + userId);
            }

            @Override
            public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
                if (mediaStreams.length > 0) {
                    // ВНИМАНИЕ: здесь раньше стояло String userId = receiver.id();
                    // Это затеняло параметр userId метода initializePeerConnection.
                    // receiver.id() возвращает не идентификатор участника, а id самого
                    // приёмника (вида "video_1788798583428"), поэтому удалённая
                    // видеодорожка клалась в remoteVideoTracks под чужим ключом,
                    // а getVideoView() по нему не находил SurfaceViewRenderer.
                    // Обработчик cameraOn искал дорожку по настоящему userId и,
                    // разумеется, не находил — видео собеседника не появлялось никогда.
                    Log.d(TAG, "onAddTrack for user " + userId + ", receiver id: " + receiver.id());
                    if (!mediaStreams[0].audioTracks.isEmpty()) {
                        AudioTrack remoteAudioTrack = mediaStreams[0].audioTracks.get(0);
                        remoteAudioTrack.setEnabled(true);
                        Log.d(TAG, "Remote audio track added for user " + userId);
                    }
                    if (!mediaStreams[0].videoTracks.isEmpty()) {
                        VideoTrack remoteVideoTrack = mediaStreams[0].videoTracks.get(0);
                        remoteVideoTrack.setEnabled(true);
                        remoteVideoTracks.put(userId, remoteVideoTrack);
                        runOnUiThread(() -> {
                            SurfaceViewRenderer videoView = videoParticipantsAdapter.getVideoView(userId);
                            if (videoView != null) {
                                // Очистка и повторная инициализация
                                videoView.release();
                                videoView.init(remoteEglBase.getEglBaseContext(), new RendererCommon.RendererEvents() {
                                    @Override
                                    public void onFirstFrameRendered() {
                                        Log.d(TAG, "First frame rendered for user " + userId);
                                    }
                                    @Override
                                    public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
                                        Log.d(TAG, "Frame resolution changed for user " + userId + ": " + videoWidth + "x" + videoHeight);
                                    }
                                });
                                videoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
                                videoView.setEnableHardwareScaler(true);
                                videoView.setZOrderMediaOverlay(true);
                                videoView.setVisibility(View.VISIBLE);
                                remoteVideoTrack.addSink(videoView);
                                videoParticipantsAdapter.setVideoViewInitialized(userId, true);
                                videoParticipantsAdapter.setVideoTrackBound(userId, true);
                                videoParticipantsAdapter.setCameraState(userId, true);
                                videoParticipantsAdapter.setVideoTrack(userId, remoteVideoTrack);
                                Log.d(TAG, "Bound video track to SurfaceViewRenderer for user " + userId);
                            } else {
                                Log.w(TAG, "SurfaceViewRenderer is null for user " + userId);
                                videoParticipantsAdapter.setCameraState(userId, true);
                            }
                        });
                        Log.d(TAG, "Remote video track added for user " + userId);
                    }
                }
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
                Log.d(TAG, "onIceGatheringChange for user " + userId + ": " + newState);
            }
        });

        // Проверяем успешность создания PeerConnection
        if (peerConnection == null) {
            Log.e(TAG, "Failed to initialize PeerConnection for user " + userId);
            runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to initialize connection for user " + userId, Toast.LENGTH_SHORT).show());
            return;
        }

        // Добавляем аудиотрек
        peerConnection.addTrack(localAudioTrack, Arrays.asList("stream0"));
        Log.d(TAG, "Added local audio track to PeerConnection for user " + userId);

        // Добавляем видеотрек, если камера включена и видеотрек готов
        if (isCameraOn && localVideoTrack != null && isVideoTrackReady.get()) {
            try {
                peerConnection.addTrack(localVideoTrack, Arrays.asList("stream0"));
                Log.d(TAG, "Added local video track to PeerConnection for user " + userId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to add video track for user " + userId + ": " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to add video track for user " + userId, Toast.LENGTH_SHORT).show());
            }
        } else {
            Log.d(TAG, "Video track not added for user " + userId + ": isCameraOn=" + isCameraOn + ", localVideoTrack=" + (localVideoTrack != null) + ", isVideoTrackReady=" + isVideoTrackReady.get());
        }

        // Сохраняем PeerConnection и обновляем состояние сигнализации
        peerConnections.put(userId, peerConnection);
        signalingStates.put(userId, PeerConnection.SignalingState.STABLE);
        Log.d(TAG, "PeerConnection initialized for user " + userId);
        if (isOfferer) {
            Log.d(TAG, "Creating and sending offer for user " + userId + " as offerer");
            createAndSendOffer(userId, peerConnection);
        }
    }

    private void createAndSendOffer(String userId, PeerConnection peerConnection) {
        if (peerConnection == null) {
            Log.e(TAG, "Cannot create offer, PeerConnection is null for user " + userId);
            runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Connection not initialized for user " + userId, Toast.LENGTH_SHORT).show());
            return;
        }
        // Проверяем, установлено ли соединение
        if (peerConnection.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED &&
                peerConnection.signalingState() == PeerConnection.SignalingState.STABLE) {
            Log.d(TAG, "Connection with user " + userId + " is already established, skipping offer creation");
            return;
        }
        if (sentOffers.contains(userId)) {
            Log.d(TAG, "Offer already sent to user " + userId + ", skipping");
            return;
        }
        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "Set local description for offer to user " + userId);
                        sendSignal(createSignalJson("offer", sdp, userId));
                        sentOffers.add(userId); // Отмечаем, что offer отправлен
                        Log.d(TAG, "Sent offer to user " + userId);
                    }
                    @Override
                    public void onSetFailure(String error) {
                        Log.e(TAG, "Failed to set local description for offer to user " + userId + ": " + error);
                        runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to send offer to user " + userId, Toast.LENGTH_SHORT).show());
                    }
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}
                    @Override
                    public void onCreateFailure(String error) {}
                }, sdp);
            }
            @Override
            public void onCreateFailure(String error) {
                Log.e(TAG, "Failed to create offer for user " + userId + ": " + error);
                runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to create offer for user " + userId, Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onSetSuccess() {}
            @Override
            public void onSetFailure(String error) {}
        }, new MediaConstraints());
    }

    private void handleSignal(JSONObject signalMessage) {
        try {
            String from = signalMessage.getString("from");
            if (from.equals(currentUserId)) {
                Log.d(TAG, "Ignoring signal from self: " + signalMessage.toString());
                return;
            }
            String to = signalMessage.optString("to", null);
            if (to != null && !to.equals(currentUserId)) {
                Log.d(TAG, "Ignoring signal not addressed to current user: " + signalMessage.toString());
                return;
            }

            JSONObject signal = signalMessage.getJSONObject("signal");
            String type = signal.getString("type");
            String fromId = from;
            Log.d(TAG, "Processing signal from: " + fromId + ", type: " + type + ", payload: " + signalMessage.toString());

            switch (type) {
                case "groupCallInvite":
                    if (!isInitiator) {
                        String receivedRoomId = signal.getString("roomId");
                        String initiatorName = signal.getString("initiatorName");
                        roomId = receivedRoomId;
                        Toast.makeText(this, initiatorName + " invites you to a group call", Toast.LENGTH_LONG).show();
                        sendSignal(createSignalJson("acceptCall", null, from));
                        sendReadySignal();
                    }
                    break;

                case "acceptCall":
                    if (isInitiator) {
                        String userName = participants.stream()
                                .filter(u -> String.valueOf(u.getId()).equals(fromId))
                                .findFirst()
                                .map(User::getUsername)
                                .orElse("User " + fromId);
                        Toast.makeText(this, userName + " accepted the group call", Toast.LENGTH_LONG).show();
                        Log.d(TAG, "User " + userName + " accepted call, readyUsers: " + readyUsers.size() + ", expected: " + (participants.size() - 1));
                    } else {
                        // Для неинициатора: создаём PeerConnection для нового участника
                        if (!peerConnections.containsKey(fromId)) {
                            Log.d(TAG, "Non-initiator initializing PeerConnection for new user " + fromId);
                            initializePeerConnection(fromId, true); // isOfferer = true
                            PeerConnection pc = peerConnections.get(fromId);
                            if (pc != null) {
                                createAndSendOffer(fromId, pc);
                            } else {
                                Log.e(TAG, "PeerConnection is null for user " + fromId);
                                runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to initialize connection for user " + fromId, Toast.LENGTH_SHORT).show());
                            }
                        }
                    }
                    break;

                case "ready":
                    if (isInitiator) {
                        readyUsers.add(fromId);
                        String userName = participants.stream()
                                .filter(u -> String.valueOf(u.getId()).equals(fromId))
                                .findFirst()
                                .map(User::getUsername)
                                .orElse("User " + fromId);
                        Log.d(TAG, "User " + userName + " is ready, readyUsers size: " + readyUsers.size() + ", expected: " + (participants.size() - 1));
                        if (readyUsers.size() == participants.size() - 1) {
                            isWaiting = false;
                            if (ringtonePlayer != null && ringtonePlayer.isPlaying()) {
                                ringtonePlayer.pause();
                                ringtonePlayer.seekTo(0);
                            }
                            Log.d(TAG, "All participants ready, starting call");
                            checkPermissionsAndStartCall();
                        } else {
                            Log.d(TAG, "Waiting for more ready signals, current: " + readyUsers.size() + ", needed: " + (participants.size() - 1));
                        }
                    } else {
                        // Для неинициатора: создаём PeerConnection и отправляем offer всем участникам
                        Log.d(TAG, "Non-initiator received ready from " + fromId + ", initializing connections to all participants");
                        for (User user : participants) {
                            String userId = String.valueOf(user.getId());
                            if (!userId.equals(currentUserId) && !peerConnections.containsKey(userId)) {
                                Log.d(TAG, "Non-initiator initializing PeerConnection for user " + userId);
                                initializePeerConnection(userId, true); // isOfferer = true
                                PeerConnection pc = peerConnections.get(userId);
                                if (pc != null) {
                                    createAndSendOffer(userId, pc);
                                } else {
                                    Log.e(TAG, "PeerConnection is null for user " + userId);
                                    runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to initialize connection for user " + userId, Toast.LENGTH_SHORT).show());
                                }
                            }
                        }
                    }
                    break;

                case "rejectCall":
                    if (isInitiator) {
                        String userName = participants.stream()
                                .filter(u -> String.valueOf(u.getId()).equals(fromId))
                                .findFirst()
                                .map(User::getUsername)
                                .orElse("User " + fromId);
                        Toast.makeText(this, userName + " rejected the group call", Toast.LENGTH_LONG).show();
                        participants.removeIf(u -> String.valueOf(u.getId()).equals(fromId));
                        participantsAdapter.notifyDataSetChanged();
                        if (participants.size() <= 1) {
                            saveCall("rejected");
                            endCall();
                        }
                    }
                    break;

                case "offer":
                    Log.d(TAG, "Processing signal from: " + fromId + ", type: offer, payload: " + signalMessage.toString());
                    if (true) {
                        // Существующее PeerConnection НЕ пересоздаём. Раньше здесь стоял
                        // existingPc.close(), и при одновременных офферах (а в mesh-звонке
                        // офферы шлют обе стороны) каждый участник уничтожал ровно то
                        // соединение, к которому относился уже летящий к нему answer.
                        // Коллизию офферов разрешаем ниже по правилу polite/impolite —
                        // так же, как это делает веб-клиент.
                        if (!peerConnections.containsKey(fromId)) {
                            initializePeerConnection(fromId, false);
                        }
                        final PeerConnection pc = peerConnections.get(fromId);
                        if (pc == null) {
                            Log.e(TAG, "PeerConnection is null for user " + fromId);
                            runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to initialize connection for user " + fromId, Toast.LENGTH_SHORT).show());
                            return;
                        }
                        String sdp = signal.getString("sdp");
                        if (isCameraOn && !isVideoTrackReady.get()) {
                            Log.w(TAG, "Video track not ready, postponing offer processing for user " + fromId);
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                if (isVideoTrackReady.get()) {
                                    Log.d(TAG, "Video track ready, processing postponed offer for user " + fromId);
                                    processOffer(pc, fromId, sdp);
                                } else {
                                    Log.w(TAG, "Video track still not ready, proceeding with audio-only for user " + fromId);
                                    processOffer(pc, fromId, sdp);
                                }
                            }, 2000);
                            return;
                        }
                        // Проверяем состояние сигнализации у самого PeerConnection,
                        // а не по кэшу в signalingStates — кэш отстаёт от реального состояния.
                        PeerConnection.SignalingState currentState = pc.signalingState();
                        signalingStates.put(fromId, currentState);

                        // Разрешение коллизии офферов. Правило то же, что в веб-клиенте:
                        // вежлив тот, у кого userId больше. Невежливый игнорирует чужой
                        // offer и ждёт answer на свой, вежливый откатывает свой offer
                        // и отвечает на чужой. Без этого две стороны отвечают друг другу
                        // одновременно и соединение не устанавливается никогда.
                        if (currentState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER
                                && !isPoliteTowards(fromId)) {
                            Log.d(TAG, "Impolite peer ignoring simultaneous offer from " + fromId);
                            break;
                        }

                        if (currentState != PeerConnection.SignalingState.STABLE) {
                            Log.d(TAG, "Rolling back signaling state for user " + fromId + " from state " + currentState);
                            pc.setLocalDescription(new SdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    Log.d(TAG, "Rollback successful for user " + fromId);
                                    signalingStates.put(fromId, PeerConnection.SignalingState.STABLE);
                                    processOffer(pc, fromId, sdp);
                                }
                                @Override
                                public void onSetFailure(String error) {
                                    Log.e(TAG, "Rollback failed for user " + fromId + ": " + error);
                                    runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to rollback state for user " + fromId, Toast.LENGTH_SHORT).show());
                                    // Создаём новое PeerConnection после неудачного отката
                                    Log.w(TAG, "Creating new PeerConnection after rollback failure for user " + fromId);
                                    peerConnections.remove(fromId);
                                    signalingStates.remove(fromId);
                                    sentOffers.remove(fromId);
                                    initializePeerConnection(fromId, false);
                                    PeerConnection newPc = peerConnections.get(fromId);
                                    if (newPc != null) {
                                        processOffer(newPc, fromId, sdp);
                                    } else {
                                        Log.e(TAG, "Failed to create new PeerConnection for user " + fromId);
                                        runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to initialize connection for user " + fromId, Toast.LENGTH_SHORT).show());
                                    }
                                }
                                @Override
                                public void onCreateSuccess(SessionDescription sdp) {}
                                @Override
                                public void onCreateFailure(String error) {}
                            }, new SessionDescription(SessionDescription.Type.ROLLBACK, ""));
                        } else {
                            processOffer(pc, fromId, sdp);
                        }
                    }
                    break;

                case "answer":
                    final PeerConnection peerConn = peerConnections.get(fromId); // Переименовано с pc на peerConn
                    if (peerConn != null) {
                        String sdp = signal.getString("sdp");
                        peerConn.setRemoteDescription(new SdpObserver() {
                            @Override
                            public void onSetSuccess() {
                                Log.d(TAG, "Remote answer set for user " + fromId);
                                signalingStates.put(fromId, PeerConnection.SignalingState.STABLE);
                                // [НОВОЕ] Принудительная ренеготиация для всех других PC, если камера включена
                                if (isCameraOn && localVideoTrack != null && isVideoTrackReady.get()) {
                                    for (Map.Entry<String, PeerConnection> entry : peerConnections.entrySet()) {
                                        String otherUserId = entry.getKey();
                                        PeerConnection otherPc = entry.getValue();
                                        // Пропускаем fromId (уже обработан) и текущего пользователя
                                        if (!otherUserId.equals(fromId) && !otherUserId.equals(currentUserId) && otherPc != null && otherPc.signalingState() == PeerConnection.SignalingState.STABLE) {
                                            // Проверяем, добавлен ли видеотрек ранее
                                            RtpSender existingSender = videoTrackSenders.get(otherUserId);
                                            if (existingSender == null) {
                                                RtpSender sender = otherPc.addTrack(localVideoTrack, Arrays.asList("stream0"));
                                                videoTrackSenders.put(otherUserId, sender);
                                                Log.d(TAG, "Forced add video track to PC for userId=" + otherUserId + " after answer from " + fromId);
                                            } else {
                                                Log.d(TAG, "Video track already added for userId=" + otherUserId);
                                            }
                                            otherPc.createOffer(new SdpObserver() {
                                                @Override
                                                public void onCreateSuccess(SessionDescription sdp) {
                                                    otherPc.setLocalDescription(new SdpObserver() {
                                                        @Override
                                                        public void onSetSuccess() {
                                                            sendSignal(createSignalJson("offer", sdp, otherUserId));
                                                            Log.d(TAG, "Forced renegotiated offer sent to userId=" + otherUserId + " after answer from " + fromId);
                                                        }
                                                        @Override
                                                        public void onSetFailure(String error) {
                                                            Log.e(TAG, "Failed to set forced renegotiated offer for userId=" + otherUserId + ": " + error);
                                                        }
                                                        @Override
                                                        public void onCreateSuccess(SessionDescription sdp) {}
                                                        @Override
                                                        public void onCreateFailure(String error) {}
                                                    }, sdp);
                                                }
                                                @Override
                                                public void onCreateFailure(String error) {
                                                    Log.e(TAG, "Failed to create forced renegotiated offer for userId=" + otherUserId + ": " + error);
                                                }
                                                @Override
                                                public void onSetSuccess() {}
                                                @Override
                                                public void onSetFailure(String error) {}
                                            }, new MediaConstraints());
                                        } else {
                                            Log.d(TAG, "Skipped renegotiation for userId=" + otherUserId + ": not STABLE, null, or self");
                                        }
                                    }
                                }
                            }
                            @Override
                            public void onSetFailure(String error) {
                                Log.e(TAG, "Failed to set remote answer for user " + fromId + ": " + error);
                                runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to process answer from user " + fromId, Toast.LENGTH_SHORT).show());
                            }
                            @Override
                            public void onCreateSuccess(SessionDescription sdp) {}
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
                    final PeerConnection pc = peerConnections.get(fromId);
                    if (pc != null) {
                        pc.addIceCandidate(iceCandidateToAdd);
                        Log.d(TAG, "ICE candidate added for user " + fromId);
                    }
                    break;

                case "endCall":
                    String userName = participants.stream()
                            .filter(u -> String.valueOf(u.getId()).equals(fromId))
                            .findFirst()
                            .map(User::getUsername)
                            .orElse("User " + fromId);
                    Toast.makeText(this, userName + " left the group call", Toast.LENGTH_LONG).show();
                    int position = -1;
                    for (int i = 0; i < participants.size(); i++) {
                        if (String.valueOf(participants.get(i).getId()).equals(fromId)) {
                            position = i;
                            break;
                        }
                    }
                    if (position != -1) {
                        participants.remove(position);
                        participantsAdapter.notifyItemRemoved(position);
                        videoParticipantsAdapter.notifyItemRemoved(position);
                    }
                    PeerConnection peerConnection = peerConnections.remove(fromId);
                    if (peerConnection != null) {
                        peerConnection.close();
                        peerConnection.dispose();
                    }
                    if (participants.size() <= 1) {
                        endCall();
                    }
                    break;

                case "cameraOn":
                    Log.d(TAG, "User " + fromId + " turned on their camera");
                    runOnUiThread(() -> {
                        cameraStates.put(fromId, true);
                        videoParticipantsAdapter.setCameraState(fromId, true);
                        SurfaceViewRenderer videoView = videoParticipantsAdapter.getVideoView(fromId);
                        if (videoView != null) {
                            videoView.release();
                            videoView.init(remoteEglBase.getEglBaseContext(), new RendererCommon.RendererEvents() {
                                @Override
                                public void onFirstFrameRendered() {
                                    Log.d(TAG, "First frame rendered for user " + fromId);
                                }
                                @Override
                                public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
                                    Log.d(TAG, "Frame resolution changed for user " + fromId + ": " + videoWidth + "x" + videoHeight);
                                }
                            });
                            videoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
                            videoView.setEnableHardwareScaler(true);
                            videoView.setZOrderMediaOverlay(true);
                            videoView.setVisibility(View.VISIBLE);
                            VideoTrack videoTrack = remoteVideoTracks.get(fromId);
                            if (videoTrack != null) {
                                videoTrack.addSink(videoView);
                                videoParticipantsAdapter.setVideoTrackBound(fromId, true);
                                videoParticipantsAdapter.setVideoTrack(fromId, videoTrack);
                                Log.d(TAG, "Bound video track to SurfaceViewRenderer for user " + fromId);
                            } else {
                                Log.w(TAG, "No video track available for user " + fromId + " yet, waiting for onAddTrack");
                            }
                        } else {
                            Log.w(TAG, "No SurfaceViewRenderer available for user " + fromId);
                        }
                        Toast.makeText(GroupCallActivity.this, participants.stream()
                                .filter(u -> String.valueOf(u.getId()).equals(fromId))
                                .findFirst()
                                .map(User::getUsername)
                                .orElse("User " + fromId) + " turned on their camera", Toast.LENGTH_SHORT).show();
                    });
                    break;

                case "cameraOff":
                    Log.d(TAG, "User " + fromId + " turned off their camera");
                    runOnUiThread(() -> {
                        cameraStates.put(fromId, false);
                        videoParticipantsAdapter.setCameraState(fromId, false);
                        VideoTrack videoTrack = remoteVideoTracks.remove(fromId);
                        if (videoTrack != null) {
                            // dispose() здесь вызывать НЕЛЬЗЯ: удалённая дорожка
                            // принадлежит PeerConnection, а не нам. Освобождение
                            // её нативного объекта роняло весь процесс по SIGABRT
                            // в signaling-потоке libjingle через секунду после
                            // прихода cameraOff. Достаточно отцепить рендерер —
                            // дорожку освободит сам PeerConnection при закрытии.
                            SurfaceViewRenderer videoView = videoParticipantsAdapter.getVideoView(fromId);
                            if (videoView != null) {
                                try {
                                    videoTrack.removeSink(videoView);
                                } catch (Exception e) {
                                    Log.w(TAG, "Не удалось отцепить рендерер для " + fromId + ": " + e.getMessage());
                                }
                            }
                        }
                        Toast.makeText(GroupCallActivity.this, participants.stream()
                                .filter(u -> String.valueOf(u.getId()).equals(fromId))
                                .findFirst()
                                .map(User::getUsername)
                                .orElse("User " + fromId) + " turned off their camera", Toast.LENGTH_SHORT).show();
                    });
                    break;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing signal message: " + e.getMessage());
        }
    }

    // [Добавлено] Метод для обработки offer после проверки состояния
    /**
     * Кто из двух участников «вежливый» при одновременных офферах.
     * Правило должно совпадать с веб-клиентом (GroupCallActivity.js):
     * вежлив тот, чей userId больше.
     */
    private boolean isPoliteTowards(String otherUserId) {
        try {
            return Long.parseLong(currentUserId) > Long.parseLong(otherUserId);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Нечисловые userId (" + currentUserId + ", " + otherUserId + "), сравниваем как строки");
            return currentUserId.compareTo(otherUserId) > 0;
        }
    }

    private void processOffer(PeerConnection pc, String fromId, String sdp) {
        pc.setRemoteDescription(new SdpObserver() {
            @Override
            public void onSetSuccess() {
                Log.d(TAG, "Set remoteDescription for user " + fromId);
                pc.createAnswer(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription answer) {
                        pc.setLocalDescription(new SdpObserver() {
                            @Override
                            public void onSetSuccess() {
                                Log.d(TAG, "Set localDescription (answer) for user " + fromId);
                                sendSignal(createSignalJson("answer", answer, fromId));
                                Log.d(TAG, "Sent answer to user " + fromId);
                                // [Изменено] Обновляем состояние на STABLE после успешной отправки answer
                                signalingStates.put(fromId, PeerConnection.SignalingState.STABLE);
                            }
                            @Override
                            public void onSetFailure(String error) {
                                Log.e(TAG, "Failed to set local description for user " + fromId + ": " + error);
                                runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to send answer to user " + fromId, Toast.LENGTH_SHORT).show());
                            }
                            @Override
                            public void onCreateSuccess(SessionDescription sdp) {}
                            @Override
                            public void onCreateFailure(String error) {}
                        }, answer);
                    }
                    @Override
                    public void onCreateFailure(String error) {
                        Log.e(TAG, "Failed to create answer for user " + fromId + ": " + error);
                        runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to create answer for user " + fromId, Toast.LENGTH_SHORT).show());
                    }
                    @Override
                    public void onSetSuccess() {}
                    @Override
                    public void onSetFailure(String error) {}
                }, new MediaConstraints());
            }
            @Override
            public void onSetFailure(String error) {
                Log.e(TAG, "Failed to set remote description for user " + fromId + ": " + error);
                runOnUiThread(() -> Toast.makeText(GroupCallActivity.this, "Failed to process offer from user " + fromId, Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onCreateSuccess(SessionDescription sdp) {}
            @Override
            public void onCreateFailure(String error) {}
        }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
    }

    private JSONObject createSignalJson(String type, Object data, String to) {
        JSONObject message = new JSONObject();
        JSONObject signal = new JSONObject();
        try {
            signal.put("type", type);
            if (data instanceof SessionDescription) {
                signal.put("sdp", ((SessionDescription) data).description);
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
            if (to != null) {
                message.put("to", to);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error creating signal JSON: " + e.getMessage());
        }
        return message;
    }

    private void sendSignal(JSONObject signalMessage) {
        if (roomId == null) {
            Log.e(TAG, "Cannot send signal: roomId is null");
            runOnUiThread(() -> Toast.makeText(this, "Connection error: roomId is missing", Toast.LENGTH_SHORT).show());
            return;
        }
        if (stompClient != null && stompClient.isConnected()) {
            String jsonBody = signalMessage.toString();
            Log.d(TAG, "Sending signal to /app/groupSignal/" + roomId + ": " + jsonBody);
            stompClient.send("/app/groupSignal/" + roomId, jsonBody)
                    .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                    .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                    .subscribe(
                            () -> Log.d(TAG, "Signal sent successfully to /app/groupSignal/" + roomId + ": " + jsonBody),
                            throwable -> Log.e(TAG, "Error sending signal to /app/groupSignal/" + roomId + ": " + throwable.getMessage())
                    );
        } else {
            Log.e(TAG, "STOMP client not connected for roomId: " + roomId);
            runOnUiThread(() -> Toast.makeText(this, "Connection lost", Toast.LENGTH_SHORT).show());
        }
    }

    private void sendReadySignal() {
        if (localAudioTrack == null) {
            Log.e(TAG, "localAudioTrack is null, cannot send ready signal");
            runOnUiThread(() -> Toast.makeText(this, "Audio not initialized", Toast.LENGTH_SHORT).show());
            return;
        }

        // Создаём PeerConnection только для инициатора
        if (!peerConnections.containsKey(String.valueOf(initiatorId))) {
            initializePeerConnection(String.valueOf(initiatorId), false);
            Log.d(TAG, "Initialized PeerConnection for initiator: " + initiatorId + " in sendReadySignal");
        }

        // Отправляем сигнал ready инициатору
        sendSignal(createSignalJson("ready", null, String.valueOf(initiatorId)));
        Log.d(TAG, "Sent ready signal to initiator: " + initiatorId);

        // Неинициатор адресует ready и acceptCall только инициатору, и остальные
        // участники их отбрасывают как «не мне». Из-за этого ветки, рассылающие
        // offer остальным, у него никогда не срабатывали: звонок так и не
        // начинался (isCallStarted оставался false), и он лишь отвечал на чужие
        // offer. С браузерами это проходило незаметно — они шлют offer всем, —
        // а два Android-участника не связывались друг с другом вообще.
        // Поэтому запускаем звонок сами, если сигнализация этого не сделала.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isCallStarted && !isCallEnded) {
                Log.d(TAG, "Ready отправлен, но звонок не стартовал по сигнализации — "
                        + "запускаем локально, чтобы разослать offer всем участникам");
                checkPermissionsAndStartCall();
            }
        }, 2000);
    }

    private void endCall() {
        if (isCallEnded) {
            return;
        }
        isCallEnded = true;

        // Отправка сигнала завершения всем участникам
        if (stompClient != null && stompClient.isConnected()) {
            for (User user : participants) {
                if (user.getId() != initiatorId) {
                    sendSignal(createSignalJson("endCall", null, String.valueOf(user.getId())));
                }
            }
        }

        // Остановка таймеров
        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerHandler = null;
            timerRunnable = null;
        }
        if (timeHandler != null && timeRunnable != null) {
            timeHandler.removeCallbacks(timeRunnable);
            timeHandler = null;
            timeRunnable = null;
        }
        if (currentTimeHandler != null && currentTimeRunnable != null) {
            currentTimeHandler.removeCallbacks(currentTimeRunnable);
            currentTimeHandler = null;
            currentTimeRunnable = null;
        }

        // [ДОБАВЛЕНО]: Остановка периодической проверки соединений
        if (connectionCheckHandler != null && connectionCheckRunnable != null) {
            connectionCheckHandler.removeCallbacks(connectionCheckRunnable);
            connectionCheckHandler = null;
            connectionCheckRunnable = null;
            Log.d(TAG, "Cleared connection check handler");
        }

        // Отписка от топика
        if (roomSubscription != null && !roomSubscription.isDisposed()) {
            roomSubscription.dispose();
            roomSubscription = null;
        }

        // Остановка статистики
        if (statsHandler != null && statsRunnable != null) {
            statsHandler.removeCallbacks(statsRunnable);
            statsHandler = null;
            statsRunnable = null;
        }

        // Очистка видеопотоков
        cleanupCamera(); // Вызываем cleanupCamera для очистки видеоресурсов
        videoTrackSenders.clear(); // Очищаем коллекцию RtpSender
        // Удалённые дорожки принадлежат PeerConnection — их нельзя освобождать
        // самим, иначе процесс падает по SIGABRT. Просто забываем ссылки,
        // а нативные объекты освободит pc.dispose() ниже.
        remoteVideoTracks.clear();
        // remoteEglBase освобождаем первым: его контекст разделяет ресурсы
        // с localEglBase, поэтому родительский контекст должен пережить дочерний.
        if (remoteEglBase != null) {
            remoteEglBase.release();
            remoteEglBase = null;
        }
        if (localEglBase != null) {
            localEglBase.release();
            localEglBase = null;
        }

        // Освобождение WebRTC
        for (PeerConnection pc : peerConnections.values()) {
            if (pc != null) {
                try {
                    pc.close();
                    pc.dispose();
                } catch (Exception e) {
                    Log.e(TAG, "Error disposing PeerConnection: " + e.getMessage(), e);
                }
            }
        }
        peerConnections.clear();
        signalingStates.clear();

        if (localAudioTrack != null) {
            try {
                localAudioTrack.setEnabled(false);
                localAudioTrack.dispose();
            } catch (Exception e) {
                Log.e(TAG, "Error disposing AudioTrack: " + e.getMessage(), e);
            }
            localAudioTrack = null;
        }

        if (audioSource != null) {
            try {
                audioSource.dispose();
            } catch (Exception e) {
                Log.e(TAG, "Error disposing AudioSource: " + e.getMessage(), e);
            }
            audioSource = null;
        }

        if (peerConnectionFactory != null) {
            try {
                peerConnectionFactory.dispose();
            } catch (Exception e) {
                Log.e(TAG, "Error disposing PeerConnectionFactory: " + e.getMessage(), e);
            }
            peerConnectionFactory = null;
        }

        // Остановка рингтона
        if (ringtonePlayer != null) {
            try {
                if (ringtonePlayer.isPlaying()) {
                    ringtonePlayer.stop();
                }
                ringtonePlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing ringtonePlayer: " + e.getMessage(), e);
            }
            ringtonePlayer = null;
        }

        // Сброс аудио
        if (audioManager != null) {
            try {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(false);
            } catch (Exception e) {
                Log.e(TAG, "Error resetting AudioManager: " + e.getMessage(), e);
            }
            audioManager = null;
        }

        Log.d(TAG, "Call ended, resources released");
        isCallStarted = false; // Сбрасываем isCallStarted перед сохранением
        saveCall("completed");
        finish();
    }

    private static final String PREFS_GROUP_CALL = "group_call";
    private static final String PREF_TILE_MODE = "tile_mode";

    /** Форма плиток, выбранная в прошлый раз. По умолчанию — автоподбор. */
    private String loadSavedTileMode() {
        return getSharedPreferences(PREFS_GROUP_CALL, MODE_PRIVATE)
                .getString(PREF_TILE_MODE, VideoParticipantsAdapter.MODE_AUTO);
    }

    /** Переключает форму плиток по кругу Auto → Wide → Tall и запоминает выбор. */
    private void cycleTileMode() {
        String current = videoParticipantsAdapter.getTileMode();
        String next;
        if (VideoParticipantsAdapter.MODE_AUTO.equals(current)) {
            next = VideoParticipantsAdapter.MODE_LANDSCAPE;
        } else if (VideoParticipantsAdapter.MODE_LANDSCAPE.equals(current)) {
            next = VideoParticipantsAdapter.MODE_PORTRAIT;
        } else {
            next = VideoParticipantsAdapter.MODE_AUTO;
        }

        videoParticipantsAdapter.setTileMode(next);
        getSharedPreferences(PREFS_GROUP_CALL, MODE_PRIVATE).edit()
                .putString(PREF_TILE_MODE, next)
                .apply();
        updateTileModeButton();
    }

    /** Подписывает кнопку текущим режимом и показывает её только в видео-режиме. */
    private void updateTileModeButton() {
        Button tileModeButton = findViewById(R.id.tile_mode_button);
        if (tileModeButton == null) return;

        String mode = videoParticipantsAdapter.getTileMode();
        String label;
        if (VideoParticipantsAdapter.MODE_LANDSCAPE.equals(mode)) {
            label = "Wide";
        } else if (VideoParticipantsAdapter.MODE_PORTRAIT.equals(mode)) {
            label = "Tall";
        } else {
            label = "Auto";
        }

        tileModeButton.setText(label);
        tileModeButton.setVisibility(isVideoMode ? View.VISIBLE : View.GONE);
    }

    // [ДОБАВЛЕНО]: Переключение между аудио- и видеовкладками
    private void toggleMode() {
        isVideoMode = !isVideoMode;
        updateTileModeButton();
        ImageButton toggleModeButton = findViewById(R.id.toggle_mode_button);
        FrameLayout contentContainer = findViewById(R.id.content_container);
        LinearLayout audioContainer = findViewById(R.id.audio_container);
        RecyclerView videoGrid = findViewById(R.id.video_grid);

        if (isVideoMode) {
            toggleModeButton.setImageResource(R.drawable.ic_microphone);
            audioContainer.setVisibility(View.GONE);
            videoGrid.setVisibility(View.VISIBLE);
            // Принудительное обновление UI
            videoGrid.requestLayout();
            videoGrid.invalidate();
            if (isCameraOn && localVideoTrack != null) {
                runOnUiThread(() -> {
                    if (localVideoView.getHolder().getSurface().isValid()) {
                        localVideoTrack.removeSink(localVideoView);
                        localVideoTrack.addSink(localVideoView);
                        localVideoView.setVisibility(View.VISIBLE);
                        localNoVideoText.setVisibility(View.GONE);
                        localVideoView.requestLayout();
                        localVideoView.invalidate();
                        Log.d(TAG, "toggleMode: Reattached localVideoTrack to localVideoView in video mode, surface valid=" + localVideoView.getHolder().getSurface().isValid());
                    } else {
                        Log.w(TAG, "toggleMode: Surface not valid in video mode");
                        localNoVideoText.setVisibility(View.VISIBLE);
                        Toast.makeText(this, "Surface not ready in video mode", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            Log.d(TAG, "toggleMode: Switched to video mode, set icon to ic_microphone, videoGrid width=" + videoGrid.getWidth() + ", height=" + videoGrid.getHeight());
        } else {
            toggleModeButton.setImageResource(R.drawable.ic_camera);
            audioContainer.setVisibility(View.VISIBLE);
            videoGrid.setVisibility(View.GONE);
            Log.d(TAG, "toggleMode: Switched to audio mode, set icon to ic_camera");
        }
        // Убедимся, что кнопка видима и без цветового оттенка
        toggleModeButton.setVisibility(View.VISIBLE);
        toggleModeButton.setBackgroundTintList(null);
        Log.d(TAG, "toggleMode: Completed, isVideoMode=" + isVideoMode + ", button visibility=" + (toggleModeButton.getVisibility() == View.VISIBLE ? "VISIBLE" : "INVISIBLE"));
    }

    private void toggleCamera() {
        Log.d(TAG, "toggleCamera: isCameraOn=" + isCameraOn + ", currentUserId=" + currentUserId);
        if (!isCameraOn) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Camera permission not granted, requesting permission");
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA}, 2);
                return;
            }

            // Убедимся, что localVideoView и кнопка видимы
            runOnUiThread(() -> {
                localVideoView.setVisibility(View.VISIBLE);
                localNoVideoText.setVisibility(View.VISIBLE);
                toggleCameraButton.setVisibility(View.VISIBLE);
                toggleCameraButton.bringToFront();
                localVideoView.requestLayout();
                localVideoView.invalidate();
                Log.d(TAG, "toggleCamera: Set localVideoView and toggleCameraButton to VISIBLE before camera init");
            });

            executor.execute(() -> {
                Log.d(TAG, "toggleCamera: Starting camera initialization in executor thread");
                try {
                    // Очистка предыдущих ресурсов
                    cleanupCamera();

                    // Ожидание готовности поверхности
                    final int maxSurfaceRetries = 5;
                    final long surfaceRetryDelayMs = 500;
                    AtomicBoolean surfaceReady = new AtomicBoolean(false);
                    for (int i = 0; i < maxSurfaceRetries; i++) {
                        if (localVideoView.getHolder().getSurface().isValid()) {
                            surfaceReady.set(true);
                            Log.d(TAG, "toggleCamera: Surface is valid on attempt " + (i + 1));
                            break;
                        }
                        Log.w(TAG, "toggleCamera: Surface not valid on attempt " + (i + 1) + ", retrying in " + surfaceRetryDelayMs + "ms");
                        try {
                            Thread.sleep(surfaceRetryDelayMs);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Interrupted while waiting for surface: " + e.getMessage());
                        }
                    }

                    if (!surfaceReady.get()) {
                        Log.e(TAG, "localVideoView surface is not valid after " + maxSurfaceRetries + " retries");
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Video surface not ready after retries", Toast.LENGTH_LONG).show();
                            localVideoView.setVisibility(View.VISIBLE);
                            localVideoView.clearImage();
                            localNoVideoText.setVisibility(View.VISIBLE);
                            toggleCameraButton.setVisibility(View.VISIBLE);
                            toggleCameraButton.bringToFront();
                        });
                        return;
                    }

                    // Инициализация камеры
                    Camera2Enumerator cameraEnumerator = new Camera2Enumerator(this);
                    String[] deviceNames = cameraEnumerator.getDeviceNames();
                    if (deviceNames.length == 0) {
                        Log.e(TAG, "No cameras available");
                        runOnUiThread(() -> {
                            Toast.makeText(this, "No camera available", Toast.LENGTH_SHORT).show();
                            localVideoView.setVisibility(View.VISIBLE);
                            localVideoView.clearImage();
                            localNoVideoText.setVisibility(View.VISIBLE);
                            toggleCameraButton.setVisibility(View.VISIBLE);
                            toggleCameraButton.bringToFront();
                        });
                        return;
                    }
                    String cameraName = null;
                    for (String deviceName : deviceNames) {
                        if (cameraEnumerator.isFrontFacing(deviceName)) {
                            cameraName = deviceName;
                            break;
                        }
                    }
                    if (cameraName == null) {
                        cameraName = deviceNames[0];
                    }
                    Log.d(TAG, "Using camera: " + cameraName);

                    videoCapturer = cameraEnumerator.createCapturer(cameraName, new CameraVideoCapturer.CameraEventsHandler() {
                        @Override
                        public void onCameraError(String errorDescription) {
                            Log.e(TAG, "Camera error for userId=" + currentUserId + ": " + errorDescription);
                            runOnUiThread(() -> {
                                Toast.makeText(GroupCallActivity.this, "Camera error: " + errorDescription, Toast.LENGTH_SHORT).show();
                                localVideoView.setVisibility(View.VISIBLE);
                                localVideoView.clearImage();
                                localNoVideoText.setVisibility(View.VISIBLE);
                                toggleCameraButton.setText("📷");
                                toggleCameraButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x80FFFFFF));
                                toggleCameraButton.setVisibility(View.VISIBLE);
                                toggleCameraButton.bringToFront();
                            });
                        }
                        @Override
                        public void onCameraDisconnected() {
                            Log.e(TAG, "Camera disconnected for userId=" + currentUserId);
                            runOnUiThread(() -> {
                                localVideoView.setVisibility(View.VISIBLE);
                                localVideoView.clearImage();
                                localNoVideoText.setVisibility(View.VISIBLE);
                                toggleCameraButton.setText("📷");
                                toggleCameraButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x80FFFFFF));
                                toggleCameraButton.setVisibility(View.VISIBLE);
                                toggleCameraButton.bringToFront();
                            });
                        }
                        @Override
                        public void onCameraFreezed(String errorDescription) {
                            Log.e(TAG, "Camera freezed for userId=" + currentUserId + ": " + errorDescription);
                            runOnUiThread(() -> {
                                localVideoView.setVisibility(View.VISIBLE);
                                localVideoView.clearImage();
                                localNoVideoText.setVisibility(View.VISIBLE);
                                toggleCameraButton.setText("📷");
                                toggleCameraButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x80FFFFFF));
                                toggleCameraButton.setVisibility(View.VISIBLE);
                                toggleCameraButton.bringToFront();
                            });
                        }
                        @Override
                        public void onCameraOpening(String cameraName) {
                            Log.d(TAG, "Camera opening: " + cameraName + " for userId=" + currentUserId);
                        }
                        @Override
                        public void onFirstFrameAvailable() {
                            Log.d(TAG, "First frame available from camera for userId=" + currentUserId + ", localVideoTrack state=" + (localVideoTrack != null ? localVideoTrack.state() : "null"));
                            isVideoTrackReady.set(true);
                            runOnUiThread(() -> {
                                Log.d(TAG, "onFirstFrameAvailable: Surface valid=" + localVideoView.getHolder().getSurface().isValid());
                                localVideoView.setVisibility(View.VISIBLE);
                                localNoVideoText.setVisibility(View.GONE);
                                localVideoView.requestLayout();
                                localVideoView.invalidate();
                                toggleCameraButton.setVisibility(View.VISIBLE);
                                toggleCameraButton.bringToFront();
                                // Минимальная корректировка позиции кнопки
                                toggleCameraButton.post(() -> {
                                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) toggleCameraButton.getLayoutParams();
                                    params.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
                                    params.setMargins(0, 0, (int) (4 * getResources().getDisplayMetrics().density), (int) (2 * getResources().getDisplayMetrics().density));
                                    toggleCameraButton.setLayoutParams(params);
                                    Log.d(TAG, "toggleCameraButton position fixed: gravity=bottom|end, margins=4dp");
                                });
                            });
                        }
                        @Override
                        public void onCameraClosed() {
                            Log.d(TAG, "Camera closed for userId=" + currentUserId);
                            runOnUiThread(() -> {
                                localVideoView.setVisibility(View.VISIBLE);
                                localVideoView.clearImage();
                                localNoVideoText.setVisibility(View.VISIBLE);
                                toggleCameraButton.setText("📷");
                                toggleCameraButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x80FFFFFF));
                                toggleCameraButton.setVisibility(View.VISIBLE);
                                toggleCameraButton.bringToFront();
                            });
                        }
                    });

                    surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", localEglBase.getEglBaseContext());
                    if (surfaceTextureHelper == null) {
                        Log.e(TAG, "Failed to create SurfaceTextureHelper");
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Failed to create SurfaceTextureHelper", Toast.LENGTH_SHORT).show();
                            localVideoView.setVisibility(View.VISIBLE);
                            localVideoView.clearImage();
                            localNoVideoText.setVisibility(View.VISIBLE);
                            toggleCameraButton.setVisibility(View.VISIBLE);
                            toggleCameraButton.bringToFront();
                        });
                        return;
                    }
                    surfaceTextureHelper.setTextureSize(1280, 720);
                    Log.d(TAG, "Created SurfaceTextureHelper: isTextureInUse=" + surfaceTextureHelper.isTextureInUse());

                    // Создание VideoSource
                    videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
                    if (videoSource == null) {
                        Log.e(TAG, "Failed to create VideoSource");
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Failed to create VideoSource", Toast.LENGTH_SHORT).show();
                            localVideoView.setVisibility(View.VISIBLE);
                            localVideoView.clearImage();
                            localNoVideoText.setVisibility(View.VISIBLE);
                            toggleCameraButton.setVisibility(View.VISIBLE);
                            toggleCameraButton.bringToFront();
                        });
                        return;
                    }
                    Log.d(TAG, "Created VideoSource: not null");

                    // Создание localVideoTrack
                    localVideoTrack = peerConnectionFactory.createVideoTrack("video_" + System.currentTimeMillis(), videoSource);
                    localVideoTrack.setEnabled(true);
                    Log.d(TAG, "Created localVideoTrack: not null");

                    // VideoProcessor
                    VideoProcessor videoProcessor = new VideoProcessor() {
                        private VideoSink sink = null;

                        @Override
                        public void onFrameCaptured(VideoFrame frame) {
                            Log.d(TAG, "VideoSource: Frame captured, format=" + frame.getBuffer().getClass().getSimpleName());
                            if (!(frame.getBuffer() instanceof org.webrtc.VideoFrame.I420Buffer)) {
                                org.webrtc.VideoFrame.Buffer i420Buffer = frame.getBuffer().toI420();
                                if (i420Buffer != null) {
                                    frame = new VideoFrame(i420Buffer, frame.getRotation(), frame.getTimestampNs());
                                } else {
                                    Log.e(TAG, "Failed to convert frame to I420, original format=" + frame.getBuffer().getClass().getSimpleName());
                                    return;
                                }
                            }
                            if (sink != null) {
                                sink.onFrame(frame);
                            } else {
                                Log.w(TAG, "VideoProcessor: No sink set for frame");
                            }
                        }

                        @Override
                        public void setSink(VideoSink sink) {
                            this.sink = sink;
                        }

                        @Override
                        public void onCapturerStarted(boolean success) {
                            Log.d(TAG, "VideoProcessor: Capturer started, success=" + success);
                        }

                        @Override
                        public void onCapturerStopped() {
                            Log.d(TAG, "VideoProcessor: Capturer stopped");
                        }
                    };
                    videoSource.setVideoProcessor(videoProcessor);

                    // Механизм повторных попыток рендеринга
                    final int maxRenderRetries = 5;
                    final long renderRetryDelayMs = 1000;
                    AtomicBoolean isRendering = new AtomicBoolean(false);
                    Handler handler = new Handler(Looper.getMainLooper());
                    Runnable renderRetryRunnable = new Runnable() {
                        int attempt = 1;
                        @Override
                        public void run() {
                            if (isRendering.get() || attempt > maxRenderRetries) {
                                if (!isRendering.get()) {
                                    Log.e(TAG, "Failed to render video after " + maxRenderRetries + " attempts");
                                    runOnUiThread(() -> {
                                        Toast.makeText(GroupCallActivity.this, "Failed to render video after retries", Toast.LENGTH_LONG).show();
                                        localVideoView.setVisibility(View.VISIBLE);
                                        localVideoView.clearImage();
                                        localNoVideoText.setVisibility(View.VISIBLE);
                                        toggleCameraButton.setVisibility(View.VISIBLE);
                                        toggleCameraButton.bringToFront();
                                    });
                                }
                                return;
                            }

                            Log.d(TAG, "Attempt " + attempt + " to initialize and render localVideoView");
                            runOnUiThread(() -> {
                                try {
                                    // Освобождаем и повторно инициализируем localVideoView
                                    localVideoView.release();
                                    localVideoView.init(localEglBase.getEglBaseContext(), new RendererCommon.RendererEvents() {
                                        @Override
                                        public void onFirstFrameRendered() {
                                            Log.d(TAG, "localVideoView: First frame rendered on attempt " + attempt);
                                            isRendering.set(true);
                                            runOnUiThread(() -> {
                                                localVideoView.setVisibility(View.VISIBLE);
                                                localNoVideoText.setVisibility(View.GONE);
                                                toggleCameraButton.setVisibility(View.VISIBLE);
                                                toggleCameraButton.bringToFront();
                                            });
                                        }
                                        @Override
                                        public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
                                            Log.d(TAG, "localVideoView: Resolution changed: " + videoWidth + "x" + videoHeight + ", rotation=" + rotation);
                                        }
                                    });
                                    localVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
                                    localVideoView.setEnableHardwareScaler(true);
                                    localVideoView.setMirror(true);
                                    localVideoView.setZOrderMediaOverlay(true);
                                    localVideoView.setVisibility(View.VISIBLE);
                                    localNoVideoText.setVisibility(View.GONE);
                                    toggleCameraButton.setVisibility(View.VISIBLE);
                                    toggleCameraButton.bringToFront();
                                    Log.d(TAG, "Reinitialized localVideoView on attempt " + attempt + ", surface valid=" + localVideoView.getHolder().getSurface().isValid());

                                    // Привязываем localVideoTrack, если готов
                                    if (isVideoTrackReady.get() && localVideoView.getHolder().getSurface().isValid()) {
                                        localVideoTrack.addSink(localVideoView);
                                        localVideoTrack.addSink(new VideoSink() {
                                            @Override
                                            public void onFrame(VideoFrame frame) {
                                                Log.d(TAG, "localVideoView: Received frame on attempt " + attempt + ", format=" + frame.getBuffer().getClass().getSimpleName());
                                            }
                                        });
                                        Log.d(TAG, "Attached localVideoTrack to localVideoView on attempt " + attempt);
                                    } else {
                                        Log.w(TAG, "Cannot attach localVideoTrack: isVideoTrackReady=" + isVideoTrackReady.get() + ", surface valid=" + localVideoView.getHolder().getSurface().isValid());
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to reinitialize localVideoView on attempt " + attempt + ": " + e.getMessage(), e);
                                }

                                // Планируем следующую попытку, если рендеринг не начался
                                if (!isRendering.get()) {
                                    attempt++;
                                    handler.postDelayed(this, renderRetryDelayMs);
                                }
                            });
                        }
                    };

                    // Запускаем первую попытку рендеринга
                    handler.postDelayed(renderRetryRunnable, renderRetryDelayMs);

                    // Инициализация и запуск захвата
                    videoCapturer.initialize(surfaceTextureHelper, GroupCallActivity.this, videoSource.getCapturerObserver());
                    Log.d(TAG, "Initialized VideoCapturer");
                    videoCapturer.startCapture(1280, 720, 30);
                    Log.d(TAG, "Started video capture: 1280x720@30");

                    // Принудительное обновление UI
                    runOnUiThread(() -> {
                        isCameraOn = true;
                        cameraStates.put(currentUserId, true);
                        toggleCameraButton.setText("🔴");
                        toggleCameraButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF4444));
                        localVideoView.setVisibility(View.VISIBLE);
                        localNoVideoText.setVisibility(View.GONE);
                        toggleCameraButton.setVisibility(View.VISIBLE);
                        toggleCameraButton.bringToFront();
                        Log.d(TAG, "UI updated: localVideoView visible, localNoVideoText gone, toggleCameraButton visible");
                    });

                    // Настройка MediaConstraints для согласования параметров
                    MediaConstraints constraints = new MediaConstraints();
                    constraints.mandatory.add(new MediaConstraints.KeyValuePair("maxWidth", "1280"));
                    constraints.mandatory.add(new MediaConstraints.KeyValuePair("maxHeight", "720"));
                    constraints.mandatory.add(new MediaConstraints.KeyValuePair("maxFrameRate", "30"));
                    constraints.mandatory.add(new MediaConstraints.KeyValuePair("minFrameRate", "15"));

                    // Добавление видеотрека и ренеготиация
                    for (Map.Entry<String, PeerConnection> entry : peerConnections.entrySet()) {
                        String userId = entry.getKey();
                        PeerConnection pc = entry.getValue();
                        if (pc != null && pc.signalingState() == PeerConnection.SignalingState.STABLE) {
                            RtpSender sender = pc.addTrack(localVideoTrack, Arrays.asList("stream0"));
                            videoTrackSenders.put(userId, sender);
                            Log.d(TAG, "Added video track to PeerConnection for userId=" + userId + ", sender=" + sender.id());
                            pc.createOffer(new SdpObserver() {
                                @Override
                                public void onCreateSuccess(SessionDescription sdp) {
                                    pc.setLocalDescription(new SdpObserver() {
                                        @Override
                                        public void onSetSuccess() {
                                            Log.d(TAG, "Renegotiated offer set for userId=" + userId);
                                            sendSignal(createSignalJson("offer", sdp, userId));
                                            Log.d(TAG, "Sent renegotiated offer to userId=" + userId);
                                        }
                                        @Override
                                        public void onSetFailure(String error) {
                                            Log.e(TAG, "Failed to set renegotiated offer for userId=" + userId + ": " + error);
                                        }
                                        @Override
                                        public void onCreateSuccess(SessionDescription sdp) {}
                                        @Override
                                        public void onCreateFailure(String error) {}
                                    }, sdp);
                                }
                                @Override
                                public void onCreateFailure(String error) {
                                    Log.e(TAG, "Failed to create renegotiated offer for userId=" + userId + ": " + error);
                                }
                                @Override
                                public void onSetSuccess() {}
                                @Override
                                public void onSetFailure(String error) {}
                            }, constraints);
                        } else {
                            Log.w(TAG, "PeerConnection for userId=" + userId + " is null or not in STABLE state: " + (pc != null ? pc.signalingState() : "null"));
                        }
                    }

                    // Отправка сигнала cameraOn
                    for (User user : participants) {
                        String userId = String.valueOf(user.getId());
                        if (!userId.equals(currentUserId)) {
                            sendSignal(createSignalJson("cameraOn", null, userId));
                            Log.d(TAG, "Sent cameraOn signal to userId=" + userId);
                        }
                    }
                    Log.d(TAG, "Camera turned on, notified other participants for userId=" + currentUserId);
                } catch (Exception e) {
                    Log.e(TAG, "Error enabling camera: " + e.getMessage(), e);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Failed to enable camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        localVideoView.setVisibility(View.VISIBLE);
                        localVideoView.clearImage();
                        localNoVideoText.setVisibility(View.VISIBLE);
                        toggleCameraButton.setText("📷");
                        toggleCameraButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x80FFFFFF));
                        toggleCameraButton.setVisibility(View.VISIBLE);
                        toggleCameraButton.bringToFront();
                    });
                }
            });
        } else {
            executor.execute(() -> {
                Log.d(TAG, "toggleCamera: Turning off camera");
                cleanupCamera();
                isCameraOn = false;
                isVideoTrackReady.set(false);
                cameraStates.put(currentUserId, false);

                // Обновление UI
                runOnUiThread(() -> {
                    localVideoView.setVisibility(View.VISIBLE);
                    localVideoView.clearImage();
                    localNoVideoText.setVisibility(View.VISIBLE);
                    toggleCameraButton.setText("📷");
                    toggleCameraButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x80FFFFFF));
                    toggleCameraButton.setVisibility(View.VISIBLE);
                    toggleCameraButton.bringToFront();
                    localVideoView.requestLayout();
                    localVideoView.invalidate();
                    Log.d(TAG, "UI updated: localVideoView visible (black), localNoVideoText visible, toggleCameraButton visible");
                });

                // Ренеготиация после отключения камеры
                MediaConstraints constraints = new MediaConstraints();
                for (Map.Entry<String, PeerConnection> entry : peerConnections.entrySet()) {
                    String userId = entry.getKey();
                    PeerConnection pc = entry.getValue();
                    if (pc != null && pc.signalingState() == PeerConnection.SignalingState.STABLE) {
                        pc.createOffer(new SdpObserver() {
                            @Override
                            public void onCreateSuccess(SessionDescription sdp) {
                                pc.setLocalDescription(new SdpObserver() {
                                    @Override
                                    public void onSetSuccess() {
                                        Log.d(TAG, "Renegotiated offer set for userId=" + userId + " after camera off");
                                        sendSignal(createSignalJson("offer", sdp, userId));
                                        Log.d(TAG, "Sent renegotiated offer to userId=" + userId);
                                    }
                                    @Override
                                    public void onSetFailure(String error) {
                                        Log.e(TAG, "Failed to set renegotiated offer for userId=" + userId + ": " + error);
                                    }
                                    @Override
                                    public void onCreateSuccess(SessionDescription sdp) {}
                                    @Override
                                    public void onCreateFailure(String error) {}
                                }, sdp);
                            }
                            @Override
                            public void onCreateFailure(String error) {
                                Log.e(TAG, "Failed to create renegotiated offer for userId=" + userId + ": " + error);
                            }
                            @Override
                            public void onSetSuccess() {}
                            @Override
                            public void onSetFailure(String error) {}
                        }, constraints);
                    }
                }

                // Отправка сигнала cameraOff
                for (User user : participants) {
                    String userId = String.valueOf(user.getId());
                    if (!userId.equals(currentUserId)) {
                        sendSignal(createSignalJson("cameraOff", null, userId));
                        Log.d(TAG, "Sent cameraOff signal to userId=" + userId);
                    }
                }
                Log.d(TAG, "Camera turned off, notified other participants for userId=" + currentUserId);
            });
        }
    }

    private int getParticipantPosition(String userId) {
        for (int i = 0; i < participants.size(); i++) {
            if (String.valueOf(participants.get(i).getId()).equals(userId)) {
                return i;
            }
        }
        return -1;
    }

    // Добавляем метод cleanupCamera
    private void cleanupCamera() {
        Log.d(TAG, "cleanupCamera: Starting cleanup for userId=" + currentUserId);
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
                Log.d(TAG, "Video capturer stopped");
                videoCapturer.dispose();
                Log.d(TAG, "Video capturer disposed");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping video capturer: " + e.getMessage());
            }
            videoCapturer = null;
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.stopListening();
            surfaceTextureHelper.dispose();
            Log.d(TAG, "SurfaceTextureHelper disposed");
            surfaceTextureHelper = null;
        }
        if (localVideoTrack != null) {
            for (Map.Entry<String, PeerConnection> entry : peerConnections.entrySet()) {
                String userId = entry.getKey();
                PeerConnection pc = entry.getValue();
                if (pc != null) {
                    RtpSender sender = videoTrackSenders.get(userId);
                    if (sender != null) {
                        pc.removeTrack(sender);
                        Log.d(TAG, "Removed video track sender from PeerConnection for userId=" + userId);
                        videoTrackSenders.remove(userId);
                    } else {
                        Log.w(TAG, "No RtpSender found for userId=" + userId);
                    }
                }
            }
            if (localVideoView != null) {
                localVideoTrack.removeSink(localVideoView);
                Log.d(TAG, "Removed video track sink from localVideoView for userId=" + currentUserId);
                runOnUiThread(() -> {
                    localVideoView.clearImage();
                    localVideoView.setVisibility(View.GONE);
                    localNoVideoText.setVisibility(View.VISIBLE);
                    localVideoView.requestLayout();
                    localVideoView.invalidate();
                    Log.d(TAG, "UI updated in cleanupCamera: localVideoView gone, localNoVideoText visible");
                });
            }
            localVideoTrack.dispose();
            Log.d(TAG, "Local video track disposed");
            localVideoTrack = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            Log.d(TAG, "Video source disposed");
            videoSource = null;
        }
        isCameraOn = false;
        isVideoTrackReady.set(false);
        cameraStates.put(currentUserId, false);
        Log.d(TAG, "cleanupCamera: Completed cleanup for userId=" + currentUserId);
    }

    private void saveCall(String status) {
        if (hasSavedCall) {
            Log.d(TAG, "Call already saved, skipping saveCall");
            return;
        }

        OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        JSONObject callData = new JSONObject();
        // Определяем статус звонка вне блока try
        final String effectiveFinalStatus;
        if (readyUsers.size() == 0 && !isCallStarted) {
            effectiveFinalStatus = isInitiator ? "rejected" : "missed";
        } else {
            effectiveFinalStatus = status;
        }

        try {
            callData.put("caller", String.valueOf(initiatorId)); // Используем "caller" вместо "initiatorId"
            JSONArray participantsArray = new JSONArray();
            for (User user : this.participants) {
                participantsArray.put(String.valueOf(user.getId()));
            }
            callData.put("participants", participantsArray);
            callData.put("direction", isInitiator ? "outgoing" : "incoming");
            callData.put("status", effectiveFinalStatus);
            callData.put("callType", "group");
            callData.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date()));
            // Убрано поле "roomId", так как его нет в React
        } catch (JSONException e) {
            Log.e(TAG, "Error creating call JSON: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(this, "Failed to save call", Toast.LENGTH_SHORT).show());
            return;
        }

        Log.d(TAG, "Group call JSON: " + callData.toString()); // Лог для отладки JSON
        RequestBody body = RequestBody.create(callData.toString(), JSON);
        Request request = new Request.Builder()
                .url("https://unlimitedpossibilities12.org/api/calls?userId=" + currentUserId)
                .post(body)
                .build();

        new Thread(() -> {
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Group call saved to server with status: " + effectiveFinalStatus);
                    hasSavedCall = true;
                } else {
                    Log.e(TAG, "Failed to save call, response code: " + response.code() + ", response body: " + (response.body() != null ? response.body().string() : "null"));
                    runOnUiThread(() -> Toast.makeText(this, "Failed to save call", Toast.LENGTH_SHORT).show());
                }
            } catch (IOException e) {
                Log.e(TAG, "Error saving group call: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "Failed to save call", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Override
    protected void onDestroy() {
        com.example.unlimitedpossibilities12.messenger.notife.CallService.setCallInProgress(false);
        super.onDestroy();
        if (!isCallEnded) {
            endCall();
        }
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        for (Map.Entry<String, SurfaceViewRenderer> entry : videoParticipantsAdapter.getVideoViews().entrySet()) {
            SurfaceViewRenderer videoView = entry.getValue();
            if (videoView != null) {
                try {
                    videoView.release();
                    Log.d(TAG, "Released SurfaceViewRenderer for user " + entry.getKey());
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing SurfaceViewRenderer for user " + entry.getKey() + ": " + e.getMessage());
                }
            }
        }
        if (localVideoView != null) {
            try {
                localVideoView.release();
                Log.d(TAG, "Released localVideoView");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing localVideoView: " + e.getMessage());
            }
            localVideoView = null;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}