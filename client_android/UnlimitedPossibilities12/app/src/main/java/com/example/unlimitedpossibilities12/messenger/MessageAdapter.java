package com.example.unlimitedpossibilities12.messenger;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ListPopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.Message;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.popupMenu.PopupMenuAdapter;
import com.example.unlimitedpossibilities12.messenger.popupMenu.PopupMenuItem;
import com.example.unlimitedpossibilities12.messenger.translate_and_spell_check.PrivateChatTranslationAndSpellCheckHelper;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {
    private static final String TAG = "MessageAdapter";
    private final Context context;
    private final List<Message> messages;
    private final int currentUserId;
    private final OnMessageActionListener actionListener;
    private final Map<Long, Boolean> userStatuses = new HashMap<>(); // Статусы пользователей (онлайн/оффлайн)
    private boolean isTranslationEnabled;
    private MediaPlayer mediaPlayer; // Добавлено: MediaPlayer для воспроизведения аудио
    private boolean isTTSEnabled; // ДОБАВЛЕНО: Переменная для TTS
    private MediaPlayer currentMediaPlayer;
    private long currentPlayingMessageId = -1;
    private boolean isPlayingTranslated = false;
    private boolean isTranscriptionEnabled; // Переменная для состояния транскрипции

    public interface OnMessageActionListener {
        void onEdit(Message message);
        void onDelete(Message message);
    }

    public MessageAdapter(Context context, List<Message> messages, int currentUserId, OnMessageActionListener listener, boolean isTranslationEnabled, boolean isTranscriptionEnabled) {
        this.context = context;
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        this.currentUserId = currentUserId;
        this.actionListener = listener;
        this.isTranslationEnabled = isTranslationEnabled;
        this.isTTSEnabled = false;
        this.isTranscriptionEnabled = isTranscriptionEnabled;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message message = messages.get(position);
        if (message == null || message.getSender() == null) {
            Log.w(TAG, "Message or sender is null at position: " + position);
            return;
        }
        holder.bind(message, position);
        // ДОБАВЛЕНО: Остановка предыдущего воспроизведения при привязке нового элемента, включая TTS
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
            Log.d(TAG, "MediaPlayer released for message id=" + message.getId());
        }
        stopCurrentTTS(); // ДОБАВЛЕНО: Остановка TTS для предотвращения наложения аудио
    }

    public void setTranslationEnabled(boolean enabled) {
        this.isTranslationEnabled = enabled;
        notifyDataSetChanged();
    }

    // Установка состояния TTS
    public void setTTSEnabled(boolean enabled) {
        this.isTTSEnabled = enabled;
        notifyDataSetChanged();
    }

    // ДОБАВЛЕНО: Сеттер для isTranscriptionEnabled
    public void setTranscriptionEnabled(boolean enabled) {
        this.isTranscriptionEnabled = enabled;
        notifyDataSetChanged();
        Log.d(TAG, "Transcription enabled set to: " + enabled);
    }


    /** Первая буква имени для кружка-заглушки. */
    private static String initialOf(String name) {
        return (name == null || name.trim().isEmpty())
                ? "?" : name.trim().substring(0, 1).toUpperCase();
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void updateMessages(List<Message> newMessages) {
        if (newMessages == null) {
            newMessages = new ArrayList<>();
        }
        Log.d(TAG, "Updating messages: oldSize=" + messages.size() + ", newSize=" + newMessages.size());

        // Сохраняем переводы и транскрипции из старого списка
        Map<Long, String> translationMap = new HashMap<>();
        Map<Long, String> transcriptionMap = new HashMap<>();
        for (Message oldMessage : messages) {
            if (oldMessage.getTranslatedContent() != null && !oldMessage.getTranslatedContent().isEmpty()) {
                translationMap.put(oldMessage.getId(), oldMessage.getTranslatedContent());
            }
            if (oldMessage.getTranscribedContent() != null && !oldMessage.getTranscribedContent().isEmpty()) {
                transcriptionMap.put(oldMessage.getId(), oldMessage.getTranscribedContent());
            }
        }

        // Обновляем новый список, сохраняя переводы и транскрипции, и устанавливаем showOriginal = false для аудиосообщений
        for (Message newMessage : newMessages) {
            if (translationMap.containsKey(newMessage.getId())) {
                newMessage.setTranslatedContent(translationMap.get(newMessage.getId()));
            }
            if (transcriptionMap.containsKey(newMessage.getId())) {
                newMessage.setTranscribedContent(transcriptionMap.get(newMessage.getId()));
            }
            // Устанавливаем showOriginal = false только для аудиосообщений с переводом
            if ("audio".equalsIgnoreCase(newMessage.getMessageType()) &&
                    newMessage.getTranslatedContent() != null && !newMessage.getTranslatedContent().isEmpty()) {
                newMessage.setShowOriginal(false);
                Log.d(TAG, "Set showOriginal=false for audio message id=" + newMessage.getId());
            }
        }

        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new MessageDiffCallback(messages, newMessages));
        messages.clear();
        messages.addAll(newMessages);
        diffResult.dispatchUpdatesTo(this);
        Log.d(TAG, "Messages updated with DiffUtil, dispatched changes");
    }

    // Метод для обновления статуса пользователя
    public void updateUserStatus(long userId, boolean online) {
        //Log.d(TAG, "Updating user status: userId=" + userId + ", online=" + online);
        userStatuses.put(userId, online);
        //Log.d(TAG, "Current userStatuses: " + userStatuses.toString());
        int updatedCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getSender() != null && messages.get(i).getSender().getId() == userId) {
                //Log.d(TAG, "Notifying item changed at position=" + i + " for userId=" + userId + ", messageId=" + messages.get(i).getId());
                notifyItemChanged(i);
                updatedCount++;
            }
        }
        Log.d(TAG, "Updated status for userId=" + userId + ", online=" + online + ", updated items=" + updatedCount);
    }

    public void playTTS(String text, String language, long messageId, boolean isTranslated) {
        // Останавливаем текущее воспроизведение, если оно есть
        stopCurrentTTS();

        // Проверяем, пытаемся ли воспроизвести то же сообщение
        if (currentPlayingMessageId == messageId && isPlayingTranslated == isTranslated) {
            Log.d(TAG, "TTS already playing for message id=" + messageId + ", translated=" + isTranslated);
            return;
        }

        ApiService apiService = ApiClient.getApiService();
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("text", text);
            requestBody.put("language", language);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating TTS request JSON: " + e.getMessage());
            Toast.makeText(context, "Failed to prepare TTS request", Toast.LENGTH_SHORT).show();
            return;
        }

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), requestBody.toString());
        apiService.generateTTS(body).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String jsonResponse = response.body().string();
                        JSONObject json = new JSONObject(jsonResponse);
                        String audioBase64 = json.optString("audio", null);
                        if (audioBase64 != null && !audioBase64.isEmpty()) {
                            byte[] audioData = Base64.decode(audioBase64, Base64.DEFAULT);
                            File tempFile = File.createTempFile("tts_" + messageId, ".mp3", context.getCacheDir());
                            FileOutputStream fos = new FileOutputStream(tempFile);
                            fos.write(audioData);
                            fos.close();

                            currentMediaPlayer = new MediaPlayer();
                            currentMediaPlayer.setDataSource(tempFile.getAbsolutePath());
                            currentMediaPlayer.setOnPreparedListener(mp -> {
                                currentMediaPlayer.start();
                                currentPlayingMessageId = messageId;
                                isPlayingTranslated = isTranslated;
                                Log.d(TAG, "TTS started for message id=" + messageId + ", translated=" + isTranslated);
                            });
                            currentMediaPlayer.setOnCompletionListener(mp -> {
                                stopCurrentTTS();
                                if (tempFile.exists()) {
                                    tempFile.delete();
                                }
                                Log.d(TAG, "TTS playback completed for message id=" + messageId);
                            });
                            currentMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                                Log.e(TAG, "TTS playback error for message id=" + messageId + ": what=" + what + ", extra=" + extra);
                                Toast.makeText(context, "Failed to play TTS audio", Toast.LENGTH_SHORT).show();
                                stopCurrentTTS();
                                if (tempFile.exists()) {
                                    tempFile.delete();
                                }
                                return true;
                            });
                            currentMediaPlayer.prepareAsync();
                        } else {
                            Log.e(TAG, "No audio data in TTS response for message id=" + messageId);
                            Toast.makeText(context, "Failed to generate TTS audio", Toast.LENGTH_SHORT).show();
                        }
                    } catch (IOException | JSONException e) {
                        Log.e(TAG, "Error processing TTS response for message id=" + messageId + ": " + e.getMessage());
                        Toast.makeText(context, "Failed to process TTS audio", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Log.e(TAG, "TTS API request failed for message id=" + messageId + ": code=" + response.code());
                    Toast.makeText(context, "Failed to generate TTS audio", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Log.e(TAG, "TTS request failed for message id=" + messageId + ": " + t.getMessage());
                Toast.makeText(context, "Failed to generate TTS audio", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void stopCurrentTTS() {
        if (currentMediaPlayer != null) {
            try {
                if (currentMediaPlayer.isPlaying()) {
                    currentMediaPlayer.stop();
                }
                currentMediaPlayer.reset();
                currentMediaPlayer.release();
                Log.d(TAG, "TTS stopped for message id=" + currentPlayingMessageId);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping MediaPlayer: " + e.getMessage());
            }
            currentMediaPlayer = null;
            currentPlayingMessageId = -1;
            isPlayingTranslated = false;
        }
    }

    public class MessageViewHolder extends RecyclerView.ViewHolder {
        ImageView avatarImageView;
        View avatarPlaceholder, statusIndicator;
        MaterialCardView messageContainer;
        TextView contentTextView, timestampTextView;
        TextView daySeparator;
        TextView attachmentUnavailable;
        View fileLinkContainer;
        LinearLayout indicatorsContainer;
        TextView translatedContentTextView;
        ImageView deliveryIndicator, readIndicator;
        LinearLayout audioContainer;
        ImageView playPauseButton;
        ImageView translateIcon;
        TextView audioDurationTextView;
        // Добавлено: SeekBar и TextView для текущего времени
        SeekBar audioSeekBar;
        TextView audioCurrentTimeTextView;
        ImageView ttsIcon; // Иконка TTS для оригинального контента
        private Handler seekBarHandler = new Handler(); // Handler для обновления прогресса SeekBar
        private Runnable seekBarUpdater;
        private int currentPosition = 0; // Переменная для хранения позиции паузы
        private boolean isPrepared = false; // Флаг для отслеживания состояния подготовки MediaPlayer
        ImageView ttsTranslatedIcon; // Иконка TTS для переведенного контента
        ImageView transcriptionIcon; // Поле для иконки транскрипции
        TextView transcribedContentTextView; // ДОБАВЛЕНО: Поле для текста транскрипции
        ImageView translateIconAudio;
        ImageView ttsIconAudio;
        ImageView ttsTranslatedIconAudio;
        ImageView imagePreview; // *** ДОБАВЛЕНО: Превью изображения для файлов в личном чате ***
        // ДОБАВЛЕНО: Превью ссылок
        CardView linkPreviewCard;
        ImageView previewImage;
        TextView previewTitle;
        TextView previewDescription;
        TextView previewUrl;
        private long boundMessageId = -1;  // ID текущего привязанного сообщения

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            avatarImageView = itemView.findViewById(R.id.avatar_image_view);
            avatarPlaceholder = itemView.findViewById(R.id.avatar_placeholder);
            messageContainer = itemView.findViewById(R.id.message_container);
            contentTextView = itemView.findViewById(R.id.content_text_view);
            fileLinkContainer = itemView.findViewById(R.id.file_link_container);
            timestampTextView = itemView.findViewById(R.id.timestamp_text_view);
            attachmentUnavailable = itemView.findViewById(R.id.attachment_unavailable);
            daySeparator = itemView.findViewById(R.id.day_separator);
            indicatorsContainer = itemView.findViewById(R.id.indicators_container);
            deliveryIndicator = itemView.findViewById(R.id.delivery_indicator);
            readIndicator = itemView.findViewById(R.id.read_indicator);
            statusIndicator = itemView.findViewById(R.id.status_indicator);
            translatedContentTextView = itemView.findViewById(R.id.translated_content_text_view);
            audioContainer = itemView.findViewById(R.id.audio_container);
            playPauseButton = itemView.findViewById(R.id.play_pause_button);
            audioDurationTextView = itemView.findViewById(R.id.audio_duration_text_view);
            audioSeekBar = itemView.findViewById(R.id.audio_seek_bar);
            audioCurrentTimeTextView = itemView.findViewById(R.id.audio_current_time_text_view);
            translateIcon = itemView.findViewById(R.id.translate_icon);
            // Инициализация иконок TTS
            ttsIcon = itemView.findViewById(R.id.tts_icon);
            ttsTranslatedIcon = itemView.findViewById(R.id.tts_translated_icon);
            // Инициализация иконки и текста транскрипции
            transcriptionIcon = itemView.findViewById(R.id.transcription_icon);
            transcribedContentTextView = itemView.findViewById(R.id.transcribed_content_text_view);
            // **ДОБАВЛЕНО**: Инициализация новых иконок для аудиосообщений
            translateIconAudio = itemView.findViewById(R.id.translate_icon_audio);
            ttsIconAudio = itemView.findViewById(R.id.tts_icon_audio);
            ttsTranslatedIconAudio = itemView.findViewById(R.id.tts_translated_icon_audio);
            imagePreview = itemView.findViewById(R.id.image_preview); // *** ДОБАВЛЕНО: Превью изображения ***
            // ДОБАВЛЕНО: Превью ссылок
            linkPreviewCard = itemView.findViewById(R.id.link_preview_card);
            previewImage = itemView.findViewById(R.id.preview_image);
            previewTitle = itemView.findViewById(R.id.preview_title);
            previewDescription = itemView.findViewById(R.id.preview_description);
            previewUrl = itemView.findViewById(R.id.preview_url);

            // Обработчик клика для переключения перевода
            messageContainer.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    Message message = messages.get(position);
                    if (message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) {
                        message.setShowOriginal(!message.isShowOriginal());
                        if ("audio".equalsIgnoreCase(message.getMessageType()) && isTranscriptionEnabled && message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty()) {
                            // Для аудиосообщений переключаем между транскрипцией и переводом
                            contentTextView.setText(message.isShowOriginal() ? message.getTranscribedContent() : message.getTranslatedContent());
                            translatedContentTextView.setVisibility(View.GONE); // Скрываем перевод внизу
                            Log.d(TAG, "Toggled audio message id=" + message.getId() + ", showOriginal=" + message.isShowOriginal() + ", text=" + contentTextView.getText());
                        } else {
                            // Для текстовых сообщений переключаем между оригиналом и переводом
                            contentTextView.setText(message.isShowOriginal() ? message.getContent() : message.getTranslatedContent());
                            Log.d(TAG, "Toggled text message id=" + message.getId() + ", showOriginal=" + message.isShowOriginal() + ", text=" + contentTextView.getText());
                        }
                        notifyItemChanged(position);
                    } else {
                        Log.d(TAG, "Translation toggle skipped for message id=" + message.getId() +
                                ", hasTranslation=" + (message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) +
                                ", isAudio=" + "audio".equalsIgnoreCase(message.getMessageType()));
                    }
                }
            });

            // Обработчик долгого клика
            messageContainer.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    Message message = messages.get(position);
                    boolean mine = message.getSender().getId() == currentUserId;
                    {

                        ListPopupWindow listPopupWindow = new ListPopupWindow(context);
                        listPopupWindow.setAnchorView(v);
                        listPopupWindow.setModal(true);
                        listPopupWindow.setDropDownGravity(Gravity.END);
                        listPopupWindow.setWidth(520);
                        listPopupWindow.setVerticalOffset(8);

                        listPopupWindow.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.popup_menu_background));

                        List<PopupMenuItem> menuItems = new ArrayList<>();
                        menuItems.add(new PopupMenuItem("Copy", null, Color.parseColor("#E2E8F0")));
                        if (mine) {
                            menuItems.add(new PopupMenuItem("Edit", null, Color.parseColor("#E2E8F0")));
                            menuItems.add(new PopupMenuItem("Delete", null, Color.parseColor("#FF6B6B")));
                        }

                        PopupMenuAdapter adapter = new PopupMenuAdapter(context, menuItems);
                        listPopupWindow.setAdapter(adapter);

                        listPopupWindow.setOnItemClickListener((parent, view, pos, id) -> {
                            String title = menuItems.get(pos).title;
                            if ("Copy".equals(title)) {
                                copyMessageText(context, contentTextView.getText().toString());
                            } else if ("Edit".equals(title)) {
                                actionListener.onEdit(message);
                            } else if ("Delete".equals(title)) {
                                actionListener.onDelete(message);
                            }
                            listPopupWindow.dismiss();
                        });

                        listPopupWindow.show();
                        return true;
                    }
                }
                return false;
            });
        }

        // Добавлено: Публичные методы для обновления UI
        public void setTranslatedContentText(String text) {
            int position = getAdapterPosition();
            if (position != RecyclerView.NO_POSITION) {
                Message message = messages.get(position);
                if ("audio".equalsIgnoreCase(message.getMessageType())) {
                    // Для аудиосообщений: устанавливаем перевод в contentTextView и скрываем translatedContentTextView
                    Log.w(TAG, "setTranslatedContentText called for audio message id=" + message.getId() + ", redirecting to contentTextView");
                    contentTextView.setText(text);
                    translatedContentTextView.setVisibility(View.GONE);
                    message.setShowOriginal(false);
                } else {
                    // Для текстовых сообщений: показываем перевод в translatedContentTextView
                    translatedContentTextView.setText(text);
                    translatedContentTextView.setVisibility(View.VISIBLE);
                    Log.d(TAG, "setTranslatedContentText for non-audio message id=" + message.getId() + ": text=" + text + ", translatedContentTextView=VISIBLE");
                }
            } else {
                Log.w(TAG, "setTranslatedContentText called with invalid position");
            }
        }

        public void setContentText(String text) {
            contentTextView.setText(text);
            contentTextView.setVisibility(View.VISIBLE);
            Log.d(TAG, "Set content text: " + text);
        }

        public void setTranslateIconVisibility(int visibility) {
            translateIcon.setVisibility(visibility);
            Log.d(TAG, "Set translate icon visibility: " + (visibility == View.VISIBLE ? "VISIBLE" : "GONE"));
        }

        public void hideTranslatedContentTextView() {
            translatedContentTextView.setVisibility(View.GONE);
            Log.d(TAG, "hideTranslatedContentTextView called for message id=" +
                    (getAdapterPosition() != RecyclerView.NO_POSITION ? messages.get(getAdapterPosition()).getId() : "unknown"));
        }

        public void bind(Message message, int position) {
            bindDaySeparator(position); // дата дня над первым сообщением дня
            itemView.post(this::applyFooterLayout); // время рядом с текстом, когда влезает
            User sender = message.getSender();
            boolean isSentByCurrentUser = sender.getId() == currentUserId;

            Log.d(TAG, "Binding message id=" + message.getId() + ", fileUrl=" + message.getFileUrl() +
                    ", delivered=" + message.isDelivered() + ", read=" + message.isRead() +
                    ", senderId=" + sender.getId() + ", messageType=" + message.getMessageType() +
                    ", isTranslationEnabled=" + isTranslationEnabled + ", isTTSEnabled=" + isTTSEnabled +
                    ", isTranscriptionEnabled=" + isTranscriptionEnabled);

            boundMessageId = message.getId();  // Запоминаем ID текущего сообщения

            // ЯВНО скрываем image_preview в начале bind — чтобы старое превью не оставалось
            imagePreview.setVisibility(View.GONE);
            imagePreview.setOnClickListener(null); // убираем старый обработчик

            // Установка выравнивания и фона
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) messageContainer.getLayoutParams();

            // Вычисляем ширину экрана и отступы
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            float density = context.getResources().getDisplayMetrics().density;
            int marginHorizontal = (int) (8 * density); // layout_marginHorizontal="8dp"
            int padding = (int) (12 * density); // padding="12dp" в LinearLayout
            int avatarWidth = (int) (32 * density); // Ширина аватара
            int marginStart = (int) (9 * density); // Увеличиваем до 12dp
            int visualMargin = (int) (56 * density); // Отступ 56dp для визуального различия

            if (isSentByCurrentUser) {
                // Отправленные сообщения: выравнивание по правому краю
                params.startToStart = ConstraintLayout.LayoutParams.UNSET;
                params.startToEnd = ConstraintLayout.LayoutParams.UNSET;
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                params.rightMargin = marginHorizontal; // Отступ 8dp от правого края
                params.leftMargin = visualMargin; // Отступ 56dp от левого края
                params.constrainedWidth = true; // Включаем ограничение ширины
                // Ограничиваем ширину
                int maxWidthPx = screenWidth - 2 * marginHorizontal - 2 * padding - marginHorizontal - visualMargin;
                params.matchConstraintMaxWidth = maxWidthPx;
                messageContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.sent_message_background));
                // Проверяем, является ли сообщение первым в серии своих сообщений
                boolean showSquareCorner = position == 0 || messages.get(position - 1).getSender().getId() != sender.getId();
                if (showSquareCorner) {
                    // Квадратный верхний правый угол для первого сообщения в серии
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setTopRightCorner(CornerFamily.ROUNDED, 0f)
                            .setTopLeftCorner(CornerFamily.ROUNDED, 12f * density)
                            .setBottomLeftCorner(CornerFamily.ROUNDED, 12f * density)
                            .setBottomRightCorner(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                    Log.d(TAG, "Sent message with square top-right corner at position " + position + ": set top-right=0dp, others=16dp");
                } else {
                    // Скругленные углы для последующих сообщений в серии
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setAllCorners(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                    Log.d(TAG, "Sent message with rounded corners at position " + position + ": set corner radius to 16dp");
                }
                avatarImageView.setVisibility(View.GONE);
                avatarPlaceholder.setVisibility(View.GONE);
                Log.d(TAG, "Sent message at position " + position + ": maxWidth=" + maxWidthPx + ", leftMargin=" + visualMargin + ", rightMargin=" + marginHorizontal);
            } else {
                // Полученные сообщения: выравнивание по аватару
                params.startToStart = ConstraintLayout.LayoutParams.UNSET;
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET;
                params.rightMargin = marginHorizontal; // Добавляем отступ от правого края (8dp)
                params.leftMargin = 0; // Сбрасываем leftMargin
                params.setMarginStart(marginStart); // Явно задаем marginStart = 12dp
                params.constrainedWidth = true;
                // Ограничиваем максимальную ширину, учитывая marginStart
                int maxWidthPx = screenWidth - marginHorizontal - avatarWidth - marginStart - visualMargin - 2 * padding;
                params.matchConstraintMaxWidth = maxWidthPx;

                // Показ аватара только для первого сообщения в серии
                boolean showAvatar = position == 0 || messages.get(position - 1).getSender().getId() != sender.getId();
                if (showAvatar) {
                    params.startToEnd = R.id.avatar_image_view;
                    avatarImageView.setVisibility(View.VISIBLE);
                    avatarPlaceholder.setVisibility(View.GONE);
                    // Квадратный верхний левый угол
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setTopLeftCorner(CornerFamily.ROUNDED, 0f)
                            .setTopRightCorner(CornerFamily.ROUNDED, 12f * density)
                            .setBottomLeftCorner(CornerFamily.ROUNDED, 12f * density)
                            .setBottomRightCorner(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                    Log.d(TAG, "Received message with avatar at position " + position + ": set top-left=0dp, others=16dp, marginStart=" + marginStart);
                } else {
                    params.startToEnd = R.id.avatar_placeholder;
                    avatarImageView.setVisibility(View.GONE);
                    avatarPlaceholder.setVisibility(View.VISIBLE);
                    // Скругленные углы
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setAllCorners(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                    Log.d(TAG, "Received message without avatar at position " + position + ": set corner radius to 16dp, marginStart=" + marginStart);
                }
                messageContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.received_message_background));
                Log.d(TAG, "Received message at position " + position + ": maxWidth=" + maxWidthPx + ", marginStart=" + marginStart);
            }
            messageContainer.setLayoutParams(params);

            // Логирование фактической ширины и положения после рендеринга
            messageContainer.post(() -> {
                int leftEdgeDistance = isSentByCurrentUser ?
                        messageContainer.getLeft() - visualMargin :
                        messageContainer.getLeft() - (marginHorizontal + avatarWidth + params.getMarginStart());
                int rightEdgeDistance = isSentByCurrentUser ?
                        (screenWidth - (messageContainer.getRight() + marginHorizontal)) :
                        (screenWidth - messageContainer.getRight() - visualMargin);
                Log.d(TAG, "Message at position " + position + ": actualWidth=" + messageContainer.getWidth() +
                        ", maxWidth=" + params.matchConstraintMaxWidth +
                        ", marginStart=" + params.getMarginStart() +
                        ", leftEdgeDistance=" + leftEdgeDistance + "px (should be ~0px for received)" +
                        ", rightEdgeDistance=" + rightEdgeDistance + "px (should be ~0px for sent or long received)" +
                        ", textContent=" + contentTextView.getText());
            });

            // Настройка аватара
            if (!isSentByCurrentUser && avatarImageView.getVisibility() == View.VISIBLE) {
                String avatarUrl = sender.getAvatar();
                if (!TextUtils.isEmpty(avatarUrl)) {
                    Glide.with(context)
                            .load("https://unlimitedpossibilities12.org" + avatarUrl)
                            .apply(new RequestOptions()
                                    .circleCrop()
                                    // Файл аватара мог пропасть с сервера — тогда
                                    // кружок с буквой, а не безликий серый
                                    .placeholder(new LetterAvatarDrawable(initialOf(sender.getUsername())))
                                    .error(new LetterAvatarDrawable(initialOf(sender.getUsername()))))
                            .into(avatarImageView);
                } else {
                    String initial = TextUtils.isEmpty(sender.getUsername()) ? "?" : sender.getUsername().substring(0, 1).toUpperCase();
                    LetterAvatarDrawable avatarDrawable = new LetterAvatarDrawable(initial);
                    avatarImageView.setImageDrawable(avatarDrawable);
                }

                // Управление индикатором статуса
                Long senderId = (long) sender.getId();
                Boolean isOnline = userStatuses.get(senderId);
                boolean isVisible = isOnline != null && isOnline;
                statusIndicator.setVisibility(isVisible ? View.VISIBLE : View.GONE);
                Log.d(TAG, "Setting status indicator for userId=" + senderId + ", isOnline=" + isOnline +
                        ", visibility=" + (isVisible ? "VISIBLE" : "GONE") + ", messageId=" + message.getId());

                avatarImageView.setOnClickListener(v -> {
                    if (sender.getId() != -1 && context instanceof Activity && !((Activity) context).isFinishing()) {
                        Intent intent = new Intent(context, UserProfileActivity.class);
                        intent.putExtra("userId", sender.getId());
                        context.startActivity(intent);
                        Log.d(TAG, "Avatar clicked: Opening UserProfileActivity for userId=" + sender.getId());
                    } else {
                        Toast.makeText(context, "User profile not available", Toast.LENGTH_SHORT).show();
                        Log.w(TAG, "Avatar click ignored: Invalid senderId=" + sender.getId() + " or activity is finishing");
                    }
                });
            } else {
                avatarImageView.setOnClickListener(null);
            }

            // Обработка аудиосообщений и файлов
            String fileUrl = message.getFileUrl();
            String messageType = message.getMessageType();
            if ("audio".equalsIgnoreCase(messageType) &&
                    messageType != null && !messageType.trim().isEmpty() &&
                    fileUrl != null && !fileUrl.trim().isEmpty() && !fileUrl.equalsIgnoreCase("null")) {
                Log.d("MessageAdapter", "Showing audioContainer for message id=" + message.getId());
                fileLinkContainer.setVisibility(View.GONE);
                audioContainer.setVisibility(View.VISIBLE);
                audioContainer.post(() -> {
                    Log.d("MessageAdapter", "audioContainer visibility for message id=" + message.getId() + ": " + (audioContainer.getVisibility() == View.VISIBLE ? "VISIBLE" : "GONE"));
                });
                playPauseButton.setImageResource(R.drawable.ic_play);
                audioCurrentTimeTextView.setText("0:00");
                audioDurationTextView.setText("/ 0:00");
                audioSeekBar.setProgress(0);
                audioSeekBar.setEnabled(false);

                // Асинхронное получение длительности аудиофайла
                MediaPlayer durationPlayer = new MediaPlayer();
                try {
                    durationPlayer.setDataSource(fileUrl);
                    durationPlayer.setOnPreparedListener(mp -> {
                        int duration = mp.getDuration();
                        int durationSec = duration / 1000;
                        audioDurationTextView.setText("/ " + String.format("%d:%02d", durationSec / 60, durationSec % 60));
                        audioSeekBar.setMax(duration);
                        mp.release();
                        Log.d(TAG, "Duration fetched for message id=" + message.getId() + ": " + duration + "ms");
                    });
                    durationPlayer.setOnErrorListener((mp, what, extra) -> {
                        Log.e(TAG, "Error fetching duration for message id=" + message.getId() + ": what=" + what + ", extra=" + extra);
                        audioDurationTextView.setText("/ 0:00");
                        mp.release();
                        return true;
                    });
                    durationPlayer.prepareAsync();
                } catch (IOException e) {
                    Log.e(TAG, "Error setting data source for duration of message id=" + message.getId() + ": " + e.getMessage());
                    audioDurationTextView.setText("/ 0:00");
                    durationPlayer.release();
                }

                // Обработчик кнопки Play/Pause
                playPauseButton.setColorFilter(ContextCompat.getColor(context, R.color.sent_message_accent));
                playPauseButton.setOnClickListener(v -> {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        // Сохраняем текущую позицию при паузе
                        currentPosition = mediaPlayer.getCurrentPosition();
                        mediaPlayer.pause();
                        playPauseButton.setImageResource(R.drawable.ic_play);
                        seekBarHandler.removeCallbacks(seekBarUpdater);
                        Log.d(TAG, "Audio paused for message id=" + message.getId() + " at position=" + currentPosition);
                    } else {
                        // Проверяем, существует ли mediaPlayer и подготовлен ли он
                        if (mediaPlayer != null && isPrepared && !mediaPlayer.isPlaying()) {
                            // Возобновляем воспроизведение с сохранённой позиции
                            mediaPlayer.seekTo(currentPosition);
                            mediaPlayer.start();
                            playPauseButton.setImageResource(R.drawable.ic_pause);
                            seekBarHandler.post(seekBarUpdater);
                            Log.d(TAG, "Audio resumed for message id=" + message.getId() + " from position=" + currentPosition);
                        } else {
                            // Создаём новый MediaPlayer, если он не существует или не подготовлен
                            if (mediaPlayer != null) {
                                mediaPlayer.release();
                                mediaPlayer = null;
                            }
                            mediaPlayer = new MediaPlayer();
                            isPrepared = false; // Сбрасываем флаг подготовки
                            try {
                                mediaPlayer.setDataSource(fileUrl);
                                mediaPlayer.setOnPreparedListener(mp -> {
                                    isPrepared = true; // Устанавливаем флаг подготовки
                                    mp.seekTo(currentPosition);
                                    mp.start();
                                    playPauseButton.setImageResource(R.drawable.ic_pause);
                                    int duration = mp.getDuration();
                                    audioSeekBar.setMax(duration);
                                    audioSeekBar.setEnabled(true);
                                    int durationSec = duration / 1000;
                                    if (audioDurationTextView.getText().toString().equals("/ 0:00")) {
                                        audioDurationTextView.setText("/ " + String.format("%d:%02d", durationSec / 60, durationSec % 60));
                                    }
                                    // Обновляем SeekBar и текущее время
                                    seekBarUpdater = new Runnable() {
                                        @Override
                                        public void run() {
                                            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                                                int currentPosition = mediaPlayer.getCurrentPosition();
                                                audioSeekBar.setProgress(currentPosition);
                                                int currentSec = currentPosition / 1000;
                                                audioCurrentTimeTextView.setText(String.format("%d:%02d", currentSec / 60, currentSec % 60));
                                                seekBarHandler.postDelayed(this, 100);
                                            }
                                        }
                                    };
                                    seekBarHandler.post(seekBarUpdater);
                                    Log.d(TAG, "Audio started for message id=" + message.getId() + ", duration=" + duration + ", starting from=" + currentPosition);
                                });
                                mediaPlayer.setOnCompletionListener(mp -> {
                                    playPauseButton.setImageResource(R.drawable.ic_play);
                                    audioSeekBar.setProgress(0);
                                    audioCurrentTimeTextView.setText("0:00");
                                    currentPosition = 0;
                                    isPrepared = false; // Сбрасываем флаг подготовки
                                    seekBarHandler.removeCallbacks(seekBarUpdater);
                                    mp.reset();
                                    Log.d(TAG, "Audio completed for message id=" + message.getId());
                                });
                                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                                    Log.e(TAG, "MediaPlayer error for message id=" + message.getId() + ": what=" + what + ", extra=" + extra);
                                    Toast.makeText(context, "Error playing audio", Toast.LENGTH_SHORT).show();
                                    playPauseButton.setImageResource(R.drawable.ic_play);
                                    audioSeekBar.setProgress(0);
                                    audioCurrentTimeTextView.setText("0:00");
                                    currentPosition = 0;
                                    isPrepared = false; // Сбрасываем флаг подготовки
                                    seekBarHandler.removeCallbacks(seekBarUpdater);
                                    mp.reset();
                                    return true;
                                });
                                mediaPlayer.prepareAsync();
                            } catch (IOException e) {
                                Log.e("MessageAdapter", "Error playing audio for message id=" + message.getId() + ": " + e.getMessage());
                                Toast.makeText(context, "Cannot play audio", Toast.LENGTH_SHORT).show();
                                playPauseButton.setImageResource(R.drawable.ic_play);
                                audioSeekBar.setProgress(0);
                                audioCurrentTimeTextView.setText("0:00");
                                currentPosition = 0;
                                isPrepared = false; // Сбрасываем флаг подготовки
                            }
                        }
                    }
                });

                // Обработчик SeekBar для перемотки
                audioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser && mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.seekTo(progress);
                            int currentSec = progress / 1000;
                            audioCurrentTimeTextView.setText(String.format("%d:%02d", currentSec / 60, currentSec % 60));
                            Log.d(TAG, "SeekBar changed by user for message id=" + message.getId() + ", progress=" + progress);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.pause();
                            playPauseButton.setImageResource(R.drawable.ic_play);
                            seekBarHandler.removeCallbacks(seekBarUpdater);
                            Log.d(TAG, "SeekBar touch started for message id=" + message.getId());
                        }
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        if (mediaPlayer != null) {
                            mediaPlayer.seekTo(seekBar.getProgress());
                            mediaPlayer.start();
                            playPauseButton.setImageResource(R.drawable.ic_pause);
                            seekBarHandler.post(seekBarUpdater);
                            Log.d(TAG, "SeekBar touch stopped for message id=" + message.getId() + ", seekTo=" + seekBar.getProgress());
                        }
                    }
                });
                audioSeekBar.setProgressBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.seekbar_background) // цвет пустой части
                ));

                // **ДОБАВЛЕНО/ИЗМЕНЕНО**: Обработка иконок в аудиосообщениях
                if (transcriptionIcon != null && translateIconAudio != null && ttsIconAudio != null && ttsTranslatedIconAudio != null) {
                    boolean showTranscriptionIcon = isTranscriptionEnabled && "audio".equalsIgnoreCase(messageType);
                    boolean hasTranscription = message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty();
                    SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
                    long chatId = message.getChat() != null ? message.getChat().getId() : -1;
                    boolean isTranslationEnabledLocal = prefs.getBoolean("translation_enabled_" + chatId, false);
                    boolean isTTSEnabledLocal = prefs.getBoolean("tts_enabled_" + chatId, false);
                    String translationLanguage = prefs.getString("translation_language_" + chatId, "auto");
                    String translationModeStr = prefs.getString("translation_mode_" + chatId, "ALL");
                    // **ИЗМЕНЕНО**: Финальная инициализация translationMode с использованием временной переменной
                    final TranslationSettingsFragment.TranslationMode translationMode;
                    TranslationSettingsFragment.TranslationMode tempTranslationMode;
                    try {
                        tempTranslationMode = TranslationSettingsFragment.TranslationMode.valueOf(translationModeStr);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Invalid translation mode for message id=" + message.getId() + ": " + translationModeStr + ", defaulting to ALL");
                        tempTranslationMode = TranslationSettingsFragment.TranslationMode.ALL;
                    }
                    translationMode = tempTranslationMode;

                    // Условия показа иконок
                    boolean showTranslateIconAudio = showTranscriptionIcon && isTranslationEnabledLocal && hasTranscription;
                    boolean showTTSIconAudio = showTranscriptionIcon && isTTSEnabledLocal && hasTranscription;
                    boolean showTTSTranslatedIconAudio = showTranscriptionIcon && isTTSEnabledLocal && isTranslationEnabledLocal &&
                            hasTranscription && (translationMode == TranslationSettingsFragment.TranslationMode.ALL ||
                            translationMode == TranslationSettingsFragment.TranslationMode.NEW_ONLY ||
                            (translationMode == TranslationSettingsFragment.TranslationMode.SELECTIVELY &&
                                    message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()));

                    // Установка видимости иконок
                    transcriptionIcon.setVisibility(showTranscriptionIcon ? View.VISIBLE : View.GONE);
                    translateIconAudio.setVisibility(showTranslateIconAudio ? View.VISIBLE : View.GONE);
                    ttsIconAudio.setVisibility(showTTSIconAudio ? View.VISIBLE : View.GONE);
                    ttsTranslatedIconAudio.setVisibility(showTTSTranslatedIconAudio ? View.VISIBLE : View.GONE);

                    Log.d(TAG, "Message id=" + message.getId() + ": transcriptionIcon=" + (showTranscriptionIcon ? "VISIBLE" : "GONE") +
                            ", translateIconAudio=" + (showTranslateIconAudio ? "VISIBLE" : "GONE") +
                            ", ttsIconAudio=" + (showTTSIconAudio ? "VISIBLE" : "GONE") +
                            ", ttsTranslatedIconAudio=" + (showTTSTranslatedIconAudio ? "VISIBLE" : "GONE"));

                    // Обработчик для иконки транскрипции
                    if (showTranscriptionIcon) {
                        transcriptionIcon.setOnClickListener(v -> {
                            Log.d(TAG, "Transcription icon clicked for message id=" + message.getId());
                            if (context instanceof PrivateChatActivity) {
                                ApiService apiService = ApiClient.getApiService(); // Получаем ApiService
                                PrivateChatTranslationAndSpellCheckHelper.transcribeMessage(
                                        context,
                                        message,
                                        chatId,
                                        MessageAdapter.this,
                                        messages,
                                        apiService,
                                        () -> {
                                            if (position != RecyclerView.NO_POSITION) {
                                                if (message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty()) {
                                                    transcribedContentTextView.setText(message.getTranscribedContent());
                                                    transcribedContentTextView.setVisibility(View.VISIBLE);
                                                    if (isTranslationEnabled && translationMode != TranslationSettingsFragment.TranslationMode.SELECTIVELY) {
                                                        message.setContent(message.getTranscribedContent());
                                                        PrivateChatTranslationAndSpellCheckHelper.translateMessage(
                                                                context,
                                                                message,
                                                                translationLanguage,
                                                                chatId,
                                                                MessageAdapter.this,
                                                                messages,
                                                                new ArrayList<>(),
                                                                () -> {
                                                                    Log.d(TAG, "Translation completed for transcribed message id=" + message.getId());
                                                                    notifyItemChanged(position);
                                                                }
                                                        );
                                                    }
                                                    notifyItemChanged(position);
                                                    Log.d(TAG, "Transcription completed for message id=" + message.getId() + " at position=" + position);
                                                } else {
                                                    transcribedContentTextView.setVisibility(View.GONE);
                                                    Log.w(TAG, "No transcription available for message id=" + message.getId());
                                                }
                                            } else {
                                                Log.w(TAG, "Cannot notify item changed: invalid position for message id=" + message.getId());
                                            }
                                        }
                                );
                            } else {
                                Log.e(TAG, "Context is not PrivateChatActivity for message id=" + message.getId());
                                Toast.makeText(context, "Transcription not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } else {
                        transcriptionIcon.setOnClickListener(null);
                    }

                    // Обработчик для иконки перевода
                    if (showTranslateIconAudio) {
                        translateIconAudio.setOnClickListener(v -> {
                            Log.d(TAG, "Translate icon clicked for audio message id=" + message.getId());
                            List<Message> translatedMessages = new ArrayList<>();
                            PrivateChatTranslationAndSpellCheckHelper.translateMessage(
                                    context,
                                    message,
                                    translationLanguage,
                                    chatId,
                                    MessageAdapter.this,
                                    messages,
                                    translatedMessages,
                                    () -> {
                                        if (position != RecyclerView.NO_POSITION) {
                                            notifyItemChanged(position);
                                            Log.d(TAG, "Translation completed for audio message id=" + message.getId() + " at position=" + position);
                                        } else {
                                            Log.w(TAG, "Cannot notify item changed: invalid position for message id=" + message.getId());
                                        }
                                    }
                            );
                        });
                    } else {
                        translateIconAudio.setOnClickListener(null);
                    }

                    // Обработчик для иконки TTS оригинала
                    if (showTTSIconAudio) {
                        ttsIconAudio.setOnClickListener(v -> {
                            if (message.getTranscribedContent() == null || message.getTranscribedContent().isEmpty()) {
                                Log.w(TAG, "No transcribed content for TTS: msgId=" + message.getId());
                                Toast.makeText(context, "No transcribed content for TTS", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            playTTS(message.getTranscribedContent(), "auto", message.getId(), false);
                        });
                    } else {
                        ttsIconAudio.setOnClickListener(null);
                    }

                    // Обработчик для иконки TTS переведенного
                    if (showTTSTranslatedIconAudio) {
                        ttsTranslatedIconAudio.setOnClickListener(v -> {
                            if (message.getTranslatedContent() == null || message.getTranslatedContent().isEmpty()) {
                                Log.w(TAG, "No translated content for TTS: msgId=" + message.getId());
                                Toast.makeText(context, "No translated content available for TTS", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            playTTS(message.getTranslatedContent(), translationLanguage, message.getId(), true);
                        });
                    } else {
                        ttsTranslatedIconAudio.setOnClickListener(null);
                    }
                } else {
                    Log.e(TAG, "One or more audio icons are null for message id=" + message.getId() +
                            ": transcriptionIcon=" + (transcriptionIcon == null ? "null" : "not null") +
                            ", translateIconAudio=" + (translateIconAudio == null ? "null" : "not null") +
                            ", ttsIconAudio=" + (ttsIconAudio == null ? "null" : "not null") +
                            ", ttsTranslatedIconAudio=" + (ttsTranslatedIconAudio == null ? "null" : "not null"));
                } // Конец добавления для иконок в аудиосообщениях

                // Добавлено: Отображение текста транскрипции
                if (transcribedContentTextView != null) {
                    if (isTranscriptionEnabled && message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty()) {
                        transcribedContentTextView.setText(message.getTranscribedContent());
                        transcribedContentTextView.setVisibility(View.VISIBLE);
                        Log.d(TAG, "Message id=" + message.getId() + ": showing transcribed content=" + message.getTranscribedContent());
                    } else {
                        transcribedContentTextView.setVisibility(View.GONE);
                        Log.d(TAG, "Message id=" + message.getId() + ": hiding transcribed content");
                    }
                } else {
                    Log.e(TAG, "transcribedContentTextView is null for message id=" + message.getId());
                }
            } else if (fileUrl != null && !fileUrl.trim().isEmpty() && !fileUrl.equalsIgnoreCase("null")) {

                String lowerFileUrl = fileUrl.toLowerCase();

                if (lowerFileUrl.endsWith(".jpg") || lowerFileUrl.endsWith(".jpeg") ||
                        lowerFileUrl.endsWith(".png") || lowerFileUrl.endsWith(".gif")) {
                    // Изображение — превью
                    fileLinkContainer.setVisibility(View.GONE);
                    audioContainer.setVisibility(View.GONE);
                    imagePreview.setVisibility(View.VISIBLE);

                    // Файл мог пропасть с сервера: вместо красного знака во всю
                    // ширину показываем строку «Image unavailable»
                    attachmentUnavailable.setVisibility(View.GONE);
                    Glide.with(context)
                            .load(fileUrl)
                            .placeholder(R.drawable.bg_message_image_placeholder)
                            .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                @Override
                                public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e,
                                                            Object model,
                                                            com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                            boolean isFirstResource) {
                                    imagePreview.setVisibility(View.GONE);
                                    attachmentUnavailable.setVisibility(View.VISIBLE);
                                    return false;
                                }

                                @Override
                                public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                                               Object model,
                                                               com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                               com.bumptech.glide.load.DataSource dataSource,
                                                               boolean isFirstResource) {
                                    attachmentUnavailable.setVisibility(View.GONE);
                                    return false;
                                }
                            })
                            .into(imagePreview);

                    imagePreview.setOnClickListener(v -> {
                        Intent intent = new Intent(context, FullScreenMessageImageActivity.class);
                        intent.putExtra("message_image_url", fileUrl);
                        context.startActivity(intent);
                    });

                    Log.d(TAG, "Image preview shown for message id=" + message.getId());
                } else {
                    // Всё остальное (включая любые MP3, PDF и т.д.) — обычная ссылка
                    fileLinkContainer.setVisibility(View.VISIBLE);
                    audioContainer.setVisibility(View.GONE);
                    imagePreview.setVisibility(View.GONE);
                    fileLinkContainer.setOnClickListener(v -> downloadFile(fileUrl));

                    // Опционально: красивый текст для аудиофайлов
                    if (lowerFileUrl.endsWith(".mp3") || lowerFileUrl.endsWith(".m4a") ||
                            lowerFileUrl.endsWith(".wav") || lowerFileUrl.endsWith(".ogg") ||
                            lowerFileUrl.endsWith(".aac")) {
                    }
                    Log.d(TAG, "Regular file link shown for message id=" + message.getId());
                }
            } else {
                fileLinkContainer.setVisibility(View.GONE);
                audioContainer.setVisibility(View.GONE);
                imagePreview.setVisibility(View.GONE);
            }

            // Установка содержимого сообщения с учётом перевода и транскрипции
            if ("audio".equalsIgnoreCase(message.getMessageType())) {
                // Всегда скрываем translatedContentTextView для аудиосообщений
                translatedContentTextView.setVisibility(View.GONE);
                Log.d(TAG, "Message id=" + message.getId() + ": translatedContentTextView forced to GONE (audio)");
                if (isTranscriptionEnabled && message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty()) {
                    // Для аудиосообщений по умолчанию показываем перевод, если он есть
                    if (message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) {
                        contentTextView.setText(message.isShowOriginal() ? message.getTranscribedContent() : message.getTranslatedContent());
                        Log.d(TAG, "Message id=" + message.getId() + ": showing " + (message.isShowOriginal() ? "transcribed content=" + message.getTranscribedContent() : "translated content=" + message.getTranslatedContent()));
                    } else {
                        contentTextView.setText(message.getTranscribedContent()); // Показываем транскрипцию, если нет перевода
                        Log.d(TAG, "Message id=" + message.getId() + ": showing transcribed content=" + message.getTranscribedContent() + ", no translation available");
                    }
                    contentTextView.setVisibility(View.VISIBLE);
                    // Отображаем транскрипцию под проигрывателем
                    if (transcribedContentTextView != null) {
                        transcribedContentTextView.setText(message.getTranscribedContent());
                        transcribedContentTextView.setVisibility(View.VISIBLE);
                        Log.d(TAG, "Message id=" + message.getId() + ": showing transcribed content under player=" + message.getTranscribedContent());
                    } else {
                        Log.e(TAG, "transcribedContentTextView is null for message id=" + message.getId());
                    }
                } else {
                    contentTextView.setVisibility(View.GONE);
                    if (transcribedContentTextView != null) {
                        transcribedContentTextView.setVisibility(View.GONE);
                    }
                }
            } else {
                // Логика для текстовых и других типов сообщений
                if (isTranslationEnabled && message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) {
                    contentTextView.setText(message.isShowOriginal() ? message.getContent() : message.getTranslatedContent());
                    translatedContentTextView.setText(message.getTranslatedContent());
                    translatedContentTextView.setVisibility(View.VISIBLE);
                    Log.d(TAG, "Message id=" + message.getId() + ": showing translated content, showOriginal=" + message.isShowOriginal() + ", translatedContentTextView=VISIBLE");
                } else {
                    contentTextView.setText(TextUtils.isEmpty(message.getContent()) ? "" : message.getContent());
                    translatedContentTextView.setVisibility(View.GONE);
                    Log.d(TAG, "Message id=" + message.getId() + ": no translation or transcription available, translatedContentTextView=GONE");
                }
                contentTextView.setVisibility(View.VISIBLE);
            }

            // Индикаторы доставки и прочтения
            indicatorsContainer.setVisibility(isSentByCurrentUser ? View.VISIBLE : View.GONE);
            if (isSentByCurrentUser) {
                // Восстановление статуса delivered из SharedPreferences
                SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
                long chatId = message.getChat() != null ? message.getChat().getId() : -1;
                boolean savedDelivered = prefs.getBoolean("delivered_" + chatId + "_" + message.getId(), message.isDelivered());
                if (savedDelivered && !message.isDelivered()) {
                    message.setDelivered(true);
                    Log.d(TAG, "Restored delivered status for message id=" + message.getId() + " from SharedPreferences: delivered=true");
                }
                deliveryIndicator.setVisibility(message.isDelivered() ? View.VISIBLE : View.GONE);
                readIndicator.setVisibility(message.isRead() ? View.VISIBLE : View.GONE);

                Log.d(TAG, "Message id=" + message.getId() + ": deliveryIndicator visibility=" +
                        (message.isDelivered() ? "VISIBLE" : "GONE") + ", readIndicator visibility=" +
                        (message.isRead() ? "VISIBLE" : "GONE"));
            }

            // Установка временной метки
            String timestampStr = message.getTimestamp();
            if (!TextUtils.isEmpty(timestampStr)) {
                try {
                    ZonedDateTime utcTime = ZonedDateTime.parse(timestampStr, DateTimeFormatter.ISO_ZONED_DATE_TIME);
                    ZonedDateTime kievTime = utcTime.withZoneSameInstant(ZoneId.of("Europe/Kiev"));
                    ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Kiev"));
                    String formattedTime;

                    // Только часы и минуты: дата стоит разделителем над первым
                    // сообщением дня, повторять её в каждом пузыре незачем
                    formattedTime = kievTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()));

                    timestampTextView.setText(formattedTime);
                    timestampTextView.setVisibility(View.VISIBLE);
                    Log.d(TAG, "Message at position " + position + ": UTC=" + timestampStr + ", Kiev=" + formattedTime);
                } catch (DateTimeParseException e) {
                    Log.w(TAG, "Failed to parse timestamp at position " + position + ": " + timestampStr, e);
                    timestampTextView.setVisibility(View.GONE);
                }
            } else {
                timestampTextView.setVisibility(View.GONE);
                Log.w(TAG, "Message at position " + position + ": timestamp is null or empty");
            }

            // Получение настроек перевода и TTS
            SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
            Log.d(TAG, "Binding message id=" + message.getId() + ", type=" + message.getMessageType() +
                    ", content=" + message.getContent() + ", position=" + position);

            boolean showTranslateIcon = false;
            boolean showTTSIcon = false;
            boolean showTTSTranslatedIcon = false;
            String translationLanguage = "auto";
            TranslationSettingsFragment.TranslationMode translationMode = TranslationSettingsFragment.TranslationMode.ALL;
            long chatId = message.getChat() != null ? message.getChat().getId() : -1;

            boolean isTranslationEnabledLocal = prefs.getBoolean("translation_enabled_" + chatId, false);
            boolean isTTSEnabledLocal = prefs.getBoolean("tts_enabled_" + chatId, false);
            translationLanguage = prefs.getString("translation_language_" + chatId, "auto");
            String translationModeStr = prefs.getString("translation_mode_" + chatId, "ALL");
            try {
                translationMode = TranslationSettingsFragment.TranslationMode.valueOf(translationModeStr);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid translation mode for message id=" + message.getId() + ": " + translationModeStr + ", defaulting to ALL");
                translationMode = TranslationSettingsFragment.TranslationMode.ALL;
            }

            // Условия для показа иконок
            boolean isTextOrFileWithContent = ("text".equalsIgnoreCase(message.getMessageType()) ||
                    ("file".equalsIgnoreCase(message.getMessageType()) &&
                            message.getContent() != null && !message.getContent().isEmpty())) &&
                    !"audio".equalsIgnoreCase(message.getMessageType()); // Явно исключаем аудиосообщения

            showTranslateIcon = isTranslationEnabledLocal && isTextOrFileWithContent;
            showTTSIcon = isTTSEnabledLocal && isTextOrFileWithContent;
            showTTSTranslatedIcon = isTTSEnabledLocal && isTranslationEnabledLocal &&
                    (translationMode == TranslationSettingsFragment.TranslationMode.ALL ||
                            translationMode == TranslationSettingsFragment.TranslationMode.NEW_ONLY ||
                            (translationMode == TranslationSettingsFragment.TranslationMode.SELECTIVELY &&
                                    message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty())) &&
                    isTextOrFileWithContent;
            Log.d(TAG, "Message id=" + message.getId() +
                    ": isTranslationEnabledLocal=" + prefs.getBoolean("translation_enabled_" + chatId, false) +
                    ", isTTSEnabledLocal=" + prefs.getBoolean("tts_enabled_" + chatId, false) +
                    ", isTranslationEnabledField=" + isTranslationEnabled +
                    ", isTTSEnabledField=" + isTTSEnabled +
                    ", showTranslateIcon=" + showTranslateIcon +
                    ", showTTSIcon=" + showTTSIcon +
                    ", showTTSTranslatedIcon=" + showTTSTranslatedIcon +
                    ", messageType=" + message.getMessageType() +
                    ", content=" + message.getContent() +
                    ", translatedContent=" + message.getTranslatedContent() +
                    ", isSentByCurrentUser=" + (message.getSender().getId() == currentUserId) +
                    ", translationLanguage=" + translationLanguage +
                    ", translationMode=" + translationMode);

            // Установка видимости и обработчиков для иконки перевода
            if (translateIcon != null) {
                setTranslateIconVisibility(showTranslateIcon ? View.VISIBLE : View.GONE);
                if (showTranslateIcon) {
                    final String finalTranslationLanguage = translationLanguage;
                    translateIcon.setOnClickListener(v -> {
                        Log.d(TAG, "Translate icon clicked for message id=" + message.getId());
                        List<Message> translatedMessages = new ArrayList<>();
                        PrivateChatTranslationAndSpellCheckHelper.translateMessage(
                                context,
                                message,
                                finalTranslationLanguage,
                                message.getChat() != null ? message.getChat().getId() : -1,
                                MessageAdapter.this,
                                messages,
                                translatedMessages,
                                () -> {
                                    if (position != RecyclerView.NO_POSITION) {
                                        notifyItemChanged(position);
                                        Log.d(TAG, "Translation completed for message id=" + message.getId() + " at position=" + position);
                                    } else {
                                        Log.w(TAG, "Cannot notify item changed: invalid position for message id=" + message.getId());
                                    }
                                }
                        );
                    });
                } else {
                    translateIcon.setOnClickListener(null);
                }
            } else {
                Log.e(TAG, "translateIcon is null for message id=" + message.getId());
            }

            // Установка видимости и обработчиков для иконки TTS (оригинальный контент)
            if (ttsIcon != null) {
                ttsIcon.setVisibility(showTTSIcon ? View.VISIBLE : View.GONE);
                if (showTTSIcon) {
                    ttsIcon.setOnClickListener(v -> {
                        if (message.getContent() == null || message.getContent().isEmpty() || "audio".equalsIgnoreCase(message.getMessageType())) {
                            Log.w(TAG, "No valid content for TTS: msgId=" + message.getId());
                            Toast.makeText(context, "No valid content for TTS", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        playTTS(message.getContent(), "auto", message.getId(), false);
                    });
                } else {
                    ttsIcon.setOnClickListener(null);
                }
            } else {
                Log.e(TAG, "ttsIcon is null for message id=" + message.getId());
            }

            // Установка видимости и обработчиков для иконки TTS (переведенный контент)
            if (ttsTranslatedIcon != null) {
                ttsTranslatedIcon.setVisibility(showTTSTranslatedIcon ? View.VISIBLE : View.GONE);
                if (showTTSTranslatedIcon) {
                    ttsTranslatedIcon.setOnClickListener(v -> {
                        if (message.getTranslatedContent() == null || message.getTranslatedContent().isEmpty() ||
                                "audio".equalsIgnoreCase(message.getMessageType())) {
                            Log.w(TAG, "No translated content for TTS: msgId=" + message.getId());
                            Toast.makeText(context, "No translated content available for TTS", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        playTTS(message.getTranslatedContent(), message.getTranslationLanguage() != null ?
                                message.getTranslationLanguage() : "auto", message.getId(), true);
                    });
                } else {
                    ttsTranslatedIcon.setOnClickListener(null);
                }
            } else {
                Log.e(TAG, "ttsTranslatedIcon is null for message id=" + message.getId());
            }

            // ДОБАВЛЕНО: Превью ссылок (только для текстовых сообщений, не аудио/файл)
            if (linkPreviewCard != null) {
                // Всегда скрываем в начале — защита от "прилипания" к чужим сообщениям
                linkPreviewCard.setVisibility(View.GONE);

                String text = message.getContent();
                String messageFileUrl = message.getFileUrl();

                if (!TextUtils.isEmpty(text) &&
                        !"audio".equalsIgnoreCase(message.getMessageType()) &&
                        (messageFileUrl == null || messageFileUrl.trim().isEmpty() || messageFileUrl.equalsIgnoreCase("null"))) {

                    String url = extractFirstUrl(text);
                    if (url != null) {

                        // Разобранное превью помним между входами в чат
                        if (message.getPreviewData() == null) {
                            message.setPreviewData(LinkPreviewCache.get(context, url));
                        }

                        // ──────────────── КЭШ ────────────────
                        if (message.getPreviewData() != null) {
                            Message.LinkPreviewData data = message.getPreviewData();

                            previewTitle.setText(data.title);
                            previewDescription.setText(data.description);
                            previewUrl.setText(android.net.Uri.parse(data.url).getHost());

                            if (!data.imageUrl.isEmpty()) {
                                Glide.with(context)
                                        .load(data.imageUrl)
                                        .apply(new RequestOptions()
                                                .centerCrop()
                                                .placeholder(android.R.color.darker_gray))
                                        .into(previewImage);
                                previewImage.setVisibility(View.VISIBLE);
                            } else {
                                previewImage.setVisibility(View.GONE);
                            }

                            linkPreviewCard.setVisibility(View.VISIBLE);

                            linkPreviewCard.setOnClickListener(v -> {
                                try {
                                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(data.url));
                                    context.startActivity(browserIntent);
                                } catch (Exception e) {
                                    Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
                                }
                            });

                            makeUrlClickable(url);

                            Log.d(TAG, "Превью из кэша для id=" + message.getId());
                        }
                        // ──────────────── ПАРСИНГ (если кэша нет) ────────────────
                        else {
                            final long msgId = message.getId();
                            final MessageViewHolder currentHolder = this;

                            new Thread(() -> {
                                try {
                                    Message.LinkPreviewData fetched = LinkPreviewCache.fetch(context, url);
                                    if (fetched == null) throw new Exception("превью не получено");

                                    final String fTitle = fetched.title;
                                    final String fDesc = fetched.description;
                                    final String fImageUrl = fetched.imageUrl;

                                    ((Activity) context).runOnUiThread(() -> {
                                        // Самая важная проверка!
                                        if (currentHolder.boundMessageId != msgId) {
                                            Log.w(TAG, "Игнорируем устаревшее превью: holder перепривязан, было msgId=" + msgId + ", сейчас " + currentHolder.boundMessageId);
                                            return;
                                        }

                                        // Сохраняем кэш в объект сообщения
                                        Message.LinkPreviewData data = new Message.LinkPreviewData();
                                        data.title = fTitle;
                                        data.description = fDesc;
                                        data.imageUrl = fImageUrl;
                                        data.url = url;
                                        message.setPreviewData(data);

                                        // Заполняем UI
                                        previewTitle.setText(fTitle);
                                        previewDescription.setText(fDesc);
                                        previewUrl.setText(android.net.Uri.parse(url).getHost());

                                        if (!fImageUrl.isEmpty()) {
                                            Glide.with(context)
                                                    .load(fImageUrl)
                                                    .apply(new RequestOptions()
                                                            .centerCrop()
                                                            .placeholder(android.R.color.darker_gray))
                                                    .into(previewImage);
                                            previewImage.setVisibility(View.VISIBLE);
                                        } else {
                                            previewImage.setVisibility(View.GONE);
                                        }

                                        linkPreviewCard.setVisibility(View.VISIBLE);

                                        linkPreviewCard.setOnClickListener(v -> {
                                            try {
                                                Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                                                context.startActivity(browserIntent);
                                            } catch (Exception e) {
                                                Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
                                            }
                                        });

                                        makeUrlClickable(url);

                                        Log.d(TAG, "Превью успешно загружено и отображено для msgId=" + msgId);
                                    });
                                } catch (Exception e) {
                                    Log.w(TAG, "Ошибка при парсинге превью для " + url + ": " + e.getMessage());
                                    ((Activity) context).runOnUiThread(() -> {
                                        if (currentHolder.boundMessageId == msgId) {
                                            linkPreviewCard.setVisibility(View.GONE);
                                        }
                                    });
                                }
                            }).start();
                        }
                    }
                }
            }
            // ====================== ЦВЕТ ССЫЛОК ======================
            if (isSentByCurrentUser) {
                // Свои сообщения — красивый синий цвет
                contentTextView.setLinkTextColor(Color.parseColor("#4FC3F7"));
                contentTextView.setHighlightColor(Color.parseColor("#3381D4FA")); // цвет при нажатии
                Log.d(TAG, "Ссылка для своего сообщения установлена в #4FC3F7");
            } else {
                // Чужие сообщения — фиолетовый
                contentTextView.setLinkTextColor(Color.parseColor("#B388FF"));
                contentTextView.setHighlightColor(Color.parseColor("#40BB86FC"));
                Log.d(TAG, "Ссылка для чужого сообщения установлена в #BB86FC");
            }
            // =========================================================
        }

        // Вспомогательные методы для проверки дат

        /**
         * Подпись разделителя дня. Сегодня и вчера называем словами — так
         * принято в телефонных мессенджерах и короче даты.
         */
        private String dayLabel(ZonedDateTime time, ZonedDateTime now) {
            if (isSameDay(time, now)) return "Today";
            if (isYesterday(time, now)) return "Yesterday";
            String pattern = time.getYear() == now.getYear() ? "d MMMM" : "d MMMM yyyy";
            return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()));
        }

        /** Время сообщения в киевской зоне или null, если метки нет. */
        private ZonedDateTime kievTimeOf(String timestamp) {
            if (TextUtils.isEmpty(timestamp)) return null;
            try {
                return ZonedDateTime.parse(timestamp, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                        .withZoneSameInstant(ZoneId.of("Europe/Kiev"));
            } catch (DateTimeParseException e) {
                return null;
            }
        }

        /**
         * Разделитель показываем над первым сообщением дня: сравниваем с
         * предыдущим в списке. У самого первого сообщения он есть всегда.
         */
        private void bindDaySeparator(int position) {
            if (daySeparator == null) return;
            ZonedDateTime mine = kievTimeOf(messages.get(position).getTimestamp());
            if (mine == null) {
                daySeparator.setVisibility(View.GONE);
                return;
            }
            ZonedDateTime previous = position > 0 ? kievTimeOf(messages.get(position - 1).getTimestamp()) : null;
            if (previous != null && isSameDay(previous, mine)) {
                daySeparator.setVisibility(View.GONE);
                return;
            }
            daySeparator.setText(dayLabel(mine, ZonedDateTime.now(ZoneId.of("Europe/Kiev"))));
            daySeparator.setVisibility(View.VISIBLE);
        }



        /**
         * Время рядом с текстом: подвал лежит накладкой в правом нижнем углу,
         * поэтому последней строке дописывается хвост под его ширину. У
         * пузырей с картинкой, файлом или звуком хвост не помогает — там
         * колонке добавляется запас снизу, и подвал стоит под содержимым.
         */
        private void applyFooterLayout() {
            View column = itemView.findViewById(R.id.bubble_column);
            final View footer = itemView.findViewById(R.id.message_footer);
            if (column == null || footer == null) return;

            float density = itemView.getResources().getDisplayMetrics().density;
            int base = (int) (12 * density);
            int footerHeight = (int) (20 * density);

            boolean hasMedia = (imagePreview != null && imagePreview.getVisibility() == View.VISIBLE)
                    || (audioContainer != null && audioContainer.getVisibility() == View.VISIBLE)
                    || (fileLinkContainer != null && fileLinkContainer.getVisibility() == View.VISIBLE)
                    || (linkPreviewCard != null && linkPreviewCard.getVisibility() == View.VISIBLE)
                    || (attachmentUnavailable != null && attachmentUnavailable.getVisibility() == View.VISIBLE);

            final TextView last = lastVisibleText();
            if (last != null) last.setText(BubbleFooter.stripTail(last.getText()));

            if (hasMedia || last == null) {
                BubbleFooter.reserveBelow(column, true, base, footerHeight);
                return;
            }
            BubbleFooter.reserveBelow(column, false, base, footerHeight);
            footer.post(() -> BubbleFooter.applyTail(last, footer.getWidth() + 12 * density));
        }

        /** Последняя видимая строка пузыря: транскрипция, перевод или сам текст. */
        private TextView lastVisibleText() {
            if (transcribedContentTextView != null
                    && transcribedContentTextView.getVisibility() == View.VISIBLE) {
                return transcribedContentTextView;
            }
            if (translatedContentTextView != null
                    && translatedContentTextView.getVisibility() == View.VISIBLE) {
                return translatedContentTextView;
            }
            if (contentTextView != null && contentTextView.getVisibility() == View.VISIBLE
                    && contentTextView.getText() != null && contentTextView.getText().length() > 0) {
                return contentTextView;
            }
            return null;
        }

        private boolean isSameDay(ZonedDateTime date1, ZonedDateTime date2) {
            return date1.toLocalDate().isEqual(date2.toLocalDate());
        }

        private boolean isYesterday(ZonedDateTime date, ZonedDateTime now) {
            return date.toLocalDate().isEqual(now.toLocalDate().minusDays(1));
        }

        private void makeUrlClickable(String url) {
            String originalText = contentTextView.getText().toString();
            if (TextUtils.isEmpty(originalText) || !originalText.contains(url)) return;

            SpannableString spannable = new SpannableString(originalText);
            int start = originalText.indexOf(url);
            if (start >= 0) {
                int end = start + url.length();

                spannable.setSpan(new android.text.style.UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new android.text.style.URLSpan(url), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                contentTextView.setText(spannable);
                contentTextView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

                Log.d(TAG, "makeUrlClickable: ссылка сделана кликабельной");
            }
        }
    }

    private void downloadFile(String fileUrl) {
        try {
            // Извлечение имени файла из URL
            String fileName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);
            String mimeType = determineMimeType(fileName);

            Log.d(TAG, "Downloading file: url=" + fileUrl + ", name=" + fileName + ", mimeType=" + mimeType);

            // Настройка запроса на скачивание
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(fileUrl));
            request.setTitle(fileName);
            request.setDescription("Downloading file");
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setMimeType(mimeType);

            // Запуск скачивания
            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            long downloadId = downloadManager.enqueue(request);

            // Регистрация слушателя завершения скачивания
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        DownloadManager.Query query = new DownloadManager.Query();
                        query.setFilterById(downloadId);
                        Cursor cursor = downloadManager.query(query);
                        if (cursor.moveToFirst()) {
                            int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                            String message = status == DownloadManager.STATUS_SUCCESSFUL ? "Download completed" : "Download failed";
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                        }
                        cursor.close();
                        context.unregisterReceiver(this);
                    }
                }
            };
            context.registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

            Toast.makeText(context, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error downloading file: " + e.getMessage(), e);
            Toast.makeText(context, "Error downloading file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String determineMimeType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "application/octet-stream";
        }
        String extension;
        try {
            extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        } catch (Exception e) {
            return "application/octet-stream";
        }
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "pdf":
                return "application/pdf";
            default:
                return "application/octet-stream";
        }
    }

    static class MessageDiffCallback extends DiffUtil.Callback {
        private final List<Message> oldList;
        private final List<Message> newList;

        public MessageDiffCallback(List<Message> oldList, List<Message> newList) {
            this.oldList = oldList != null ? oldList : new ArrayList<>();
            this.newList = newList != null ? new ArrayList<>(newList) : new ArrayList<>();
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            Message oldMessage = oldList.get(oldItemPosition);
            Message newMessage = newList.get(newItemPosition);
            return oldMessage != null && newMessage != null && oldMessage.getId() == newMessage.getId();
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            Message oldMessage = oldList.get(oldItemPosition);
            Message newMessage = newList.get(newItemPosition);
            if (oldMessage == null || newMessage == null) {
                return false;
            }
            return TextUtils.equals(oldMessage.getContent(), newMessage.getContent()) &&
                    oldMessage.isDelivered() == newMessage.isDelivered() &&
                    oldMessage.isRead() == newMessage.isRead() &&
                    TextUtils.equals(oldMessage.getFileUrl(), newMessage.getFileUrl()) &&
                    TextUtils.equals(oldMessage.getTimestamp(), newMessage.getTimestamp()) &&
                    oldMessage.getSender().getId() == newMessage.getSender().getId() &&
                    TextUtils.equals(oldMessage.getSender().getUsername(), newMessage.getSender().getUsername()) &&
                    TextUtils.equals(oldMessage.getSender().getAvatar(), newMessage.getSender().getAvatar());
        }
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
            Log.d(TAG, "MediaPlayer released on detach from RecyclerView");
        }
    }

    // ДОБАВЛЕНО: Превью ссылок — находит первую ссылку в тексте
    private String extractFirstUrl(String text) {
        if (TextUtils.isEmpty(text)) return null;

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\))+(?:\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))"
        );
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String url = matcher.group();
            if (!url.startsWith("http")) url = "https://" + url;
            return url;
        }
        return null;
    }

    /**
     * Скопировать текст сообщения в буфер обмена.
     *
     * Копирования не было нигде: ни ссылку из сообщения достать, ни кусок
     * текста. А меню по долгому нажатию показывалось только для своих
     * сообщений, поэтому к чужим не было доступа вовсе.
     */
    static void copyMessageText(android.content.Context context, String text) {
        if (text == null || text.isEmpty()) return;
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Message", text));
        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show();
    }
}