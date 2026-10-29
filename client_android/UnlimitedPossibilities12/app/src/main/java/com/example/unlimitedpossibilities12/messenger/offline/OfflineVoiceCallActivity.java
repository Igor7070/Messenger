package com.example.unlimitedpossibilities12.messenger.offline;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.example.unlimitedpossibilities12.R;

public class OfflineVoiceCallActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceCall";
    public static final String EXTRA_ENDPOINT_ID = "endpoint_id";
    public static final String EXTRA_USER_NAME = "user_name";
    public static final String EXTRA_IS_OUTGOING = "is_outgoing";

    private static final int REQUEST_RECORD_AUDIO = 201;

    private String endpointId;
    private String userName;
    private boolean isOutgoing;

    private NearbyManager nearbyManager;
    private OfflineCallManager callManager;
    private OfflineAvatarStorage avatarStorage;

    private TextView callName;
    private TextView callStatus;
    private TextView callTimer;
    private ImageView callAvatar;
    private ImageButton btnEndCall;
    private ImageButton btnAcceptCall;
    private ImageButton btnMute;
    private ImageButton btnSpeaker;

    private boolean isMuted = false;
    private boolean isSpeakerOn = true;
    private boolean isCallActive = false;
    private boolean isEnding = false;

    private Handler timerHandler;
    private Runnable timerRunnable;
    private long callStartTime;

    private MediaPlayer ringtonePlayer;
    private NearbyManager.OnMessageReceivedListener previousMessageListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_voice_call);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        endpointId = getIntent().getStringExtra(EXTRA_ENDPOINT_ID);
        userName = getIntent().getStringExtra(EXTRA_USER_NAME);
        isOutgoing = getIntent().getBooleanExtra(EXTRA_IS_OUTGOING, true);

        if (endpointId == null) {
            finish();
            return;
        }

        initViews();
        initManagers();
        setupListeners();
        updateUiForCallType();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        } else {
            startCallFlow();
        }
    }

    private void initViews() {
        callName = findViewById(R.id.call_name);
        callStatus = findViewById(R.id.call_status);
        callTimer = findViewById(R.id.call_timer);
        callAvatar = findViewById(R.id.call_avatar);
        btnEndCall = findViewById(R.id.btn_end_call);
        btnAcceptCall = findViewById(R.id.btn_accept_call);
        btnMute = findViewById(R.id.btn_mute);
        btnSpeaker = findViewById(R.id.btn_speaker);

        callName.setText(userName != null ? userName : "Peer");

        avatarStorage = new OfflineAvatarStorage(this);
        Uri avatarUri = avatarStorage.getAvatar(endpointId);
        if (avatarUri != null) {
            Glide.with(this)
                    .load(avatarUri)
                    .circleCrop()
                    .placeholder(R.drawable.default_avatar_offline)
                    .into(callAvatar);
        } else {
            callAvatar.setImageResource(R.drawable.default_avatar_offline);
        }
    }

    private void updateUiForCallType() {
        if (isOutgoing) {
            callStatus.setText("Calling...");
            if (btnAcceptCall != null) {
                btnAcceptCall.setVisibility(View.GONE);
            }
        } else {
            callStatus.setText("Incoming call");
            if (btnAcceptCall != null) {
                btnAcceptCall.setVisibility(View.VISIBLE);
            }
        }
    }

    private void initManagers() {
        nearbyManager = new NearbyManager(this);
        callManager = new OfflineCallManager(this, nearbyManager, endpointId);
        callManager.setListener(new OfflineCallManager.CallListener() {
            @Override
            public void onCallEnded() {
                runOnUiThread(() -> finishCall(false));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(OfflineVoiceCallActivity.this, message, Toast.LENGTH_SHORT).show();
                    finishCall(false);
                });
            }
        });
    }

    private void setupListeners() {
        btnEndCall.setOnClickListener(v -> {
            if (!isCallActive) {
                if (isOutgoing) {
                    callManager.sendEnd();
                } else {
                    callManager.sendReject();
                }
            } else {
                callManager.sendEnd();
            }
            finishCall(false);
        });

        if (btnAcceptCall != null) {
            btnAcceptCall.setOnClickListener(v -> acceptIncomingCall());
        }

        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            callManager.setMuted(isMuted);
            btnMute.setImageResource(isMuted
                    ? R.drawable.ic_mic_off_offline
                    : R.drawable.ic_mic);
        });

        btnSpeaker.setOnClickListener(v -> {
            isSpeakerOn = !isSpeakerOn;
            callManager.setSpeakerphoneOn(isSpeakerOn);
            btnSpeaker.setImageResource(isSpeakerOn
                    ? R.drawable.ic_volume_up
                    : R.drawable.ic_volume_off_offline);
        });
    }

    private void startCallFlow() {
        // Сигналы звонка (ACCEPT / REJECT / END ...)
        // Экран звонка перехватывает приём сообщений на время разговора. Прежнего
        // слушателя (это открытый чат) запоминаем и возвращаем при завершении —
        // иначе после звонка чат переставал получать сообщения до перезахода.
        previousMessageListener = NearbyManager.getGlobalMessageListener();
        NearbyManager.setGlobalMessageListener(new NearbyManager.OnMessageReceivedListener() {
            @Override
            public void onMessageReceived(String fromEndpointId, String text) {
                if (!endpointId.equals(fromEndpointId)) return;
                handleSignal(text);
            }

            @Override
            public void onFileReceived(String fromEndpointId, Uri fileUri) {
                // не используем для звонка
            }

            @Override
            public void onAvatarReceived(String fromEndpointId, Uri avatarUri) {
                // не используем для звонка
            }
        });

        // Аудио-чанки
        NearbyManager.setGlobalAudioChunkListener((fromEndpointId, data) -> {
            if (!endpointId.equals(fromEndpointId)) return;
            if (callManager != null) {
                callManager.onAudioChunkReceived(data);
            }
        });

        if (isOutgoing) {
            callManager.sendInvite();
            playRingtone(true);
        } else {
            playRingtone(false);
        }
    }

    private void acceptIncomingCall() {
        if (isOutgoing || isCallActive || isEnding) return;

        stopRingtone();

        if (btnAcceptCall != null) {
            btnAcceptCall.setVisibility(View.GONE);
        }

        callManager.sendAccept();
        onCallAccepted();
    }

    private void handleSignal(String signal) {
        runOnUiThread(() -> {
            if (isEnding) return;

            switch (signal) {
                case OfflineCallManager.SIGNAL_ACCEPT:
                    if (isOutgoing && !isCallActive) {
                        stopRingtone();
                        onCallAccepted();
                    }
                    break;

                case OfflineCallManager.SIGNAL_REJECT:
                case OfflineCallManager.SIGNAL_BUSY:
                    Toast.makeText(this, "Call declined", Toast.LENGTH_SHORT).show();
                    finishCall(false);
                    break;

                case OfflineCallManager.SIGNAL_END:
                    Toast.makeText(this, "Call ended", Toast.LENGTH_SHORT).show();
                    finishCall(false);
                    break;

                case OfflineCallManager.SIGNAL_INVITE:
                    // Уже открыты как входящий
                    break;
            }
        });
    }

    private void onCallAccepted() {
        if (isCallActive || isEnding) return;

        isCallActive = true;
        callStatus.setText("In call");
        callTimer.setVisibility(View.VISIBLE);

        if (btnAcceptCall != null) {
            btnAcceptCall.setVisibility(View.GONE);
        }

        startTimer();
        callManager.startAudio();
    }

    private void startTimer() {
        callStartTime = System.currentTimeMillis();
        timerHandler = new Handler(Looper.getMainLooper());
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isEnding) return;
                long duration = System.currentTimeMillis() - callStartTime;
                int min = (int) (duration / 1000) / 60;
                int sec = (int) (duration / 1000) % 60;
                callTimer.setText(String.format("%02d:%02d", min, sec));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void playRingtone(boolean outgoing) {
        try {
            stopRingtone();

            // Та же мелодия, что и в обычных звонках мессенджера, — чтобы вызов
            // был слышен и узнаваем, а не зависел от системного рингтона
            ringtonePlayer = new MediaPlayer();
            android.content.res.AssetFileDescriptor afd =
                    getResources().openRawResourceFd(com.example.unlimitedpossibilities12.R.raw.melodiya_kovboy);
            try {
                ringtonePlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                afd.close();
            }
            ringtonePlayer.setAudioStreamType(android.media.AudioManager.STREAM_RING);
            ringtonePlayer.setLooping(true);
            ringtonePlayer.prepare();
            ringtonePlayer.start();

            // Входящий вызов сопровождаем вибрацией — телефон может лежать экраном вниз
            if (!outgoing) startVibration();
        } catch (Exception e) {
            Log.e(TAG, "Ringtone error", e);
        }
    }

    private android.os.Vibrator vibrator;

    private void startVibration() {
        try {
            vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = {0, 700, 800};
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Vibration error", e);
        }
    }

    private void stopVibration() {
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Exception ignored) {}
            vibrator = null;
        }
    }

    private void stopRingtone() {
        stopVibration();
        if (ringtonePlayer != null) {
            try {
                if (ringtonePlayer.isPlaying()) {
                    ringtonePlayer.stop();
                }
                ringtonePlayer.release();
            } catch (Exception ignored) {
            }
            ringtonePlayer = null;
        }
    }

    private void finishCall(boolean alreadySentEnd) {
        if (isEnding) return;
        isEnding = true;

        stopRingtone();

        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        if (callManager != null) {
            callManager.release();
        }

        NearbyManager.setGlobalAudioChunkListener(null);
        NearbyManager.setGlobalMessageListener(previousMessageListener); // вернуть чату приём сообщений
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCallFlow();
            } else {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (!isCallActive) {
            if (isOutgoing) {
                callManager.sendEnd();
            } else {
                callManager.sendReject();
            }
        } else {
            callManager.sendEnd();
        }
        finishCall(false);
    }

    @Override
    protected void onDestroy() {
        if (!isEnding) {
            if (callManager != null) {
                callManager.sendEnd();
            }
            finishCall(false);
        }
        super.onDestroy();
    }
}
