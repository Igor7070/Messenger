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
import android.widget.Button;
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
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
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
    private Disposable roomSubscription; // Добавлен Disposable для управления подпиской

    //переменные для управления таймером, временем начала и данными графика
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
        startTime = findViewById(R.id.start_time);
        currentTime = findViewById(R.id.current_time);
        waveformView = findViewById(R.id.waveform_view);
        endCallButton = findViewById(R.id.end_call_button);

        // Установка заголовка
        callTitle.setText("Voice communication with " + recipientName);

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

        // Handler для текущего времени (каждую секунду)
        currentTimeHandler = new Handler(Looper.getMainLooper());
        currentTimeRunnable = new Runnable() {
            @Override
            public void run() {
                currentTime.setText("Current: " + sdf.format(new Date()));
                currentTimeHandler.postDelayed(this, 1000); // Каждую секунду
            }
        };
        currentTimeHandler.post(currentTimeRunnable);

        // Handler для времени начала и графика (каждые 5 секунд)
        timeRunnable = new Runnable() {
            @Override
            public void run() {
                if (startTime.getText().toString().startsWith("Start: --")) {
                    startTime.setText("Start: " + sdf.format(new Date(callStartTime)));
                }
                waveformView.updateAmplitude(); // Обновляем график
                timeHandler.postDelayed(this, 5000); // Каждые 5 секунд
            }
        };
        timeHandler.post(timeRunnable);

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

        // Остановка таймера и времени
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
    }
}