package com.example.unlimitedpossibilities12.messenger.offline;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.example.unlimitedpossibilities12.R;

import java.util.List;

/**
 * Адаптер для списка найденных устройств в оффлайн-режиме
 */
public class OfflineUsersAdapter extends RecyclerView.Adapter<OfflineUsersAdapter.ViewHolder> {

    private final List<NearbyUser> users;
    private final OnUserClickListener listener;
    private final OfflineAvatarStorage avatarStorage;

    public interface OnUserClickListener {
        void onUserClick(NearbyUser user);
    }

    public OfflineUsersAdapter(List<NearbyUser> users, OnUserClickListener listener, OfflineAvatarStorage avatarStorage) {
        this.users = users;
        this.listener = listener;
        this.avatarStorage = avatarStorage;
    }

    // Конструктор для обратной совместимости (если где-то используется без storage)
    public OfflineUsersAdapter(List<NearbyUser> users, OnUserClickListener listener) {
        this(users, listener, null);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_offline_user, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        NearbyUser user = users.get(position);

        holder.nameTextView.setText(user.getName());

        // Сокращённый Endpoint ID
        String endpointId = user.getEndpointId();
        String shortId = endpointId.length() > 8
                ? endpointId.substring(0, 8) + "..."
                : endpointId;

        holder.endpointTextView.setText("ID: " + shortId);

        // === ЗАГРУЗКА АВАТАРА ===
        loadAvatarIntoView(holder, user);

        holder.itemView.setOnClickListener(v -> listener.onUserClick(user));
    }

    private void loadAvatarIntoView(ViewHolder holder, NearbyUser user) {
        Uri avatarUri = user.getAvatarUri();

        // Если аватар уже есть в объекте — используем его
        if (avatarUri != null) {
            loadWithGlide(holder, avatarUri);
            return;
        }

        // Если аватара нет в объекте — пытаемся взять из хранилища
        if (avatarStorage != null) {
            Uri savedUri = avatarStorage.getAvatar(user.getEndpointId());
            if (savedUri != null) {
                user.setAvatarUri(savedUri);           // кэшируем в объект
                loadWithGlide(holder, savedUri);
                return;
            }
        }

        // Если аватара нет вообще — показываем плейсхолдер
        holder.avatarImageView.setImageResource(R.drawable.default_avatar_offline);
    }

    private void loadWithGlide(ViewHolder holder, Uri avatarUri) {
        Glide.with(holder.itemView.getContext())
                .load(avatarUri)
                .transform(new CircleCrop())
                .placeholder(R.drawable.default_avatar_offline)
                .error(R.drawable.default_avatar_offline)
                .into(holder.avatarImageView);
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView avatarImageView;
        TextView nameTextView;
        TextView endpointTextView;

        ViewHolder(View itemView) {
            super(itemView);
            avatarImageView = itemView.findViewById(R.id.user_avatar);
            nameTextView = itemView.findViewById(R.id.user_name);
            endpointTextView = itemView.findViewById(R.id.user_endpoint);
        }
    }
}
