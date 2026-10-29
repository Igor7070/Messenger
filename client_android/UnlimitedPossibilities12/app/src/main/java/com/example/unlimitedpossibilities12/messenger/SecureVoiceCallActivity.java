package com.example.unlimitedpossibilities12.messenger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.messenger.notife.CallService;
import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import com.goterl.lazysodium.interfaces.Sign;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.*;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import io.reactivex.disposables.Disposable;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import ua.naiksoftware.stomp.StompClient;

public class SecureVoiceCallActivity extends AppCompatActivity {

    private static final String TAG = "SecureVoiceCall";

    private StompClient stompClient;
    private Disposable roomSubscription;

    private String currentUserId, recipientId, roomId, callerName, recipientName;
    private boolean isCaller;

    private PeerConnection peerConnection;
    private PeerConnectionFactory factory;
    private AudioTrack localAudioTrack;
    private AudioManager audioManager;
    private MediaPlayer ringtonePlayer;

    private TextView secureStatusText;
    private Button endCallButton;
    private CallService callService;
    private boolean isBound = false;
    private boolean isCallEnded = false;
    private boolean hasSavedCall = false;
    private boolean terminationInitiator = false;
    private boolean readySent = false;
    private TextView callTimer;
    private CallWaveformView waveformView;

    private Handler timerHandler;
    private Runnable timerRunnable;
    private Handler waveformHandler;
    private Runnable waveformRunnable;
    private long callStartTime;

    // === Правильная инициализация LazySodium (без ambiguous) ===
    private final LazySodiumAndroid ls = new LazySodiumAndroid(new SodiumAndroid());

    private byte[] mySignPublicKey;
    private byte[] mySignPrivateKey;
    private byte[] partnerSignPublicKey;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            callService = ((CallService.LocalBinder) service).getService();
            isBound = true;
            stompClient = callService.getStompClient();
            if (stompClient != null && stompClient.isConnected()) {
                subscribeToRoom();
                loadOrGenerateSignKeys();
            } else {
                finishWithError("No connection");
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { isBound = false; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.example.unlimitedpossibilities12.messenger.notife.CallService.setCallInProgress(true); // идёт звонок — соединение держим
        setContentView(R.layout.activity_secure_voice_call);

        secureStatusText = findViewById(R.id.secure_status);
        secureStatusText.setText("Encryption verification...");
        secureStatusText.setTextColor(0xFFFFAA00);

        endCallButton = findViewById(R.id.end_call_button);
        TextView title = findViewById(R.id.call_title);
        title.setText("Secure call with " + getIntent().getStringExtra("recipientName"));

        currentUserId = getIntent().getStringExtra("currentUserId");
        recipientId = getIntent().getStringExtra("recipientId");
        roomId = getIntent().getStringExtra("roomId");
        isCaller = getIntent().getBooleanExtra("isCaller", false);
        callerName = getIntent().getStringExtra("callerName");
        recipientName = getIntent().getStringExtra("recipientName");

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);

        endCallButton.setOnClickListener(v -> endCall());

        // === Таймер и график (добавлено для SecureVoiceCallActivity) ===
        callTimer = findViewById(R.id.call_timer);
        waveformView = findViewById(R.id.waveform_view);

        // Таймер
        timerHandler = new Handler(Looper.getMainLooper());
        callStartTime = System.currentTimeMillis();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long duration = System.currentTimeMillis() - callStartTime;
                int minutes = (int) (duration / 1000) / 60;
                int seconds = (int) (duration / 1000) % 60;
                if (callTimer != null) {
                    callTimer.setText(String.format("%02d:%02d", minutes, seconds));
                }
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);

        // Обновление графика
        waveformHandler = new Handler(Looper.getMainLooper());
        waveformRunnable = new Runnable() {
            @Override
            public void run() {
                if (waveformView != null) {
                    waveformView.updateAmplitude();
                }
                waveformHandler.postDelayed(this, 1000);
            }
        };
        waveformHandler.post(waveformRunnable);

        setupRingtone();
        bindService(new Intent(this, CallService.class), connection, Context.BIND_AUTO_CREATE);
        initializeWebRTC();
    }

    private void loadOrGenerateSignKeys() {
        new Thread(() -> {
            try {
                String prefKey = "secure_sign_" + currentUserId;
                String stored = getSharedPreferences("secure_call", MODE_PRIVATE).getString(prefKey, null);

                if (stored == null) {
                    Log.d(TAG, "Генерируем ключи подписи Ed25519");
                    mySignPublicKey = new byte[Sign.PUBLICKEYBYTES];
                    mySignPrivateKey = new byte[Sign.SECRETKEYBYTES];
                    ls.cryptoSignKeypair(mySignPublicKey, mySignPrivateKey);

                    // КАК В СЕКРЕТНОМ ЧАТЕ — URL_SAFE + NO_WRAP + УБИРАЕМ =
                    String pkB64 = Base64.encodeToString(mySignPublicKey, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
                    String skB64 = Base64.encodeToString(mySignPrivateKey, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");

                    getSharedPreferences("secure_call", MODE_PRIVATE).edit()
                            .putString(prefKey, pkB64 + "|" + skB64)
                            .apply();
                    syncPublicKeyToServer(pkB64);
                } else {
                    String[] parts = stored.split("\\|", 2);
                    // Оба ключа записаны URL_SAFE-алфавитом, поэтому и читать их
                    // нужно с URL_SAFE. Без этого флага декодер спотыкается
                    // о символы - и _ и бросает исключение.
                    mySignPublicKey = Base64.decode(parts[0], Base64.URL_SAFE | Base64.NO_WRAP);
                    mySignPrivateKey = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP);

                    // Если в хранилище лежит мусор — не падаем, а перевыпускаем пару.
                    if (mySignPublicKey.length != Sign.PUBLICKEYBYTES
                            || mySignPrivateKey.length != Sign.SECRETKEYBYTES) {
                        Log.w(TAG, "Сохранённые ключи повреждены (pub=" + mySignPublicKey.length
                                + ", sec=" + mySignPrivateKey.length + "), генерируем новые");
                        getSharedPreferences("secure_call", MODE_PRIVATE).edit().remove(prefKey).apply();
                        loadOrGenerateSignKeys();
                        return;
                    }

                    // При загрузке тоже убираем =, если вдруг есть
                    String pkB64 = parts[0].replace("=", "");
                    syncPublicKeyToServer(pkB64);
                }

                runOnUiThread(this::fetchPartnerPublicKey);
            } catch (Exception e) {
                Log.e(TAG, "Не удалось загрузить или сгенерировать ключи подписи", e);
                runOnUiThread(() -> finishWithError("Key error"));
            }
        }).start();
    }

    private void syncPublicKeyToServer(String publicKeyB64) {
        new Thread(() -> {
            try {
                OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
                Request request = new Request.Builder()
                        .url("https://unlimitedpossibilities12.org/api/sign_keys")
                        .post(RequestBody.create(
                                "{\"userId\":\"" + currentUserId + "\",\"publicKey\":\"" + publicKeyB64 + "\"}",
                                MediaType.parse("application/json")
                        ))
                        .build();
                client.newCall(request).execute();
            } catch (Exception ignored) {}
        }).start();
    }

    private void fetchPartnerPublicKey() {
        new Thread(() -> {
            try {
                String targetId = isCaller ? recipientId : getIntent().getStringExtra("callerId");
                if (targetId == null) targetId = recipientId;

                OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
                Request request = new Request.Builder()
                        .url("https://unlimitedpossibilities12.org/api/sign_keys/" + targetId)
                        .build();
                Response response = client.newCall(request).execute();

                if (response.isSuccessful() && response.body() != null) {
                    JSONObject json = new JSONObject(response.body().string());
                    partnerSignPublicKey = Base64.decode(json.getString("publicKey"), Base64.URL_SAFE | Base64.NO_WRAP);

                    Log.d(TAG, "Partner public key length: " + partnerSignPublicKey.length); // ДОЛЖНО БЫТЬ 32!

                    runOnUiThread(() -> {
                        secureStatusText.setText("Ready to call");
                        if (isCaller && ringtonePlayer != null) {
                            ringtonePlayer.start();
                        }
                        // Отправляем ready ТОЛЬКО после полной готовности STOMP
                        handler.postDelayed(() -> {
                            if (!isCaller) {
                                handleIncomingSecureCall();
                            }
                        }, 800); // даём время на подписку
                    });
                } else {
                    finishWithError("Partner key not found");
                }
            } catch (Exception e) {
                finishWithError("Network error");
            }
        }).start();
    }

    private void subscribeToRoom() {
        roomSubscription = stompClient.topic("/topic/room/" + roomId)
                .subscribe(message -> {
                    try {
                        JSONObject json = new JSONObject(message.getPayload());
                        if (json.getString("from").equals(currentUserId)) return;
                        handleSignal(json.getJSONObject("signal"));
                    } catch (Exception e) {
                        Log.e(TAG, "Signal error", e);
                    }
                }, err -> Log.e(TAG, "STOMP error", err));
    }

    private void initializeWebRTC() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions());
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory();

        AudioSource audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);
    }

    private void createPeerConnection() {
        if (peerConnection != null) {
            peerConnection.close();
        }

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Arrays.asList(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("turn:13.49.238.121:3478?transport=tcp")
                        .setUsername(isCaller ? "username1" : "username2")
                        .setPassword(isCaller ? "key1" : "key2")
                        .createIceServer()
        ));

        peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate candidate) {
                if (candidate != null) {
                    sendSignal(createSignal("candidate", candidate));
                }
            }

            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    runOnUiThread(() -> {
                        secureStatusText.setText("End-to-end encryption is active");
                        secureStatusText.setTextColor(0xFF00FF00);
                    });
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    runOnUiThread(() -> secureStatusText.setText("The connection was interrupted"));
                }
            }

            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                // уже не нужно — трек добавлен вручную
            }

            // остальные методы можно оставить пустыми
            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionReceivingChange(boolean r) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onAddStream(MediaStream s) {}
            @Override public void onRemoveStream(MediaStream s) {}
            @Override public void onDataChannel(DataChannel d) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState s) {}
        });

        // Добавляем трек ТОЛЬКО ЗДЕСЬ, один раз
        if (localAudioTrack != null) {
            peerConnection.addTrack(localAudioTrack, Arrays.asList("stream"));
        }
    }

    private void sendReadySignal() {
        createPeerConnection();
        sendSignal(createSignal("ready", null));
    }

    private void handleIncomingSecureCall() {
        if (readySent) return;
        readySent = true;

        createPeerConnection();  // ← здесь трек уже добавляется внутри createPeerConnection()
        sendSignal(createSignal("ready", null));
    }

    private void startSecureCall() {
        createPeerConnection();
        peerConnection.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription offer) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onSetSuccess() {
                        byte[] sig = new byte[Sign.BYTES];
                        ls.cryptoSignDetached(sig, offer.description.getBytes(), offer.description.length(), mySignPrivateKey);

                        // КАК В СЕКРЕТНОМ ЧАТЕ — URL_SAFE + NO_WRAP + УБИРАЕМ =
                        String sigB64 = Base64.encodeToString(sig, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
                        String pkB64 = Base64.encodeToString(mySignPublicKey, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");

                        sendSignal(createSignal("offer", offer.description, sigB64, pkB64));
                    }
                    @Override public void onSetFailure(String e) {}
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onCreateFailure(String e) {}
                }, offer);
            }
            @Override public void onCreateFailure(String e) {}
            @Override public void onSetSuccess() {}
            @Override public void onSetFailure(String e) {}
        }, new MediaConstraints());
    }

    private void handleSignal(JSONObject s) throws JSONException {
        String type = s.getString("type");

        if ("ready".equals(type) && isCaller) {
            if (ringtonePlayer != null) ringtonePlayer.pause();
            startSecureCall();
            return;
        }

        if ("offer".equals(type) && !isCaller) {
            String sdp = s.getString("sdp");
            String signature = s.getString("signature");
            String publicKeyB64 = s.has("publicKey") && !s.isNull("publicKey")
                    ? s.getString("publicKey")
                    : Base64.encodeToString(partnerSignPublicKey, Base64.URL_SAFE | Base64.NO_WRAP);

            byte[] pk = Base64.decode(publicKeyB64, Base64.URL_SAFE | Base64.NO_WRAP);
            partnerSignPublicKey = pk; // ← ОБНОВЛЕНИЕ ГЛОБАЛЬНОЙ ПЕРЕМЕННОЙ!
            byte[] sig = Base64.decode(signature, Base64.URL_SAFE | Base64.NO_WRAP);

            boolean valid = ls.cryptoSignVerifyDetached(sig, sdp.getBytes(), sdp.length(), pk);

            if (!valid) {
                runOnUiThread(() -> {
                    secureStatusText.setText("ATTACK! UNSAFE");
                    secureStatusText.setTextColor(0xFFFF0000);
                    handler.postDelayed(this::endCall, 2000);
                });
                return;
            }

            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override public void onSetSuccess() {
                    peerConnection.createAnswer(new SdpObserver() {
                        @Override public void onCreateSuccess(SessionDescription answer) {
                            peerConnection.setLocalDescription(new SdpObserver() {
                                @Override public void onSetSuccess() {
                                    byte[] sigOut = new byte[Sign.BYTES];
                                    ls.cryptoSignDetached(sigOut, answer.description.getBytes(), answer.description.length(), mySignPrivateKey);

                                    // КАК В СЕКРЕТНОМ ЧАТЕ — URL_SAFE + NO_WRAP + УБИРАЕМ =
                                    String sigB64 = Base64.encodeToString(sigOut, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");
                                    String pkB64 = Base64.encodeToString(mySignPublicKey, Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "");

                                    sendSignal(createSignal("answer", answer.description, sigB64, pkB64));
                                    runOnUiThread(() -> {
                                        secureStatusText.setText("End-to-end encryption is active");
                                        secureStatusText.setTextColor(0xFF00FF00);
                                    });
                                }
                                @Override public void onSetFailure(String e) {}
                                @Override public void onCreateSuccess(SessionDescription s) {}
                                @Override public void onCreateFailure(String e) {}
                            }, answer);
                        }
                        @Override public void onCreateFailure(String e) {}
                        @Override public void onSetSuccess() {}
                        @Override public void onSetFailure(String e) {}
                    }, new MediaConstraints());
                }
                @Override public void onSetFailure(String e) {}
                @Override public void onCreateSuccess(SessionDescription s) {}
                @Override public void onCreateFailure(String e) {}
            }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
            return;
        }

        if ("answer".equals(type) && isCaller) {
            String sdp = s.getString("sdp");
            String signature = s.getString("signature");
            String publicKeyB64 = s.has("publicKey") && !s.isNull("publicKey")
                    ? s.getString("publicKey")
                    : Base64.encodeToString(partnerSignPublicKey, Base64.URL_SAFE | Base64.NO_WRAP);

            byte[] pk = Base64.decode(publicKeyB64, Base64.URL_SAFE | Base64.NO_WRAP);
            partnerSignPublicKey = pk; // ← ОБНОВЛЕНИЕ ГЛОБАЛЬНОЙ ПЕРЕМЕННОЙ!
            byte[] sig = Base64.decode(signature, Base64.URL_SAFE | Base64.NO_WRAP);

            boolean valid = ls.cryptoSignVerifyDetached(sig, sdp.getBytes(), sdp.length(), pk);
            if (!valid) {
                runOnUiThread(() -> {
                    secureStatusText.setText("ATTACK! Signature is invalid");
                    secureStatusText.setTextColor(0xFFFF0000);
                });
                return;
            }

            SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, sdp);

            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override
                public void onSetSuccess() {
                    // КЛЮЧЕВАЯ СТРОКА — без неё DTLS на Android НЕКОТОРЫХ версиях Android не стартует!
                    peerConnection.getStats(statsReport -> {
                        Log.d(TAG, "DTLS handshake принудительно запущен через getStats()");
                        // Ничего больше не делаем — только триггерим handshake
                    });

                    runOnUiThread(() -> {
                        secureStatusText.setText("End-to-end encryption is active");
                        secureStatusText.setTextColor(0xFF00FF00);
                    });
                }

                @Override
                public void onSetFailure(String error) {
                    Log.e(TAG, "setRemoteDescription(answer) failed: " + error);
                    runOnUiThread(() -> secureStatusText.setText("Connection error"));
                }

                @Override public void onCreateSuccess(SessionDescription s) {}
                @Override public void onCreateFailure(String e) {}
            }, answer);

            return;
        }

        if ("candidate".equals(type) && peerConnection != null) {
            JSONObject ic = s.getJSONObject("iceCandidate");
            IceCandidate candidate = new IceCandidate(
                    ic.getString("sdpMid"),
                    ic.getInt("sdpMLineIndex"),
                    ic.getString("candidate")
            );
            peerConnection.addIceCandidate(candidate);
        }

        if ("endCall".equals(type)) {
            terminationInitiator = true;
            endCall();
        }
    }

    private JSONObject createSignal(String type, Object data) {
        JSONObject json = new JSONObject();
        JSONObject signal = new JSONObject();
        try {
            signal.put("type", type);
            if (data instanceof SessionDescription) {
                signal.put("sdp", ((SessionDescription) data).description);
            } else if (data instanceof IceCandidate) {
                IceCandidate c = (IceCandidate) data;
                JSONObject ic = new JSONObject();
                ic.put("candidate", c.sdp);
                ic.put("sdpMid", c.sdpMid);
                ic.put("sdpMLineIndex", c.sdpMLineIndex);
                ic.put("sdpMLineIndex", c.sdpMLineIndex); // два раза — второй перезапишет как число
                signal.put("iceCandidate", ic);
            }
            json.put("from", currentUserId);
            json.put("signal", signal);
        } catch (JSONException e) { Log.e(TAG, "JSON error", e); }
        return json;
    }

    private JSONObject createSignal(String type, String sdp, String signature, String publicKey) {
        JSONObject json = new JSONObject();
        JSONObject signal = new JSONObject();
        try {
            signal.put("type", type);
            if (sdp != null) signal.put("sdp", sdp);
            if (signature != null) signal.put("signature", signature);
            if (publicKey != null) signal.put("publicKey", publicKey);
            json.put("from", currentUserId);
            json.put("signal", signal);
        } catch (JSONException e) { Log.e(TAG, "JSON error", e); }
        return json;
    }

    private void sendSignal(JSONObject json) {
        if (stompClient != null && stompClient.isConnected()) {
            stompClient.send("/app/signal/" + roomId, json.toString())
                    .subscribe(() -> {}, err -> Log.e(TAG, "Send failed", err));
        }
    }

    private void endCall() {
        if (isCallEnded) return;
        isCallEnded = true;

        if (!terminationInitiator && stompClient != null && stompClient.isConnected()) {
            sendSignal(createSignal("endCall", null));
        }

        if (ringtonePlayer != null && ringtonePlayer.isPlaying()) ringtonePlayer.stop();
        if (peerConnection != null) { peerConnection.close(); peerConnection = null; }
        if (localAudioTrack != null) localAudioTrack.dispose();
        if (factory != null) factory.dispose();

        if (!hasSavedCall) {
            saveCallToServer("completed");
            hasSavedCall = true;
        }

        finish();
    }

    private void saveCallToServer(String status) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("caller", isCaller ? currentUserId : recipientId);
                json.put("receiver", isCaller ? recipientId : currentUserId);
                json.put("direction", isCaller ? "outgoing" : "incoming");
                json.put("status", status);
                json.put("callType", "voice_secure");
                json.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date()));
                json.put("roomId", roomId);

                OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
                Request request = new Request.Builder()
                        .url("https://unlimitedpossibilities12.org/api/calls?userId=" + currentUserId)
                        .post(RequestBody.create(json.toString(), MediaType.parse("application/json")))
                        .build();
                client.newCall(request).execute();
            } catch (Exception ignored) {}
        }).start();
    }

    private void setupRingtone() {
        ringtonePlayer = new MediaPlayer();
        try {
            ringtonePlayer.setDataSource("https://igor7070.github.io/Messenger/incoming_dial_tone.mp3");
            ringtonePlayer.setLooping(true);
            ringtonePlayer.prepareAsync();
            ringtonePlayer.setOnPreparedListener(mp -> {
                if (isCaller) ringtonePlayer.start();
            });
        } catch (IOException e) { Log.e(TAG, "Ringtone error", e); }
    }

    private void finishWithError(String msg) {
        runOnUiThread(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    @Override protected void onDestroy() {
        com.example.unlimitedpossibilities12.messenger.notife.CallService.setCallInProgress(false);
        super.onDestroy();
        if (!isCallEnded) endCall();
        if (isBound) unbindService(connection);
        if (roomSubscription != null) roomSubscription.dispose();
    }
}