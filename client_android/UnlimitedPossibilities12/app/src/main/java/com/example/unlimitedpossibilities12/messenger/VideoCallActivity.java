package com.example.unlimitedpossibilities12.messenger;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
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
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.disposables.Disposable;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Request;
import ua.naiksoftware.stomp.StompClient;

public class VideoCallActivity extends AppCompatActivity {
    private static final String TAG = "VideoCallActivity";
    private StompClient stompClient;
    private String currentUserId;
    private String recipientId;
    private String roomId;
    private boolean isCaller;
    private String callerName;
    private String recipientName;
    private PeerConnection peerConnection;
    private PeerConnectionFactory peerConnectionFactory;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private VideoCapturer videoCapturer;
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
    private EglBase eglBase;
    private SurfaceViewRenderer localVideoView;
    private SurfaceViewRenderer remoteVideoView;
    private AtomicBoolean isVideoTrackReady = new AtomicBoolean(false); // Флаг готовности видеотрека
    private JSONObject pendingOffer = null; // Храним отложенный offer

    // UI элементы
    private TextView callTitle;
    private TextView callTimer;
    // Заглушка на месте видео собеседника: снимок, имя и состояние
    private View noVideoOverlay;
    private ImageView avatarImage;
    private TextView avatarText;
    private TextView partnerNameText;
    private TextView noVideoStatus;
    private View localVideoCard;
    private ImageView localVideoOffIcon;
    private View topBar;
    private View callControls;
    private ImageView muteButton;
    private ImageView cameraButton;
    private ImageView switchCameraButton;
    private ImageView speakerButton;
    private View minimizeButton;
    /** Состояние разговора: что выключено и чьё видео сейчас на весь экран. */
    private boolean micMuted = false;
    private boolean cameraOff = false;
    private boolean speakerOn = true;
    private boolean frontCamera = true;
    private boolean swapped = false;
    private boolean remoteVideoLive = false;
    private boolean callConnected = false;
    private VideoTrack remoteVideoTrack;
    private final Handler hideControlsHandler = new Handler(Looper.getMainLooper());
    private static final String ACTION_PIP_MUTE = "com.example.unlimitedpossibilities12.PIP_MUTE";
    private static final String ACTION_PIP_END = "com.example.unlimitedpossibilities12.PIP_END";
    private Handler timerHandler;
    private Runnable timerRunnable;
    private Handler timeHandler;
    private Runnable timeRunnable;
    private long callStartTime;
    private Handler currentTimeHandler;
    private Runnable currentTimeRunnable;

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
        setContentView(R.layout.activity_video_call);

        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        currentUserId = getIntent().getStringExtra("currentUserId");
        recipientId = getIntent().getStringExtra("recipientId");
        roomId = getIntent().getStringExtra("roomId");
        isCaller = getIntent().getBooleanExtra("isCaller", false);
        callerName = getIntent().getStringExtra("callerName");
        recipientName = getIntent().getStringExtra("recipientName");
        rememberOngoingCall(recipientName != null ? recipientName : callerName);

        Log.d(TAG, "onCreate: currentUserId=" + currentUserId + ", recipientId=" + recipientId + ", roomId=" + roomId + ", isCaller=" + isCaller);

        callTitle = findViewById(R.id.call_title);
        callTimer = findViewById(R.id.call_timer);
        endCallButton = findViewById(R.id.end_call_button);
        localVideoView = findViewById(R.id.local_video_view);
        remoteVideoView = findViewById(R.id.remote_video_view);
        noVideoOverlay = findViewById(R.id.no_video_overlay);
        avatarImage = findViewById(R.id.call_avatar_image);
        avatarText = findViewById(R.id.call_avatar_text);
        partnerNameText = findViewById(R.id.partner_name);
        noVideoStatus = findViewById(R.id.no_video_status);
        localVideoCard = findViewById(R.id.local_video_card);
        localVideoOffIcon = findViewById(R.id.local_video_off_icon);
        topBar = findViewById(R.id.top_bar);
        callControls = findViewById(R.id.call_controls);
        muteButton = findViewById(R.id.mute_button);
        cameraButton = findViewById(R.id.camera_button);
        switchCameraButton = findViewById(R.id.switch_camera_button);
        speakerButton = findViewById(R.id.speaker_button);
        minimizeButton = findViewById(R.id.minimize_button);

        String partner = recipientName != null && !recipientName.isEmpty() ? recipientName : "?";
        callTitle.setText(partner);
        partnerNameText.setText(partner);
        showPartnerAvatar(partner);

        setupCallGuard();

        muteButton.setOnClickListener(v -> toggleMic());
        cameraButton.setOnClickListener(v -> toggleCamera());
        switchCameraButton.setOnClickListener(v -> flipCamera());
        speakerButton.setOnClickListener(v -> toggleSpeaker());
        minimizeButton.setOnClickListener(v -> onBackPressed());
        localVideoCard.setOnClickListener(v -> swapVideos());
        findViewById(R.id.video_call_root).setOnClickListener(v -> toggleControls());

        android.content.IntentFilter pipFilter = new android.content.IntentFilter();
        pipFilter.addAction(ACTION_PIP_MUTE);
        pipFilter.addAction(ACTION_PIP_END);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActions, pipFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(pipActions, pipFilter);
        }

        timerHandler = new Handler(Looper.getMainLooper());
        callStartTime = System.currentTimeMillis();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (callConnected) {
                    long duration = System.currentTimeMillis() - callStartTime;
                    int minutes = (int) (duration / 1000) / 60;
                    int seconds = (int) (duration / 1000) % 60;
                    callTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
                }
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);

        // Часы «Start:»/«Current:» и полоса графика убраны: это была отладка,
        // человеку на звонке она ни о чём не говорила.

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
        speakerButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE2E8F0));
        speakerButton.setColorFilter(0xFF0F172A);
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

        eglBase = EglBase.create();
        localVideoView.init(eglBase.getEglBaseContext(), null);
        remoteVideoView.init(eglBase.getEglBaseContext(), null);
        localVideoView.setZOrderMediaOverlay(true);
        remoteVideoView.setZOrderMediaOverlay(false);

        Intent serviceIntent = new Intent(this, CallService.class);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);

        initializeWebRTC();
        if (isCaller) {
            Log.d(TAG, "Caller waiting for 'ready' or 'acceptCall' signal...");
        } else {
            Log.d(TAG, "Initializing for incoming call");
            initializePeerConnection(false);
            joinCall();
        }
    }

    private void joinCall() {
        Log.d(TAG, "joinCall: Initializing for incoming call");

        String[] permissions = {android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA};
        ArrayList<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), 1);
            return;
        }

        // Создаем локальный медиапоток и добавляем треки
        isVideoTrackReady.set(false); // Сбрасываем флаг
        MediaStream localStream = createLocalMediaStream();
        if (localStream != null) {
            for (AudioTrack audioTrack : localStream.audioTracks) {
                audioTrack.setEnabled(true);
                peerConnection.addTrack(audioTrack, Collections.singletonList("stream0"));
                Log.d(TAG, "Audio track added for incoming call: " + audioTrack.id() + ", enabled: " + audioTrack.enabled());
            }
            for (VideoTrack videoTrack : localStream.videoTracks) {
                videoTrack.setEnabled(true);
                peerConnection.addTrack(videoTrack, Collections.singletonList("stream0"));
                Log.d(TAG, "Video track added for incoming call: " + videoTrack.id() + ", enabled: " + videoTrack.enabled());
                isVideoTrackReady.set(true); // Устанавливаем флаг
            }
        } else {
            Log.w(TAG, "Local stream is null, initializing audio-only");
            MediaConstraints audioConstraints = new MediaConstraints();
            audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
            localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource);
            localAudioTrack.setEnabled(true);
            peerConnection.addTrack(localAudioTrack, Collections.singletonList("stream0"));
            Log.d(TAG, "Audio track added for incoming call: " + localAudioTrack.id() + ", enabled: " + localAudioTrack.enabled());
        }

        // Проверяем, начался ли видеозахват
        if (videoCapturer != null && isVideoTrackReady.get()) {
            Log.d(TAG, "Video capturer initialized, checking pending offer");
            if (pendingOffer != null) {
                Log.d(TAG, "Processing pending offer after video track is ready");
                handlePendingOffer();
            }
        } else if (pendingOffer != null) {
            Log.w(TAG, "Video capturer not ready, waiting for initialization");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (videoCapturer != null && isVideoTrackReady.get()) {
                    Log.d(TAG, "Video capturer ready after delay, processing pending offer");
                    handlePendingOffer();
                } else {
                    Log.e(TAG, "Video capturer still not ready, proceeding with audio-only answer");
                    handlePendingOffer(); // Отвечаем без видео, чтобы не блокировать звонок
                }
            }, 2000); // Ждем 2 секунды
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
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        if (currentUserId == null) {
            Log.e(TAG, "currentUserId is undefined. Check the Intent extras.");
            finish();
        }
    }

    private void checkPermissionsAndStartCall() {
        String[] permissions = {android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA};
        ArrayList<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), 1);
        } else if (!isCallStarted) {
            startCall();
        } else {
            Log.d(TAG, "Call already started, ignoring checkPermissionsAndStartCall");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Log.d(TAG, "All permissions granted");
                if (isCaller && !isCallStarted) {
                    startCall();
                } else if (!isCaller) {
                    joinCall();
                }
            } else {
                Log.e(TAG, "Required permissions not granted, ending call");
                Toast.makeText(this, "Camera and microphone permissions are required", Toast.LENGTH_LONG).show();
                endCall();
            }
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

        MediaStream localStream = createLocalMediaStream();
        if (localStream != null) {
            for (AudioTrack audioTrack : localStream.audioTracks) {
                audioTrack.setEnabled(true);
                peerConnection.addTrack(audioTrack, Collections.singletonList("stream0"));
                Log.d(TAG, "Audio track added: " + audioTrack.id() + ", enabled: " + audioTrack.enabled());
            }
            for (VideoTrack videoTrack : localStream.videoTracks) {
                videoTrack.setEnabled(true);
                peerConnection.addTrack(videoTrack, Collections.singletonList("stream0"));
                Log.d(TAG, "Video track added: " + videoTrack.id() + ", enabled: " + videoTrack.enabled());
            }
        } else {
            Log.w(TAG, "Local stream is null, proceeding with audio only");
            MediaConstraints audioConstraints = new MediaConstraints();
            audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
            localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource);
            localAudioTrack.setEnabled(true);
            peerConnection.addTrack(localAudioTrack, Collections.singletonList("stream0"));
            Log.d(TAG, "Audio track added: " + localAudioTrack.id() + ", enabled: " + localAudioTrack.enabled());
        }

        MediaConstraints sdpConstraints = new MediaConstraints();
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Log.d(TAG, "Created SDP offer: " + sdp.description);
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "Local description set: " + sdp.description);
                        sendSignal(createSignalJson("offer", sdp));
                    }

                    @Override
                    public void onSetFailure(String error) {
                        Log.e(TAG, "Failed to set local description: " + error);
                    }

                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}

                    @Override
                    public void onCreateFailure(String error) {}
                }, sdp);
            }

            @Override
            public void onCreateFailure(String error) {
                Log.e(TAG, "Failed to create offer: " + error);
                endCall();
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onSetFailure(String error) {}
        }, sdpConstraints);

        statsHandler = new Handler(Looper.getMainLooper());
        statsRunnable = () -> peerConnection.getStats(statsReport -> {
            for (RTCStats stats : statsReport.getStatsMap().values()) {
                if (stats.getType().equals("outbound-rtp")) {
                    Log.d(TAG, "Outbound RTP: bytesSent=" + stats.getMembers().get("bytesSent"));
                }
            }
            statsHandler.postDelayed(statsRunnable, 5000);
        });
        statsHandler.postDelayed(statsRunnable, 5000);
    }

    private MediaStream createLocalMediaStream() {
        try {
            // Создаем аудиотрек
            MediaConstraints audioConstraints = new MediaConstraints();
            audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
            localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource);
            localAudioTrack.setEnabled(true);
            Log.d(TAG, "Audio track created: " + localAudioTrack.id() + ", enabled: " + localAudioTrack.enabled());

            // Создаем видеотрек
            Camera2Enumerator cameraEnumerator = new Camera2Enumerator(this);
            String cameraId = null;
            for (String deviceName : cameraEnumerator.getDeviceNames()) {
                if (cameraEnumerator.isFrontFacing(deviceName)) {
                    cameraId = deviceName;
                    break;
                }
            }
            VideoTrack videoTrack = null;
            if (cameraId != null) {
                videoCapturer = cameraEnumerator.createCapturer(cameraId, null);
                if (videoCapturer != null) {
                    videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
                    SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
                    videoCapturer.initialize(surfaceTextureHelper, this, videoSource.getCapturerObserver());
                    try {
                        videoCapturer.startCapture(1280, 720, 30);
                        Log.d(TAG, "Video capture started successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start video capture: " + e.getMessage());
                        videoCapturer.dispose();
                        videoCapturer = null;
                        return null;
                    }
                    videoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource);
                    videoTrack.setEnabled(true);
                    localVideoView.setMirror(true);
                    videoTrack.addSink(localVideoView);
                    Log.d(TAG, "Video track created: " + videoTrack.id() + ", enabled: " + videoTrack.enabled());
                } else {
                    Log.w(TAG, "Failed to create video capturer");
                }
            } else {
                Log.w(TAG, "No front-facing camera found");
            }

            MediaStream mediaStream = peerConnectionFactory.createLocalMediaStream("stream0");
            mediaStream.addTrack(localAudioTrack);
            if (videoTrack != null) {
                mediaStream.addTrack(videoTrack);
                localVideoTrack = videoTrack; // Сохраняем ссылку на видеотрек
            }
            return mediaStream;

        } catch (Exception e) {
            Log.e(TAG, "Error creating local media stream: " + e.getMessage());
            return null;
        }
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
                    runOnUiThread(() -> {
                        if (!callConnected) {
                            callConnected = true;
                            callStartTime = System.currentTimeMillis();
                            com.example.unlimitedpossibilities12.messenger.notife.OngoingCall.connected();
                        }
                        updateRemoteVideoState();
                        hideControlsLater();
                    });
                    peerConnection.getStats(statsReport -> {
                        for (RTCStats stats : statsReport.getStatsMap().values()) {
                            if (stats.getType().equals("candidate-pair") && stats.getMembers().get("state").equals("succeeded")) {
                                Log.d(TAG, "Selected ICE pair: " + stats.toString());
                            }
                        }
                    });
                } else if (newState == PeerConnection.IceConnectionState.FAILED || newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    Log.w(TAG, "ICE connection failed or disconnected, ending call...");
                    runOnUiThread(() -> Toast.makeText(VideoCallActivity.this, "Connection failed, ending call", Toast.LENGTH_SHORT).show());
                    endCall();
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
                Log.d(TAG, "Remote track added: " + receiver.id() + ", streams: " + mediaStreams.length);
                if (mediaStreams.length > 0) {
                    if (!mediaStreams[0].audioTracks.isEmpty()) {
                        AudioTrack remoteAudioTrack = mediaStreams[0].audioTracks.get(0);
                        remoteAudioTrack.setEnabled(true);
                        Log.d(TAG, "Remote audio track enabled: " + remoteAudioTrack.id() + ", enabled: " + remoteAudioTrack.enabled());
                    }
                    if (!mediaStreams[0].videoTracks.isEmpty()) {
                        remoteVideoTrack = mediaStreams[0].videoTracks.get(0);
                        remoteVideoTrack.setEnabled(true);
                        remoteVideoTrack.addSink(remoteVideoView);
                        remoteVideoLive = true;
                        runOnUiThread(VideoCallActivity.this::updateRemoteVideoState);
                        Log.d(TAG, "Remote video track enabled and added to remoteVideoView: " + remoteVideoTrack.id() + ", enabled: " + remoteVideoTrack.enabled());
                    } else {
                        // Собеседник без камеры: вместо черноты показываем снимок
                        remoteVideoLive = false;
                        runOnUiThread(VideoCallActivity.this::updateRemoteVideoState);
                        Log.d(TAG, "No remote video track available (expected if React has no camera)");
                    }
                } else {
                    Log.w(TAG, "No media streams received in onAddTrack");
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

            if (type.equals("endCall") && isCallEnded) {
                Log.d(TAG, "Ignoring duplicate endCall signal");
                return;
            }

            switch (type) {
                case "offer":
                    if (!isCaller) {
                        // Убедимся, что PeerConnection инициализирован
                        if (peerConnection == null || peerConnection.signalingState() == PeerConnection.SignalingState.CLOSED) {
                            Log.d(TAG, "PeerConnection is null or closed when handling offer, initializing...");
                            initializePeerConnection(false);
                            joinCall(); // Вызываем joinCall для добавления треков
                        } else {
                            Log.d(TAG, "Reusing existing PeerConnection for offer");
                            // Проверяем, добавлены ли треки
                            if (localVideoTrack == null || !localVideoTrack.enabled()) {
                                Log.w(TAG, "Video track not added or not enabled, reinitializing");
                                joinCall();
                            }
                        }

                        // Проверяем готовность видеотрека
                        if (!isVideoTrackReady.get() || localVideoTrack == null) {
                            Log.w(TAG, "Video track not ready, postponing offer processing");
                            pendingOffer = signalMessage; // Сохраняем offer
                            joinCall(); // Пытаемся повторно инициализировать треки
                            return;
                        }

                        String sdp = signal.getString("sdp");
                        Log.d(TAG, "Received offer SDP: " + sdp);
                        if (checkSignature(sdp, signal)) return;
                        peerConnection.setRemoteDescription(new SdpObserver() {
                            @Override
                            public void onSetSuccess() {
                                Log.d(TAG, "Remote offer set successfully");
                                MediaConstraints sdpConstraints = new MediaConstraints();
                                sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
                                sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
                                peerConnection.createAnswer(new SdpObserver() {
                                    @Override
                                    public void onCreateSuccess(SessionDescription answer) {
                                        Log.d(TAG, "Answer created: " + answer.description);
                                        peerConnection.setLocalDescription(new SdpObserver() {
                                            @Override
                                            public void onSetSuccess() {
                                                Log.d(TAG, "Local answer set successfully");
                                                sendSignal(createSignalJson("answer", answer));
                                                pendingOffer = null; // Очищаем отложенный offer
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
                                        endCall();
                                    }

                                    @Override
                                    public void onSetSuccess() {}

                                    @Override
                                    public void onSetFailure(String error) {}
                                }, sdpConstraints);
                            }

                            @Override
                            public void onSetFailure(String error) {
                                Log.e(TAG, "Failed to set remote offer: " + error);
                                endCall();
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
                        if (checkSignature(sdp, signal)) return;
                        peerConnection.setRemoteDescription(new SdpObserver() {
                            @Override
                            public void onSetSuccess() {
                                Log.d(TAG, "Remote answer set");
                                if (ringtonePlayer != null && ringtonePlayer.isPlaying()) {
                                    ringtonePlayer.pause();
                                    ringtonePlayer.seekTo(0);
                                    Log.d(TAG, "Ringtone paused after answer");
                                }
                            }

                            @Override
                            public void onSetFailure(String error) {
                                Log.e(TAG, "Failed to set remote description: " + error);
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
                        isWaiting = false;
                        if (ringtonePlayer != null && ringtonePlayer.isPlaying()) {
                            ringtonePlayer.pause();
                            ringtonePlayer.seekTo(0);
                            Log.d(TAG, "Ringtone paused after acceptCall");
                        }
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

    private void handlePendingOffer() {
        if (pendingOffer == null) {
            Log.d(TAG, "No pending offer to process");
            return;
        }

        try {
            JSONObject signal = pendingOffer.getJSONObject("signal");
            String sdp = signal.getString("sdp");
            Log.d(TAG, "Processing pending offer SDP: " + sdp);
            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override
                public void onSetSuccess() {
                    Log.d(TAG, "Remote offer set successfully for pending offer");
                    MediaConstraints sdpConstraints = new MediaConstraints();
                    sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
                    sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
                    peerConnection.createAnswer(new SdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription answer) {
                            Log.d(TAG, "Answer created for pending offer: " + answer.description);
                            peerConnection.setLocalDescription(new SdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    Log.d(TAG, "Local answer set successfully for pending offer");
                                    sendSignal(createSignalJson("answer", answer));
                                    pendingOffer = null; // Очищаем отложенный offer
                                }

                                @Override
                                public void onSetFailure(String error) {
                                    Log.e(TAG, "Failed to set local answer for pending offer: " + error);
                                }

                                @Override
                                public void onCreateSuccess(SessionDescription sdp) {}

                                @Override
                                public void onCreateFailure(String error) {}
                            }, answer);
                        }

                        @Override
                        public void onCreateFailure(String error) {
                            Log.e(TAG, "Failed to create answer for pending offer: " + error);
                            endCall();
                        }

                        @Override
                        public void onSetSuccess() {}

                        @Override
                        public void onSetFailure(String error) {}
                    }, sdpConstraints);
                }

                @Override
                public void onSetFailure(String error) {
                    Log.e(TAG, "Failed to set remote offer for pending offer: " + error);
                    endCall();
                }

                @Override
                public void onCreateSuccess(SessionDescription sdp) {}

                @Override
                public void onCreateFailure(String error) {}
            }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
        } catch (JSONException e) {
            Log.e(TAG, "Error processing pending offer: " + e.getMessage());
            endCall();
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

        // Останавливаем таймеры
        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerHandler = null;
            timerRunnable = null;
        }
        hideControlsHandler.removeCallbacksAndMessages(null);
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

        // Отправляем сигнал endCall, если не мы инициировали завершение
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
            }
        }

        // Отписываемся от STOMP
        if (roomSubscription != null && !roomSubscription.isDisposed()) {
            roomSubscription.dispose();
            Log.d(TAG, "Unsubscribed from room topic: /topic/room/" + roomId);
        }

        // Останавливаем статистику
        if (statsHandler != null && statsRunnable != null) {
            statsHandler.removeCallbacks(statsRunnable);
            statsHandler = null;
            statsRunnable = null;
        }

        // Останавливаем захват видео
        if (videoCapturer != null) {
            try {
                Log.d(TAG, "Stopping video capturer...");
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping video capturer: " + e.getMessage());
            }
        }

        // Сохраняем звонок
        saveCall("completed");

        // Закрываем PeerConnection и ждем завершения операций
        if (peerConnection != null) {
            Log.d(TAG, "Closing peer connection...");
            if (peerConnection.signalingState() != PeerConnection.SignalingState.CLOSED) {
                peerConnection.close();
            }
            // Задержка для завершения операций WebRTC
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                cleanupResources();
                finish();
            }, 500); // Задержка 500 мс
        } else {
            cleanupResources();
            finish();
        }
    }

    private void cleanupResources() {
        Log.d(TAG, "Cleaning up resources...");

        // Освобождаем ресурсы WebRTC
        if (videoCapturer != null) {
            videoCapturer.dispose();
            videoCapturer = null;
            Log.d(TAG, "Video capturer disposed");
        }
        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
            Log.d(TAG, "Local video track disposed");
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
            Log.d(TAG, "Video source disposed");
        }
        if (peerConnection != null) {
            peerConnection.dispose();
            peerConnection = null;
            Log.d(TAG, "Peer connection disposed");
        }
        if (localAudioTrack != null) {
            localAudioTrack.dispose();
            localAudioTrack = null;
            Log.d(TAG, "Local audio track disposed");
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
            Log.d(TAG, "Audio source disposed");
        }
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
            Log.d(TAG, "Peer connection factory disposed");
        }

        // Освобождаем видео-рендереры
        localVideoView.release();
        remoteVideoView.release();
        Log.d(TAG, "Video views released");
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
            Log.d(TAG, "EGL base released");
        }

        // Останавливаем рингтон
        if (ringtonePlayer != null) {
            if (ringtonePlayer.isPlaying()) {
                ringtonePlayer.stop();
            }
            ringtonePlayer.release();
            ringtonePlayer = null;
            Log.d(TAG, "Ringtone player released");
        }

        // Восстанавливаем настройки аудио
        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.setSpeakerphoneOn(false);
        Log.d(TAG, "Audio manager settings restored");
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
            callData.put("callType", "video");
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
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Video call saved to server with status: " + status);
                    hasSavedCall = true;
                } else {
                    Log.e(TAG, "Failed to save call, response code: " + response.code());
                    runOnUiThread(() -> Toast.makeText(this, "Failed to save call", Toast.LENGTH_SHORT).show());
                }
            } catch (IOException e) {
                Log.e(TAG, "Error saving video call: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "Failed to save call", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        // Свёрнутый разговор оставлял свои поверхности на экране: список чатов
        // под ним оказывался закрыт чёрным. Убираем их, пока экран не на виду.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && isInPictureInPictureMode()) {
            return; // в окошке собеседника видно, поверхности нужны
        }
        localVideoView.setVisibility(View.GONE);
        remoteVideoView.setVisibility(View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        remoteVideoView.setVisibility(View.VISIBLE);
        localVideoView.setVisibility(cameraOff ? View.INVISIBLE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(pipActions);
        } catch (Exception ignored) {
            // приёмника могло и не быть, если экран не успел подняться
        }
        com.example.unlimitedpossibilities12.messenger.notife.OngoingCall.clear();
        com.example.unlimitedpossibilities12.messenger.notife.CallService.setCallInProgress(false);
        super.onDestroy();
        if (!isCallEnded) {
            endCall();
        }
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }

    /** Запомнить разговор и экран возврата — для полоски поверх приложения. */
    private void rememberOngoingCall(String partner) {
        com.example.unlimitedpossibilities12.messenger.notife.OngoingCall
                .start(VideoCallActivity.class, partner);
    }

    /**
     * «Назад» сворачивает разговор, а не кладёт трубку: собеседник уезжает в
     * окошко поверх других приложений, а если телефон так не умеет — разговор
     * просто остаётся жить в своей стопке экранов, и вернуться в него можно по
     * зелёной полоске.
     */
    @Override
    public void onBackPressed() {
        if (!com.example.unlimitedpossibilities12.messenger.notife.OngoingCall.isActive()) {
            super.onBackPressed();
            return;
        }
        if (enterPipIfPossible()) return;
        moveTaskToBack(true);
    }

    /** Ушли на домашний экран во время разговора — тоже сжимаемся в окошко. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!isCallEnded && com.example.unlimitedpossibilities12.messenger.notife.OngoingCall.isActive()) {
            enterPipIfPossible();
        }
    }

    /** Окошко поверх других приложений. false — телефон так не умеет. */
    private boolean enterPipIfPossible() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return false;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false;
        try {
            return enterPictureInPictureMode(pipParams());
        } catch (Exception e) {
            Log.w(TAG, "Не удалось свернуть в окошко: " + e.getMessage());
            return false;
        }
    }

    /** Размер окошка и кнопки под ним: заглушить и положить трубку. */
    private android.app.PictureInPictureParams pipParams() {
        android.app.PictureInPictureParams.Builder builder =
                new android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(new android.util.Rational(9, 16));
        builder.setActions(java.util.Arrays.asList(
                pipAction(micMuted ? R.drawable.ic_mic_off_offline : R.drawable.ic_mic_white,
                        getString(R.string.call_mute), ACTION_PIP_MUTE, 1),
                pipAction(R.drawable.ic_call_end_down,
                        getString(R.string.call_end), ACTION_PIP_END, 2)));
        return builder.build();
    }

    private android.app.RemoteAction pipAction(int icon, String title, String action, int requestCode) {
        Intent intent = new Intent(action).setPackage(getPackageName());
        PendingIntent pending = PendingIntent.getBroadcast(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(this, icon), title, title, pending);
    }

    /**
     * В окошке остаётся только собеседник: заголовок, кнопки и своё видео в нём
     * не поместятся, а управление даёт сама система.
     */
    @Override
    public void onPictureInPictureModeChanged(boolean inPip, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        com.example.unlimitedpossibilities12.messenger.notife.OngoingCall.setWindowed(inPip);
        if (inPip) {
            hideControls();
            localVideoCard.setVisibility(View.GONE);
            // В окошке крупное имя и подпись не помещаются — остаётся снимок
            partnerNameText.setVisibility(View.GONE);
            noVideoStatus.setVisibility(View.GONE);
        } else {
            localVideoCard.setVisibility(View.VISIBLE);
            partnerNameText.setVisibility(View.VISIBLE);
            noVideoStatus.setVisibility(View.VISIBLE);
            showControls();
        }
    }

    /** Кнопки окошка приходят сюда. */
    private final android.content.BroadcastReceiver pipActions = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PIP_MUTE.equals(intent.getAction())) {
                toggleMic();
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    setPictureInPictureParams(pipParams());
                }
            } else if (ACTION_PIP_END.equals(intent.getAction())) {
                endCall();
            }
        }
    };

    // ---------- управление разговором ----------

    /** Снимок собеседника для заглушки: если его нет — буква имени. */
    private void showPartnerAvatar(String name) {
        avatarText.setText(name.substring(0, 1).toUpperCase(Locale.getDefault()));
        avatarText.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colorForName(name)));
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
                        com.bumptech.glide.Glide.with(VideoCallActivity.this)
                                .load("https://unlimitedpossibilities12.org" + avatar)
                                .circleCrop()
                                .into(avatarImage);
                        avatarText.setVisibility(View.GONE);
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

    /** Есть ли что показывать на большом экране, и что писать, если нет. */
    private void updateRemoteVideoState() {
        boolean bigScreenHasVideo = swapped ? !cameraOff : remoteVideoLive;
        noVideoOverlay.setVisibility(bigScreenHasVideo ? View.GONE : View.VISIBLE);
        if (!bigScreenHasVideo) {
            noVideoStatus.setText(!callConnected ? R.string.call_connecting
                    : swapped ? R.string.call_camera_off : R.string.call_no_video);
        }
        if (!callConnected) callTimer.setText(R.string.call_connecting);
    }

    /** Микрофон. Звук перестаёт уходить собеседнику, разговор продолжается. */
    private void toggleMic() {
        micMuted = !micMuted;
        if (localAudioTrack != null) localAudioTrack.setEnabled(!micMuted);
        muteButton.setImageResource(micMuted ? R.drawable.ic_mic_off_offline : R.drawable.ic_mic_white);
        paintButton(muteButton, micMuted);
        showControls();
    }

    /** Камера. Собеседник видит вместо картинки наш снимок — как и мы в ответ. */
    private void toggleCamera() {
        cameraOff = !cameraOff;
        if (localVideoTrack != null) localVideoTrack.setEnabled(!cameraOff);
        cameraButton.setImageResource(cameraOff ? R.drawable.ic_videocam_off : R.drawable.ic_video_call);
        paintButton(cameraButton, cameraOff);
        localVideoView.setVisibility(cameraOff ? View.INVISIBLE : View.VISIBLE);
        localVideoOffIcon.setVisibility(cameraOff ? View.VISIBLE : View.GONE);
        updateRemoteVideoState();
        showControls();
    }

    /** Передняя камера или основная. */
    private void flipCamera() {
        if (!(videoCapturer instanceof org.webrtc.CameraVideoCapturer)) return;
        ((org.webrtc.CameraVideoCapturer) videoCapturer).switchCamera(
                new org.webrtc.CameraVideoCapturer.CameraSwitchHandler() {
                    @Override
                    public void onCameraSwitchDone(boolean isFrontCamera) {
                        frontCamera = isFrontCamera;
                        runOnUiThread(() -> {
                            // Зеркалим только своё лицо с передней камеры
                            localVideoView.setMirror(!swapped && isFrontCamera);
                            remoteVideoView.setMirror(swapped && isFrontCamera);
                        });
                    }

                    @Override
                    public void onCameraSwitchError(String error) {
                        Log.w(TAG, "Не удалось переключить камеру: " + error);
                    }
                });
        showControls();
    }

    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        if (audioManager != null) audioManager.setSpeakerphoneOn(speakerOn);
        paintButton(speakerButton, !speakerOn);
        speakerButton.setImageResource(speakerOn ? R.drawable.ic_volume_up : R.drawable.ic_volume_off_offline);
        showControls();
    }

    /** Нажатая кнопка — светлая, обычная — тёмная. */
    private void paintButton(ImageView button, boolean active) {
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(active ? 0xFFE2E8F0 : 0xFF1E293B));
        button.setColorFilter(active ? 0xFF0F172A : 0xFFE2E8F0);
    }

    /** Поменять местами себя и собеседника. */
    private void swapVideos() {
        if (remoteVideoTrack == null || localVideoTrack == null) return;
        remoteVideoTrack.removeSink(swapped ? localVideoView : remoteVideoView);
        localVideoTrack.removeSink(swapped ? remoteVideoView : localVideoView);
        swapped = !swapped;
        remoteVideoTrack.addSink(swapped ? localVideoView : remoteVideoView);
        localVideoTrack.addSink(swapped ? remoteVideoView : localVideoView);
        localVideoView.setMirror(!swapped && frontCamera);
        remoteVideoView.setMirror(swapped && frontCamera);
        updateRemoteVideoState();
        showControls();
    }

    // ---------- панели ----------

    /** Панели уезжают, чтобы не закрывать картинку, и возвращаются по касанию. */
    private void toggleControls() {
        if (callControls.getVisibility() == View.VISIBLE) {
            hideControls();
        } else {
            showControls();
        }
    }

    private void showControls() {
        topBar.setVisibility(View.VISIBLE);
        callControls.setVisibility(View.VISIBLE);
        hideControlsLater();
    }

    private void hideControls() {
        hideControlsHandler.removeCallbacksAndMessages(null);
        topBar.setVisibility(View.GONE);
        callControls.setVisibility(View.GONE);
    }

    private void hideControlsLater() {
        hideControlsHandler.removeCallbacksAndMessages(null);
        if (!callConnected) return; // пока идёт соединение, пусть всё будет видно
        hideControlsHandler.postDelayed(this::hideControls, 5000);
    }

    // ---------- подтверждение собеседника ----------

    /**
     * Подпись описания соединения. Звук и видео шифрует сам WebRTC; подпись
     * отвечает на другой вопрос — тот ли человек по ту сторону.
     */
    private com.example.unlimitedpossibilities12.messenger.notife.CallGuard callGuard;

    private void setupCallGuard() {
        callGuard = new com.example.unlimitedpossibilities12.messenger.notife.CallGuard(this, currentUserId);
        // recipientId здесь всегда собеседник — и когда звоним мы, и когда нам
        callGuard.prepare(recipientId, () -> runOnUiThread(this::updateSecurityLine));
        findViewById(R.id.call_security).setOnClickListener(v -> showFingerprint());
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
        showControls();
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
                callTimer.setText(R.string.call_security_failed);
                new Handler(Looper.getMainLooper()).postDelayed(this::endCall, 2500);
            });
            return true;
        }
        return false;
    }
}
