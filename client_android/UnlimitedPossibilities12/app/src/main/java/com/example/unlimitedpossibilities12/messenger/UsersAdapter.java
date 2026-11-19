package com.example.unlimitedpossibilities12.messenger;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Список людей.
 *
 * Имена идут по алфавиту и разбиты буквами, как в телефонной книге;
 * строка плоская и той же высоты, что в списке чатов, а кружок с буквой
 * красится от имени — тем же правилом, что и там, иначе два списка
 * выглядят как из разных приложений.
 */
public class UsersAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_LETTER = 0;
    private static final int TYPE_USER = 1;

    /** Та же палитра, что у кружков в списке чатов. */
    private static final int[] AVATAR_COLORS = {
            0xFF6366F1, 0xFF0D9488, 0xFF8B5CF6, 0xFF0EA5E9, 0xFFDB2777, 0xFF475569
    };

    private final List<User> users = new ArrayList<>();
    /** Что показываем: строка — это либо буква (String), либо человек (User). */
    private final List<Object> rows = new ArrayList<>();
    private final OnUserClickListener listener;
    private String term = "";

    public UsersAdapter(OnUserClickListener listener) {
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof User ? TYPE_USER : TYPE_LETTER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_LETTER) {
            return new LetterViewHolder(inflater.inflate(R.layout.item_user_letter, parent, false));
        }
        return new UserViewHolder(inflater.inflate(R.layout.item_user, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        Object row = rows.get(position);

        if (row instanceof String) {
            ((LetterViewHolder) viewHolder).letter.setText((String) row);
            return;
        }

        final User user = (User) row;
        final UserViewHolder holder = (UserViewHolder) viewHolder;

        holder.usernameTextView.setText(user.getVisibleName());

        // Под именем — описание, а если его нет, присутствие: «No bio» у
        // каждого второго человека не говорило ничего
        String bio = user.getBio();
        holder.bioTextView.setText(bio != null && !bio.trim().isEmpty()
                ? bio
                : (user.isOnline() ? "Online" : "Offline"));

        holder.statusIndicator.setVisibility(user.isOnline() ? View.VISIBLE : View.GONE);

        String letter = user.getUsername() == null || user.getUsername().isEmpty()
                ? "?" : user.getUsername().substring(0, 1).toUpperCase(Locale.getDefault());
        holder.avatarTextView.setText(letter);
        holder.avatarTextView.setBackgroundTintList(
                ColorStateList.valueOf(colorFor(user.getUsername())));

        if (user.getAvatar() != null && !user.getAvatar().isEmpty()) {
            String avatarUrl = "https://unlimitedpossibilities12.org" + user.getAvatar()
                    + "?t=" + System.currentTimeMillis();
            Glide.with(holder.itemView.getContext())
                    .load(avatarUrl)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .circleCrop()
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                    Target<Drawable> target, boolean isFirstResource) {
                            // Снимка на сервере может не быть — тогда буква имени,
                            // а не пустое место рядом со сдвинутой строкой
                            holder.avatarImageView.setImageDrawable(null);
                            holder.avatarTextView.setVisibility(View.VISIBLE);
                            return true;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model,
                                                       Target<Drawable> target, DataSource dataSource,
                                                       boolean isFirstResource) {
                            holder.avatarTextView.setVisibility(View.GONE);
                            return false;
                        }
                    })
                    .into(holder.avatarImageView);
            holder.avatarTextView.setVisibility(View.GONE);
        } else {
            holder.avatarImageView.setImageDrawable(null);
            holder.avatarTextView.setVisibility(View.VISIBLE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onUserClick(user);
            Intent intent = new Intent(holder.itemView.getContext(), UserProfileActivity.class);
            intent.putExtra("currentUserId",
                    ((MessengerMainActivity) holder.itemView.getContext()).getCurrentUserId());
            intent.putExtra("userId", user.getId());
            ((MessengerMainActivity) holder.itemView.getContext()).startActivityForResult(intent, 1);
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    public void filter(String term) {
        this.term = term == null ? "" : term;
        rebuild();
    }

    public void updateUsers(List<User> newUsers) {
        users.clear();
        if (newUsers != null) users.addAll(newUsers);
        rebuild();
    }

    public void updateUserStatus(long userId, boolean online) {
        boolean changed = false;
        for (User user : users) {
            if (user.getId() == userId && user.isOnline() != online) {
                user.setOnline(online);
                changed = true;
                break;
            }
        }
        if (!changed) return;

        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (row instanceof User && ((User) row).getId() == userId) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    /** Собирает показываемый список: имена по алфавиту, над каждой группой буква. */
    private void rebuild() {
        List<User> shown = new ArrayList<>();
        String needle = term.toLowerCase(Locale.getDefault());
        for (User user : users) {
            String name = user.getVisibleName() == null ? "" : user.getVisibleName();
            String login = user.getUsername() == null ? "" : user.getUsername();
            if (needle.isEmpty()
                    || name.toLowerCase(Locale.getDefault()).contains(needle)
                    || login.toLowerCase(Locale.getDefault()).contains(needle)) {
                shown.add(user);
            }
        }
        Collections.sort(shown, (left, right) -> {
            String a = left.getVisibleName() == null ? "" : left.getVisibleName();
            String b = right.getVisibleName() == null ? "" : right.getVisibleName();
            return a.compareToIgnoreCase(b);
        });

        rows.clear();
        String current = null;
        for (User user : shown) {
            String name = user.getVisibleName() == null || user.getVisibleName().isEmpty()
                    ? "#" : user.getVisibleName();
            String letter = name.substring(0, 1).toUpperCase(Locale.getDefault());
            if (!letter.equals(current)) {
                rows.add(letter);
                current = letter;
            }
            rows.add(user);
        }
        Log.d("UsersAdapter", "Строк в списке: " + rows.size() + " (людей " + shown.size() + ")");
        notifyDataSetChanged();
    }

    private int colorFor(String name) {
        if (name == null || name.isEmpty()) return AVATAR_COLORS[0];
        int hash = 0;
        for (int i = 0; i < name.length(); i++) hash = hash * 31 + name.charAt(i);
        return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
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

    static class LetterViewHolder extends RecyclerView.ViewHolder {
        TextView letter;

        LetterViewHolder(View itemView) {
            super(itemView);
            letter = itemView.findViewById(R.id.letter_text_view);
        }
    }

    interface OnUserClickListener {
        void onUserClick(User user);
    }
}
