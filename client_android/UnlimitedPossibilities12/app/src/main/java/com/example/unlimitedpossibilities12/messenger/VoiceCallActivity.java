package com.example.unlimitedpossibilities12.messenger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

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

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import io.reactivex.disposables.Disposable;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Request;
import ua.naiksoftware.stomp.StompClient;

public class VoiceCallActivity extends AppCompatActivity {
    private static final String TAG = "VoiceCallActivity";
    private StompClient stompClient;
    private String currentUserId;
    private String recipientId;
    private String roomId;
    private boolean isCaller;
    private String callerName;
    private String recipientName;
    private PeerConnection peerConnection;
    private PeerConnectionFactory peerConnectionFactory;
    /**
     * Картинка в том же разговоре.
     *
     * Прежде видео жило отдельным экраном: начав голосом, включить камеру
     * было нельзя — только положить трубку и позвонить заново. Теперь
     * камера включается кнопкой, её дорожка добавляется к живому
     * соединению, и собеседнику уходит новое предложение связи.
     */
    private org.webrtc.EglBase eglBase;
    private org.webrtc.SurfaceViewRenderer remoteVideoView;
    private org.webrtc.SurfaceViewRenderer localVideoView;
    private org.webrtc.VideoCapturer videoCapturer;
    private org.webrtc.SurfaceTextureHelper surfaceHelper;
    private org.webrtc.VideoSource videoSource;
    private org.webrtc.VideoTrack localVideoTrack;
    private org.webrtc.RtpSender videoSender;
    private boolean isCameraOn = false;
    /**
     * Камера занята: заводится или гасится.
     *
     * Открытие камеры идёт в своём потоке и занимает доли секунды. Второе
     * нажатие в это время уходило в ветку выключения и освобождало
     * источник видео, с которым первый поток ещё работал, — приложение
     * падало в родном коде (SIGSEGV в nativeSetState на потоке захвата) и
     * звонок обрывался вместе с ним.
     */
    private volatile boolean cameraBusy = false;
    private boolean partnerCameraOn = false;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private AudioManager audioManager;
    private MediaPlayer ringtonePlayer;
    private boolean isWaiting = false;
    private String userName;
    private boolean terminationInitiator = false;
    private android.widget.ImageView endCallButton, muteButton, speakerButton, minimizeButton;
    private android.widget.ImageView callAvatarImage;
    private TextView callAvatarText, callStatus, muteLabel, speakerLabel;
    /** Микрофон выключен — звонок идёт, но нас не слышно. */
    private boolean micMuted = false;
    private boolean speakerOn = true;
    private boolean callConnected = false;
    private CallService callService;
    private boolean isBound = false;
    private Handler statsHandler;
    private Runnable statsRunnable;
    private boolean isCallStarted = false;
    private boolean isCallEnded = false;
    private boolean isAcceptCallSent = false;
    private boolean hasSavedCall = false; // Флаг для предотвращения дублирования сохранения звонка
    private boolean isOfferSent = false;
    private Disposable roomSubscription; // Добавлен Disposable для управления подпиской

    //переменные для управления таймером, временем начала и данными графика
    private TextView callTitle;
    private TextView callTimer;

    private Handler timerHandler;
    private Runnable timerRunnable;
    private long callStartTime;

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
        setContentView(R.layout.activity_voice_call);

        // Запрещаем поворот экрана
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        currentUserId = getIntent().getStringExtra("currentUserId");
        recipientId = getIntent().getStringExtra("recipientId");
        roomId = getIntent().getStringExtra("roomId");
        isCaller = getIntent().getBooleanExtra("isCaller", false);
        callerName = getIntent().getStringExtra("callerName");
        recipientName = getIntent().getStringExtra("recipientName");

        Log.d(TAG, "onCreate: currentUserId=" + currentUserId + ", recipientId=" + recipientId + ", roomId=" + roomId + ", isCaller=" + isCaller);

        // Инициализация UI
        callTitle = findViewById(R.id.call_title);
        callTimer = findViewById(R.id.call_timer);
        callStatus = findViewById(R.id.call_status);
        callAvatarImage = findViewById(R.id.call_avatar_image);
        callAvatarText = findViewById(R.id.call_avatar_text);
        endCallButton = findViewById(R.id.end_call_button);
        muteButton = findViewById(R.id.mute_button);
        muteLabel = findViewById(R.id.mute_label);
        speakerButton = findViewById(R.id.speaker_button);
        speakerLabel = findViewById(R.id.speaker_label);
        minimizeButton = findViewById(R.id.minimize_button);

        // Установка заголовка
        // Главное на экране — собеседник, а не фраза про звонок
        callTitle.setText(recipientName != null ? recipientName : "Unknown");
        showPartner();
        setupCallGuard();

        // Инициализация таймера
        timerHandler = new Handler(Looper.getMainLooper());
        callStartTime = System.currentTimeMillis();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long duration = System.currentTimeMillis() - callStartTime;
                int minutes = (int) (duration / 1000) / 60;
                int seconds = (int) (duration / 1000) % 60;
                if (callConnected) {
                    callTimer.setText(String.format("%02d:%02d", minutes, seconds));
                }
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);

        // Прежде тут шли отладочные строки «Start:» и «Current:» и график
        // уровня звука: человеку во время разговора они ничего не давали

        registerNotificationActions();
        updateCallNotification();

        muteButton.setOnClickListener(v -> toggleMic());
        speakerButton.setOnClickListener(v -> toggleSpeaker());
        findViewById(R.id.camera_button).setOnClickListener(v -> toggleCamera());
        minimizeButton.setOnClickListener(v -> minimizeCall());

        com.example.unlimitedpossibilities12.messenger.notife.OngoingCall
                .start(VoiceCallActivity.class, recipientName);

        // Анимация кнопки
        endCallButton.setOnClickListener(v -> {
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction(() -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                endCall();
            }).start();
        });

        // Настройка звука
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
        Log.d(TAG, "VOICE_CALL volume set to max: " + maxVolume);

        // Динамик сразу включён — кнопка должна показывать это с первой секунды
        speakerButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE2E8F0));
        speakerButton.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF0F172A));

        // Настройка рингтона
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

        // Привязка к сервису
        Intent serviceIntent = new Intent(this, CallService.class);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);

        initializeWebRTC();
        if (isCaller) {
            // Пока собеседник не ответил, честнее «звоним», чем «соединяем»:
            // соединять ещё нечего, вызов только ушёл
            showStatus(R.string.call_calling);
            Log.d(TAG, "Caller waiting for 'ready' or 'acceptCall' signal...");
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
        eglBase = org.webrtc.EglBase.create();
        peerConnectionFactory = PeerConnectionFactory.builder()
                // Без этих двух разговор остаётся без картинки: завод
                // соединений просто не умеет её сжимать и разбирать
                .setVideoEncoderFactory(new org.webrtc.DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new org.webrtc.DefaultVideoDecoderFactory(
                        eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        if (currentUserId == null) {
            Log.e(TAG, "currentUserId is undefined. Check the Intent extras.");
            finish();
        }
    }

    /** Готовим поверхности для картинки — один раз за разговор. */
    private void подготовитьЭкраны() {
        if (remoteVideoView == null) {
            remoteVideoView = findViewById(R.id.remote_video_view);
            localVideoView = findViewById(R.id.local_video_view);
            remoteVideoView.init(eglBase.getEglBaseContext(), null);
            remoteVideoView.setEnableHardwareScaler(true);
            localVideoView.init(eglBase.getEglBaseContext(), null);
            localVideoView.setEnableHardwareScaler(true);
            localVideoView.setZOrderMediaOverlay(true);
            localVideoView.setMirror(true);
        }
    }

    /** Показываем картинку, когда она есть, и прячем, когда её нет. */
    private void обновитьВидКартинки() {
        boolean есть = isCameraOn || partnerCameraOn;
        View рамкаЛица = findViewById(R.id.avatar_card);
        if (remoteVideoView != null) {
            remoteVideoView.setVisibility(partnerCameraOn ? View.VISIBLE : View.GONE);
            localVideoView.setVisibility(isCameraOn ? View.VISIBLE : View.GONE);
        }
        if (рамкаЛица != null) рамкаЛица.setVisibility(есть ? View.GONE : View.VISIBLE);
    }

    /**
     * Включить и выключить свою камеру посреди разговора: дорожка
     * добавляется к живому соединению, собеседнику уходит новое
     * предложение связи, и разговор при этом не прерывается.
     */
    /**
     * Включить и выключить свою камеру посреди разговора: дорожка
     * добавляется к живому соединению, собеседнику уходит новое
     * предложение связи, и разговор при этом не прерывается.
     */
    private void toggleCamera() {
        if (peerConnection == null) return;
        if (cameraBusy) {
            Log.d(TAG, "Камера занята — нажатие пропускаем");
            return;
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(
                    this, new String[]{android.Manifest.permission.CAMERA}, 42);
            return;
        }
        cameraBusy = true;
        if (!isCameraOn) {
            // Камеру заводим в своём потоке: её открытие занимает секунды,
            // и в главном потоке экран звонка подвисал — система считала
            // его зависшим и закрывала, а разговор обрывался
            подготовитьЭкраны();
            new Thread(this::включитьКамеру).start();
        } else {
            new Thread(this::выключитьКамеру).start();
        }
    }

    /** Открыть камеру и добавить её дорожку к разговору. */
    private void включитьКамеру() {
        try {
            org.webrtc.CameraEnumerator перечень = org.webrtc.Camera2Enumerator.isSupported(this)
                    ? new org.webrtc.Camera2Enumerator(this)
                    : new org.webrtc.Camera1Enumerator(true);
            String имя = null;
            for (String устройство : перечень.getDeviceNames()) {
                if (перечень.isFrontFacing(устройство)) { имя = устройство; break; }
            }
            if (имя == null && перечень.getDeviceNames().length > 0) имя = перечень.getDeviceNames()[0];
            if (имя == null) {
                runOnUiThread(() -> Toast.makeText(this, "No camera found", Toast.LENGTH_SHORT).show());
                return;
            }
            videoCapturer = перечень.createCapturer(имя, null);
            videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
            surfaceHelper = org.webrtc.SurfaceTextureHelper.create(
                    "CaptureThread", eglBase.getEglBaseContext());
            videoCapturer.initialize(surfaceHelper, this, videoSource.getCapturerObserver());
            videoCapturer.startCapture(1280, 720, 30);
            localVideoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource);
            localVideoTrack.setEnabled(true);
            videoSender = peerConnection.addTrack(localVideoTrack, Arrays.asList("stream0"));
            isCameraOn = true;
            sendSignal(createSignalJson("cameraOn", null));
            runOnUiThread(() -> {
                localVideoTrack.addSink(localVideoView);
                обновитьВидКартинки();
            });
            предложитьСвязьЗаново();
        } catch (Exception e) {
            Log.e(TAG, "Камеру включить не вышло: " + e.getMessage());
            isCameraOn = false;
            runOnUiThread(() -> {
                Toast.makeText(this, "Could not turn the camera on", Toast.LENGTH_SHORT).show();
                обновитьВидКартинки();
            });
        } finally {
            cameraBusy = false;
        }
    }

    /** Остановить и освободить захват с камеры — по порядку и до конца. */
    private void остановитьКамеру() {
        cameraBusy = true;
        try {
            if (videoCapturer != null) {
                try { videoCapturer.stopCapture(); } catch (InterruptedException ignored) { }
                videoCapturer.dispose();
                videoCapturer = null;
            }
            if (localVideoTrack != null) { localVideoTrack.dispose(); localVideoTrack = null; }
            if (videoSource != null) { videoSource.dispose(); videoSource = null; }
            if (surfaceHelper != null) { surfaceHelper.dispose(); surfaceHelper = null; }
            videoSender = null;
            isCameraOn = false;
        } catch (Exception e) {
            Log.w(TAG, "Камера гасилась с заминкой: " + e.getMessage());
        } finally {
            cameraBusy = false;
        }
    }

    /** Погасить камеру: тоже не в главном потоке и по порядку. */
    private void выключитьКамеру() {
        try {
            if (videoSender != null) {
                peerConnection.removeTrack(videoSender);
                videoSender = null;
            }
            if (videoCapturer != null) {
                try { videoCapturer.stopCapture(); } catch (InterruptedException ignored) { }
                videoCapturer.dispose();
                videoCapturer = null;
            }
            if (localVideoTrack != null) { localVideoTrack.dispose(); localVideoTrack = null; }
            if (videoSource != null) { videoSource.dispose(); videoSource = null; }
            if (surfaceHelper != null) { surfaceHelper.dispose(); surfaceHelper = null; }
            isCameraOn = false;
            sendSignal(createSignalJson("cameraOff", null));
            runOnUiThread(this::обновитьВидКартинки);
            предложитьСвязьЗаново();
        } catch (Exception e) {
            Log.e(TAG, "Камеру выключить не вышло: " + e.getMessage());
        } finally {
            cameraBusy = false;
        }
    }

    /** Новое предложение связи — иначе дорожка никуда не пойдёт. */
    private void предложитьСвязьЗаново() {
        peerConnection.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription предложение) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onSetSuccess() {
                        try {
                            // Описание передаём целиком: по дороге его подпишут
                            JSONObject сигнал = createSignalJson("offer", предложение);
                            sendSignal(сигнал);
                        } catch (Exception e) {
                            Log.e(TAG, "Предложение не ушло: " + e.getMessage());
                        }
                    }
                    @Override public void onSetFailure(String s) { Log.e(TAG, "setLocalDescription: " + s); }
                    @Override public void onCreateSuccess(SessionDescription s) { }
                    @Override public void onCreateFailure(String s) { }
                }, предложение);
            }
            @Override public void onCreateFailure(String s) { Log.e(TAG, "createOffer: " + s); }
            @Override public void onSetSuccess() { }
            @Override public void onSetFailure(String s) { }
        }, new org.webrtc.MediaConstraints());
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
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                            .setNativeLibraryName("jingle_peerconnection_so")
                            .createInitializationOptions()
            );
            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
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
                    markConnected();
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
                if (mediaStreams.length > 0 && !mediaStreams[0].audioTracks.isEmpty()) {
                    AudioTrack remoteAudioTrack = mediaStreams[0].audioTracks.get(0);
                    remoteAudioTrack.setEnabled(true);
                    Log.d(TAG, "Remote audio track enabled: " + remoteAudioTrack.id());
                }
                org.webrtc.MediaStreamTrack track = receiver.track();
                if (track instanceof org.webrtc.VideoTrack) {
                    org.webrtc.VideoTrack входящая = (org.webrtc.VideoTrack) track;
                    runOnUiThread(() -> {
                        подготовитьЭкраны();
                        входящая.setEnabled(true);
                        входящая.addSink(remoteVideoView);
                        partnerCameraOn = true;
                        обновитьВидКартинки();
                    });
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
                case "moveToGroup": {
                    /*
                     * Нас зовут в общий разговор.
                     *
                     * Молчаливый переход у телефона пока не выходит: общий
                     * экран начинает разговор только по приглашению, а оно
                     * уходит раньше, чем он успевает подписаться на комнату,
                     * — звук не поднимается и экран закрывается сам. Поэтому
                     * здесь мы просто кладём трубку: следом придёт обычное
                     * приглашение, со звоном, и разговор соберётся. В вебе
                     * переход уже молчаливый.
                     */
                    terminationInitiator = true;
                    endCall();
                    break;
                }

                case "cameraOn":
                    partnerCameraOn = true;
                    runOnUiThread(() -> { подготовитьЭкраны(); обновитьВидКартинки(); });
                    break;

                case "cameraOff":
                    partnerCameraOn = false;
                    runOnUiThread(this::обновитьВидКартинки);
                    break;

                case "offer":
                    /*
                     * Предложение посреди разговора — собеседник включил или
                     * выключил камеру.
                     *
                     * Прежде такие предложения принимал только тот, кому
                     * звонили. Звонящий их не разбирал вовсе: собеседник
                     * включал камеру, его предложение оставалось без ответа,
                     * и соединение рвалось за несколько секунд — звонок
                     * завершался сам.
                     */
                    boolean переговорыНаХоду = peerConnection != null
                            && peerConnection.signalingState() == PeerConnection.SignalingState.STABLE
                            && peerConnection.getRemoteDescription() != null;
                    if (!isCaller || переговорыНаХоду) {
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
                        // Не ответили — это не отказ: человека не было у окна
                        boolean noAnswer = "noAnswer".equals(signal.optString("reason", null));
                        Toast.makeText(this,
                                recipientName + (noAnswer ? " did not answer." : " rejected the call."),
                                Toast.LENGTH_LONG).show();
                        saveCall(noAnswer ? "missed" : "rejected");
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
            failOnBadSignature();
            return true;
        }
        return false;
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

    /** Снимок собеседника, а если его нет — буква имени на цветном кружке. */
    /**
     * Подпись собеседника: подтверждаем, что соединение устанавливает именно он.
     * Раньше это жило в отдельном «защищённом звонке», и включать его надо было
     * вручную — то есть как раз тогда, когда о подмене никто не подозревает.
     */
    private com.example.unlimitedpossibilities12.messenger.notife.CallGuard callGuard;

    private void setupCallGuard() {
        callGuard = new com.example.unlimitedpossibilities12.messenger.notife.CallGuard(this, currentUserId);
        // recipientId здесь всегда собеседник — и когда звоним мы, и когда нам
        callGuard.prepare(recipientId, () -> runOnUiThread(this::updateSecurityLine));

        View security = findViewById(R.id.call_security);
        security.setOnClickListener(v -> showFingerprint());
        updateSecurityLine();
    }

    /** Что написано в строке о защите. */
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
                // Новое устройство — обычное дело, тревожить незачем
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
        if (code != null) {
            message.append(code).append("\n").append(digits);
        } else {
            message.append(getString(R.string.call_security_no_code));
        }
        if (state == com.example.unlimitedpossibilities12.messenger.notife.CallGuard.State.ENCRYPTED) {
            message.append("\n\n").append(getString(R.string.call_security_unverified_note));
        }

        androidx.appcompat.app.AlertDialog.Builder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                        .setTitle(R.string.call_security_title)
                        .setMessage(message.toString())
                        .setNegativeButton(android.R.string.ok, null);
        if (code != null) {
            // Сверили вслух — запоминаем, и дальше подмена будет видна
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

    /** Подпись не сошлась — разговор прекращаем, собеседник не тот. */
    private void failOnBadSignature() {
        runOnUiThread(() -> {
            updateSecurityLine();
            callStatus.setText(R.string.call_security_failed);
            new Handler(Looper.getMainLooper()).postDelayed(this::endCall, 2500);
        });
    }

    private void showPartner() {
        String name = recipientName != null && !recipientName.isEmpty() ? recipientName : "?";
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

                        com.bumptech.glide.Glide.with(VoiceCallActivity.this)
                                .load("https://unlimitedpossibilities12.org" + avatar)
                                .circleCrop()
                                .into(callAvatarImage);
                        callAvatarText.setVisibility(android.view.View.GONE);
                    }

                    @Override
                    public void onFailure(retrofit2.Call<com.example.unlimitedpossibilities12.api.User> call, Throwable t) {
                        Log.e(TAG, "Could not load partner avatar: " + t.getMessage());
                    }
                });
    }

    private int colorForName(String name) {
        int[] colors = {0xFF6366F1, 0xFF0D9488, 0xFF8B5CF6, 0xFF0EA5E9, 0xFFDB2777, 0xFF475569};
        int hash = 0;
        for (int i = 0; i < name.length(); i++) hash = hash * 31 + name.charAt(i);
        return colors[Math.abs(hash) % colors.length];
    }

    /** Соединение установлено: вместо «Connecting…» идёт время разговора. */
    private void markConnected() {
        runOnUiThread(() -> {
            if (callConnected || isFinishing() || isDestroyed()) return;
            callConnected = true;
            callStartTime = System.currentTimeMillis();
            com.example.unlimitedpossibilities12.messenger.notife.OngoingCall.connected();
            callStatus.setText(R.string.call_ongoing);
            callTimer.setVisibility(android.view.View.VISIBLE);
        });
    }

    /** Выключить микрофон: раньше этого нельзя было сделать вовсе. */
    private void toggleMic() {
        micMuted = !micMuted;
        if (localAudioTrack != null) localAudioTrack.setEnabled(!micMuted);

        muteButton.setImageResource(micMuted ? R.drawable.ic_mic_off_offline : R.drawable.ic_mic_white);
        muteButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                micMuted ? 0xFFE2E8F0 : 0xFF1E293B));
        muteButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                micMuted ? 0xFF0F172A : 0xFFE2E8F0));
        muteLabel.setText(micMuted ? R.string.call_unmute : R.string.call_mute);
        if (callStatus != null && callConnected) {
            callStatus.setText(micMuted ? R.string.call_muted_note : R.string.call_ongoing);
        }
        updateCallNotification();
        Log.d(TAG, "Microphone " + (micMuted ? "muted" : "unmuted"));
    }

    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        if (audioManager != null) audioManager.setSpeakerphoneOn(speakerOn);

        speakerButton.setImageResource(speakerOn ? R.drawable.ic_volume_up : R.drawable.ic_volume_off_offline);
        speakerButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                speakerOn ? 0xFFE2E8F0 : 0xFF1E293B));
        speakerButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                speakerOn ? 0xFF0F172A : 0xFFE2E8F0));
        Log.d(TAG, "Speaker " + (speakerOn ? "on" : "off"));
    }

    /**
     * Свернуть разговор. Звонок продолжается, а вернуться в него можно по
     * уведомлению — прежде уведомление вело в список чатов.
     */
    private void minimizeCall() {
        updateCallNotification();
        // Разговор продолжается в своей стопке экранов, а она уходит назад —
        // человек оказывается там, откуда звонил. Закрыть экран нельзя: с ним
        // оборвётся и звонок.
        moveTaskToBack(true);
    }

    /**
     * «Назад» сворачивает разговор, а не кладёт трубку.
     *
     * Прежде кнопка закрывала экран, а вместе с ним обрывался и звонок:
     * продолжить разговор можно было, только свернув приложение. Теперь экран
     * остаётся позади, а сверху едет полоска, возвращающая в разговор.
     */
    @Override
    public void onBackPressed() {
        if (isCallEnded) {
            super.onBackPressed();
            return;
        }
        minimizeCall();
    }


    /**
     * Уведомление о звонке — это то же уведомление службы: пока идёт
     * разговор, оно показывает собеседника и кнопки, а не служебную строку.
     * Прежде рядом висели два уведомления, и ни на одном не было кнопок.
     */
    private void updateCallNotification() {
        com.example.unlimitedpossibilities12.messenger.notife.CallService.showOngoingCall(
                this, recipientName, micMuted, VoiceCallActivity.class);
    }

    private void hideOngoingCallNotification() {
        com.example.unlimitedpossibilities12.messenger.notife.CallService.showIdleNotification(this);
    }

    /** Кнопки в уведомлении: «заглушить» и «завершить» без открытия экрана. */
    private final android.content.BroadcastReceiver notificationActions = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            String action = intent.getAction();
            if (com.example.unlimitedpossibilities12.messenger.notife.CallService.ACTION_CALL_MUTE.equals(action)) {
                toggleMic();
            } else if (com.example.unlimitedpossibilities12.messenger.notife.CallService.ACTION_CALL_HANGUP.equals(action)) {
                endCall();
            }
        }
    };

    private void registerNotificationActions() {
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(com.example.unlimitedpossibilities12.messenger.notife.CallService.ACTION_CALL_MUTE);
        filter.addAction(com.example.unlimitedpossibilities12.messenger.notife.CallService.ACTION_CALL_HANGUP);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(notificationActions, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(notificationActions, filter);
        }
    }

    private void endCall() {
        if (isCallEnded) {
            Log.d(TAG, "Call already ended, skipping...");
            return;
        }
        Log.d(TAG, "Ending call...");
        isCallEnded = true;

        // Остановка таймера и времени
        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerHandler = null;
            timerRunnable = null;
            Log.d(TAG, "Timer stopped.");
        }

        // Отправка сигнала завершения
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

        // Отписка от топика
        if (roomSubscription != null && !roomSubscription.isDisposed()) {
            roomSubscription.dispose();
            Log.d(TAG, "Unsubscribed from room topic: /topic/room/" + roomId);
        }

        // Остановка статистики
        if (statsHandler != null && statsRunnable != null) {
            statsHandler.removeCallbacks(statsRunnable);
            statsHandler = null;
            statsRunnable = null;
            Log.d(TAG, "Stats collection task cancelled.");
        }

        // Освобождение ресурсов WebRTC
        /*
         * Камеру гасим раньше всего остального.
         *
         * Прежде разговор заканчивался, а захват с камеры продолжал жить:
         * его не трогали ни здесь, ни при закрытии экрана. Завод
         * соединений при этом освобождался, а вместе с ним и источник
         * видео — и поток захвата, ещё живой, обращался к освобождённой
         * памяти. Падало не сразу: следующий звонок поднимал новый
         * источник, и приложение рушилось в родном коде через пару секунд
         * после включения камеры, унося разговор с собой.
         */
        остановитьКамеру();

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

        // Остановка рингтона
        if (ringtonePlayer != null) {
            if (ringtonePlayer.isPlaying()) {
                ringtonePlayer.stop();
                Log.d(TAG, "Ringtone stopped.");
            }
            ringtonePlayer.release();
            ringtonePlayer = null;
            Log.d(TAG, "Ringtone player released.");
        }

        // Сброс аудио
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
            callData.put("callType", "voice");
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
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Voice call saved to server with status: " + status);
                    hasSavedCall = true;
                } else {
                    Log.e(TAG, "Failed to save call, response code: " + response.code());
                    runOnUiThread(() -> Toast.makeText(this, "Failed to save call", Toast.LENGTH_SHORT).show());
                }
            } catch (IOException e) {
                Log.e(TAG, "Error saving voice call: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "Failed to save call", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Разрешаем поворот экрана при уходе из активности
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    /**
     * Экран звонка теперь единственный в своей стопке, поэтому новый звонок
     * приходит сюда же. Если это другой разговор — пересобираем экран, иначе
     * просто возвращаемся в текущий (так работает полоска сверху).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String newRoom = intent.getStringExtra("roomId");
        if (newRoom != null && !newRoom.equals(roomId)) {
            setIntent(intent);
            recreate();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Уведомление остаётся на весь разговор, но пусть совпадает с экраном
        updateCallNotification();
    }

    @Override
    protected void onDestroy() {
        com.example.unlimitedpossibilities12.messenger.notife.OngoingCall.clear();
        com.example.unlimitedpossibilities12.messenger.notife.CallService.setCallInProgress(false);
        hideOngoingCallNotification();
        try {
            unregisterReceiver(notificationActions);
        } catch (Exception ignored) {
            // приёмника могло и не быть, если экран не успел подняться
        }
        super.onDestroy();
        if (!isCallEnded) {
            endCall();
        }
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }

    /** Надпись под именем, пока разговор не начался. */
    private void showStatus(int textRes) {
        runOnUiThread(() -> {
            android.widget.TextView status = findViewById(R.id.call_status);
            if (status != null) status.setText(textRes);
        });
    }
}