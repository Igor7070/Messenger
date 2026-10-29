package com.example.unlimitedpossibilities12.messenger.offline;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.messenger.LocalTranslationBinder;
import com.example.unlimitedpossibilities12.messenger.SecretText;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class OfflineChatAdapter extends RecyclerView.Adapter<OfflineChatAdapter.ViewHolder> {

    private final List<OfflineMessage> messages;
    private final OnFileClickListener fileClickListener;
    private final OnMessageLongClickListener longClickListener;

    private final Uri partnerAvatarUri;   // ← Изменено: теперь просто Uri

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    // MediaPlayer
    private MediaPlayer mediaPlayer;
    private ViewHolder currentlyPlayingHolder = null;
    private Uri currentlyPlayingUri = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateSeekBarRunnable;

    /** Пометка доставки у своих сообщений: ожидает / отправлено / доставлено. */
    private static String statusMark(com.example.unlimitedpossibilities12.messenger.offline.OfflineMessage m) {
        if (!m.isMine()) return "";
        switch (m.getStatus()) {
            case OfflineMessage.STATUS_QUEUED: return "  ⏳";
            case OfflineMessage.STATUS_DELIVERED: return "  ✓✓";
            default: return "  ✓";
        }
    }
    private LocalTranslationBinder translationBinder; // перевод на устройстве (null = выключен)

    public void setTranslationBinder(LocalTranslationBinder binder) { this.translationBinder = binder; }

    public OfflineChatAdapter(List<OfflineMessage> messages,
                              OnFileClickListener fileClickListener,
                              OnMessageLongClickListener longClickListener,
                              Uri partnerAvatarUri) {   // ← упрощённый конструктор

        this.messages = messages;
        this.fileClickListener = fileClickListener;
        this.longClickListener = longClickListener;
        this.partnerAvatarUri = partnerAvatarUri;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_offline_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        OfflineMessage message = messages.get(position);

        if (message.isSystemMessage()) {
            holder.itemView.setVisibility(View.GONE);
            return;
        } else {
            holder.itemView.setVisibility(View.VISIBLE);
        }

        String time = TIME_FORMAT.format(new Date(message.getTimestamp()));

        // Полная очистка
        holder.outgoingBubble.setVisibility(View.GONE);
        holder.incomingBubble.setVisibility(View.GONE);
        holder.outgoingAudioLayout.setVisibility(View.GONE);
        holder.incomingAudioLayout.setVisibility(View.GONE);
        holder.outgoingImage.setVisibility(View.GONE);
        holder.incomingImage.setVisibility(View.GONE);
        holder.outgoingText.setVisibility(View.GONE);
        holder.incomingText.setVisibility(View.GONE);
        holder.outgoingCaption.setVisibility(View.GONE);
        holder.incomingCaption.setVisibility(View.GONE);
        holder.outgoingTranslation.setVisibility(View.GONE);
        holder.incomingTranslation.setVisibility(View.GONE);
        holder.messageAvatar.setVisibility(View.GONE);

        if (message.isMine()) {
            holder.outgoingBubble.setVisibility(View.VISIBLE);

            if (message.isAudio() && message.getFileUri() != null) {
                setupAudioHolder(holder, message, true);
                holder.outgoingTime.setText(time);
            } else if (message.isImage() && message.getFileUri() != null) {
                setupImageHolder(holder, message, true);
                holder.outgoingTime.setText(time);
            } else if (message.isFile() && message.getFileUri() != null) {
                setupFileHolder(holder, message, true);
                holder.outgoingTime.setText(time);
            } else {
                // Текст может быть обёрткой {"text","translated","lang"} — перевод отправителя
                SecretText st = SecretText.parse(message.getText());
                holder.outgoingText.setVisibility(View.VISIBLE);
                holder.outgoingText.setText(st.text);
                holder.outgoingTime.setText(time + statusMark(message));
                if (translationBinder != null) translationBinder.bind(holder.outgoingTranslation, message.getMessageId(), st);
            }
        } else {
            // Входящее сообщение
            holder.incomingBubble.setVisibility(View.VISIBLE);
            holder.messageAvatar.setVisibility(View.VISIBLE);

            loadAvatar(holder);   // ← Загрузка аватара

            if (message.isAudio() && message.getFileUri() != null) {
                setupAudioHolder(holder, message, false);
                holder.incomingTime.setText(time);
            } else if (message.isImage() && message.getFileUri() != null) {
                setupImageHolder(holder, message, false);
                holder.incomingTime.setText(time);
            } else if (message.isFile() && message.getFileUri() != null) {
                setupFileHolder(holder, message, false);
                holder.incomingTime.setText(time);
            } else {
                SecretText st = SecretText.parse(message.getText());
                holder.incomingText.setVisibility(View.VISIBLE);
                holder.incomingText.setText(st.text);
                holder.incomingTime.setText(time);
                if (translationBinder != null) translationBinder.bind(holder.incomingTranslation, message.getMessageId(), st);
            }
        }

        // Долгое нажатие
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null && !message.isSystemMessage()) {
                longClickListener.onMessageLongClick(message, position);
            }
            return true;
        });
    }

    // ==================== ЗАГРУЗКА АВАТАРА ====================
    private void loadAvatar(ViewHolder holder) {
        holder.messageAvatar.setVisibility(View.VISIBLE);

        if (partnerAvatarUri != null) {
            Glide.with(holder.itemView.getContext())
                    .load(partnerAvatarUri)
                    .circleCrop()
                    .placeholder(R.drawable.default_avatar_offline)
                    .into(holder.messageAvatar);
        } else {
            Glide.with(holder.itemView.getContext())
                    .load(R.drawable.default_avatar_offline)
                    .circleCrop()
                    .into(holder.messageAvatar);
        }
    }

    // ==================== АУДИО ====================
    // (остальной код без изменений)
    private void setupAudioHolder(ViewHolder holder, OfflineMessage message, boolean isMine) {
        LinearLayout audioLayout = isMine ? holder.outgoingAudioLayout : holder.incomingAudioLayout;
        ImageView playButton = isMine ? holder.outgoingAudioPlay : holder.incomingAudioPlay;
        SeekBar seekBar = isMine ? holder.outgoingAudioSeekbar : holder.incomingAudioSeekbar;
        TextView durationTv = isMine ? holder.outgoingAudioDuration : holder.incomingAudioDuration;

        audioLayout.setVisibility(View.VISIBLE);
        playButton.setImageResource(R.drawable.ic_play);
        seekBar.setProgress(0);
        durationTv.setText("0:00 / 0:00");

        playButton.setOnClickListener(v -> {
            if (mediaPlayer != null && currentlyPlayingUri != null &&
                    currentlyPlayingUri.equals(message.getFileUri())) {

                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    playButton.setImageResource(R.drawable.ic_play);
                } else {
                    mediaPlayer.start();
                    playButton.setImageResource(R.drawable.ic_pause);
                    startSeekBarUpdate(holder, seekBar, durationTv);
                }
            } else {
                playAudio(message.getFileUri(), holder, playButton, seekBar, durationTv);
            }
        });

        if (message.getFileUri() == null) {
            playButton.setEnabled(false);
        }
    }

    private void playAudio(Uri uri, ViewHolder holder, ImageView playButton,
                           SeekBar seekBar, TextView durationTv) {
        releaseMediaPlayer();

        try {
            mediaPlayer = new MediaPlayer();

            if (uri.toString().startsWith("content://")) {
                mediaPlayer.setDataSource(holder.itemView.getContext(), uri);
            } else {
                mediaPlayer.setDataSource(uri.getPath());
            }

            mediaPlayer.prepareAsync();

            mediaPlayer.setOnPreparedListener(mp -> {
                currentlyPlayingHolder = holder;
                currentlyPlayingUri = uri;
                mp.start();
                playButton.setImageResource(R.drawable.ic_pause);
                seekBar.setMax(mp.getDuration());

                String totalDuration = formatDuration(mp.getDuration());
                durationTv.setText("0:00 / " + totalDuration);

                startSeekBarUpdate(holder, seekBar, durationTv);
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                playButton.setImageResource(R.drawable.ic_play);
                stopSeekBarUpdate();
                String total = formatDuration(mp.getDuration());
                durationTv.setText(total + " / " + total);
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e("Audio", "MediaPlayer error: " + what + ", " + extra);
                releaseMediaPlayer();
                playButton.setImageResource(R.drawable.ic_play);
                durationTv.setText("0:00 / 0:00");
                return true;
            });

        } catch (Exception e) {
            Log.e("Audio", "Error playing audio", e);
            Toast.makeText(holder.itemView.getContext(), "Failed to play audio", Toast.LENGTH_SHORT).show();
        }
    }

    private void startSeekBarUpdate(ViewHolder holder, SeekBar seekBar, TextView durationTv) {
        stopSeekBarUpdate();

        updateSeekBarRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying() && holder == currentlyPlayingHolder) {
                    int currentPos = mediaPlayer.getCurrentPosition();
                    seekBar.setProgress(currentPos);

                    String current = formatDuration(currentPos);
                    String total = formatDuration(mediaPlayer.getDuration());
                    durationTv.setText(current + " / " + total);

                    handler.postDelayed(this, 200);
                }
            }
        };
        handler.post(updateSeekBarRunnable);
    }

    private void stopSeekBarUpdate() {
        if (updateSeekBarRunnable != null) {
            handler.removeCallbacks(updateSeekBarRunnable);
        }
    }

    private void releaseMediaPlayer() {
        stopSeekBarUpdate();
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        currentlyPlayingHolder = null;
        currentlyPlayingUri = null;
    }

    private String formatDuration(int milliseconds) {
        if (milliseconds <= 0) return "0:00";
        int totalSeconds = milliseconds / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + String.format(Locale.getDefault(), "%02d", seconds);
    }

    private void setupImageHolder(ViewHolder holder, OfflineMessage message, boolean isMine) {
        ImageView imageView = isMine ? holder.outgoingImage : holder.incomingImage;
        imageView.setVisibility(View.VISIBLE);

        Glide.with(holder.itemView.getContext())
                .load(message.getFileUri())
                .centerCrop()
                .into(imageView);

        imageView.setOnClickListener(v ->
                FullScreenImageOfflineActivity.open(holder.itemView.getContext(), message.getFileUri()));
    }

    private void setupFileHolder(ViewHolder holder, OfflineMessage message, boolean isMine) {
        ImageView imageView = isMine ? holder.outgoingImage : holder.incomingImage;
        TextView textView = isMine ? holder.outgoingText : holder.incomingText;

        imageView.setVisibility(View.VISIBLE);
        textView.setVisibility(View.VISIBLE);

        imageView.setImageResource(R.drawable.ic_file);
        imageView.getLayoutParams().width = 56;
        imageView.getLayoutParams().height = 56;

        String displayName = message.getFileName() != null ? message.getFileName() : "[File]";
        textView.setText(displayName);

        if (fileClickListener != null) {
            View.OnClickListener listener = v ->
                    fileClickListener.onFileClick(message.getFileUri(), message.getFileName(), message.getMimeType());

            imageView.setOnClickListener(listener);
            textView.setOnClickListener(listener);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    // ====================== ViewHolder ======================
    static class ViewHolder extends RecyclerView.ViewHolder {
        // ... твой текущий ViewHolder без изменений ...
        LinearLayout outgoingBubble, incomingBubble;

        TextView outgoingText, outgoingTime, outgoingCaption;
        TextView incomingText, incomingTime, incomingCaption;
        TextView outgoingTranslation, incomingTranslation; // перевод на устройстве

        ImageView outgoingImage, incomingImage;
        ImageView messageAvatar;

        LinearLayout outgoingAudioLayout, incomingAudioLayout;
        ImageView outgoingAudioPlay, incomingAudioPlay;
        SeekBar outgoingAudioSeekbar, incomingAudioSeekbar;
        TextView outgoingAudioDuration, incomingAudioDuration;

        ViewHolder(View itemView) {
            super(itemView);
            outgoingBubble = itemView.findViewById(R.id.bubble_outgoing);
            incomingBubble = itemView.findViewById(R.id.bubble_incoming);

            outgoingText = itemView.findViewById(R.id.message_text);
            outgoingTime = itemView.findViewById(R.id.message_time);
            outgoingCaption = itemView.findViewById(R.id.message_caption_out);
            outgoingTranslation = itemView.findViewById(R.id.message_translation_out);
            incomingTranslation = itemView.findViewById(R.id.message_translation_in);

            incomingText = itemView.findViewById(R.id.message_text_in);
            incomingTime = itemView.findViewById(R.id.message_time_in);
            incomingCaption = itemView.findViewById(R.id.message_caption_in);

            outgoingImage = itemView.findViewById(R.id.outgoing_image);
            incomingImage = itemView.findViewById(R.id.incoming_image);

            messageAvatar = itemView.findViewById(R.id.message_avatar);

            outgoingAudioLayout = itemView.findViewById(R.id.outgoing_audio_layout);
            incomingAudioLayout = itemView.findViewById(R.id.incoming_audio_layout);

            outgoingAudioPlay = itemView.findViewById(R.id.outgoing_audio_play);
            incomingAudioPlay = itemView.findViewById(R.id.incoming_audio_play);

            outgoingAudioSeekbar = itemView.findViewById(R.id.outgoing_audio_seekbar);
            incomingAudioSeekbar = itemView.findViewById(R.id.incoming_audio_seekbar);

            outgoingAudioDuration = itemView.findViewById(R.id.outgoing_audio_duration);
            incomingAudioDuration = itemView.findViewById(R.id.incoming_audio_duration);
        }
    }

    // Интерфейсы
    public interface OnFileClickListener {
        void onFileClick(Uri fileUri, String fileName, String mimeType);
    }

    public interface OnMessageLongClickListener {
        void onMessageLongClick(OfflineMessage message, int position);
    }

    public void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        currentlyPlayingHolder = null;
        currentlyPlayingUri = null;
    }
}
