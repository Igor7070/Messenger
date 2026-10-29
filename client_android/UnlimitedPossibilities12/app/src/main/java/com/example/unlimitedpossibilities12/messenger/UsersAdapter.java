package com.example.unlimitedpossibilities12.messenger;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.User;

import java.util.ArrayList;
import java.util.List;

public class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.UserViewHolder> {
    private List<User> users = new ArrayList<>();
    private List<User> filteredUsers = new ArrayList<>();
    private OnUserClickListener listener;

    public UsersAdapter(OnUserClickListener listener) {
        this.listener = listener;
    }

    @Override
    public UserViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(UserViewHolder holder, int position) {
        User user = filteredUsers.get(position);
        holder.usernameTextView.setText(user.getUsername());
        holder.bioTextView.setText(user.getBio() != null && !user.getBio().isEmpty() ? user.getBio() : "No bio");

        // Обновляем статус
        holder.statusIndicator.setVisibility(user.isOnline() ? View.VISIBLE : View.GONE);

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

        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(holder.itemView.getContext(), UserProfileActivity.class);
            intent.putExtra("currentUserId", ((MessengerMainActivity) holder.itemView.getContext()).getCurrentUserId());
            intent.putExtra("userId", user.getId());
            ((MessengerMainActivity) holder.itemView.getContext()).startActivityForResult(intent, 1);
        });
    }

    @Override
    public int getItemCount() {
        Log.d("UsersAdapter", "Item count: " + filteredUsers.size());
        return filteredUsers.size();
    }

    public void filter(String term) {
        filteredUsers.clear();
        if (term.isEmpty()) {
            filteredUsers.addAll(users);
        } else {
            for (User user : users) {
                if (user.getUsername().toLowerCase().contains(term.toLowerCase())) {
                    filteredUsers.add(user);
                }
            }
        }
        Log.d("UsersAdapter", "Filtered users size: " + filteredUsers.size());
        notifyDataSetChanged();
    }

    public void updateUsers(List<User> newUsers) {
        Log.d("UsersAdapter", "Received newUsers size: " + newUsers.size());
        this.users.clear();
        this.users.addAll(newUsers);
        this.filteredUsers.clear();
        this.filteredUsers.addAll(newUsers);
        Log.d("UsersAdapter", "Updated users size: " + filteredUsers.size());
        notifyDataSetChanged();
    }

    public void updateUserStatus(long userId, boolean online) {
        int position = -1;
        for (int i = 0; i < filteredUsers.size(); i++) {
            User user = filteredUsers.get(i);
            if (user.getId() == userId && user.isOnline() != online) { // Проверяем, изменился ли статус
                user.setOnline(online);
                position = i;
                Log.d("UsersAdapter", "Status changed for userId: " + userId + ", online: " + online + ", position: " + position);
                break;
            }
        }
        for (User user : users) {
            if (user.getId() == userId) {
                user.setOnline(online);
                break;
            }
        }
        if (position != -1) {
            notifyItemChanged(position); // Обновляем только изменившийся элемент
            Log.d("UsersAdapter", "Notified item changed at position: " + position);
        } else {
            Log.d("UsersAdapter", "No UI update needed for userId: " + userId);
        }
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView usernameTextView, avatarTextView, bioTextView;
        ImageView avatarImageView;
        View statusIndicator;

        UserViewHolder(View itemView) {
            super(itemView);
            usernameTextView = itemView.findViewById(R.id.username_text_view);
            avatarImageView = itemView.findViewById(R.id.avatar_image_view);
            avatarTextView = itemView.findViewById(R.id.avatar_text_view);
            bioTextView = itemView.findViewById(R.id.bio_text_view);
            statusIndicator = itemView.findViewById(R.id.status_indicator);
        }
    }

    interface OnUserClickListener {
        void onUserClick(User user);
    }
}
