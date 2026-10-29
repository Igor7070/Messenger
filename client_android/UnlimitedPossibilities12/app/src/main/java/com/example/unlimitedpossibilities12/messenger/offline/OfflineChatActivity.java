package com.example.unlimitedpossibilities12.messenger.offline;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.messenger.LocalTranslationBinder;
import com.example.unlimitedpossibilities12.messenger.LocalTranslator;
import com.example.unlimitedpossibilities12.messenger.SecretText;
import com.example.unlimitedpossibilities12.messenger.SecretTranslationDialogFragment;
import com.google.android.gms.nearby.connection.Payload;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OfflineChatActivity extends AppCompatActivity
        implements OfflineChatAdapter.OnFileClickListener,
        OfflineChatAdapter.OnMessageLongClickListener {

    public static final String EXTRA_ENDPOINT_ID = "endpoint_id";
    public static final String EXTRA_USER_NAME = "user_name";
    public static final String EXTRA_IS_RECONNECT = "is_reconnect";

    private NearbyManager nearbyManager;
    private OfflineChatStorage chatStorage;
    private OfflineAvatarStorage avatarStorage;

    private String endpointId;
    private String userName;
    private boolean isReconnect = false;

    private RecyclerView chatRecyclerView;
    private EditText messageInput;
    private ImageView sendIcon;
    private ImageView micIcon;
    private TextView statusText;

    private LinearLayout attachmentPreviewContainer;
    private ImageView attachmentPreview;
    private ImageView btnRemoveAttachment;
    private ImageView partnerAvatarView;
    private ImageView btnCallOffline;

    private final List<OfflineMessage> messages = new ArrayList<>();
    private OfflineChatAdapter chatAdapter;

    private boolean isConnected = false; // только для подписи online/offline; для действий — isPeerConnected()
    private Uri pendingImageUri = null;
    private String lastReceivedFileName = null;
    private String lastReceivedFileMessageId = null;
    private boolean isRecording = false;

    private MediaRecorder mediaRecorder;
    private File audioFile;

    private static final int PICK_IMAGE_REQUEST = 1001;
    private static final int AUDIO_PERMISSION_REQUEST_CODE = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_chat);

        // 1. Данные из Intent
        endpointId = getIntent().getStringExtra(EXTRA_ENDPOINT_ID);
        userName = getIntent().getStringExtra(EXTRA_USER_NAME);
        isReconnect = getIntent().getBooleanExtra(EXTRA_IS_RECONNECT, false);

        // 2. Инициализация Views
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        TextView chatTitle = findViewById(R.id.chat_title);
        if (chatTitle != null) {
            String displayName = (userName != null && !userName.isEmpty()) ? userName : "Unknown";
            chatTitle.setText("Chat with " + displayName);
        }

        statusText = findViewById(R.id.status_text);
        chatRecyclerView = findViewById(R.id.chat_recycler_view);
        messageInput = findViewById(R.id.message_input);
        sendIcon = findViewById(R.id.send_icon);
        micIcon = findViewById(R.id.mic_icon);

        attachmentPreviewContainer = findViewById(R.id.attachment_preview_container);
        attachmentPreview = findViewById(R.id.attachment_preview);
        btnRemoveAttachment = findViewById(R.id.btn_remove_attachment);
        partnerAvatarView = findViewById(R.id.partner_avatar);

        btnCallOffline = findViewById(R.id.btn_call_offline);
        if (btnCallOffline != null) {
            btnCallOffline.setOnClickListener(v -> startOfflineCall());
        }

        // 3. Инициализация объектов
        chatStorage = new OfflineChatStorage(this);
        avatarStorage = new OfflineAvatarStorage(this);
        nearbyManager = new NearbyManager(this);

        // 4. Загружаем аватар для тулбара
        loadPartnerAvatar();

        // 5. Создаём адаптер
        Uri partnerAvatarUri = (avatarStorage != null && endpointId != null)
                ? avatarStorage.getAvatar(endpointId)
                : null;

        chatRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        chatAdapter = new OfflineChatAdapter(
                messages,
                this,
                this,
                partnerAvatarUri
        );
        chatRecyclerView.setAdapter(chatAdapter);

        // 6. Теперь можно загружать историю (после создания адаптера!)
        loadChatHistory();

        // Глобальный listener сообщений
        chatListener = new NearbyManager.OnMessageReceivedListener() {
            @Override
            public void onMessageReceived(String fromEndpointId, String text) {
                if (!isDestroyed() && !isFinishing()) {
                    OfflineChatActivity.this.onMessageReceived(fromEndpointId, text);
                }
            }

            @Override
            public void onFileReceived(String fromEndpointId, Uri fileUri) {
                if (!isDestroyed() && !isFinishing()) {
                    OfflineChatActivity.this.onFileReceived(fromEndpointId, fileUri);
                }
            }

            @Override
            public void onAvatarReceived(String fromEndpointId, Uri avatarUri) {
                if (avatarUri == null) return;
                if (isDestroyed() || isFinishing()) {
                    Log.d("OfflineChat", "Activity уже уничтожена, пропускаем обновление аватара");
                    return;
                }

                avatarStorage.saveAvatar(fromEndpointId, avatarUri);

                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        loadPartnerAvatar();
                    }
                });
            }
        };
        NearbyManager.setGlobalMessageListener(chatListener);

        // Галочки доставки: статус меняется в NearbyManager (подтверждение от
        // собеседника или отправка из очереди) — обновляем сообщение на экране
        NearbyManager.setMessageStatusListener((ep, messageId, status) -> runOnUiThread(() -> {
            if (!endpointId.equals(ep)) return;
            for (int i = 0; i < messages.size(); i++) {
                OfflineMessage m = messages.get(i);
                if (messageId.equals(m.getMessageId())) {
                    if (m.getStatus() < status) {
                        m.setStatus(status);
                        chatAdapter.notifyItemChanged(i);
                    }
                    return;
                }
            }
        }));

        nearbyManager.setOnConnectionSuccessListener(this::onConnectionSuccess);

        if (isMeshChat()) {
            // Узел за посредником: прямого соединения нет, статус — по достижимости через сеть
            isConnected = nearbyManager.isConnectedTo(endpointId);
            updateConnectionStatus(isConnected);
        } else if (isReconnect) {
            attemptReconnect();
        } else {
            // Чат мог быть открыт из «недавних» без живого соединения — не выдаём
            // желаемое за действительное, статус берём у менеджера
            isConnected = nearbyManager.isConnectedTo(endpointId);
            updateConnectionStatus(isConnected);
        }

        // Кнопки
        sendIcon.setOnClickListener(v -> sendMessage());

        // Перевод на устройстве: те же пакеты и настройки, что в секретном чате, но
        // ключ — по имени собеседника (endpointId меняется от сессии к сессии).
        translationBinder = new LocalTranslationBinder(this, translationChatKey());
        chatAdapter.setTranslationBinder(translationBinder);
        ImageView translateBtn = findViewById(R.id.btn_translate_offline);
        if (translateBtn != null) {
            translateBtn.setOnClickListener(v -> new SecretTranslationDialogFragment(translationChatKey(), (enabled, src, tgt) -> {
                translationBinder.clearCache();
                updateTranslateIconTint();
                chatAdapter.notifyDataSetChanged();
            }, "Offline chat: translation runs on this phone and works without internet. "
                    + "Language packs are downloaded once - do it while you are online.")
                    .show(getSupportFragmentManager(), "OfflineTranslation"));
            updateTranslateIconTint();
        }

        micIcon.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                requestAudioPermission();
            }
        });

        // Скрепка — это drawableStart поля ввода. Нажатие по ней открывает выбор фото
        // независимо от того, введён ли уже текст (текст станет подписью к фото).
        messageInput.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                android.graphics.drawable.Drawable[] d = messageInput.getCompoundDrawables();
                android.graphics.drawable.Drawable start = d[0];
                int clipWidth = start != null ? start.getBounds().width() : 0;
                float zoneEnd = messageInput.getPaddingStart() + clipWidth + messageInput.getCompoundDrawablePadding();
                if (event.getX() <= zoneEnd) {
                    if (pendingImageUri == null) {
                        openFilePicker();
                    } else {
                        Toast.makeText(this, "Photo already attached", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            }
            return false;
        });

        btnRemoveAttachment.setOnClickListener(v -> removePendingImage());

        messageInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateInputIcons();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        updateInputIcons();
    }

    private void updateInputIcons() {
        boolean hasText = !messageInput.getText().toString().trim().isEmpty();
        boolean hasPendingImage = pendingImageUri != null;

        if (isRecording) {
            micIcon.setVisibility(View.VISIBLE);
            sendIcon.setVisibility(View.GONE);
            micIcon.setImageResource(R.drawable.ic_recording);
            micIcon.setColorFilter(0xFFEF4444);
        } else if (hasText || hasPendingImage) {
            micIcon.setVisibility(View.GONE);
            sendIcon.setVisibility(View.VISIBLE);
        } else {
            micIcon.setVisibility(View.VISIBLE);
            sendIcon.setVisibility(View.GONE);
            micIcon.setImageResource(R.drawable.ic_mic);
            micIcon.setColorFilter(0xFF94A3B8);
        }
    }

    // ==================== ЗАПИСЬ АУДИО ====================

    private void requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST_CODE);
        } else {
            startRecording();
        }
    }

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

    private void startRecording() {
        if (isMeshChat()) {
            Toast.makeText(this, "Voice messages are direct-only for now (not over mesh)", Toast.LENGTH_SHORT).show();
            return;
        }
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
            updateInputIcons();

            Toast.makeText(this, "Recording...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e("AUDIO", "Error starting recording", e);
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
            isRecording = false;
            updateInputIcons();
        }
    }

    private void stopRecording() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            } catch (Exception e) {
                Log.e("AUDIO", "Error stopping recording", e);
            }
        }

        isRecording = false;
        updateInputIcons();

        if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
            sendAudioMessage();
        } else {
            Toast.makeText(this, "Recording is empty", Toast.LENGTH_SHORT).show();
            if (audioFile != null) audioFile.delete();
            audioFile = null;
        }
    }

    private void sendAudioMessage() {
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            Toast.makeText(this, "Audio recording is empty or missing", Toast.LENGTH_SHORT).show();

            if (audioFile != null && audioFile.exists()) {
                audioFile.delete();
            }
            audioFile = null;
            return;
        }

        Toast.makeText(this, "Sending voice message...", Toast.LENGTH_SHORT).show();

        Uri audioUri = Uri.fromFile(audioFile);

        // Отправляем файл
        sendFile(audioUri, "");

        // Важно: не удаляем файл сразу — он нужен для проигрывания своего сообщения
        // Можно удалить позже, например в onDestroy
        // audioFile = null;   // оставляем ссылку
    }

    // ==================== ОСНОВНЫЕ МЕТОДЫ (оставлены как в старом коде) ====================

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri fileUri = data.getData();
            if (fileUri != null) {
                showFilePreview(fileUri);
            }
        }
    }

    private void showFilePreview(Uri fileUri) {
        pendingImageUri = fileUri;
        attachmentPreviewContainer.setVisibility(View.VISIBLE);

        String mimeType = getContentResolver().getType(fileUri);
        if (mimeType != null && mimeType.startsWith("image/")) {
            Glide.with(this).load(fileUri).centerCrop().into(attachmentPreview);
        } else {
            attachmentPreview.setImageResource(R.drawable.ic_file);
        }
        updateInputIcons();
    }

    private void removePendingImage() {
        pendingImageUri = null;
        attachmentPreviewContainer.setVisibility(View.GONE);
        updateInputIcons();
    }

    // ===== Перевод на устройстве (офлайн-чат) =====

    private LocalTranslationBinder translationBinder;

    /** Стабильный ключ настроек перевода для этого собеседника. */
    private long translationChatKey() {
        String name = userName != null ? userName : (endpointId != null ? endpointId : "offline");
        return 0x0FF1000000000000L + (name.hashCode() & 0xFFFFFFFFL);
    }

    private void updateTranslateIconTint() {
        ImageView icon = findViewById(R.id.btn_translate_offline);
        if (icon == null || translationBinder == null) return;
        icon.setColorFilter(Color.parseColor(translationBinder.isOn() ? "#67E8F9" : "#94A3B8"));
    }

    /**
     * Если перевод включён и пакеты на месте — переводит текст на устройстве и
     * заворачивает в {"text","translated","lang"}; иначе отдаёт как есть.
     * Никогда не задерживает отправку из-за ошибки: любая проблема -> оригинал.
     */
    private void prepareOutgoingText(String text, java.util.function.Consumer<String> then) {
        long key = translationChatKey();
        String tgt = LocalTranslator.targetLanguage(this, key);
        if (!LocalTranslator.isEnabled(this, key) || tgt == null || text == null || text.isEmpty()) {
            then.accept(text);
            return;
        }
        LocalTranslator.translateToIfNeeded(text, tgt).addOnCompleteListener(t -> {
            String translated = (t.isSuccessful() && t.getResult() != null && !t.getResult().isEmpty()) ? t.getResult() : null;
            runOnUiThread(() -> then.accept(translated != null ? SecretText.wrap(text, translated, tgt) : text));
        });
    }

    private void sendMessage() {
        String text = messageInput.getText().toString().trim();
        if (!isPeerConnected() && pendingImageUri == null && !text.isEmpty()) {
            // Соединения нет — сообщение встанет в очередь и уйдёт, когда собеседник появится
            Toast.makeText(this, "No connection — message queued", Toast.LENGTH_SHORT).show();
        } else if (!isPeerConnected()) {
            Toast.makeText(this, "No active connection to the peer", Toast.LENGTH_SHORT).show();
            return;
        }

        if (pendingImageUri != null) {
            sendFile(pendingImageUri, text);
            removePendingImage();
            messageInput.setText(""); // подпись ушла с фото — поле ввода очищаем, как и при обычной отправке
            updateInputIcons();
        } else if (!text.isEmpty()) {
            // Создаём сообщение с уникальным ID
            OfflineMessage msg = new OfflineMessage(text, true);

            messages.add(msg);
            chatAdapter.notifyItemInserted(messages.size() - 1);
            chatRecyclerView.scrollToPosition(messages.size() - 1);

            chatStorage.saveMessage(endpointId, msg);

            messageInput.setText("");
            updateInputIcons();

            // Перевод на устройстве до отправки: собеседник получит {"text","translated","lang"}
            prepareOutgoingText(text, prepared -> {
                if (!prepared.equals(text)) {
                    msg.setText(prepared);
                }
                String payload = msg.getMessageId() + "|" + prepared;
                // Не ушло (собеседник вне радиуса) — кладём в очередь, уйдёт само
                boolean sent = nearbyManager.sendMessage(endpointId, payload);
                if (!sent) {
                    new OfflineOutbox(this).add(endpointId, msg.getMessageId(), payload);
                    msg.setStatus(OfflineMessage.STATUS_QUEUED);
                } else {
                    msg.setStatus(OfflineMessage.STATUS_SENT);
                }
                chatStorage.updateMessage(endpointId, msg);
                int pos = messages.indexOf(msg);
                if (pos >= 0) chatAdapter.notifyItemChanged(pos);
                Log.d("SEND", (sent ? "Отправлено" : "В очередь") + " | ID: " + msg.getMessageId() + " | Текст: " + prepared);
            });
        }
    }

    private NearbyManager.OnMessageReceivedListener chatListener;

    /**
     * Есть ли связь с собеседником прямо сейчас. Спрашиваем у менеджера, а не
     * храним у себя: событие «соединение установлено» приходит в тот экземпляр,
     * который его инициировал, и до этого экрана может не дойти — тогда флаг
     * оставался ложным и, например, фото отправить было нельзя, хотя текст уходил.
     */
    private boolean isPeerConnected() {
        return nearbyManager != null && nearbyManager.isConnectedTo(endpointId);
    }

    private final android.os.Handler statusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusTask = new Runnable() {
        @Override public void run() {
            boolean now = isPeerConnected();
            if (now != isConnected) {
                isConnected = now;
                updateConnectionStatus(now);
                if (now) nearbyManager.flushOutbox(); // связь появилась — отправить накопленное
            }
            statusHandler.postDelayed(this, 2000);
        }
    };

    private boolean isMeshChat() {
        return com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshNode.isVirtual(endpointId);
    }

    private void sendFile(Uri fileUri, String caption) {
        if (isMeshChat()) {
            Toast.makeText(this, "Files are direct-only for now (not over mesh)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (fileUri == null || !isPeerConnected()) {
            Toast.makeText(this, "No connection or the file is missing", Toast.LENGTH_SHORT).show();
            return;
        }

        String originalFileName = getRealFileName(fileUri);

        // Создаём сообщение с messageId
        String mimeType = getContentResolver().getType(fileUri);
        boolean isImage = (mimeType != null && mimeType.startsWith("image/")) ||
                originalFileName.toLowerCase().matches(".*\\.(jpg|jpeg|png|webp|gif|bmp)$");

        OfflineMessage fileMsg = new OfflineMessage(fileUri, true, isImage);
        String messageId = fileMsg.getMessageId();

        // 1. Отправляем мета-информацию о файле (ID + имя)
        String fileInfo = "___FILE___" + messageId + "|" + originalFileName;
        nearbyManager.sendMessage(endpointId, fileInfo);

        // 2. Подпись (если есть)
        if (caption != null && !caption.isEmpty()) {
            OfflineMessage captionMsg = new OfflineMessage(caption, true);
            messages.add(captionMsg);
            chatAdapter.notifyItemInserted(messages.size() - 1);
            chatRecyclerView.scrollToPosition(messages.size() - 1);
            chatStorage.saveMessage(endpointId, captionMsg);

            prepareOutgoingText(caption, prepared -> {
                if (!prepared.equals(caption)) {
                    captionMsg.setText(prepared);
                    chatStorage.updateMessage(endpointId, captionMsg);
                    int cpos = messages.indexOf(captionMsg);
                    if (cpos >= 0) chatAdapter.notifyItemChanged(cpos);
                }
                nearbyManager.sendMessage(endpointId, captionMsg.getMessageId() + "|" + prepared);
            });
        }

        // 3. Добавляем файл в свой чат
        messages.add(fileMsg);
        chatAdapter.notifyItemInserted(messages.size() - 1);
        chatRecyclerView.scrollToPosition(messages.size() - 1);
        chatStorage.saveMessage(endpointId, fileMsg);

        Toast.makeText(this, "Sending: " + originalFileName, Toast.LENGTH_SHORT).show();

        // 4. Отправляем сам файл
        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(fileUri, "r");
            if (pfd != null) {
                Payload payload = Payload.fromFile(pfd);
                nearbyManager.sendFile(endpointId, payload);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to send the file", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private String getRealFileName(Uri uri) {
        if (uri == null) return "File";

        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {}

        String path = uri.getLastPathSegment();
        if (path != null && !path.equals("null")) {
            return path;
        }

        return "File";
    }

    private void loadChatHistory() {
        List<OfflineMessage> history = chatStorage.getMessages(endpointId);
        messages.addAll(history);
        chatAdapter.notifyDataSetChanged();

        if (!messages.isEmpty()) {
            chatRecyclerView.scrollToPosition(messages.size() - 1);
        }
    }

    private void attemptReconnect() {
        updateConnectionStatus(false);
        if (endpointId != null) {
            nearbyManager.connectToExistingDevice(endpointId);
        }
    }

    private void onMessageReceived(String fromEndpointId, String payload) {
        Log.d("OfflineChat", "📩 Получено от " + fromEndpointId + " | Payload: " + payload);

        if (!endpointId.equals(fromEndpointId)) {
            Log.w("OfflineChat", "⚠️ Сообщение из другого чата, игнорируем");
            return;
        }

        runOnUiThread(() -> {

            // ==================== СИГНАЛЫ ЗВОНКА ====================
            if (OfflineCallManager.SIGNAL_INVITE.equals(payload)) {
                // Входящий звонок
                Intent intent = new Intent(OfflineChatActivity.this, OfflineVoiceCallActivity.class);
                intent.putExtra(OfflineVoiceCallActivity.EXTRA_ENDPOINT_ID, fromEndpointId);
                intent.putExtra(OfflineVoiceCallActivity.EXTRA_USER_NAME,
                        userName != null ? userName : "Peer");
                intent.putExtra(OfflineVoiceCallActivity.EXTRA_IS_OUTGOING, false);
                startActivity(intent);
                return;
            }

            if (OfflineCallManager.SIGNAL_ACCEPT.equals(payload)
                    || OfflineCallManager.SIGNAL_REJECT.equals(payload)
                    || OfflineCallManager.SIGNAL_END.equals(payload)
                    || OfflineCallManager.SIGNAL_BUSY.equals(payload)) {
                // Эти сигналы обрабатывает OfflineVoiceCallActivity
                return;
            }

            // ==================== КОМАНДЫ ====================
            if (payload.startsWith("___DELETE___")) {
                String messageId = payload.substring("___DELETE___".length());
                Log.d("DELETE", "🗑 Получена команда удаления | messageId = " + messageId);
                deleteMessageLocallyById(messageId);
                return;
            }

            if (payload.startsWith("___EDIT___")) {
                try {
                    String data = payload.substring("___EDIT___".length());
                    String[] parts = data.split("\\|", 2);
                    if (parts.length == 2) {
                        editMessageLocallyById(parts[0], parts[1]);
                    }
                } catch (Exception e) {
                    Log.e("EDIT", "Ошибка парсинга EDIT команды", e);
                }
                return;
            }

            // ==================== МЕТА ИНФОРМАЦИЯ ФАЙЛА ====================
            if (payload.startsWith("___FILE___")) {
                try {
                    String data = payload.substring("___FILE___".length());
                    String[] parts = data.split("\\|", 2);
                    if (parts.length == 2) {
                        lastReceivedFileMessageId = parts[0];
                        lastReceivedFileName = parts[1];
                        Log.d("FILE", "Получена мета файла → ID: " + lastReceivedFileMessageId
                                + " | Name: " + lastReceivedFileName);
                    }
                } catch (Exception e) {
                    Log.e("FILE", "Ошибка парсинга ___FILE___", e);
                }
                return;
            }

            // ==================== ОБЫЧНОЕ ТЕКСТОВОЕ СООБЩЕНИЕ ====================
            String messageId = null;
            String text = payload;

            if (payload.contains("|")) {
                String[] parts = payload.split("\\|", 2);
                if (parts.length == 2) {
                    messageId = parts[0];
                    text = parts[1];
                }
            }

            Log.d("OfflineChat", "💬 Текстовое сообщение | ID: " + messageId + " | Текст: " + text);

            OfflineMessage msg = new OfflineMessage(text, false, messageId);

            messages.add(msg);
            chatAdapter.notifyItemInserted(messages.size() - 1);
            chatRecyclerView.scrollToPosition(messages.size() - 1);
            chatStorage.saveMessage(endpointId, msg);
        });
    }

    private void onFileReceived(String fromEndpointId, Uri fileUri) {
        runOnUiThread(() -> {
            if (fileUri == null) return;

            String fileName = (lastReceivedFileName != null) ? lastReceivedFileName : getRealFileName(fileUri);
            String fileMessageId = (lastReceivedFileMessageId != null) ? lastReceivedFileMessageId : null;

            // Очищаем
            lastReceivedFileName = null;
            lastReceivedFileMessageId = null;

            boolean isImage = false;
            boolean isAudio = false;

            if (fileName != null) {
                String lower = fileName.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                        lower.endsWith(".png") || lower.endsWith(".webp") ||
                        lower.endsWith(".gif") || lower.endsWith(".bmp")) {
                    isImage = true;
                } else if (lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
                        lower.endsWith(".aac") || lower.endsWith(".wav") ||
                        lower.endsWith(".ogg")) {
                    isAudio = true;
                }
            }

            OfflineMessage msg = new OfflineMessage(fileUri, false, isImage);

            if (fileMessageId != null) {
                msg.setMessageId(fileMessageId);   // ← Самое важное для удаления
            }
            if (fileName != null) {
                msg.setFileName(fileName);
            }
            if (isAudio) {
                msg.setIsAudio(true);
            }

            messages.add(msg);
            chatAdapter.notifyItemInserted(messages.size() - 1);
            chatRecyclerView.scrollToPosition(messages.size() - 1);

            chatStorage.saveMessage(endpointId, msg);

            Log.d("FILE_DEBUG", "Файл добавлен | ID: " + fileMessageId
                    + " | Name: " + fileName + " | isImage=" + isImage);
        });
    }

    @Override
    public void onFileClick(Uri fileUri, String fileName, String mimeType) {
        saveReceivedFile(fileUri, fileName, mimeType);
    }

    public void saveReceivedFile(Uri sourceUri, String fileName, String mimeType) {
        if (sourceUri == null) return;

        String finalFileName = fileName;

        if (finalFileName == null || finalFileName.equals("[Файл]") || finalFileName.equals("Файл") || finalFileName.equals("[File]") || finalFileName.equals("File")) {
            finalFileName = "UP12_File_" + System.currentTimeMillis();
        }

        if (!finalFileName.contains(".")) {
            String ext = "";
            if (mimeType != null) {
                if (mimeType.contains("pdf")) ext = ".pdf";
                else if (mimeType.contains("image/")) ext = ".jpg";
                else if (mimeType.contains("text")) ext = ".txt";
                else if (mimeType.contains("application/")) ext = ".bin";
            }
            if (!ext.isEmpty()) {
                finalFileName += ext;
            } else {
                finalFileName += ".bin";
            }
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE,
                    mimeType != null ? mimeType : "application/octet-stream");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Messenger UP12");
            }

            ContentResolver resolver = getContentResolver();
            Uri targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

            if (targetUri != null) {
                try (InputStream in = resolver.openInputStream(sourceUri);
                     OutputStream out = resolver.openOutputStream(targetUri)) {

                    if (in != null && out != null) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                }
                Toast.makeText(this, "Saved:\n" + finalFileName, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save the file", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    public void onConnectionSuccess(String endpointIdParam) {
        isConnected = true;
        updateConnectionStatus(true);
        nearbyManager.flushOutbox(); // отправить то, что ждало этого собеседника
    }

    private void updateConnectionStatus(boolean connected) {
        if (statusText == null) return;
        runOnUiThread(() -> {
            if (connected) {
                statusText.setText("online");
                statusText.setTextColor(0xFF34D399);
            } else {
                statusText.setText("offline");
                statusText.setTextColor(0xFF94A3B8);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Вернулись из звонка или свернутого состояния — приём сообщений снова наш
        if (chatListener != null) NearbyManager.setGlobalMessageListener(chatListener);
        statusHandler.removeCallbacks(statusTask);
        statusHandler.post(statusTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusTask);
    }

    @Override
    protected void onDestroy() {
        // Снимаем глобальный listener — ОЧЕНЬ ВАЖНО!
        if (NearbyManager.getGlobalMessageListener() == chatListener) NearbyManager.setGlobalMessageListener(null);
        NearbyManager.setMessageStatusListener(null);

        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception ignored) {}
            mediaRecorder = null;
        }

        if (chatAdapter != null) {
            chatAdapter.releasePlayer();
        }

        super.onDestroy();
    }

    // ====================== ДОЛГОЕ НАЖАТИЕ НА СООБЩЕНИЕ ======================
    @Override
    public void onMessageLongClick(OfflineMessage message, int position) {
        if (message.isSystemMessage()) return;

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);

        androidx.appcompat.app.AlertDialog dialog = builder
                .setTitle("Message")
                .setItems(new String[]{"Edit", "Delete"}, (dialogInterface, which) -> {
                    if (which == 0) {
                        editMessage(message, position);
                    } else if (which == 1) {
                        deleteMessage(position);
                    }
                })
                .create();

        // ←←← Применяем всё ДО show()
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_dark_background);
        }

        dialog.setOnShowListener(dialogInterface -> {
            // Заголовок
            TextView titleView = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
            if (titleView != null) {
                titleView.setTextColor(0xFFFFFFFF);
            }

            // Пункты меню
            ListView listView = dialog.getListView();
            if (listView != null) {
                listView.setCacheColorHint(0x00000000);
                listView.setBackgroundColor(0x00000000);
                listView.setDivider(null);
                listView.setDividerHeight(0);

                for (int i = 0; i < listView.getChildCount(); i++) {
                    View child = listView.getChildAt(i);
                    if (child instanceof TextView) {
                        TextView tv = (TextView) child;
                        if (i == 1) { // Удалить
                            tv.setTextColor(0xFFFF6B6B);
                        } else { // Редактировать
                            tv.setTextColor(0xFF94A3B8);
                        }
                    }
                }
            }
        });

        dialog.show();
    }

    // ====================== УДАЛЕНИЕ СООБЩЕНИЯ ======================
    private void deleteMessage(int position) {
        if (position < 0 || position >= messages.size()) {
            Log.e("DELETE", "❌ Некорректная позиция: " + position);
            return;
        }

        OfflineMessage msg = messages.get(position);
        String messageId = msg.getMessageId();

        Log.d("DELETE", "🗑 Удаляем сообщение локально | messageId = " + messageId
                + " | Текст: " + msg.getText());

        // Удаляем локально
        messages.remove(position);
        chatAdapter.notifyItemRemoved(position);

        // Удаляем из хранилища
        chatStorage.deleteMessage(endpointId, messageId);

        // Отправляем команду собеседнику
        if (isPeerConnected()) {
            String command = "___DELETE___" + messageId;
            Log.d("DELETE", "📤 Отправляем команду удаления: " + command);
            nearbyManager.sendMessage(endpointId, command);
        } else {
            Log.w("DELETE", "⚠️ Нет соединения — команда удаления не отправлена");
        }

        Toast.makeText(this, "Message deleted", Toast.LENGTH_SHORT).show();
    }

    // ====================== РЕДАКТИРОВАНИЕ СООБЩЕНИЯ ======================
    private void editMessage(OfflineMessage oldMessage, int position) {
        // В хранилище текст может лежать обёрткой {"text","translated","lang"} —
        // редактировать пользователь должен свою фразу, а не служебный JSON
        final String plainOld = SecretText.parse(oldMessage.getText()).text;
        final EditText input = new EditText(this);
        input.setText(plainOld);
        input.setSelection(plainOld.length());

        // Настраиваем EditText под тёмную тему
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF94A3B8);
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF334155));

        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Edit message")
                .setView(input)
                .setPositiveButton("Save", (dialogInterface, which) -> {
                    String newText = input.getText().toString().trim();
                    if (newText.isEmpty() || newText.equals(plainOld)) {
                        Log.d("EDIT", "Отмена редактирования (текст не изменился)");
                        return;
                    }

                    String messageId = oldMessage.getMessageId();
                    Log.d("EDIT", "✏ Редактируем сообщение | messageId = " + messageId
                            + " | Старый текст: " + plainOld
                            + " | Новый текст: " + newText);

                    // Правленый текст переводим заново, чтобы у собеседника обновился и перевод
                    prepareOutgoingText(newText, prepared -> {
                    // Обновляем объект
                    oldMessage.setText(prepared);

                    // Обновляем в списке и хранилище
                    chatAdapter.notifyItemChanged(position);
                    chatStorage.updateMessage(endpointId, oldMessage);

                    // Отправляем команду собеседнику
                    if (isPeerConnected()) {
                        String command = "___EDIT___" + messageId + "|" + prepared;
                        Log.d("EDIT", "📤 Отправляем команду редактирования: " + command);
                        nearbyManager.sendMessage(endpointId, command);
                    } else {
                        Log.w("EDIT", "⚠️ Нет соединения — команда редактирования не отправлена");
                    }

                    Toast.makeText(this, "Message edited", Toast.LENGTH_SHORT).show();
                    }); // prepareOutgoingText
                })
                .setNegativeButton("Cancel", null)
                .create();

        // Применяем тёмный фон до показа
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_dark_background);
        }

        dialog.setOnShowListener(dialogInterface -> {
            // Заголовок белый
            TextView titleView = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
            if (titleView != null) {
                titleView.setTextColor(0xFFFFFFFF);
            }

            // Кнопки
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(0xFF4ADE80);   // зелёный для "Сохранить"

            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(0xFF94A3B8);
        });

        dialog.show();
    }

    // ====================== ЛОКАЛЬНАЯ ОБРАБОТКА КОМАНД ======================

    // Удаление по messageId (приходит от собеседника)
    private void deleteMessageLocallyById(String messageId) {
        Log.d("DELETE", "🗑 Получена команда удаления | messageId = " + messageId
                + " | Всего сообщений в чате: " + messages.size());

        for (int i = 0; i < messages.size(); i++) {
            OfflineMessage msg = messages.get(i);
            String currentId = msg.getMessageId();

            Log.v("DELETE", "Сравниваем: " + currentId + " == " + messageId);

            if (currentId != null && currentId.equals(messageId)) {
                Log.d("DELETE", "✅ Сообщение найдено! Удаляем позицию " + i);

                String text = msg.getText();
                messages.remove(i);
                chatAdapter.notifyItemRemoved(i);
                chatStorage.deleteMessage(endpointId, messageId);

                Toast.makeText(this, "Message deleted", Toast.LENGTH_SHORT).show();
                Log.d("DELETE", "✅ Успешно удалено локально: " + text);
                return;
            }
        }

        Log.w("DELETE", "❌ Сообщение с messageId = " + messageId + " НЕ НАЙДЕНО в чате!");
    }

    // Редактирование по messageId (приходит от собеседника)
    private void editMessageLocallyById(String messageId, String newText) {
        Log.d("EDIT", "✏ Получена команда редактирования | messageId = " + messageId
                + " | Новый текст: " + newText);

        for (int i = 0; i < messages.size(); i++) {
            OfflineMessage msg = messages.get(i);
            String currentId = msg.getMessageId();

            if (currentId != null && currentId.equals(messageId)) {
                Log.d("EDIT", "✅ Сообщение найдено! Изменяем текст");

                msg.setText(newText);
                chatAdapter.notifyItemChanged(i);
                chatStorage.updateMessage(endpointId, msg);

                Toast.makeText(this, "Message updated", Toast.LENGTH_SHORT).show();
                Log.d("EDIT", "✅ Сообщение успешно отредактировано локально");
                return;
            }
        }

        Log.w("EDIT", "❌ Сообщение с messageId = " + messageId + " НЕ НАЙДЕНО!");
    }

    private void loadPartnerAvatar() {
        if (partnerAvatarView == null || endpointId == null) return;

        Uri avatarUri = avatarStorage.getAvatar(endpointId);

        if (avatarUri != null) {
            Glide.with(this)
                    .load(avatarUri)
                    .circleCrop()
                    .placeholder(R.drawable.default_avatar_offline)
                    .into(partnerAvatarView);
        } else {
            // Если аватара нет — показываем первую букву имени
            String firstLetter = (userName != null && !userName.isEmpty())
                    ? userName.substring(0, 1).toUpperCase()
                    : "?";

            // Можно сделать красивый плейсхолдер с буквой
            partnerAvatarView.setImageDrawable(null); // очищаем
            partnerAvatarView.setBackgroundResource(R.drawable.circle_background); // создай этот drawable
            // Или просто поставь дефолтную картинку
            partnerAvatarView.setImageResource(R.drawable.default_avatar_offline);
        }
    }

    private void startOfflineCall() {
        if (isMeshChat()) {
            Toast.makeText(this, "Calls are direct-only for now (not over mesh)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isPeerConnected()) {
            Toast.makeText(this, "No connection to the peer", Toast.LENGTH_SHORT).show();
            return;
        }

        if (endpointId == null || endpointId.isEmpty()) {
            Toast.makeText(this, "Error: no endpointId", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, OfflineVoiceCallActivity.class);
        intent.putExtra(OfflineVoiceCallActivity.EXTRA_ENDPOINT_ID, endpointId);
        intent.putExtra(OfflineVoiceCallActivity.EXTRA_USER_NAME, userName != null ? userName : "Peer");
        intent.putExtra(OfflineVoiceCallActivity.EXTRA_IS_OUTGOING, true);
        startActivity(intent);
    }
}
