package com.example.unlimitedpossibilities12.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Handler;
import android.net.Uri;
import android.os.Looper;
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
import android.view.ContextThemeWrapper;
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
import com.example.unlimitedpossibilities12.messenger.translate_and_spell_check.GroupChatTranslationAndSpellCheckHelper;
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

public class GroupMessageAdapter extends RecyclerView.Adapter<GroupMessageAdapter.MessageViewHolder> {
    private static final String TAG = "GroupMessageAdapter";
    private final Context context;
    private final List<Message> messages;
    private final Map<Long, Boolean> userStatuses = new HashMap<>();
    private final int currentUserId;
    private final OnMessageActionListener actionListener;
    private boolean isTranslationEnabled;
    private MediaPlayer mediaPlayer; // *** ДОБАВЛЕНО: Поле для MediaPlayer ***
    private boolean isTTSEnabled; // Флаг состояния TTS
    private MediaPlayer currentMediaPlayer; // MediaPlayer для текущего TTS
    private long currentPlayingMessageId = -1; // ID текущего воспроизводимого сообщения
    private boolean isPlayingTranslated = false; // Флаг, указывающий, воспроизводится ли переведённый текст
    private boolean isTranscriptionEnabled; // ДОБАВЛЕНО: Переменная для состояния транскрипции

    public interface OnMessageActionListener {
        void onEdit(Message message);
        void onDelete(Message message);
    }

    public GroupMessageAdapter(Context context, List<Message> messages, int currentUserId, OnMessageActionListener listener, boolean isTranslationEnabled, boolean isTTSEnabled, boolean isTranscriptionEnabled) {
        this.context = context;
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        this.currentUserId = currentUserId;
        this.actionListener = listener;
        this.isTranslationEnabled = isTranslationEnabled;
        this.isTTSEnabled = isTTSEnabled;
        this.isTranscriptionEnabled = isTranscriptionEnabled;
        Log.d(TAG, "GroupMessageAdapter initialized with isTranscriptionEnabled=" + isTranscriptionEnabled);
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_group_message, parent, false);
        return new MessageViewHolder(view, messages, currentUserId);
    }

    public void setTranslationEnabled(boolean enabled) {
        this.isTranslationEnabled = enabled;
        notifyDataSetChanged();
    }

    public void setTTSEnabled(boolean enabled) {
        this.isTTSEnabled = enabled;
        notifyDataSetChanged();
        Log.d(TAG, "TTS enabled set to: " + enabled);
    }

    public void setTranscriptionEnabled(boolean enabled) {
        this.isTranscriptionEnabled = enabled;
        // Обновляем только аудиосообщения
        for (int i = 0; i < messages.size(); i++) {
            if ("audio".equalsIgnoreCase(messages.get(i).getMessageType())) {
                notifyItemChanged(i);
            }
        }
        Log.d(TAG, "Transcription enabled set to: " + enabled);
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

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message message = messages.get(position);
        if (message == null || message.getSender() == null) {
            Log.w(TAG, "Message or sender is null at position: " + position);
            return;
        }
        holder.bind(message, position);
        stopCurrentTTS(); // ДОБАВЛЕНО: Остановка TTS при привязке нового элемента
        // *** ДОБАВЛЕНО: Остановка предыдущего воспроизведения при привязке нового элемента ***
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
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

    public void updateUserStatus(long userId, boolean online) {
        userStatuses.put(userId, online);
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getSender() != null && messages.get(i).getSender().getId() == userId) {
                notifyItemChanged(i);
            }
        }
        Log.d(TAG, "Updated status for userId: " + userId + ", online: " + online);
    }

    class MessageViewHolder extends RecyclerView.ViewHolder {
        ImageView avatarImageView;
        View avatarPlaceholder, statusIndicator;
        com.google.android.material.card.MaterialCardView messageContainer;
        TextView senderNameTextView, contentTextView, timestampTextView, translatedContentTextView;
        TextView daySeparator;
        TextView attachmentUnavailable;
        View fileLinkContainer;
        LinearLayout indicatorsContainer;
        LinearLayout audioContainer;
        ImageView playPauseButton;
        ImageView translateIcon;
        ImageView ttsIcon;
        ImageView ttsTranslatedIcon;
        TextView audioDurationTextView;
        SeekBar audioSeekBar;
        TextView audioCurrentTimeTextView;
        ImageView transcriptionIcon;
        TextView transcribedContentTextView;
        ImageView translateIconAudio;
        ImageView ttsIconAudio;
        ImageView ttsTranslatedIconAudio;
        ImageView imagePreview; // *** ДОБАВЛЕНО: Превью изображения для файлов ***
        private Handler seekBarHandler = new Handler(Looper.getMainLooper());
        private Runnable seekBarUpdater;
        private int currentPosition = 0;
        private boolean isPrepared = false;
        private final List<Message> messages;
        private final int currentUserId;
        // ДОБАВЛЕНО: Превью ссылок для групповых чатов
        CardView linkPreviewCard;
        ImageView previewImage;
        TextView previewTitle;
        TextView previewDescription;
        TextView previewUrl;
        private long boundMessageId = -1; // ← запоминаем ID сообщения, к которому сейчас привязан этот holder

        public MessageViewHolder(@NonNull View itemView, List<Message> messages, int currentUserId) {
            super(itemView);
            this.messages = messages;
            this.currentUserId = currentUserId;
            avatarImageView = itemView.findViewById(R.id.avatar_image_view);
            avatarPlaceholder = itemView.findViewById(R.id.avatar_placeholder);
            messageContainer = itemView.findViewById(R.id.message_container);
            senderNameTextView = itemView.findViewById(R.id.sender_name_text_view);
            contentTextView = itemView.findViewById(R.id.content_text_view);
            fileLinkContainer = itemView.findViewById(R.id.file_link_container);
            timestampTextView = itemView.findViewById(R.id.timestamp_text_view);
            attachmentUnavailable = itemView.findViewById(R.id.attachment_unavailable);
            daySeparator = itemView.findViewById(R.id.day_separator);
            indicatorsContainer = itemView.findViewById(R.id.indicators_container);
            statusIndicator = itemView.findViewById(R.id.status_indicator);
            translatedContentTextView = itemView.findViewById(R.id.translated_content_text_view);
            audioContainer = itemView.findViewById(R.id.audio_container);
            playPauseButton = itemView.findViewById(R.id.play_pause_button);
            audioDurationTextView = itemView.findViewById(R.id.audio_duration_text_view);
            audioSeekBar = itemView.findViewById(R.id.audio_seek_bar);
            audioCurrentTimeTextView = itemView.findViewById(R.id.audio_current_time_text_view);
            translateIcon = itemView.findViewById(R.id.translate_icon);
            ttsIcon = itemView.findViewById(R.id.tts_icon);
            ttsTranslatedIcon = itemView.findViewById(R.id.tts_translated_icon);
            transcriptionIcon = itemView.findViewById(R.id.transcription_icon);
            transcribedContentTextView = itemView.findViewById(R.id.transcribed_content_text_view);
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

            // Клик для переключения перевода
            messageContainer.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    Message message = messages.get(position);
                    if ("audio".equalsIgnoreCase(message.getMessageType())) {
                        if (isTranscriptionEnabled && message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty() &&
                                message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) {
                            message.setShowOriginal(!message.isShowOriginal());
                            contentTextView.setText(message.isShowOriginal() ? message.getTranscribedContent() : message.getTranslatedContent());
                            translatedContentTextView.setVisibility(View.GONE); // Скрываем перевод внизу для аудио
                            notifyItemChanged(position);
                            Log.d(TAG, "Toggled showOriginal for audio message id=" + message.getId() + " to " + message.isShowOriginal() + ", text=" + contentTextView.getText());
                        }
                    } else if (message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) {
                        message.setShowOriginal(!message.isShowOriginal());
                        contentTextView.setText(message.isShowOriginal() ? message.getContent() : message.getTranslatedContent());
                        notifyItemChanged(position);
                        Log.d(TAG, "Toggled showOriginal for text message id=" + message.getId() + " to " + message.isShowOriginal() + ", text=" + contentTextView.getText());
                    } else {
                        Log.d(TAG, "Translation toggle skipped for message id=" + message.getId() +
                                ", hasTranslation=" + (message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) +
                                ", isAudio=" + "audio".equalsIgnoreCase(message.getMessageType()));
                    }
                }
            });

            // Долгое нажатие для меню
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
                        listPopupWindow.setWidth(520); // примерно 200-220 dp
                        listPopupWindow.setVerticalOffset(8);

                        // Фон меню
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

            // Клик на аватар
            avatarImageView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    Message message = messages.get(position);
                    if (message.getSender() != null && message.getSender().getId() > 0) {
                        Intent intent = new Intent(context, UserProfileActivity.class);
                        intent.putExtra("userId", message.getSender().getId());
                        context.startActivity(intent);
                    } else {
                        Toast.makeText(context, "User info not available", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            // Обработчик клика по иконке перевода
            translateIcon.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    Message message = messages.get(position);
                    if ("text".equalsIgnoreCase(message.getMessageType()) ||
                            ("file".equalsIgnoreCase(message.getMessageType()) &&
                                    message.getContent() != null && !message.getContent().isEmpty())) {
                        ((GroupChatsActivity) context).translateMessage(message);
                        Log.d(TAG, "Translate icon clicked for message id=" + message.getId());
                    } else {
                        Log.d(TAG, "Skipping translation for message id=" + message.getId() + " (invalid message type: " + message.getMessageType() + ")");
                    }
                }
            });
        }

        public void setTranslatedContentText(String text) {
            translatedContentTextView.setText(text);
            translatedContentTextView.setVisibility(View.VISIBLE);
            Log.d(TAG, "Set translated content text: " + text);
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

        public void bind(Message message, int position) {
            bindDaySeparator(position); // дата дня над первым сообщением дня
            itemView.post(this::applyFooterLayout); // время рядом с текстом, когда влезает
            // Реализация метода bind остаётся без изменений, как в предоставленном коде
            User sender = message.getSender();
            boolean isSentByCurrentUser = sender.getId() == currentUserId;

            boundMessageId = message.getId(); // ← Обязательно!

            Log.d(TAG, "Binding message id=" + message.getId() + ", fileUrl=" + message.getFileUrl() +
                    ", senderId=" + sender.getId() + ", messageType=" + message.getMessageType());

            // ЯВНО скрываем image_preview в начале bind — чтобы старое превью не оставалось
            imagePreview.setVisibility(View.GONE);
            imagePreview.setOnClickListener(null); // убираем старый обработчик

            // Установка выравнивания и фона
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) messageContainer.getLayoutParams();
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            float density = context.getResources().getDisplayMetrics().density;
            int marginHorizontal = (int) (8 * density);
            int padding = (int) (12 * density);
            int avatarWidth = (int) (32 * density);
            int marginStart = (int) (9 * density);
            int visualMargin = (int) (56 * density);

            if (isSentByCurrentUser) {
                params.startToStart = ConstraintLayout.LayoutParams.UNSET;
                params.startToEnd = ConstraintLayout.LayoutParams.UNSET;
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                params.rightMargin = marginHorizontal;
                params.leftMargin = visualMargin;
                params.constrainedWidth = true;
                int maxWidthPx = screenWidth - 2 * marginHorizontal - 2 * padding - visualMargin;
                params.matchConstraintMaxWidth = maxWidthPx;
                messageContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.sent_message_background));
                messageContainer.setShapeAppearanceModel(
                        new ShapeAppearanceModel()
                                .toBuilder()
                                .setTopRightCorner(CornerFamily.ROUNDED, 0f)
                                .setTopLeftCorner(CornerFamily.ROUNDED, 16f * density)
                                .setBottomLeftCorner(CornerFamily.ROUNDED, 16f * density)
                                .setBottomRightCorner(CornerFamily.ROUNDED, 16f * density)
                                .build()
                );
                avatarImageView.setVisibility(View.GONE);
                avatarPlaceholder.setVisibility(View.GONE);
                senderNameTextView.setVisibility(View.GONE);
            } else {
                params.startToStart = ConstraintLayout.LayoutParams.UNSET;
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET;
                params.rightMargin = marginHorizontal;
                params.leftMargin = 0;
                params.setMarginStart(marginStart);
                params.constrainedWidth = true;
                int maxWidthPx = screenWidth - marginHorizontal - avatarWidth - marginStart - visualMargin - 2 * padding;
                params.matchConstraintMaxWidth = maxWidthPx;
                params.startToEnd = R.id.avatar_image_view;
                messageContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.received_message_background));
                messageContainer.setShapeAppearanceModel(
                        new ShapeAppearanceModel()
                                .toBuilder()
                                .setTopLeftCorner(CornerFamily.ROUNDED, 0f)
                                .setTopRightCorner(CornerFamily.ROUNDED, 16f * density)
                                .setBottomLeftCorner(CornerFamily.ROUNDED, 16f * density)
                                .setBottomRightCorner(CornerFamily.ROUNDED, 16f * density)
                                .build()
                );
                avatarImageView.setVisibility(firstInSeries(position) ? View.VISIBLE : View.INVISIBLE);
                avatarPlaceholder.setVisibility(View.GONE);
                senderNameTextView.setVisibility(firstInSeries(position) ? View.VISIBLE : View.GONE);
            }
            messageContainer.setLayoutParams(params);

            // Настройка имени отправителя и аватара
            senderNameTextView.setText(sender.getUsername());
            String avatarUrl = sender.getAvatar();
            if (!isSentByCurrentUser && !TextUtils.isEmpty(avatarUrl)) {
                Glide.with(context)
                        .load("https://unlimitedpossibilities12.org" + avatarUrl)
                        .apply(new RequestOptions()
                                .circleCrop()
                                .placeholder(R.drawable.ic_default_avatar)
                                .error(R.drawable.ic_default_avatar))
                        .into(avatarImageView);
            } else if (!isSentByCurrentUser) {
                String initial = TextUtils.isEmpty(sender.getUsername()) ? "?" : sender.getUsername().substring(0, 1).toUpperCase();
                LetterAvatarDrawable avatarDrawable = new LetterAvatarDrawable(initial);
                avatarImageView.setImageDrawable(avatarDrawable);
            }

            // Управление индикатором статуса
            if (!isSentByCurrentUser) {
                Long senderId = (long) sender.getId();
                Boolean isOnline = userStatuses.get(senderId);
                boolean isVisible = isOnline != null && isOnline;
                statusIndicator.setVisibility(isVisible ? View.VISIBLE : View.GONE);
            } else {
                statusIndicator.setVisibility(View.GONE);
            }

            // Логика отображения иконки перевода и TTS
            SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
            long chatId = message.getChat() != null ? message.getChat().getId() : -1;
            final boolean isTranslationEnabledLocal = (context instanceof GroupChatsActivity) ?
                    ((GroupChatsActivity) context).isTranslationEnabled() : isTranslationEnabled;
            final boolean isTTSEnabledLocal = prefs.getBoolean("tts_enabled_" + chatId, isTTSEnabled);
            final String translationLanguage = prefs.getString("translation_language_" + chatId, "auto");
            final String translationModeStr = prefs.getString("translation_mode_" + chatId, "ALL");
            final TranslationSettingsFragment.TranslationMode translationMode;
            TranslationSettingsFragment.TranslationMode tempMode = null;
            try {
                tempMode = TranslationSettingsFragment.TranslationMode.valueOf(translationModeStr);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid translation mode for message id=" + message.getId() + ": " + translationModeStr + ", defaulting to ALL");
            }
            translationMode = (tempMode != null) ? tempMode : TranslationSettingsFragment.TranslationMode.ALL;

            String messageType = message.getMessageType();
            if ("audio".equalsIgnoreCase(messageType)) {
                translatedContentTextView.setVisibility(View.GONE);
                Log.d(TAG, "Message id=" + message.getId() + ": translatedContentTextView forced to GONE (audio)");
                if (isTranscriptionEnabled && message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty()) {
                    if (message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) {
                        contentTextView.setText(message.isShowOriginal() ? message.getTranscribedContent() : message.getTranslatedContent());
                        Log.d(TAG, "Message id=" + message.getId() + ": showing " + (message.isShowOriginal() ? "transcribed content=" + message.getTranscribedContent() : "translated content=" + message.getTranslatedContent()));
                    } else {
                        contentTextView.setText(message.getTranscribedContent());
                        Log.d(TAG, "Message id=" + message.getId() + ": showing transcribed content=" + message.getTranscribedContent() + ", no translation available");
                    }
                    contentTextView.setVisibility(View.VISIBLE);
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
                if (isTranslationEnabledLocal && message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) {
                    setContentText(message.isShowOriginal() ? message.getContent() : message.getTranslatedContent());
                    setTranslatedContentText(message.getTranslatedContent());
                    Log.d(TAG, "Message id=" + message.getId() + ": showing translated content, showOriginal=" + message.isShowOriginal() + ", translatedContentTextView=VISIBLE");
                } else {
                    setContentText(TextUtils.isEmpty(message.getContent()) ? "" : message.getContent());
                    translatedContentTextView.setVisibility(View.GONE);
                    Log.d(TAG, "Message id=" + message.getId() + ": no translation or transcription available, translatedContentTextView=GONE");
                }
                contentTextView.setVisibility(View.VISIBLE);
            }

            // Условия для показа иконок перевода и TTS для текстовых сообщений
            boolean isTextOrFileWithContent = !"image".equalsIgnoreCase(messageType) &&
                    !"audio".equalsIgnoreCase(messageType) ||
                    ("file".equalsIgnoreCase(messageType) &&
                            message.getContent() != null && !message.getContent().isEmpty());

            boolean showTranslateIcon = isTranslationEnabledLocal && isTextOrFileWithContent;
            boolean showTTSIcon = isTTSEnabledLocal && isTextOrFileWithContent;
            boolean showTTSTranslatedIcon = isTTSEnabledLocal && isTranslationEnabledLocal &&
                    (translationMode == TranslationSettingsFragment.TranslationMode.ALL ||
                            translationMode == TranslationSettingsFragment.TranslationMode.NEW_ONLY ||
                            (translationMode == TranslationSettingsFragment.TranslationMode.SELECTIVELY &&
                                    message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty())) &&
                    isTextOrFileWithContent;

            setTranslateIconVisibility(showTranslateIcon ? View.VISIBLE : View.GONE);

            if (ttsIcon != null) {
                ttsIcon.setVisibility(showTTSIcon ? View.VISIBLE : View.GONE);
                if (showTTSIcon) {
                    ttsIcon.setOnClickListener(v -> {
                        if (message.getContent() == null || message.getContent().isEmpty()) {
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

            if (ttsTranslatedIcon != null) {
                ttsTranslatedIcon.setVisibility(showTTSTranslatedIcon ? View.VISIBLE : View.GONE);
                if (showTTSTranslatedIcon) {
                    ttsTranslatedIcon.setOnClickListener(v -> {
                        if (message.getTranslatedContent() == null || message.getTranslatedContent().isEmpty()) {
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

            if (transcriptionIcon != null && translateIconAudio != null && ttsIconAudio != null && ttsTranslatedIconAudio != null) {
                boolean showTranscriptionIcon = GroupMessageAdapter.this.isTranscriptionEnabled && "audio".equalsIgnoreCase(messageType);
                boolean hasTranscription = message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty();

                boolean showTranslateIconAudio = showTranscriptionIcon && isTranslationEnabledLocal && hasTranscription;
                boolean showTTSIconAudio = showTranscriptionIcon && isTTSEnabledLocal && hasTranscription;
                boolean showTTSTranslatedIconAudio = showTranscriptionIcon && isTTSEnabledLocal && isTranslationEnabledLocal &&
                        hasTranscription && (translationMode == TranslationSettingsFragment.TranslationMode.ALL ||
                        translationMode == TranslationSettingsFragment.TranslationMode.NEW_ONLY ||
                        (translationMode == TranslationSettingsFragment.TranslationMode.SELECTIVELY &&
                                message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()));

                transcriptionIcon.setVisibility(showTranscriptionIcon ? View.VISIBLE : View.GONE);
                translateIconAudio.setVisibility(showTranslateIconAudio ? View.VISIBLE : View.GONE);
                ttsIconAudio.setVisibility(showTTSIconAudio ? View.VISIBLE : View.GONE);
                ttsTranslatedIconAudio.setVisibility(showTTSTranslatedIconAudio ? View.VISIBLE : View.GONE);

                Log.d(TAG, "Message id=" + message.getId() + ": transcriptionIcon=" + (showTranscriptionIcon ? "VISIBLE" : "GONE") +
                        ", translateIconAudio=" + (showTranslateIconAudio ? "VISIBLE" : "GONE") +
                        ", ttsIconAudio=" + (showTTSIconAudio ? "VISIBLE" : "GONE") +
                        ", ttsTranslatedIconAudio=" + (showTTSTranslatedIconAudio ? "VISIBLE" : "GONE"));

                if (showTranscriptionIcon) {
                    transcriptionIcon.setOnClickListener(v -> {
                        Log.d(TAG, "Transcription icon clicked for message id=" + message.getId());
                        if (context instanceof GroupChatsActivity) {
                            GroupChatsActivity activity = (GroupChatsActivity) context;
                            activity.transcribeMessage(message, () -> {
                                if (position != RecyclerView.NO_POSITION) {
                                    if (message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty()) {
                                        transcribedContentTextView.setText(message.getTranscribedContent());
                                        transcribedContentTextView.setVisibility(View.VISIBLE);
                                        if (isTranslationEnabledLocal && translationMode != TranslationSettingsFragment.TranslationMode.SELECTIVELY) {
                                            message.setContent(message.getTranscribedContent());
                                            GroupChatTranslationAndSpellCheckHelper.translateMessage(
                                                    context,
                                                    message,
                                                    translationLanguage,
                                                    chatId,
                                                    GroupMessageAdapter.this,
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
                            });
                        } else {
                            Log.e(TAG, "Context is not GroupChatsActivity for message id=" + message.getId());
                            Toast.makeText(context, "Transcription not available", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    transcriptionIcon.setOnClickListener(null);
                }

                if (showTranslateIconAudio) {
                    translateIconAudio.setOnClickListener(v -> {
                        Log.d(TAG, "Translate icon clicked for audio message id=" + message.getId());
                        if (message.getTranscribedContent() == null || message.getTranscribedContent().isEmpty()) {
                            Log.w(TAG, "No transcribed content available for translation for message id=" + message.getId());
                            Toast.makeText(context, "No transcribed content available for translation", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // *** ИЗМЕНЕНО: Устанавливаем content равным transcribedContent для корректного перевода ***
                        message.setContent(message.getTranscribedContent());
                        // *** ДОБАВЛЕНО: Получаем язык перевода из активности ***
                        String targetLanguage = (context instanceof GroupChatsActivity) ?
                                ((GroupChatsActivity) context).getTranslationLanguage() : translationLanguage;
                        Log.d(TAG, "Using translation language for audio message id=" + message.getId() + ": " + targetLanguage);
                        GroupChatTranslationAndSpellCheckHelper.translateMessage(
                                context,
                                message,
                                targetLanguage,
                                chatId,
                                GroupMessageAdapter.this,
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
            }

            // Обработка fileUrl с учётом messageType
            String fileUrl = message.getFileUrl();
            if ("audio".equalsIgnoreCase(messageType) && fileUrl != null && !fileUrl.trim().isEmpty() && !fileUrl.equalsIgnoreCase("null")) {
                Log.d(TAG, "Showing audioContainer for message id=" + message.getId());
                fileLinkContainer.setVisibility(View.GONE);
                audioContainer.setVisibility(View.VISIBLE);
                audioContainer.post(() -> {
                    Log.d(TAG, "audioContainer visibility for message id=" + message.getId() + ": " + (audioContainer.getVisibility() == View.VISIBLE ? "VISIBLE" : "GONE"));
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

                playPauseButton.setColorFilter(ContextCompat.getColor(context, R.color.sent_message_accent));
                audioSeekBar.setProgressBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.seekbar_background)
                ));

                // Обработчик кнопки Play/Pause
                playPauseButton.setOnClickListener(v -> {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        currentPosition = mediaPlayer.getCurrentPosition();
                        mediaPlayer.pause();
                        playPauseButton.setImageResource(R.drawable.ic_play);
                        seekBarHandler.removeCallbacks(seekBarUpdater);
                        Log.d(TAG, "Audio paused for message id=" + message.getId() + " at position=" + currentPosition);
                    } else {
                        if (mediaPlayer != null && isPrepared && !mediaPlayer.isPlaying()) {
                            mediaPlayer.seekTo(currentPosition);
                            mediaPlayer.start();
                            playPauseButton.setImageResource(R.drawable.ic_pause);
                            seekBarHandler.post(seekBarUpdater);
                            Log.d(TAG, "Audio resumed for message id=" + message.getId() + " from position=" + currentPosition);
                        } else {
                            if (mediaPlayer != null) {
                                mediaPlayer.release();
                                mediaPlayer = null;
                            }
                            mediaPlayer = new MediaPlayer();
                            isPrepared = false;
                            try {
                                mediaPlayer.setDataSource(fileUrl);
                                mediaPlayer.setOnPreparedListener(mp -> {
                                    isPrepared = true;
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
                                    isPrepared = false;
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
                                    isPrepared = false;
                                    seekBarHandler.removeCallbacks(seekBarUpdater);
                                    mp.reset();
                                    return true;
                                });
                                mediaPlayer.prepareAsync();
                            } catch (IOException e) {
                                Log.e(TAG, "Error playing audio for message id=" + message.getId() + ": " + e.getMessage());
                                Toast.makeText(context, "Cannot play audio", Toast.LENGTH_SHORT).show();
                                playPauseButton.setImageResource(R.drawable.ic_play);
                                audioSeekBar.setProgress(0);
                                audioCurrentTimeTextView.setText("0:00");
                                currentPosition = 0;
                                isPrepared = false;
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
                            currentPosition = progress;
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
                            currentPosition = seekBar.getProgress();
                            mediaPlayer.seekTo(currentPosition);
                            mediaPlayer.start();
                            playPauseButton.setImageResource(R.drawable.ic_pause);
                            seekBarHandler.post(seekBarUpdater);
                            Log.d(TAG, "SeekBar touch stopped for message id=" + message.getId() + ", seekTo=" + currentPosition);
                        }
                    }
                });
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

                    Log.d(TAG, "Image preview shown for group message id=" + message.getId());
                } else {
                    // Всё остальное (включая любые MP3, PDF и т.д.) — обычная ссылка
                    fileLinkContainer.setVisibility(View.VISIBLE);
                    audioContainer.setVisibility(View.GONE);
                    imagePreview.setVisibility(View.GONE);
                    fileLinkContainer.setOnClickListener(v -> downloadFile(fileUrl));

                    Log.d(TAG, "Regular file link shown for group message id=" + message.getId());
                }
            } else {
                fileLinkContainer.setVisibility(View.GONE);
                audioContainer.setVisibility(View.GONE);
                imagePreview.setVisibility(View.GONE);
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
                } catch (DateTimeParseException e) {
                    Log.w(TAG, "Failed to parse timestamp at position " + position + ": " + timestampStr, e);
                    timestampTextView.setVisibility(View.GONE);
                }
            } else {
                timestampTextView.setVisibility(View.GONE);
            }

            // ДОБАВЛЕНО: Превью ссылок в групповых чатах
            if (linkPreviewCard != null) {
                linkPreviewCard.setVisibility(View.GONE);   // Всегда скрываем в начале bind

                String text = message.getContent();
                String messageFileUrl = message.getFileUrl();

                if (!TextUtils.isEmpty(text) &&
                        !"audio".equalsIgnoreCase(message.getMessageType()) &&
                        (messageFileUrl == null || messageFileUrl.trim().isEmpty() || messageFileUrl.equalsIgnoreCase("null"))) {

                    String url = extractFirstUrl(text);
                    if (url != null) {

                        // ─── КЭШ ───
                        if (message.getPreviewData() != null) {
                            Message.LinkPreviewData data = message.getPreviewData();

                            previewTitle.setText(data.title);
                            previewDescription.setText(data.description);
                            previewUrl.setText(android.net.Uri.parse(data.url).getHost());

                            if (!data.imageUrl.isEmpty()) {
                                Glide.with(context)
                                        .load(data.imageUrl)
                                        .apply(new RequestOptions().centerCrop().placeholder(android.R.color.darker_gray))
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

                            makeUrlClickable(url);   // ← метод ниже

                            Log.d(TAG, "Group preview from cache → msg " + message.getId());
                        }
                        // ─── Парсинг (если кэша нет) ───
                        else {
                            final long msgId = message.getId();
                            final MessageViewHolder currentHolder = this;

                            new Thread(() -> {
                                try {
                                    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(url)
                                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                            .timeout(10000)   // 10 секунд — разумный компромисс
                                            .get();

                                    String title = doc.selectFirst("meta[property=og:title]") != null ?
                                            doc.selectFirst("meta[property=og:title]").attr("content") : doc.title();

                                    String desc = doc.selectFirst("meta[property=og:description]") != null ?
                                            doc.selectFirst("meta[property=og:description]").attr("content") : "";

                                    if (desc.isEmpty()) {
                                        desc = doc.selectFirst("meta[name=description]") != null ?
                                                doc.selectFirst("meta[name=description]").attr("content") : "";
                                    }

                                    String imageUrl = doc.selectFirst("meta[property=og:image]") != null ?
                                            doc.selectFirst("meta[property=og:image]").attr("content") : "";

                                    final String fTitle = title;
                                    final String fDesc = desc;
                                    final String fImageUrl = imageUrl;

                                    ((Activity) context).runOnUiThread(() -> {
                                        // Самая важная защита
                                        if (currentHolder.boundMessageId != msgId) {
                                            Log.w(TAG, "Игнорируем устаревшее превью в группе: holder теперь для msg " +
                                                    currentHolder.boundMessageId + ", а парсинг был для " + msgId);
                                            return;
                                        }

                                        // Сохраняем кэш
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
                                                    .apply(new RequestOptions().centerCrop().placeholder(android.R.color.darker_gray))
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

                                        Log.d(TAG, "Group preview успешно загружено → msg " + msgId);
                                    });
                                } catch (Exception e) {
                                    Log.w(TAG, "Ошибка парсинга превью в группе для " + url + ": " + e.getMessage());
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
            // ====================== ЦВЕТ ССЫЛОК (как в личном чате) ======================
            if (isSentByCurrentUser) {
                // Свои сообщения — синий
                contentTextView.setLinkTextColor(Color.parseColor("#4FC3F7"));
                contentTextView.setHighlightColor(Color.parseColor("#3381D4FA"));
            } else {
                // Чужие сообщения — фиолетовый
                contentTextView.setLinkTextColor(Color.parseColor("#B388FF"));
                contentTextView.setHighlightColor(Color.parseColor("#40BB86FC"));
            }
            // =========================================================
        }


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
         * В группе имя и аватар нужны один раз — над первым сообщением серии.
         * Прежде они повторялись у каждого сообщения подряд, как будто люди
         * говорят по одному слову. В личном чате так было и раньше.
         */
        private boolean firstInSeries(int position) {
            if (position == 0) return true;
            Message mine = messages.get(position);
            Message previous = messages.get(position - 1);
            if (mine.getSender() == null || previous.getSender() == null) return true;
            return mine.getSender().getId() != previous.getSender().getId();
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

        private void downloadFile(String fileUrl) {
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(fileUrl));
                context.startActivity(browserIntent);
            } catch (Exception e) {
                Log.e(TAG, "Error opening file URL: " + e.getMessage());
                Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show();
            }
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

                Log.d(TAG, "makeUrlClickable: ссылка сделана кликабельной для msg " + boundMessageId);
            }
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
                    TextUtils.equals(oldMessage.getFileUrl(), newMessage.getFileUrl()) &&
                    TextUtils.equals(oldMessage.getTimestamp(), newMessage.getTimestamp()) &&
                    oldMessage.getSender().getId() == newMessage.getSender().getId() &&
                    TextUtils.equals(oldMessage.getSender().getUsername(), newMessage.getSender().getUsername()) &&
                    TextUtils.equals(oldMessage.getSender().getAvatar(), newMessage.getSender().getAvatar()) &&
                    TextUtils.equals(oldMessage.getTranscribedContent(), newMessage.getTranscribedContent()) &&
                    TextUtils.equals(oldMessage.getTranslatedContent(), newMessage.getTranslatedContent());
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
        stopCurrentTTS();
        Log.d(TAG, "TTS stopped on detach from RecyclerView");
    }

    // ДОБАВЛЕНО: Находит первую ссылку в тексте
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
