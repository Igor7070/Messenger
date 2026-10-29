package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.Chat;
import com.example.unlimitedpossibilities12.api.User;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PrivateChatsAdapter extends RecyclerView.Adapter<PrivateChatsAdapter.ChatViewHolder> {
    private static final String TAG = "PrivateChatsAdapter";
    private final Context context;
    private final List<Chat> allChats; // Полный список чатов
    private final List<Chat> filteredChats; // Отфильтрованный список чатов
    private final Map<Long, User> chatPartners;
    private final String currentUserName;
    private final OnChatClickListener onChatClickListener;
    private final OnChatLongClickListener onChatLongClickListener;

    public interface OnChatClickListener {
        void onChatClick(long chatId);
    }

    public interface OnChatLongClickListener {
        void onChatLongClick(long chatId, View view);
    }

    public PrivateChatsAdapter(Context context, List<Chat> chats, Map<Long, User> chatPartners,
                               String currentUserName, OnChatClickListener onChatClickListener,
                               OnChatLongClickListener onChatLongClickListener) {
        this.context = context;
        this.allChats = new ArrayList<>(chats);
        this.filteredChats = new ArrayList<>(chats);
        this.chatPartners = chatPartners;
        this.currentUserName = currentUserName;
        this.onChatClickListener = onChatClickListener;
        this.onChatLongClickListener = onChatLongClickListener;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_chat_private, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        Chat chat = filteredChats.get(position);
        User partner = chatPartners.get(chat.getId());
        String partnerName = getChatPartnerName(chat.getName());

        holder.partnerNameTextView.setText(partnerName);

        // Показываем замок только для секретных чатов
        ImageView secretIcon = holder.itemView.findViewById(R.id.secret_chat_icon);
        if (secretIcon != null) {
            if (chat.getIsSecret() != null && chat.getIsSecret()) {
                secretIcon.setVisibility(View.VISIBLE);
                Log.d(TAG, "Showing secret lock for chat id=" + chat.getId());
            } else {
                secretIcon.setVisibility(View.GONE);
            }
        } else {
            Log.w(TAG, "secret_chat_icon not found in item layout for position " + position);
        }

        // Сначала сразу ставим заглушку, чтобы не было пустоты
        holder.avatarImageView.setImageResource(R.drawable.default_messenger_avatar);
        holder.avatarImageView.setVisibility(View.VISIBLE);
        holder.avatarTextView.setVisibility(View.GONE);

        if (partner != null && partner.getAvatar() != null && !partner.getAvatar().isEmpty()) {
            String avatarUrl = "https://unlimitedpossibilities12.org" + partner.getAvatar(); // ← Убрали ?t=

            Log.d(TAG, "Loading avatar for " + partnerName + ": " + avatarUrl);

            Glide.with(context)
                    .load(avatarUrl)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)          // ← Кэш включён
                    .placeholder(R.drawable.default_messenger_avatar)         // ← Пока грузится — дефолт
                    .error(R.drawable.default_messenger_avatar)
                    .circleCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(300)) // ← Плавное появление
                    .into(holder.avatarImageView);
        } else {
            Log.d(TAG, "Using default avatar for " + partnerName);
            holder.avatarTextView.setText(partnerName.isEmpty() ? "" : partnerName.substring(0, 1).toUpperCase());
            holder.avatarTextView.setVisibility(View.VISIBLE);
            holder.avatarImageView.setVisibility(View.GONE);
        }

        // Установка индикатора онлайна
        if (partner != null && partner.isOnline()) {
            holder.statusIndicator.setVisibility(View.VISIBLE);
            Log.d(TAG, "Showing online indicator for " + partnerName);
        } else {
            holder.statusIndicator.setVisibility(View.GONE);
            Log.d(TAG, "Hiding online indicator for " + partnerName);
        }

        // Установка последнего сообщения и временной метки
        String lastMessage = chat.getLastMessage() != null ? chat.getLastMessage() : "No messages yet";
        String timestamp = "";
        if (chat.getTimestamp() != null) {
            try {
                ZonedDateTime utcTime = ZonedDateTime.parse(chat.getTimestamp(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
                ZonedDateTime kievTime = utcTime.withZoneSameInstant(java.time.ZoneId.of("Europe/Kiev"));
                ZonedDateTime now = ZonedDateTime.now(java.time.ZoneId.of("Europe/Kiev"));
                if (kievTime.toLocalDate().isEqual(now.toLocalDate())) {
                    timestamp = kievTime.format(DateTimeFormatter.ofPattern("HH:mm"));
                } else {
                    timestamp = kievTime.format(DateTimeFormatter.ofPattern("dd.MM.yy"));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse timestamp for chat " + chat.getId() + ": " + e.getMessage());
            }
        }
        holder.lastMessageTextView.setText(lastMessage);
        holder.timestampTextView.setText(timestamp);

        // Установка индикатора непрочитанных сообщений
        if (chat.getUnreadCount() > 0) {
            holder.unreadIndicator.setText(String.valueOf(chat.getUnreadCount()));
            holder.unreadIndicator.setVisibility(View.VISIBLE);
        } else {
            holder.unreadIndicator.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> onChatClickListener.onChatClick(chat.getId()));
        holder.itemView.setOnLongClickListener(v -> {
            onChatLongClickListener.onChatLongClick(chat.getId(), v);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return filteredChats.size();
    }

    private String getChatPartnerName(String chatName) {
        String[] names = chatName.split("_");
        for (String name : names) {
            if (!name.equals(currentUserName)) {
                return name;
            }
        }
        return "";
    }

    public void updateChats(List<Chat> newChats) {
        Log.d(TAG, "Updating chats, received " + newChats.size() + " chats");
        for (Chat chat : newChats) {
            Log.d(TAG, "Received chat: id=" + chat.getId() + ", name=" + chat.getName());
        }
        allChats.clear();
        allChats.addAll(newChats);
        filteredChats.clear();
        filteredChats.addAll(newChats);
        Log.d(TAG, "Chats list updated, now contains " + filteredChats.size() + " chats");
        notifyDataSetChanged();
    }

    public void updateChatPartners(Map<Long, User> newPartners) {
        Log.d(TAG, "Updating chat partners, received " + newPartners.size() + " partners");
        chatPartners.clear();
        chatPartners.putAll(newPartners);
        notifyDataSetChanged();
    }

    public void updatePartnerStatus(long userId, boolean online) {
        int position = -1;
        boolean partnerFound = false;
        Log.d(TAG, "updatePartnerStatus called for userId=" + userId + ", online=" + online + ", filteredChats size=" + filteredChats.size());

        // Обновляем статус в filteredChats
        for (int i = 0; i < filteredChats.size(); i++) {
            Chat chat = filteredChats.get(i);
            User partner = chatPartners.get(chat.getId());
            if (partner != null) {
                Log.d(TAG, "Checking partner for chatId=" + chat.getId() + ", partnerId=" + partner.getId() + ", currentOnline=" + partner.isOnline());
                if (partner.getId() == userId) {
                    partner.setOnline(online);
                    position = i;
                    partnerFound = true;
                    Log.d(TAG, "Status updated for partner userId=" + userId + ", online=" + online + ", position=" + position);
                    break;
                }
            } else {
                Log.w(TAG, "No partner found for chatId=" + chat.getId());
            }
        }

        // Обновляем статус в chatPartners
        for (Map.Entry<Long, User> entry : chatPartners.entrySet()) {
            User partner = entry.getValue();
            if (partner != null && partner.getId() == userId) {
                partner.setOnline(online);
                partnerFound = true;
                Log.d(TAG, "Updated status in chatPartners for userId=" + userId + ", chatId=" + entry.getKey());
            }
        }

        if (position != -1) {
            notifyItemChanged(position);
            Log.d(TAG, "Notified item changed at position: " + position);
        } else if (partnerFound) {
            Log.d(TAG, "Partner found but not in filteredChats, reapplying filter if active");
            // Если партнер найден, но чат отфильтрован, повторно применяем фильтр
            if (context instanceof PersonalChatsActivity) {
                androidx.appcompat.widget.SearchView searchView = ((PersonalChatsActivity) context).findViewById(R.id.search_view);
                if (searchView != null && !searchView.getQuery().toString().isEmpty()) {
                    ((PersonalChatsActivity) context).filterChats(searchView.getQuery().toString());
                    Log.d(TAG, "Reapplied filter after status update for userId=" + userId);
                }
            }
        } else {
            Log.w(TAG, "No partner found for userId=" + userId + " in chatPartners");
        }
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        ImageView avatarImageView, statusIndicator;
        TextView avatarTextView, partnerNameTextView, lastMessageTextView, timestampTextView, unreadIndicator;

        ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            avatarImageView = itemView.findViewById(R.id.avatar_image_view);
            avatarTextView = itemView.findViewById(R.id.avatar_text_view);
            partnerNameTextView = itemView.findViewById(R.id.partner_name_text_view);
            lastMessageTextView = itemView.findViewById(R.id.last_message_text_view);
            timestampTextView = itemView.findViewById(R.id.timestamp_text_view);
            unreadIndicator = itemView.findViewById(R.id.unread_indicator);
            statusIndicator = itemView.findViewById(R.id.status_indicator);
        }
    }
}
