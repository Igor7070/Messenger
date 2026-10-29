package com.example.unlimitedpossibilities12.messenger;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.User;

import java.util.ArrayList;
import java.util.List;

public class SelectionUsersAdapter extends RecyclerView.Adapter<SelectionUsersAdapter.UserViewHolder> {
    private List<User> usersList = new ArrayList<>();
    private List<User> selectedUsers = new ArrayList<>();
    private final OnUserClickListener clickListener;

    public interface OnUserClickListener {
        void onUserClick(User user);
    }

    public SelectionUsersAdapter(OnUserClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void updateUsers(List<User> users) {
        this.usersList = users;
        notifyDataSetChanged();
    }

    public void updateSelectedUsers(List<User> selectedUsers) {
        this.selectedUsers = selectedUsers;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user_selection, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        User user = usersList.get(position);
        holder.bind(user, selectedUsers.contains(user));
    }

    @Override
    public int getItemCount() {
        return usersList.size();
    }

    class UserViewHolder extends RecyclerView.ViewHolder {
        private final ImageView avatarImageView;
        private final TextView usernameTextView;
        private final TextView avatarTextView;
        private final CheckBox checkBox;

        public UserViewHolder(@NonNull View itemView) {
            super(itemView);
            avatarImageView = itemView.findViewById(R.id.avatar_image_view);
            usernameTextView = itemView.findViewById(R.id.username_text_view);
            avatarTextView = itemView.findViewById(R.id.avatar_text_view);
            checkBox = itemView.findViewById(R.id.user_check_box);

            // Обработчик клика для itemView
            itemView.setOnClickListener(v -> {
                clickListener.onUserClick(usersList.get(getAdapterPosition()));
            });

            // Обработчик клика для CheckBox
            checkBox.setOnClickListener(v -> {
                clickListener.onUserClick(usersList.get(getAdapterPosition()));
            });
        }

        public void bind(User user, boolean isSelected) {
            usernameTextView.setText(user.getUsername());
            checkBox.setChecked(isSelected);

            // Отключаем взаимодействие, если пользователь не выбран и лимит достигнут
            boolean isLimitReached = selectedUsers.size() >= 4 && !selectedUsers.contains(user);
            itemView.setEnabled(!isLimitReached);
            checkBox.setEnabled(!isLimitReached);
            usernameTextView.setAlpha(isLimitReached ? 0.5f : 1.0f);

            // Логика отображения аватара и первой буквы
            if (user.getAvatar() != null && !user.getAvatar().isEmpty()) {
                String avatarUrl = "https://unlimitedpossibilities12.org" + user.getAvatar() + "?t=" + System.currentTimeMillis();
                Glide.with(itemView.getContext())
                        .load(avatarUrl)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .circleCrop()
                        .into(avatarImageView);
                avatarTextView.setVisibility(View.GONE);
            } else {
                avatarImageView.setImageResource(R.drawable.default_messenger_avatar);
                avatarTextView.setText(user.getUsername().substring(0, 1).toUpperCase());
                avatarTextView.setVisibility(View.VISIBLE);
            }
        }
    }
}
