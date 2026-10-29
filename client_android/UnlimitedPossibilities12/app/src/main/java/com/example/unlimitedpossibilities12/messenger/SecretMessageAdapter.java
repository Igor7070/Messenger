package com.example.unlimitedpossibilities12.messenger;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.text.TextUtils;
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
import android.Manifest;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.FileMetadata;
import com.example.unlimitedpossibilities12.api.Message;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.popupMenu.PopupMenuAdapter;
import com.example.unlimitedpossibilities12.messenger.popupMenu.PopupMenuItem;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SecretMessageAdapter extends RecyclerView.Adapter<SecretMessageAdapter.MessageViewHolder> {
    private static final String TAG = "SecretMessageAdapter";
    private final Context context;
    private final List<Message> messages;
    private final int currentUserId;
    private final OnMessageActionListener actionListener;
    private final Map<Long, Boolean> userStatuses = new HashMap<>(); // Статусы пользователей (онлайн/оффлайн)
    private MediaPlayer mediaPlayer;
    private int currentPosition = 0; // Переменная для хранения позиции паузы
    private boolean isPrepared = false; // Флаг для отслеживания состояния подготовки MediaPlayer
    private final SecretPrivateChatActivity activity;
    private final ApiService apiService;

    public interface OnMessageActionListener {
        void onEdit(Message message);
        void onDelete(Message message);
    }

    public SecretMessageAdapter(Context context, List<Message> messages, int currentUserId, OnMessageActionListener listener, ApiService apiService) {
        this.context = context;
        this.activity = (SecretPrivateChatActivity) context;
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        this.currentUserId = currentUserId;
        this.actionListener = listener;
        this.apiService = apiService;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view, this);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message message = messages.get(position);
        Log.d(TAG, "Binding message: id=" + message.getId() + ", contentType=" + message.getContentType() + ", messageType=" + message.getMessageType() + ", fileUrl=" + message.getFileUrl());
        if (message == null || message.getSender() == null) {
            Log.w(TAG, "Message or sender is null at position: " + position);
            return;
        }
        holder.itemView.setVisibility(View.VISIBLE);
        // Получаем существующие LayoutParams, чтобы сохранить отступы из XML
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
        if (params == null) {
            params = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
            );
            // Задаём отступы вручную, если params отсутствует
            float density = context.getResources().getDisplayMetrics().density;
            int marginVertical = (int) (4 * density); // 4dp, как в item_message.xml
            params.setMargins(0, marginVertical, 0, marginVertical);
        }
        holder.itemView.setLayoutParams(params);
        holder.bind(message, position);
        // Остановка предыдущего воспроизведения при привязке нового элемента
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
            Log.d(TAG, "MediaPlayer released for message id=" + message.getId());
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void updateMessages(List<Message> newMessages) {
        for (Message msg : newMessages) {
            Log.d(TAG, "Message in adapter: id=" + msg.getId() + ", contentType=" + msg.getContentType() + ", messageType=" + msg.getMessageType());
        }
        if (newMessages == null) {
            newMessages = new ArrayList<>();
        }
        Log.d(TAG, "Updating messages: oldSize=" + messages.size() + ", newSize=" + newMessages.size());
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new MessageDiffCallback(messages, newMessages));
        messages.clear();
        messages.addAll(newMessages);
        diffResult.dispatchUpdatesTo(this);
        Log.d(TAG, "Messages updated with DiffUtil, dispatched changes");
    }

    public void updateUserStatus(long userId, boolean online) {
        userStatuses.put(userId, online);
        int updatedCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getSender() != null && messages.get(i).getSender().getId() == userId) {
                notifyItemChanged(i);
                updatedCount++;
            }
        }
        Log.d(TAG, "Updated status for userId=" + userId + ", online=" + online + ", updated items=" + updatedCount);
    }

    public class MessageViewHolder extends RecyclerView.ViewHolder {
        ImageView avatarImageView;
        View avatarPlaceholder, statusIndicator;
        MaterialCardView messageContainer;
        TextView contentTextView, timestampTextView;
        TextView daySeparator;
        TextView attachmentUnavailable;
        TextView translatedContentTextView; // перевод на устройстве (секретный чат)
        TextView fileLinkContainer;
        LinearLayout indicatorsContainer;
        ImageView deliveryIndicator, readIndicator;
        LinearLayout audioContainer;
        ImageView playPauseButton;
        TextView audioDurationTextView;
        SeekBar audioSeekBar;
        TextView audioCurrentTimeTextView;
        private final Handler seekBarHandler = new Handler();
        private Runnable seekBarUpdater;
        private final SecretMessageAdapter adapter;
        ImageView imagePreview;

        public MessageViewHolder(@NonNull View itemView, SecretMessageAdapter adapter) {
            super(itemView);
            this.adapter = adapter;
            avatarImageView = itemView.findViewById(R.id.avatar_image_view);
            avatarPlaceholder = itemView.findViewById(R.id.avatar_placeholder);
            messageContainer = itemView.findViewById(R.id.message_container);
            contentTextView = itemView.findViewById(R.id.content_text_view);
            translatedContentTextView = itemView.findViewById(R.id.translated_content_text_view);
            fileLinkContainer = itemView.findViewById(R.id.file_link_container);
            timestampTextView = itemView.findViewById(R.id.timestamp_text_view);
            attachmentUnavailable = itemView.findViewById(R.id.attachment_unavailable);
            daySeparator = itemView.findViewById(R.id.day_separator);
            indicatorsContainer = itemView.findViewById(R.id.indicators_container);
            deliveryIndicator = itemView.findViewById(R.id.delivery_indicator);
            readIndicator = itemView.findViewById(R.id.read_indicator);
            statusIndicator = itemView.findViewById(R.id.status_indicator);
            audioContainer = itemView.findViewById(R.id.audio_container);
            playPauseButton = itemView.findViewById(R.id.play_pause_button);
            audioDurationTextView = itemView.findViewById(R.id.audio_duration_text_view);
            audioSeekBar = itemView.findViewById(R.id.audio_seek_bar);
            audioCurrentTimeTextView = itemView.findViewById(R.id.audio_current_time_text_view);
            imagePreview = itemView.findViewById(R.id.image_preview);

            // Обработчик долгого клика
            messageContainer.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    Message message = adapter.messages.get(position);
                    boolean mine = message.getSender().getId() == adapter.currentUserId;
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

                        PopupMenuAdapter adapterPopup = new PopupMenuAdapter(context, menuItems);
                        listPopupWindow.setAdapter(adapterPopup);

                        listPopupWindow.setOnItemClickListener((parent, view, pos, id) -> {
                            String title = menuItems.get(pos).title;
                            if ("Copy".equals(title)) {
                                // Копируем расшифрованный текст с экрана: в поле content шифртекст
                                MessageAdapter.copyMessageText(context, contentTextView.getText().toString());
                            } else if ("Edit".equals(title)) {
                                adapter.actionListener.onEdit(message);
                            } else if ("Delete".equals(title)) {
                                adapter.actionListener.onDelete(message);
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

        public void bind(Message message, int position) {
            bindDaySeparator(position); // дата дня над первым сообщением дня
            itemView.post(this::applyFooterLayout); // время рядом с текстом, когда влезает
            User sender = message.getSender();
            boolean isSentByCurrentUser = sender.getId() == adapter.currentUserId;
            Log.d(TAG, "Binding message id=" + message.getId() + ", fileUrl=" + message.getFileUrl() +
                    ", delivered=" + message.isDelivered() + ", read=" + message.isRead() +
                    ", senderId=" + sender.getId() + ", messageType=" + message.getMessageType() +
                    ", isTemporary=" + message.isTemporary() + ", contentType=" + message.getContentType());
            // ЯВНО скрываем image_preview в начале bind — чтобы старое превью не оставалось
            imagePreview.setVisibility(View.GONE);
            imagePreview.setOnClickListener(null);
            translatedContentTextView.setVisibility(View.GONE);
            // Установка выравнивания и фона
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) messageContainer.getLayoutParams();
            int screenWidth = adapter.context.getResources().getDisplayMetrics().widthPixels;
            float density = adapter.context.getResources().getDisplayMetrics().density;
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
                int maxWidthPx = screenWidth - 2 * marginHorizontal - 2 * padding - marginHorizontal - visualMargin;
                params.matchConstraintMaxWidth = maxWidthPx;
                messageContainer.setCardBackgroundColor(ContextCompat.getColor(adapter.context, R.color.sent_message_background));
                boolean showSquareCorner = position == 0 || adapter.messages.get(position - 1).getSender().getId() != sender.getId();
                if (showSquareCorner) {
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setTopRightCorner(CornerFamily.ROUNDED, 0f)
                            .setTopLeftCorner(CornerFamily.ROUNDED, 12f * density)
                            .setBottomLeftCorner(CornerFamily.ROUNDED, 12f * density)
                            .setBottomRightCorner(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                } else {
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setAllCorners(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                }
                avatarImageView.setVisibility(View.GONE);
                avatarPlaceholder.setVisibility(View.GONE);
            } else {
                params.startToStart = ConstraintLayout.LayoutParams.UNSET;
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET;
                params.rightMargin = marginHorizontal;
                params.leftMargin = 0;
                params.setMarginStart(marginStart);
                params.constrainedWidth = true;
                int maxWidthPx = screenWidth - marginHorizontal - avatarWidth - marginStart - visualMargin - 2 * padding;
                params.matchConstraintMaxWidth = maxWidthPx;
                boolean showAvatar = position == 0 || adapter.messages.get(position - 1).getSender().getId() != sender.getId();
                if (showAvatar) {
                    params.startToEnd = R.id.avatar_image_view;
                    avatarImageView.setVisibility(View.VISIBLE);
                    avatarPlaceholder.setVisibility(View.GONE);
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setTopLeftCorner(CornerFamily.ROUNDED, 0f)
                            .setTopRightCorner(CornerFamily.ROUNDED, 12f * density)
                            .setBottomLeftCorner(CornerFamily.ROUNDED, 12f * density)
                            .setBottomRightCorner(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                } else {
                    params.startToEnd = R.id.avatar_placeholder;
                    avatarImageView.setVisibility(View.GONE);
                    avatarPlaceholder.setVisibility(View.VISIBLE);
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setAllCorners(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                }
                messageContainer.setCardBackgroundColor(ContextCompat.getColor(adapter.context, R.color.received_message_background));
            }
            messageContainer.setLayoutParams(params);
            // Настройка аватара
            if (!isSentByCurrentUser && avatarImageView.getVisibility() == View.VISIBLE) {
                String avatarUrl = sender.getAvatar();
                if (!TextUtils.isEmpty(avatarUrl)) {
                    Glide.with(adapter.context)
                            .load("https://unlimitedpossibilities12.org" + avatarUrl)
                            .apply(new RequestOptions()
                                    .circleCrop()
                                    .placeholder(R.drawable.ic_default_avatar)
                                    .error(R.drawable.ic_default_avatar))
                            .into(avatarImageView);
                } else {
                    String initial = TextUtils.isEmpty(sender.getUsername()) ? "?" : sender.getUsername().substring(0, 1).toUpperCase();
                    LetterAvatarDrawable avatarDrawable = new LetterAvatarDrawable(initial);
                    avatarImageView.setImageDrawable(avatarDrawable);
                }
                Long senderId = (long) sender.getId();
                Boolean isOnline = adapter.userStatuses.get(senderId);
                boolean isVisible = isOnline != null && isOnline;
                statusIndicator.setVisibility(isVisible ? View.VISIBLE : View.GONE);
                Log.d(TAG, "Setting status indicator for userId=" + senderId + ", isOnline=" + isOnline +
                        ", visibility=" + (isVisible ? "VISIBLE" : "GONE") + ", messageId=" + message.getId());
                avatarImageView.setOnClickListener(v -> {
                    if (sender.getId() != -1 && adapter.context instanceof Activity && !((Activity) adapter.context).isFinishing()) {
                        Intent intent = new Intent(adapter.context, UserProfileActivity.class);
                        intent.putExtra("userId", sender.getId());
                        adapter.context.startActivity(intent);
                        Log.d(TAG, "Avatar clicked: Opening UserProfileActivity for userId=" + sender.getId());
                    } else {
                        Toast.makeText(adapter.context, "User profile not available", Toast.LENGTH_SHORT).show();
                        Log.w(TAG, "Avatar click ignored: Invalid senderId=" + sender.getId() + " or activity is finishing");
                    }
                });
            } else {
                avatarImageView.setOnClickListener(null);
            }
            // Обработка сообщений
            contentTextView.setVisibility(View.GONE);
            fileLinkContainer.setVisibility(View.GONE);
            audioContainer.setVisibility(View.GONE);
            if (message.isTemporary()) {
                // Временное сообщение. Если это ещё не отправленная картинка — показываем
                // её сразу из локального файла, а не текст «Uploading…»: так своё фото
                // видно с первой секунды, а не после перезахода в чат.
                JSONObject tmpMeta = adapter.activity.getLocalFileMetadata(message.getId());
                String tmpMime = tmpMeta != null ? tmpMeta.optString("mimeType", null) : null;
                String tmpName = tmpMeta != null ? tmpMeta.optString("fileName", null) : null;
                File tmpImage = null;
                if ("file".equalsIgnoreCase(message.getMessageType())
                        && (isImageMime(tmpMime) || isImageMime(message.getContentType()) || hasImageExtension(tmpName))) {
                    tmpImage = adapter.activity.getLocalFile(message.getId(), tmpName);
                }
                if (tmpImage != null && tmpImage.exists()) {
                    imagePreview.setVisibility(View.VISIBLE);
                    Glide.with(context)
                            .load(tmpImage)
                            .signature(new com.bumptech.glide.signature.ObjectKey(tmpImage.lastModified()))
                            .override(900)
                            .fitCenter()
                            .dontAnimate()
                            .into(imagePreview);
                    fileLinkContainer.setVisibility(View.VISIBLE);
                    fileLinkContainer.setText("⏳ Sending…");
                } else {
                    fileLinkContainer.setVisibility(View.VISIBLE);
                    fileLinkContainer.setText(message.getContent() != null && !message.getContent().isEmpty()
                            ? message.getContent() : "📄 Uploading...");
                }
                indicatorsContainer.setVisibility(View.GONE);
                timestampTextView.setVisibility(View.VISIBLE);
                // Установка временной метки для temporary
                String timestampStr = message.getTimestamp();
                if (!TextUtils.isEmpty(timestampStr)) {
                    try {
                        ZonedDateTime utcTime = ZonedDateTime.parse(timestampStr, DateTimeFormatter.ISO_ZONED_DATE_TIME);
                        ZonedDateTime kievTime = utcTime.withZoneSameInstant(ZoneId.of("Europe/Kiev"));
                        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Kiev"));
                        String formattedTime;
                        if (isSameDay(kievTime, now)) {
                            formattedTime = kievTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()));
                        } else if (isYesterday(kievTime, now)) {
                            formattedTime = "Yesterday, " + kievTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()));
                        } else {
                            formattedTime = kievTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.getDefault()));
                        }
                        timestampTextView.setText(formattedTime);
                        Log.d(TAG, "Temporary message timestamp formatted: " + formattedTime);
                    } catch (DateTimeParseException e) {
                        Log.w(TAG, "Failed to parse timestamp for temp message: " + timestampStr, e);
                        timestampTextView.setText("Now");
                    }
                } else {
                    timestampTextView.setText("Now");
                }
                Log.d(TAG, "Binding temporary message id=" + message.getId() + ", content=" + message.getContent());
                return;
            }
            String messageType = message.getMessageType();
            String fileName;
            String mimeType;
            JSONObject localMetadata = adapter.activity.getLocalFileMetadata(message.getId());
            if (localMetadata != null) {
                fileName = localMetadata.optString("fileName", message.getFileUrl() != null ?
                        message.getFileUrl().substring(message.getFileUrl().lastIndexOf('/') + 1) : "Unknown file");
                mimeType = localMetadata.optString("mimeType", determineMimeType(fileName));
            } else {
                fileName = message.getFileUrl() != null ?
                        message.getFileUrl().substring(message.getFileUrl().lastIndexOf('/') + 1) : "Unknown file";
                mimeType = determineMimeType(fileName);
            }
            if ("text".equalsIgnoreCase(messageType)) {
                // Текстовое сообщение
                String content = isSentByCurrentUser ? adapter.activity.getLocalMessageContent(message.getId()) : message.getContent();
                if (content == null || content.isEmpty()) {
                    content = message.getContent(); // Fallback to server content
                    Log.w(TAG, "Local content missing for own msg id=" + message.getId() + ", using fallback: " + content);
                }
                message.setContent(content); // Cache back to message object for consistency

                // Содержимое может быть обёрткой {"text","translated","lang"} — перевод,
                // сделанный отправителем на его устройстве. Показываем оригинал, а под
                // ним перевод (если он на нужном языке) либо переводим сами локально.
                SecretText st = SecretText.parse(content);
                contentTextView.setText(st.text);
                markNoticeStyle(contentTextView, st.text);
                contentTextView.setVisibility(!st.text.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.activity.bindLocalTranslation(translatedContentTextView, message, st, isSentByCurrentUser);
                Log.d(TAG, "Binding text message id=" + message.getId() + ", content=" + st.text
                        + (st.translated != null ? " [+translated:" + st.lang + "]" : ""));
            } else if ("error".equalsIgnoreCase(messageType)) {
                // Сообщение с ошибкой
                fileLinkContainer.setVisibility(View.VISIBLE);
                fileLinkContainer.setText(message.getContent() != null ? "📄 " + message.getContent() : "📄 Failed to send");
                Log.d(TAG, "Binding error message id=" + message.getId() + ", content=" + message.getContent());
            } else if ("file".equalsIgnoreCase(messageType)) {
                // Подпись к файлу (если есть) — над картинкой/ссылкой
                String caption = adapter.activity.getLocalMessageContent(message.getId());
                if (caption == null || caption.isEmpty()) caption = message.getContent();
                if (caption != null && !caption.isEmpty() && !caption.equals(fileName)) {
                    // Подпись может нести перевод отправителя ({"text","translated","lang"})
                    SecretText cst = SecretText.parse(caption);
                    contentTextView.setText(cst.text);
                    markNoticeStyle(contentTextView, cst.text);
                    contentTextView.setVisibility(View.VISIBLE);
                    adapter.activity.bindLocalTranslation(translatedContentTextView, message, cst, isSentByCurrentUser);
                }

                // Файлы в секретном чате
                Log.d(TAG, "Processing file message id=" + message.getId() + ", fileUrl=" + message.getFileUrl() + ", mimeType=" + mimeType + ", isFileDownloaded=" + message.isFileDownloaded() + ", isBeingProcessed=" + message.isBeingProcessed());

                // Картинка или нет — по MIME из локальных метаданных, из сообщения
                // (веб-клиент шлёт contentType) или по расширению имени файла.
                // Раньше проверялся только contentType сообщения, а Android его не
                // отправлял, поэтому свои фото до перезахода показывались как ссылки.
                boolean isImage = isImageMime(message.getContentType())
                        || isImageMime(mimeType)
                        || hasImageExtension(fileName)
                        || hasImageExtension(message.getFileUrl());

                if (isImage) {
                    // У картинки нет «ссылки на файл»: кликабельна она сама.
                    fileLinkContainer.setVisibility(View.GONE);
                    audioContainer.setVisibility(View.GONE);
                    imagePreview.setVisibility(View.VISIBLE);

                    final File imageFile = adapter.activity.getLocalFile(message, fileName);
                    if (imageFile != null && imageFile.exists()) {
                        // Показываем ТОЛЬКО расшифрованный локальный файл. По fileUrl на
                        // сервере лежат зашифрованные байты — Glide их не декодирует,
                        // из-за этого раньше и висел серый placeholder.
                        Glide.with(context)
                                .load(imageFile)
                                .signature(new com.bumptech.glide.signature.ObjectKey(imageFile.lastModified()))
                                .override(900)
                                .fitCenter()
                                .dontAnimate()
                                .into(imagePreview);
                        imagePreview.setOnClickListener(v -> {
                            Intent intent = new Intent(context, FullScreenMessageImageActivity.class);
                            intent.putExtra("local_file_path", imageFile.getAbsolutePath());
                            context.startActivity(intent);
                        });
                    } else {
                        // Тёмная плашка в цвет пузыря, а не белый ic_placeholder
                        imagePreview.setImageResource(R.drawable.bg_message_image_placeholder);
                        imagePreview.setOnClickListener(v ->
                                Toast.makeText(context, "Изображение ещё загружается...", Toast.LENGTH_SHORT).show());
                        // fileUrl у сообщения секретного чата всегда пустой (сервер его
                        // обнуляет, настоящая ссылка приходит из file_metadata по id),
                        // поэтому по нему ничего не решаем — просто запускаем загрузку.
                        // По завершении downloadFileFromServer вызывает notifyItemChanged —
                        // элемент перерисуется и подхватит локальный файл.
                        if (!message.isFileDownloaded() && !message.isBeingProcessed()) {
                            downloadFileFromServer(message, fileName, mimeType, position);
                        }
                    }
                    return;
                }

                if (message.isBeingProcessed()) {
                    fileLinkContainer.setVisibility(View.VISIBLE);
                    fileLinkContainer.setText("📄 Loading " + fileName + "...");
                    Log.d(TAG, "File message id=" + message.getId() + " is being processed, skipping");
                    return;
                }
                File localFile = adapter.activity.getLocalFile(message, fileName);
                if (localFile != null && localFile.exists()) {
                    // [FIX] Create final copies for lambda
                    final File finalLocalFile = localFile;
                    final String finalFileName = fileName;
                    final String finalMimeType = mimeType;
                    fileLinkContainer.setVisibility(View.VISIBLE);
                    fileLinkContainer.setText("📄 " + fileName);
                    fileLinkContainer.setOnClickListener(v -> {
                        Log.d(TAG, "Opening local file for messageId=" + message.getId());
                        openDecryptedFile(finalLocalFile, finalMimeType);
                        saveToDownloads(finalLocalFile, finalFileName, finalMimeType);
                    });
                    Log.d(TAG, "Using local file for messageId=" + message.getId() + ": " + fileName);
                } else if (message.getFileUrl() != null && !message.getFileUrl().isEmpty() && !message.isFileDownloaded()) {
                    fileLinkContainer.setVisibility(View.VISIBLE);
                    fileLinkContainer.setText("📄 Loading " + fileName + "...");
                    downloadFileFromServer(message, fileName, mimeType, position);
                } else {
                    fileLinkContainer.setVisibility(View.VISIBLE);
                    fileLinkContainer.setText("📄 File not available: " + fileName);
                    Log.w(TAG, "No local file or fileUrl for messageId=" + message.getId());
                }
            } else if ("audio".equalsIgnoreCase(messageType)) {
                // [FIX] Decrypt metadata for foreign audio to get real fileName/mimeType and cache it
                if (!isSentByCurrentUser && localMetadata == null && message.getEncryptedContent() != null && message.getNonce() != null) {
                    try {
                        String decryptedMeta = adapter.activity.decryptMessage(
                                message.getEncryptedContent(), message.getNonce(),
                                message.getSender().getId(), message.getPublicKeyId()
                        );
                        if (decryptedMeta != null) {
                            JSONObject meta = new JSONObject(decryptedMeta);
                            fileName = meta.optString("fileName", fileName);
                            mimeType = meta.optString("contentType", mimeType);  // or "mimeType" if field differs
                            adapter.activity.saveLocalFileMetadata(message.getId(), fileName, mimeType, message.getNonce());
                            Log.d(TAG, "Decrypted real audio metadata for id=" + message.getId() + ": fileName=" + fileName + ", mimeType=" + mimeType);
                        } else {
                            Log.w(TAG, "Failed to decrypt audio metadata for id=" + message.getId());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error decrypting audio metadata for id=" + message.getId() + ": " + e.getMessage());
                        // Fallback: keep current fileName/mimeType
                    }
                }
                // Аудио в секретном чате
                Log.d(TAG, "Processing audio message id=" + message.getId() + ", fileUrl=" + message.getFileUrl() + ", isBeingProcessed=" + message.isBeingProcessed());
                if (message.isBeingProcessed()) {
                    fileLinkContainer.setVisibility(View.VISIBLE);
                    fileLinkContainer.setText("📄 Loading audio...");
                    Log.d(TAG, "Audio message id=" + message.getId() + " is being processed, skipping");
                    return;
                }
                if (isSentByCurrentUser) {
                    File localFile = adapter.activity.getLocalFile(message, fileName);
                    if (localFile != null && localFile.exists()) {
                        fileLinkContainer.setVisibility(View.GONE);
                        audioContainer.setVisibility(View.VISIBLE);
                        setupAudioPlayer(localFile.getAbsolutePath(), message);
                        Log.d(TAG, "Using local audio for messageId=" + message.getId() + ": " + fileName);
                    } else if (message.getFileUrl() != null && !message.getFileUrl().isEmpty()) {
                        fileLinkContainer.setVisibility(View.VISIBLE);
                        fileLinkContainer.setText("📄 Loading audio...");
                        downloadAudioFromServer(message, fileName, mimeType, position);
                    } else {
                        fileLinkContainer.setVisibility(View.VISIBLE);
                        fileLinkContainer.setText("📄 Audio not available: " + fileName);
                        Log.w(TAG, "No local audio or fileUrl for messageId=" + message.getId());
                    }
                } else {
                    fileLinkContainer.setVisibility(View.VISIBLE);
                    fileLinkContainer.setText("📄 Loading audio...");
                    downloadAudioFromServer(message, fileName, mimeType, position);
                }
            } else {
                // Неизвестный тип сообщения
                Log.w(TAG, "Unknown messageType for messageId=" + message.getId() + ": " + messageType);
                fileLinkContainer.setVisibility(View.VISIBLE);
                fileLinkContainer.setText("📄 Invalid message type");
                Toast.makeText(adapter.context, "Unknown message type: " + messageType, Toast.LENGTH_LONG).show();
            }
            // Индикаторы доставки и прочтения
            indicatorsContainer.setVisibility(isSentByCurrentUser ? View.VISIBLE : View.GONE);
            if (isSentByCurrentUser) {
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
        }

        private void setupAudioPlayer(String filePath, Message message) {
            playPauseButton.setImageResource(R.drawable.ic_play);
            audioCurrentTimeTextView.setText("0:00");
            audioDurationTextView.setText("/ 0:00");
            audioSeekBar.setProgress(0);
            audioSeekBar.setEnabled(false);

            playPauseButton.setColorFilter(ContextCompat.getColor(context, R.color.sent_message_accent));
            audioSeekBar.setProgressBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.seekbar_background)
            ));

            MediaPlayer durationPlayer = new MediaPlayer();
            try {
                durationPlayer.setDataSource(filePath);
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

            playPauseButton.setOnClickListener(v -> {
                if (adapter.mediaPlayer != null && adapter.mediaPlayer.isPlaying()) {
                    adapter.currentPosition = adapter.mediaPlayer.getCurrentPosition();
                    adapter.mediaPlayer.pause();
                    playPauseButton.setImageResource(R.drawable.ic_play);
                    seekBarHandler.removeCallbacks(seekBarUpdater);
                    Log.d(TAG, "Audio paused for message id=" + message.getId() + " at position=" + adapter.currentPosition);
                } else {
                    if (adapter.mediaPlayer != null && adapter.isPrepared && !adapter.mediaPlayer.isPlaying()) {
                        adapter.mediaPlayer.seekTo(adapter.currentPosition);
                        adapter.mediaPlayer.start();
                        playPauseButton.setImageResource(R.drawable.ic_pause);
                        seekBarHandler.post(seekBarUpdater);
                        Log.d(TAG, "Audio resumed for message id=" + message.getId() + " from position=" + adapter.currentPosition);
                    } else {
                        if (adapter.mediaPlayer != null) {
                            adapter.mediaPlayer.release();
                            adapter.mediaPlayer = null;
                        }
                        adapter.mediaPlayer = new MediaPlayer();
                        adapter.isPrepared = false;
                        try {
                            adapter.mediaPlayer.setDataSource(filePath);
                            adapter.mediaPlayer.setOnPreparedListener(mp -> {
                                adapter.isPrepared = true;
                                mp.seekTo(adapter.currentPosition);
                                mp.start();
                                playPauseButton.setImageResource(R.drawable.ic_pause);
                                int duration = mp.getDuration();
                                audioSeekBar.setMax(duration);
                                audioSeekBar.setEnabled(true);
                                int durationSec = duration / 1000;
                                if (audioDurationTextView.getText().toString().equals("/ 0:00")) {
                                    audioDurationTextView.setText("/ " + String.format("%d:%02d", durationSec / 60, durationSec % 60));
                                }
                                seekBarUpdater = () -> {
                                    if (adapter.mediaPlayer != null && adapter.mediaPlayer.isPlaying()) {
                                        int currentPos = adapter.mediaPlayer.getCurrentPosition();
                                        audioSeekBar.setProgress(currentPos);
                                        int currentSec = currentPos / 1000;
                                        audioCurrentTimeTextView.setText(String.format("%d:%02d", currentSec / 60, currentSec % 60));
                                        seekBarHandler.postDelayed(seekBarUpdater, 100);
                                    }
                                };
                                seekBarHandler.post(seekBarUpdater);
                                Log.d(TAG, "Audio started for message id=" + message.getId() + ", duration=" + duration + ", starting from=" + adapter.currentPosition);
                            });
                            adapter.mediaPlayer.setOnCompletionListener(mp -> {
                                playPauseButton.setImageResource(R.drawable.ic_play);
                                audioSeekBar.setProgress(0);
                                audioCurrentTimeTextView.setText("0:00");
                                adapter.currentPosition = 0;
                                adapter.isPrepared = false;
                                seekBarHandler.removeCallbacks(seekBarUpdater);
                                mp.reset();
                                Log.d(TAG, "Audio completed for message id=" + message.getId());
                            });
                            adapter.mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                                Log.e(TAG, "MediaPlayer error for message id=" + message.getId() + ": what=" + what + ", extra=" + extra);
                                Toast.makeText(adapter.context, "Error playing audio", Toast.LENGTH_SHORT).show();
                                playPauseButton.setImageResource(R.drawable.ic_play);
                                audioSeekBar.setProgress(0);
                                audioCurrentTimeTextView.setText("0:00");
                                adapter.currentPosition = 0;
                                adapter.isPrepared = false;
                                seekBarHandler.removeCallbacks(seekBarUpdater);
                                mp.reset();
                                return true;
                            });
                            adapter.mediaPlayer.prepareAsync();
                        } catch (IOException e) {
                            Log.e(TAG, "Error playing audio for message id=" + message.getId() + ": " + e.getMessage());
                            Toast.makeText(adapter.context, "Cannot play audio", Toast.LENGTH_SHORT).show();
                            playPauseButton.setImageResource(R.drawable.ic_play);
                            audioSeekBar.setProgress(0);
                            audioCurrentTimeTextView.setText("0:00");
                            adapter.currentPosition = 0;
                            adapter.isPrepared = false;
                        }
                    }
                }
            });

            audioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && adapter.mediaPlayer != null && adapter.mediaPlayer.isPlaying()) {
                        adapter.mediaPlayer.seekTo(progress);
                        int currentSec = progress / 1000;
                        audioCurrentTimeTextView.setText(String.format("%d:%02d", currentSec / 60, currentSec % 60));
                        Log.d(TAG, "SeekBar changed by user for message id=" + message.getId() + ", progress=" + progress);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    if (adapter.mediaPlayer != null && adapter.mediaPlayer.isPlaying()) {
                        adapter.mediaPlayer.pause();
                        playPauseButton.setImageResource(R.drawable.ic_play);
                        seekBarHandler.removeCallbacks(seekBarUpdater);
                        Log.d(TAG, "SeekBar touch started for message id=" + message.getId());
                    }
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (adapter.mediaPlayer != null) {
                        adapter.mediaPlayer.seekTo(seekBar.getProgress());
                        adapter.mediaPlayer.start();
                        playPauseButton.setImageResource(R.drawable.ic_pause);
                        seekBarHandler.post(seekBarUpdater);
                        Log.d(TAG, "SeekBar touch stopped for message id=" + message.getId() + ", seekTo=" + seekBar.getProgress());
                    }
                }
            });
        }

        private void downloadFileFromServer(Message message, String fileName, String mimeType, int position) {
            Log.d(TAG, "Checking contentType for messageId=" + message.getId() + ": " + message.getContentType()); // [ADD] Отладочный лог
            if (message.isBeingProcessed()) {
                Log.d(TAG, "Skipping download for messageId=" + message.getId() + ": already being processed");
                return;
            }
            message.setBeingProcessed(true);
            try {
                // Пустой fileUrl — не повод не качать: для секретных чатов сервер его
                // обнуляет, а ссылку отдаёт /api/messages/file_metadata/{id} ниже.
                // (Раньше проверка «проходила» только потому, что org.json.optString
                // возвращает строку "null" вместо null.)
                if (message.getFileUrl() == null || message.getFileUrl().isEmpty()
                        || "null".equalsIgnoreCase(message.getFileUrl())) {
                    Log.d(TAG, "fileUrl is empty for messageId=" + message.getId() + ", will take it from file metadata");
                }

                File localFile = adapter.activity.getLocalFile(message, fileName);
                if (localFile != null && localFile.exists()) {
                    Log.d(TAG, "Using existing local file for messageId=" + message.getId() + ": " + localFile.getAbsolutePath());
                    adapter.activity.runOnUiThread(() -> {
                        fileLinkContainer.setVisibility(View.VISIBLE);
                        fileLinkContainer.setText("📄 " + fileName);
                        fileLinkContainer.setOnClickListener(v -> {
                            openDecryptedFile(localFile, mimeType);
                            saveToDownloads(localFile, fileName, mimeType);
                        });
                        notifyItemChanged(position);
                    });
                    message.setFileDownloaded(true);
                    message.setBeingProcessed(false);
                    return;
                } else if (message.isFileDownloaded()) {
                    Log.w(TAG, "File marked as downloaded but not found for messageId=" + message.getId() + ", resetting isFileDownloaded");
                    message.setFileDownloaded(false);
                }

                Log.d(TAG, "Starting download for messageId=" + message.getId() + ", fileUrl=" + message.getFileUrl() +
                        ", contentType=" + (message.getContentType() != null ? message.getContentType() : "null"));

                adapter.apiService.getFileMetadata(message.getId()).enqueue(new Callback<FileMetadata>() {
                    @Override
                    public void onResponse(Call<FileMetadata> call, Response<FileMetadata> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            FileMetadata metadata = response.body();
                            String fileUrl = metadata.getFileUrl();
                            message.setFileUrl(fileUrl);
                            String fetchedFileName = fileUrl != null ? fileUrl.substring(fileUrl.lastIndexOf('/') + 1) : fileName;
                            String fetchedMimeType = message.getContentType() != null && !message.getContentType().isEmpty() ?
                                    message.getContentType() : determineMimeType(fetchedFileName);
                            Log.d(TAG, "Fetched metadata for messageId=" + message.getId() + ": fileUrl=" + fileUrl +
                                    ", fileName=" + fetchedFileName + ", mimeType=" + fetchedMimeType);

                            new Thread(() -> {
                                try {
                                    URL url = new URL(fileUrl);
                                    InputStream inputStream = url.openStream();
                                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                                    byte[] buffer = new byte[8192];
                                    int bytesRead;
                                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                                        byteArrayOutputStream.write(buffer, 0, bytesRead);
                                    }
                                    inputStream.close();
                                    byte[] encryptedFileBytes = byteArrayOutputStream.toByteArray();

                                    byte[] decryptedFileBytes = adapter.activity.decryptFile(
                                            encryptedFileBytes,
                                            metadata.getNonce(),
                                            message.getSender().getId(),
                                            metadata.getPublicKeyId()
                                    );
                                    if (decryptedFileBytes != null) {
                                        String extension = getFileExtension(fetchedMimeType, fetchedFileName);
                                        String uniqueSuffix = String.valueOf(message.getId());
                                        String uniqueFileName = "file_" + message.getId() + "_" + uniqueSuffix + "." + extension;
                                        File decryptedFile = new File(adapter.context.getCacheDir(), uniqueFileName);
                                        try (FileOutputStream fos = new FileOutputStream(decryptedFile)) {
                                            fos.write(decryptedFileBytes);
                                        }
                                        adapter.activity.saveLocalFileMetadata(message.getId(), fetchedFileName, fetchedMimeType, message.getNonce());
                                        adapter.activity.saveLocalFileContent(message.getId(), decryptedFileBytes, fetchedFileName);
                                        message.setFileDownloaded(true);
                                        message.setBeingProcessed(false);
                                        Log.d(TAG, "File downloaded and decrypted for messageId=" + message.getId() +
                                                ", path=" + decryptedFile.getAbsolutePath() + ", fileName=" + fetchedFileName +
                                                ", mimeType=" + fetchedMimeType + ", extension=" + extension);

                                        adapter.activity.runOnUiThread(() -> {
                                            fileLinkContainer.setText("📄 " + fetchedFileName);
                                            fileLinkContainer.setOnClickListener(v -> {
                                                openDecryptedFile(decryptedFile, fetchedMimeType);
                                                saveToDownloads(decryptedFile, fetchedFileName, fetchedMimeType);
                                            });
                                            notifyItemChanged(position);
                                        });
                                    } else {
                                        adapter.activity.runOnUiThread(() -> {
                                            Log.e(TAG, "Failed to decrypt file for messageId=" + message.getId());
                                            message.setBeingProcessed(false);
                                            fileLinkContainer.setVisibility(View.GONE);
                                            imagePreview.setVisibility(View.GONE);
                                            if (attachmentUnavailable != null) {
                                                attachmentUnavailable.setText("Attachment can't be decrypted on this device");
                                                attachmentUnavailable.setVisibility(View.VISIBLE);
                                            }
                                        });
                                    }
                                } catch (IOException e) {
                                    Log.e(TAG, "Error downloading/decrypting file for messageId=" + message.getId() + ": " + e.getMessage());
                                    message.setBeingProcessed(false);
                                    adapter.activity.runOnUiThread(() -> {
                                        // Файлы на сервере пропадают при переразвёртывании:
                                        // показываем строку вместо пустой рамки и не пугаем
                                        // человека длинной ссылкой в всплывашке
                                        fileLinkContainer.setVisibility(View.GONE);
                                        imagePreview.setVisibility(View.GONE);
                                        if (attachmentUnavailable != null) {
                                            attachmentUnavailable.setText("Attachment unavailable");
                                            attachmentUnavailable.setVisibility(View.VISIBLE);
                                        }
                                    });
                                }
                            }).start();
                        } else {
                            Log.e(TAG, "Failed to fetch metadata for messageId=" + message.getId() + ": " +
                                    (response != null ? response.message() : "Response is null"));
                            message.setBeingProcessed(false);
                            adapter.activity.runOnUiThread(() -> {
                                fileLinkContainer.setText("📄 Failed to load file");
                                Toast.makeText(adapter.context, "Failed to fetch file metadata", Toast.LENGTH_LONG).show();
                            });
                        }
                    }

                    @Override
                    public void onFailure(Call<FileMetadata> call, Throwable t) {
                        Log.e(TAG, "Error fetching file metadata for messageId=" + message.getId() + ": " + t.getMessage());
                        message.setBeingProcessed(false);
                        adapter.activity.runOnUiThread(() -> {
                            fileLinkContainer.setText("📄 Failed to load file");
                            Toast.makeText(adapter.context, "Error fetching file metadata: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } catch (RuntimeException e) {
                message.setBeingProcessed(false);
                throw e;
            }
        }

        private void saveToDownloads(File sourceFile, String fileName, String mimeType) {
            Log.d(TAG, "Saving file to Downloads: sourcePath=" + sourceFile.getAbsolutePath() + ", fileName=" + fileName + ", mimeType=" + mimeType);

            // Формируем имя файла с правильным расширением
            String extension = getFileExtension(mimeType, fileName);
            String cleanFileName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            String finalFileName = cleanFileName + "." + extension;

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Для Android 10+ используем MediaStore
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, finalFileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                    Uri uri = adapter.context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream out = adapter.context.getContentResolver().openOutputStream(uri);
                             FileInputStream in = new FileInputStream(sourceFile)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                out.write(buffer, 0, bytesRead);
                            }
                            out.flush();
                        }
                        Log.d(TAG, "File saved to Downloads via MediaStore: " + finalFileName);
                        adapter.activity.runOnUiThread(() ->
                                Toast.makeText(adapter.context, "File saved to Downloads: " + finalFileName, Toast.LENGTH_LONG).show()
                        );
                    } else {
                        Log.e(TAG, "Failed to create MediaStore URI for file: " + finalFileName);
                        adapter.activity.runOnUiThread(() ->
                                Toast.makeText(adapter.context, "Failed to save file to Downloads", Toast.LENGTH_LONG).show()
                        );
                    }
                } else {
                    // Для Android 9 и ниже проверяем разрешение WRITE_EXTERNAL_STORAGE
                    if (ContextCompat.checkSelfPermission(adapter.context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "WRITE_EXTERNAL_STORAGE permission not granted");
                        adapter.activity.runOnUiThread(() ->
                                Toast.makeText(adapter.context, "Storage permission required to save file", Toast.LENGTH_LONG).show()
                        );
                        Activity activity = (Activity) adapter.context;
                        ActivityCompat.requestPermissions(activity,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
                        return;
                    }

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File destFile = new File(downloadsDir, finalFileName);
                    try (FileInputStream in = new FileInputStream(sourceFile);
                         FileOutputStream out = new FileOutputStream(destFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                        out.flush();
                        Log.d(TAG, "File saved to Downloads: " + destFile.getAbsolutePath());
                        adapter.activity.runOnUiThread(() ->
                                Toast.makeText(adapter.context, "File saved to Downloads: " + finalFileName, Toast.LENGTH_LONG).show()
                        );

                        // Уведомляем систему о новом файле
                        MediaScannerConnection.scanFile(adapter.context,
                                new String[]{destFile.getAbsolutePath()},
                                new String[]{mimeType}, null);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error saving file to Downloads: " + e.getMessage());
                adapter.activity.runOnUiThread(() ->
                        Toast.makeText(adapter.context, "Error saving file: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }

        private void downloadAudioFromServer(Message message, String fileName, String mimeType, int position) {
            if (message.isBeingProcessed()) {
                Log.d(TAG, "Skipping audio download for messageId=" + message.getId() + ": already being processed");
                return;
            }
            message.setBeingProcessed(true);
            try {
                // [FIX] Removed early return for null fileUrl - always attempt metadata fetch like files
                // [FIX] Add flag reset if downloaded but local missing
                File localFile = adapter.activity.getLocalFile(message, fileName);
                if (message.isFileDownloaded() && (localFile == null || !localFile.exists())) {
                    message.setFileDownloaded(false);
                    Log.w(TAG, "Resetting downloaded flag for audio id=" + message.getId() + " (local missing)");
                }
                if (localFile != null && localFile.exists()) {
                    Log.d(TAG, "Using existing local file for messageId=" + message.getId() + ": " + localFile.getAbsolutePath());
                    adapter.activity.runOnUiThread(() -> {
                        fileLinkContainer.setVisibility(View.GONE);
                        audioContainer.setVisibility(View.VISIBLE);
                        setupAudioPlayer(localFile.getAbsolutePath(), message);
                    });
                    return;
                }
                if (message.isFileDownloaded()) {
                    Log.d(TAG, "Audio already downloaded for messageId=" + message.getId());
                    return;
                }
                Log.d(TAG, "Starting audio download for messageId=" + message.getId() + ", initial fileUrl=" + message.getFileUrl() +
                        ", contentType=" + (message.getContentType() != null ? message.getContentType() : "null"));
                adapter.apiService.getFileMetadata(message.getId()).enqueue(new Callback<FileMetadata>() {
                    @Override
                    public void onResponse(Call<FileMetadata> call, Response<FileMetadata> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            FileMetadata metadata = response.body();
                            String fileUrl = metadata.getFileUrl();
                            String fetchedFileName = fileUrl != null ? fileUrl.substring(fileUrl.lastIndexOf('/') + 1) : fileName;
                            String fetchedMimeType = message.getContentType() != null && !message.getContentType().isEmpty() ?
                                    message.getContentType() : determineMimeType(fetchedFileName);
                            Log.d(TAG, "Fetched metadata for audio messageId=" + message.getId() + ": fileUrl=" + fileUrl +
                                    ", fileName=" + fetchedFileName + ", mimeType=" + fetchedMimeType);
                            new Thread(() -> {
                                try {
                                    URL url = new URL(fileUrl);
                                    InputStream inputStream = url.openStream();
                                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                                    byte[] buffer = new byte[8192];
                                    int bytesRead;
                                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                                        byteArrayOutputStream.write(buffer, 0, bytesRead);
                                    }
                                    inputStream.close();
                                    byte[] encryptedFileBytes = byteArrayOutputStream.toByteArray();
                                    byte[] decryptedFileBytes = adapter.activity.decryptFile(
                                            encryptedFileBytes,
                                            metadata.getNonce(),
                                            message.getSender().getId(),
                                            metadata.getPublicKeyId()
                                    );
                                    if (decryptedFileBytes != null) {
                                        String extension = getFileExtension(fetchedMimeType, fetchedFileName);
                                        String uniqueSuffix = String.valueOf(message.getId());
                                        String uniqueFileName = "file_" + message.getId() + "_" + uniqueSuffix + "." + extension;
                                        File decryptedFile = new File(adapter.context.getCacheDir(), uniqueFileName);
                                        try (FileOutputStream fos = new FileOutputStream(decryptedFile)) {
                                            fos.write(decryptedFileBytes);
                                        }
                                        adapter.activity.saveLocalFileMetadata(message.getId(), fetchedFileName, fetchedMimeType, message.getNonce());
                                        adapter.activity.saveLocalFileContent(message.getId(), decryptedFileBytes, fetchedFileName);
                                        message.setFileDownloaded(true);
                                        Log.d(TAG, "Audio downloaded and decrypted for messageId=" + message.getId() +
                                                ", path=" + decryptedFile.getAbsolutePath() + ", fileName=" + fetchedFileName +
                                                ", mimeType=" + fetchedMimeType + ", extension=" + extension);
                                        adapter.activity.runOnUiThread(() -> {
                                            fileLinkContainer.setVisibility(View.GONE);
                                            audioContainer.setVisibility(View.VISIBLE);
                                            setupAudioPlayer(decryptedFile.getAbsolutePath(), message);
                                            notifyItemChanged(position);
                                        });
                                    } else {
                                        adapter.activity.runOnUiThread(() -> {
                                            Log.e(TAG, "Failed to decrypt audio for messageId=" + message.getId());
                                            fileLinkContainer.setVisibility(View.VISIBLE);
                                            audioContainer.setVisibility(View.GONE);
                                            fileLinkContainer.setText("📄 Failed to decrypt: " + fetchedFileName);
                                            Toast.makeText(adapter.context, "Failed to decrypt audio", Toast.LENGTH_LONG).show();
                                        });
                                    }
                                } catch (IOException e) {
                                    Log.e(TAG, "Error downloading/decrypting audio for messageId=" + message.getId() + ": " + e.getMessage());
                                    adapter.activity.runOnUiThread(() -> {
                                        fileLinkContainer.setVisibility(View.VISIBLE);
                                        audioContainer.setVisibility(View.GONE);
                                        fileLinkContainer.setText("📄 Failed to load: " + fetchedFileName);
                                        Toast.makeText(adapter.context, "Error downloading audio: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                    });
                                }
                            }).start();
                        } else {
                            Log.e(TAG, "Failed to fetch metadata for audio messageId=" + message.getId() + ": " +
                                    (response != null ? response.message() : "Response is null"));
                            adapter.activity.runOnUiThread(() -> {
                                fileLinkContainer.setVisibility(View.VISIBLE);
                                audioContainer.setVisibility(View.GONE);
                                fileLinkContainer.setText("📄 Failed to load audio");
                                Toast.makeText(adapter.context, "Failed to fetch audio metadata", Toast.LENGTH_LONG).show();
                            });
                        }
                    }
                    @Override
                    public void onFailure(Call<FileMetadata> call, Throwable t) {
                        Log.e(TAG, "Error fetching audio metadata for messageId=" + message.getId() + ": " + t.getMessage());
                        adapter.activity.runOnUiThread(() -> {
                            fileLinkContainer.setVisibility(View.VISIBLE);
                            audioContainer.setVisibility(View.GONE);
                            fileLinkContainer.setText("📄 Failed to load audio");
                            Toast.makeText(adapter.context, "Error fetching audio metadata: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } finally {
                message.setBeingProcessed(false);
            }
        }

        private void openDecryptedFile(File file, String messageType) {
            try {
                if (!file.exists()) {
                    Log.e(TAG, "File does not exist: " + file.getAbsolutePath());
                    Toast.makeText(adapter.context, "File not found", Toast.LENGTH_SHORT).show();
                    return;
                }
                String mimeType = determineMimeType(file.getName());
                Uri fileUri = FileProvider.getUriForFile(adapter.context, adapter.context.getPackageName() + ".fileprovider", file);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(fileUri, mimeType);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                adapter.context.startActivity(Intent.createChooser(intent, "Open file"));
                Log.d(TAG, "Opening decrypted file: path=" + file.getAbsolutePath() + ", mimeType=" + mimeType);
            } catch (Exception e) {
                Log.e(TAG, "Error opening decrypted file: " + e.getMessage(), e);
                Toast.makeText(adapter.context, "Error opening file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        private String getFileExtension(String mimeType, String fileName) {
            Map<String, String> mimeToExtension = new HashMap<>();
            mimeToExtension.put("image/jpeg", "jpg");
            mimeToExtension.put("image/jpg", "jpg");
            mimeToExtension.put("image/png", "png");
            mimeToExtension.put("image/gif", "gif");
            mimeToExtension.put("image/bmp", "bmp");
            mimeToExtension.put("image/webp", "webp");
            mimeToExtension.put("audio/mpeg", "mp3");
            mimeToExtension.put("audio/wav", "wav");
            mimeToExtension.put("audio/ogg", "ogg");
            mimeToExtension.put("audio/aac", "aac");
            mimeToExtension.put("video/mp4", "mp4");
            mimeToExtension.put("video/mpeg", "mpeg");
            mimeToExtension.put("video/webm", "webm");
            mimeToExtension.put("video/3gpp", "3gp");
            mimeToExtension.put("video/avi", "avi");
            mimeToExtension.put("application/pdf", "pdf");
            mimeToExtension.put("application/msword", "doc");
            mimeToExtension.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
            mimeToExtension.put("application/vnd.ms-excel", "xls");
            mimeToExtension.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
            mimeToExtension.put("application/vnd.ms-powerpoint", "ppt");
            mimeToExtension.put("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
            mimeToExtension.put("text/plain", "txt");
            mimeToExtension.put("text/csv", "csv");
            mimeToExtension.put("application/zip", "zip");
            mimeToExtension.put("application/x-7z-compressed", "7z");
            mimeToExtension.put("application/x-rar-compressed", "rar");
            mimeToExtension.put("application/json", "json");
            mimeToExtension.put("text/html", "html");
            mimeToExtension.put("image/tiff", "tiff");

            String extension = mimeToExtension.get(mimeType.toLowerCase());
            if (extension != null) {
                return extension;
            }

            if (fileName != null && fileName.contains(".")) {
                try {
                    return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to extract extension from fileName=" + fileName + ", defaulting to bin");
                }
            }

            return "bin";
        }

        private boolean isImageMime(String mime) {
            return mime != null && mime.toLowerCase().startsWith("image/");
        }

        private boolean hasImageExtension(String name) {
            if (name == null) return false;
            String lower = name.toLowerCase();
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                    || lower.endsWith(".gif") || lower.endsWith(".webp");
        }

        private String determineMimeType(String fileName) {
            String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
            switch (extension) {
                case "mp3":
                    return "audio/mpeg";
                case "jpg":
                case "jpeg":
                    return "image/jpeg";
                case "png":
                    return "image/png";
                case "pdf":
                    return "application/pdf";
                default:
                    return "*/*";
            }
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
         * Подписи «ключа больше нет» и «не сохранено на устройстве» — это не
         * сообщения собеседника, а пояснение от приложения: показываем их
         * курсивом и приглушённым цветом, как и прочие пометки.
         */
        private void markNoticeStyle(TextView view, String text) {
            boolean notice = SecretPrivateChatActivity.KEY_GONE_NOTICE.equals(text)
                    || SecretPrivateChatActivity.OWN_TEXT_GONE_NOTICE.equals(text);
            view.setTypeface(null, notice ? android.graphics.Typeface.ITALIC : android.graphics.Typeface.NORMAL);
            view.setTextColor(notice ? 0xFF94A3B8 : 0xFFE2E8F0);
            view.setTextSize(notice ? 14 : 16);
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
    }

    private void downloadFile(String fileUrl) {
        try {
            String fileName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);
            String mimeType = determineMimeType(fileName);

            Log.d(TAG, "Downloading file: url=" + fileUrl + ", name=" + fileName + ", mimeType=" + mimeType);

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(fileUrl));
            request.setTitle(fileName);
            request.setDescription("Downloading file");
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setMimeType(mimeType);

            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            long downloadId = downloadManager.enqueue(request);

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
                    oldMessage.isFileDownloaded() == newMessage.isFileDownloaded() &&
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

    public Boolean getUserOnlineStatus(long userId) {
        return userStatuses.get(userId);
    }
}