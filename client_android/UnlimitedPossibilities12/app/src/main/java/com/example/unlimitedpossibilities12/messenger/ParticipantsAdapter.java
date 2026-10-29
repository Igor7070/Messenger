package com.example.unlimitedpossibilities12.messenger;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.User;
import java.util.List;

public class ParticipantsAdapter extends RecyclerView.Adapter<ParticipantsAdapter.ParticipantViewHolder> {
    private List<User> participants;
    // [ДОБАВЛЕНО]: Callback для обработки нажатий на аватар
    private OnAvatarClickListener avatarClickListener;

    // [ДОБАВЛЕНО]: Интерфейс для обработки кликов
    public interface OnAvatarClickListener {
        void onAvatarClick(int userId);
    }

    // [ИЗМЕНЕНО]: Конструктор принимает callback
    public ParticipantsAdapter(List<User> participants, OnAvatarClickListener avatarClickListener) {
        this.participants = participants;
        this.avatarClickListener = avatarClickListener;
    }

    @NonNull
    @Override
    public ParticipantViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_participant, parent, false);
        return new ParticipantViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ParticipantViewHolder holder, int position) {
        User user = participants.get(position);
        holder.usernameTextView.setText(user.getUsername());

        if (user.getAvatar() != null && !user.getAvatar().isEmpty()) {
            String avatarUrl = "https://unlimitedpossibilities12.org" + user.getAvatar() + "?t=" + System.currentTimeMillis();
            Glide.with(holder.itemView.getContext())
                    .load(avatarUrl)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .circleCrop()
                    .into(holder.avatarImageView);
            holder.avatarTextView.setVisibility(View.GONE);
        } else {
            holder.avatarImageView.setImageResource(R.drawable.default_messenger_avatar);
            holder.avatarTextView.setText(user.getUsername().substring(0, 1).toUpperCase());
            holder.avatarTextView.setVisibility(View.VISIBLE);
        }

        // [ДОБАВЛЕНО]: Обработка клика по аватару
        holder.avatarImageView.setOnClickListener(v -> {
            if (avatarClickListener != null) {
                avatarClickListener.onAvatarClick(user.getId());
            }
        });
    }

    @Override
    public int getItemCount() {
        return participants.size();
    }

    static class ParticipantViewHolder extends RecyclerView.ViewHolder {
        TextView usernameTextView, avatarTextView;
        ImageView avatarImageView;

        ParticipantViewHolder(@NonNull View itemView) {
            super(itemView);
            usernameTextView = itemView.findViewById(R.id.username_text_view);
            avatarImageView = itemView.findViewById(R.id.avatar_image_view);
            avatarTextView = itemView.findViewById(R.id.avatar_text_view);
        }
    }
}
