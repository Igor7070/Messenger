package com.example.unlimitedpossibilities12.messenger;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.User;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Участники группового звонка в голосовом режиме.
 *
 * Раньше строка показывала только имя и снимок, причём снимок грузился мимо
 * кэша и без запасного варианта: не загрузился — оставалось пустое место.
 * И по списку нельзя было понять, кто уже в разговоре, а кому ещё звонит.
 */
public class ParticipantsAdapter extends RecyclerView.Adapter<ParticipantsAdapter.ParticipantViewHolder> {
    private final List<User> participants;
    private final OnAvatarClickListener avatarClickListener;

    /** Кто уже в разговоре; остальным ещё звонит. */
    private Set<String> joined = Collections.emptySet();
    /** Кто сейчас говорит. */
    private Set<String> speaking = Collections.emptySet();
    private String currentUserId;
    private boolean selfMuted;

    public interface OnAvatarClickListener {
        void onAvatarClick(int userId);
    }

    public ParticipantsAdapter(List<User> participants, OnAvatarClickListener avatarClickListener) {
        this.participants = participants;
        this.avatarClickListener = avatarClickListener;
    }

    /** Состояние разговора: кто ответил, кто говорит, выключен ли свой микрофон. */
    public void updateState(Set<String> joined, Set<String> speaking, String currentUserId, boolean selfMuted) {
        this.joined = joined != null ? joined : Collections.emptySet();
        this.speaking = speaking != null ? speaking : Collections.emptySet();
        this.currentUserId = currentUserId;
        this.selfMuted = selfMuted;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ParticipantViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_participant, parent, false);
        return new ParticipantViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ParticipantViewHolder holder, int position) {
        User user = participants.get(position);
        String id = String.valueOf(user.getId());
        String name = user.getUsername() != null ? user.getUsername() : "?";
        boolean isSelf = id.equals(currentUserId);

        holder.usernameTextView.setText(isSelf
                ? holder.itemView.getContext().getString(R.string.call_participant_you, name)
                : name);

        // Буква на цветном круге — она же видна, пока грузится снимок
        holder.avatarTextView.setText(name.substring(0, 1).toUpperCase(Locale.getDefault()));
        holder.avatarTextView.setBackgroundTintList(ColorStateList.valueOf(colorForName(name)));
        holder.avatarTextView.setVisibility(View.VISIBLE);
        holder.avatarImageView.setImageDrawable(null);
        if (user.getAvatar() != null && !user.getAvatar().isEmpty()) {
            Glide.with(holder.itemView.getContext())
                    .load("https://unlimitedpossibilities12.org" + user.getAvatar())
                    .circleCrop()
                    .into(holder.avatarImageView);
            holder.avatarTextView.setVisibility(View.GONE);
        }

        boolean inCall = isSelf || joined.contains(id);
        holder.participantState.setText(inCall ? R.string.call_participant_in_call : R.string.call_participant_ringing);
        holder.participantState.setTextColor(inCall ? 0xFF34D399 : 0xFF94A3B8);

        holder.speakingDot.setVisibility(speaking.contains(id) ? View.VISIBLE : View.INVISIBLE);
        // О чужом микрофоне мы пока не знаем — показываем только свой
        holder.micOffIcon.setVisibility(isSelf && selfMuted ? View.VISIBLE : View.GONE);

        holder.avatarImageView.setOnClickListener(v -> {
            if (avatarClickListener != null) avatarClickListener.onAvatarClick(user.getId());
        });
    }

    /** Цвет круга по имени — тот же приём, что на остальных экранах звонка. */
    private int colorForName(String name) {
        int[] colors = {0xFF6366F1, 0xFF0D9488, 0xFF8B5CF6, 0xFF0EA5E9, 0xFFDB2777, 0xFF475569};
        int hash = 0;
        for (int i = 0; i < name.length(); i++) hash = hash * 31 + name.charAt(i);
        return colors[Math.abs(hash) % colors.length];
    }

    @Override
    public int getItemCount() {
        return participants.size();
    }

    static class ParticipantViewHolder extends RecyclerView.ViewHolder {
        TextView usernameTextView, avatarTextView, participantState;
        ImageView avatarImageView, micOffIcon;
        View speakingDot;

        ParticipantViewHolder(@NonNull View itemView) {
            super(itemView);
            usernameTextView = itemView.findViewById(R.id.username_text_view);
            avatarImageView = itemView.findViewById(R.id.avatar_image_view);
            avatarTextView = itemView.findViewById(R.id.avatar_text_view);
            participantState = itemView.findViewById(R.id.participant_state);
            speakingDot = itemView.findViewById(R.id.speaking_dot);
            micOffIcon = itemView.findViewById(R.id.mic_off_icon);
        }
    }
}
