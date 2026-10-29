package com.example.unlimitedpossibilities12.messenger.offline;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.nearby.connection.Payload;

import java.util.concurrent.atomic.AtomicBoolean;

public class OfflineCallManager {

    private static final String TAG = "OfflineCallManager";

    // Сигналы
    public static final String SIGNAL_INVITE = "___CALL_INVITE___";
    public static final String SIGNAL_ACCEPT = "___CALL_ACCEPT___";
    public static final String SIGNAL_REJECT = "___CALL_REJECT___";
    public static final String SIGNAL_END    = "___CALL_END___";
    public static final String SIGNAL_BUSY   = "___CALL_BUSY___";

    // Маркер аудио-чанка (первый байт payload)
    public static final byte AUDIO_MARKER = (byte) 0xA1;

    // 8 kHz — достаточно для голоса и легче для Nearby
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_IN  = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final Context context;
    private final NearbyManager nearbyManager;
    private final String endpointId;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AudioManager audioManager;

    private Thread recordThread;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isMuted = new AtomicBoolean(false);

    private CallListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface CallListener {
        void onCallEnded();
        void onError(String message);
    }

    public OfflineCallManager(Context context, NearbyManager nearbyManager, String endpointId) {
        this.context = context.getApplicationContext();
        this.nearbyManager = nearbyManager;
        this.endpointId = endpointId;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setListener(CallListener listener) {
        this.listener = listener;
    }

    // ====================== СИГНАЛИНГ ======================

    public void sendInvite() {
        nearbyManager.sendMessage(endpointId, SIGNAL_INVITE);
    }

    public void sendAccept() {
        nearbyManager.sendMessage(endpointId, SIGNAL_ACCEPT);
    }

    public void sendReject() {
        nearbyManager.sendMessage(endpointId, SIGNAL_REJECT);
    }

    public void sendEnd() {
        nearbyManager.sendMessage(endpointId, SIGNAL_END);
    }

    public void sendBusy() {
        nearbyManager.sendMessage(endpointId, SIGNAL_BUSY);
    }

    // ====================== АУДИО ======================

    public void startAudio() {
        if (isRunning.get()) return;
        isRunning.set(true);

        try {
            setupAudioMode();
            startPlaying();
            startRecordingAndSending();
            Log.d(TAG, "Audio started (BYTES chunks)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio", e);
            stopAudio();
            if (listener != null) {
                mainHandler.post(() -> listener.onError("Failed to start audio"));
            }
        }
    }

    private void setupAudioMode() {
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0);
    }

    private void startRecordingAndSending() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT);
        if (minBuf <= 0) {
            minBuf = SAMPLE_RATE; // fallback
        }

        // ~40–80 ms кадры
        final int bufferSize = Math.max(minBuf, 640);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT,
                bufferSize * 2
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioRecord init failed");
        }

        audioRecord.startRecording();

        recordThread = new Thread(() -> {
            byte[] pcm = new byte[bufferSize];
            while (isRunning.get()) {
                int read = audioRecord.read(pcm, 0, pcm.length);
                if (read > 0 && !isMuted.get()) {
                    byte[] packet = new byte[read + 1];
                    packet[0] = AUDIO_MARKER;
                    System.arraycopy(pcm, 0, packet, 1, read);

                    try {
                        Payload payload = Payload.fromBytes(packet);
                        nearbyManager.sendFile(endpointId, payload);
                    } catch (Exception e) {
                        Log.e(TAG, "Send audio chunk failed", e);
                    }
                }
            }
        }, "CallRecordThread");
        recordThread.start();
    }

    private void startPlaying() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT);
        if (minBuf <= 0) {
            minBuf = SAMPLE_RATE;
        }

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .setEncoding(AUDIO_FORMAT)
                        .build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        audioTrack.play();
    }

    /**
     * Вызывать при получении BYTES с маркером AUDIO_MARKER
     */
    public void onAudioChunkReceived(byte[] data) {
        if (!isRunning.get() || audioTrack == null || data == null || data.length < 2) {
            return;
        }
        if (data[0] != AUDIO_MARKER) {
            return;
        }

        try {
            if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.write(data, 1, data.length - 1);
            }
        } catch (Exception e) {
            Log.e(TAG, "Play chunk error", e);
        }
    }

    public void setMuted(boolean muted) {
        isMuted.set(muted);
    }

    public boolean isMuted() {
        return isMuted.get();
    }

    public void setSpeakerphoneOn(boolean on) {
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(on);
        }
    }

    public void stopAudio() {
        isRunning.set(false);

        if (recordThread != null) {
            try {
                recordThread.join(400);
            } catch (InterruptedException ignored) {
            }
            recordThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }

        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }

        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(false);
        }

        Log.d(TAG, "Audio stopped");
    }

    public void release() {
        stopAudio();
        listener = null;
    }
}
