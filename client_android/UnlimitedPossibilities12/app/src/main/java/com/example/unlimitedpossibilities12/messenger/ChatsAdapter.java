package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.Chat;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Единый список разговоров: личные, секретные, групповые и ассистент.
 *
 * Раньше адаптер показывал только групповые чаты, а личные жили на отдельном
 * экране — человек не видел всю переписку в одном месте. Теперь строка знает
 * свой тип: у секретного чата метка SECRET, у чата с включённым переводом —
 * метка с языком, у личного — имя собеседника вместо служебного «A_B».
 */
public class ChatsAdapter extends RecyclerView.Adapter<ChatsAdapter.ChatViewHolder> {

    private static final String TAG = "ChatsAdapter";

    /** Строка ассистента: он не чат на сервере, поэтому с невозможным номером. */
    public static final long ASSISTANT_ID = -100L;

    /** Цвета буквенных аватаров. Выбираются по имени, чтобы у чата он не менялся. */
    private static final int[] AVATAR_COLORS = {
            0xFF6366F1, 0xFF0D9488, 0xFF8B5CF6, 0xFF0EA5E9, 0xFFDB2777, 0xFF475569
    };

    private final List<Chat> chats = new ArrayList<>();
    private final List<Chat> filteredChats = new ArrayList<>();
    private final OnChatClickListener listener;
    private OnChatLongClickListener longClickListener;

    /** Нужно, чтобы вытащить имя собеседника из названия личного чата «я_он». */
    private String currentUsername = "";

    public ChatsAdapter(OnChatClickListener listener) {
        this.listener = listener;
    }

    public void setCurrentUsername(String username) {
        this.currentUsername = username == null ? "" : username;
    }

    @Override
    public ChatViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ChatViewHolder holder, int position) {
        Chat chat = filteredChats.get(position);
        Context context = holder.itemView.getContext();
        boolean assistant = chat.getId() == ASSISTANT_ID;

        String title = displayName(chat);
        holder.chatNameTextView.setText(title);

        bindPicture(holder, chat, title, assistant);
        bindBadge(holder, chat, context, assistant);

        String lastMessage = chat.getLastMessage() != null ? chat.getLastMessage() : "No messages yet";
        holder.lastMessageTextView.setText(lastMessage);
        holder.timestampTextView.setText(formatTime(context, chat.getTimestamp()));

        int unreadCount = chat.getUnreadCount();
        if (unreadCount > 0) {
            holder.unreadCountView.setText(String.valueOf(unreadCount));
            holder.unreadCountView.setVisibility(View.VISIBLE);
        } else {
            holder.unreadCountView.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> listener.onChatClick(chat));
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener == null || assistant) return false;
            longClickListener.onChatLongClick(chat);
            return true;
        });
    }

    /** Личный чат назван «я_он» — показываем собеседника, а не служебную склейку. */
    private String displayName(Chat chat) {
        String name = chat.getName() == null ? "" : chat.getName();
        if (chat.getId() == ASSISTANT_ID || !isOneToOne(chat)) return name;

        String[] parts = name.split("_");
        if (parts.length >= 2) {
            String partner = parts[0].equals(currentUsername) ? parts[1] : parts[0];
            if (!partner.isEmpty()) return partner;
        }
        return name;
    }

    private boolean isOneToOne(Chat chat) {
        return chat.isPrivate() || Boolean.TRUE.equals(chat.getIsSecret());
    }

    private void bindPicture(ChatViewHolder holder, Chat chat, String title, boolean assistant) {
        if (holder.chatIconView == null) return;

        if (assistant) {
            com.bumptech.glide.Glide.with(holder.itemView.getContext()).clear(holder.chatIconView);
            holder.chatIconView.setImageResource(R.drawable.ic_assistant_molnia);
            holder.chatIconView.setBackgroundResource(R.drawable.bg_letter_avatar);
            holder.chatIconView.setBackgroundTintList(ColorStateList.valueOf(0xFF312E81));
            holder.chatIconView.setPadding(13, 13, 13, 13);
            if (holder.chatIconLetter != null) holder.chatIconLetter.setVisibility(View.GONE);
            return;
        }

        holder.chatIconView.setBackground(null);
        holder.chatIconView.setBackgroundTintList(null);
        holder.chatIconView.setPadding(0, 0, 0, 0);

        if (chat.getAvatar() != null && !chat.getAvatar().isEmpty()) {
            com.bumptech.glide.Glide.with(holder.itemView.getContext())
                    .load("https://unlimitedpossibilities12.org" + chat.getAvatar())
                    .circleCrop()
                    .into(holder.chatIconView);
            holder.chatIconView.setVisibility(View.VISIBLE);
            if (holder.chatIconLetter != null) holder.chatIconLetter.setVisibility(View.GONE);
            return;
        }

        // Картинки нет — кружок с первой буквой. Прятать ImageView нельзя:
        // скрытый вид схлопывается, и текст наезжает на место кружка.
        com.bumptech.glide.Glide.with(holder.itemView.getContext()).clear(holder.chatIconView);
        holder.chatIconView.setImageDrawable(null);
        if (holder.chatIconLetter == null) return;

        String trimmed = title == null ? "" : title.trim();
        holder.chatIconLetter.setText(trimmed.isEmpty()
                ? "?" : trimmed.substring(0, 1).toUpperCase());
        holder.chatIconLetter.setBackgroundTintList(ColorStateList.valueOf(colorFor(trimmed)));
        holder.chatIconLetter.setVisibility(View.VISIBLE);
    }

    private int colorFor(String name) {
        if (name == null || name.isEmpty()) return AVATAR_COLORS[0];
        int hash = 0;
        for (int i = 0; i < name.length(); i++) hash = hash * 31 + name.charAt(i);
        return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
    }

    /**
     * Метка рядом с именем: сначала секретность, иначе включённый перевод.
     * Две сразу не показываем — строка узкая, а имя важнее.
     */
    private void bindBadge(ChatViewHolder holder, Chat chat, Context context, boolean assistant) {
        if (holder.chatBadge == null) return;

        if (!assistant && Boolean.TRUE.equals(chat.getIsSecret())) {
            holder.chatBadge.setText("SECRET");
            holder.chatBadge.setVisibility(View.VISIBLE);
            return;
        }

        if (!assistant) {
            SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
            if (prefs.getBoolean("translation_enabled_" + chat.getId(), false)) {
                String language = prefs.getString("translation_language_" + chat.getId(), "");
                holder.chatBadge.setText(language.isEmpty() ? "TRANSLATE" : "→" + language.toUpperCase());
                holder.chatBadge.setVisibility(View.VISIBLE);
                return;
            }
        }

        holder.chatBadge.setVisibility(View.GONE);
    }

    private String formatTime(Context context, String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return "";
        try {
            long millis = Instant.parse(timestamp).toEpochMilli();
            int flags = DateUtils.isToday(millis)
                    ? DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_ALL
                    : DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_ALL;
            return DateUtils.formatDateTime(context, millis, flags);
        } catch (DateTimeParseException e) {
            Log.e(TAG, "Error parsing timestamp: " + timestamp + ", error: " + e.getMessage());
            return "";
        }
    }

    @Override
    public int getItemCount() {
        return filteredChats.size();
    }

    public void filter(String term) {
        filteredChats.clear();
        if (term.isEmpty()) {
            filteredChats.addAll(chats);
        } else {
            String needle = term.toLowerCase();
            for (Chat chat : chats) {
                if (displayName(chat).toLowerCase().contains(needle)) filteredChats.add(chat);
            }
        }
        notifyDataSetChanged();
    }

    public void updateChats(List<Chat> newChats) {
        this.chats.clear();
        this.chats.addAll(newChats);
        this.filteredChats.clear();
        this.filteredChats.addAll(newChats);
        notifyDataSetChanged();
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        TextView chatNameTextView, lastMessageTextView, timestampTextView, unreadCountView, chatBadge;
        ImageView chatIconView;
        TextView chatIconLetter;
        View onlineDot;

        ChatViewHolder(View itemView) {
            super(itemView);
            chatNameTextView = itemView.findViewById(R.id.chat_name_text_view);
            lastMessageTextView = itemView.findViewById(R.id.last_message_text_view);
            timestampTextView = itemView.findViewById(R.id.timestamp_text_view);
            unreadCountView = itemView.findViewById(R.id.unread_count_view);
            chatBadge = itemView.findViewById(R.id.chat_badge);
            chatIconView = itemView.findViewById(R.id.chat_icon_view);
            chatIconLetter = itemView.findViewById(R.id.chat_icon_letter);
            onlineDot = itemView.findViewById(R.id.chat_online_dot);
        }
    }

    interface OnChatClickListener {
        void onChatClick(Chat chat);
    }

    interface OnChatLongClickListener {
        void onChatLongClick(Chat chat);
    }

    public void setOnChatLongClickListener(OnChatLongClickListener listener) {
        this.longClickListener = listener;
    }
}
