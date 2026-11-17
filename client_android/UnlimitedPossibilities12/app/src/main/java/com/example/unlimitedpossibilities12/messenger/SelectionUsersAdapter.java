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
            // Выбранная строка светлее и с рамкой: прежде выбор был виден
            // только по мелкой галочке у правого края
            itemView.setActivated(isSelected);

            // Отключаем взаимодействие, если пользователь не выбран и лимит достигнут
            boolean isLimitReached = selectedUsers.size() >= 4 && !selectedUsers.contains(user);
            itemView.setEnabled(!isLimitReached);
            checkBox.setEnabled(!isLimitReached);
            usernameTextView.setAlpha(isLimitReached ? 0.5f : 1.0f);

            // Логика отображения аватара и первой буквы
            // Буква имени видна, пока снимок грузится, и остаётся, если его нет
            String name = user.getUsername() != null ? user.getUsername() : "?";
            avatarTextView.setText(name.substring(0, 1).toUpperCase(java.util.Locale.getDefault()));
            avatarTextView.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(colorForName(name)));
            avatarTextView.setVisibility(View.VISIBLE);
            avatarImageView.setImageDrawable(null);
            if (user.getAvatar() != null && !user.getAvatar().isEmpty()) {
                Glide.with(itemView.getContext())
                        .load("https://unlimitedpossibilities12.org" + user.getAvatar())
                        .circleCrop()
                        .into(avatarImageView);
                avatarTextView.setVisibility(View.GONE);
            }
        }
    }

    /** Цвет круга по имени — как на экранах звонка. */
    private int colorForName(String name) {
        int[] colors = {0xFF6366F1, 0xFF0D9488, 0xFF8B5CF6, 0xFF0EA5E9, 0xFFDB2777, 0xFF475569};
        int hash = 0;
        for (int i = 0; i < name.length(); i++) hash = hash * 31 + name.charAt(i);
        return colors[Math.abs(hash) % colors.length];
    }
}
