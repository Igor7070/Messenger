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
import java.util.Map;

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
    /**
     * Снимки людей по имени.
     *
     * У личной переписки своей картинки нет — поле avatar у чата заполняется
     * только для групп. Поэтому в списке чатов вместо снимков собеседников
     * рисовались буквы, хотя в самой переписке и в списке людей снимки были.
     */
    private Map<String, String> avatarsByName = java.util.Collections.emptyMap();
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
        bindSecretMark(holder, chat, context);
        bindOwnStatus(holder, chat);
        bindPhotoThumb(holder, chat, context);
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
    /**
      * Имя входа собеседника — то, из чего сложено название переписки.
      * По нему разложены снимки людей, поэтому картинку ищем именно им:
      * в строке стоит выбранное имя, и по нему снимок уже не найти.
      */
    private String partnerLogin(Chat chat) {
        String name = chat.getName() == null ? "" : chat.getName();
        if (chat.getId() == ASSISTANT_ID || !isOneToOne(chat)) return "";
        String[] parts = name.split("_");
        if (parts.length >= 2) {
            return parts[0].equals(currentUsername) ? parts[1] : parts[0];
        }
        return "";
    }

    private String displayName(Chat chat) {
        String name = chat.getName() == null ? "" : chat.getName();
        if (chat.getId() == ASSISTANT_ID || !isOneToOne(chat)) return name;

        String[] parts = name.split("_");
        if (parts.length >= 2) {
            String partner = parts[0].equals(currentUsername) ? parts[1] : parts[0];
            // Название переписки сложено из имён входа; показываем то имя,
            // которым человек себя назвал, если оно известно.
            if (!partner.isEmpty()) return PeopleNames.of(partner);
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

        String picture = chat.getAvatar();
        // У личной переписки картинки нет — берём снимок собеседника
        if ((picture == null || picture.isEmpty()) && isOneToOne(chat)) {
            String login = partnerLogin(chat);
            picture = avatarsByName.get(login.isEmpty() ? title : login);
        }
        if (picture != null && !picture.isEmpty()) {
            // Букву прячем не заранее, а когда снимок действительно пришёл:
            // иначе у человека с пропавшим файлом картинки оставалось пустое
            // место — ни фото, ни буквы
            showLetter(holder, title);
            com.bumptech.glide.Glide.with(holder.itemView.getContext())
                    .load("https://unlimitedpossibilities12.org" + picture)
                    .circleCrop()
                    .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                        @Override
                        public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                                    Object model,
                                                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                    boolean isFirstResource) {
                            return false; // буква остаётся на месте
                        }

                        @Override
                        public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                       com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                       com.bumptech.glide.load.DataSource dataSource,
                                                       boolean isFirstResource) {
                            if (holder.chatIconLetter != null) holder.chatIconLetter.setVisibility(View.GONE);
                            return false;
                        }
                    })
                    .into(holder.chatIconView);
            holder.chatIconView.setVisibility(View.VISIBLE);
            return;
        }

        // Картинки нет — кружок с первой буквой. Прятать ImageView нельзя:
        // скрытый вид схлопывается, и текст наезжает на место кружка.
        com.bumptech.glide.Glide.with(holder.itemView.getContext()).clear(holder.chatIconView);
        holder.chatIconView.setImageDrawable(null);
        showLetter(holder, title);
    }

    /** Кружок с первой буквой имени. */
    private void showLetter(ChatViewHolder holder, String title) {
        if (holder.chatIconLetter == null) return;
        String trimmed = title == null ? "" : title.trim();
        holder.chatIconLetter.setText(trimmed.isEmpty()
                ? "?" : trimmed.substring(0, 1).toUpperCase());
        holder.chatIconLetter.setBackgroundTintList(ColorStateList.valueOf(colorFor(trimmed)));
        holder.chatIconLetter.setVisibility(View.VISIBLE);
    }

    /** Снимки людей: имя — путь к картинке. */
    public void setUserAvatars(Map<String, String> avatars) {
        this.avatarsByName = avatars != null ? avatars : java.util.Collections.emptyMap();
        notifyDataSetChanged();
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
    /** Замок перед именем — так секретный чат виден сразу, а не по плашке у края. */
    private void bindSecretMark(ChatViewHolder holder, Chat chat, Context context) {
        if (holder.chatNameTextView == null) return;
        if (!Boolean.TRUE.equals(chat.getIsSecret())) {
            holder.chatNameTextView.setCompoundDrawablesRelative(null, null, null, null);
            holder.chatNameTextView.setCompoundDrawablePadding(0);
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        android.graphics.drawable.Drawable lock =
                androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_lock);
        if (lock != null) {
            // Значок сам по себе крупный и серый: уменьшаем до строчной высоты
            // и красим в тот же цвет, что и метка языка, — замок тогда читается
            // как пометка, а не как кнопка.
            lock = androidx.core.graphics.drawable.DrawableCompat.wrap(lock.mutate());
            androidx.core.graphics.drawable.DrawableCompat.setTint(lock,
                    android.graphics.Color.parseColor("#67E8F9"));
            int size = Math.round(15 * density);
            lock.setBounds(0, 0, size, size);
            holder.chatNameTextView.setCompoundDrawablesRelative(lock, null, null, null);
            holder.chatNameTextView.setCompoundDrawablePadding(Math.round(5 * density));
        }
    }

    /** Галочки у своего последнего сообщения: одна — дошло, две — прочли. */
    private void bindOwnStatus(ChatViewHolder holder, Chat chat) {
        if (holder.lastStatusView == null) return;
        if (!chat.isLastFromMe() || !chat.isLastDelivered()) {
            holder.lastStatusView.setVisibility(View.GONE);
            return;
        }
        // Прочитано — две галочки одним значком; доставлено — одна.
        holder.lastStatusView.setImageResource(chat.isLastRead()
                ? R.drawable.ic_ticks_read : R.drawable.ic_delivered);
        holder.lastStatusView.setVisibility(View.VISIBLE);
    }

    /** Миниатюра снимка перед словом «Photo». Нет файла — нет и миниатюры. */
    private void bindPhotoThumb(ChatViewHolder holder, Chat chat, Context context) {
        if (holder.lastPhotoView == null) return;
        String url = chat.getLastPhotoUrl();
        if (url == null || url.trim().isEmpty()) {
            holder.lastPhotoView.setVisibility(View.GONE);
            return;
        }
        holder.lastPhotoView.setVisibility(View.VISIBLE);
        com.bumptech.glide.Glide.with(context)
                .load(url)
                .centerCrop()
                .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                                                Object model,
                                                com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                boolean isFirstResource) {
                        // Файл мог пропасть с сервера: пустого квадратика в
                        // списке быть не должно — остаётся одно слово «Photo».
                        holder.lastPhotoView.setVisibility(View.GONE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                   com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                   com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
                .into(holder.lastPhotoView);
    }

    private void bindBadge(ChatViewHolder holder, Chat chat, Context context, boolean assistant) {
        if (holder.chatBadge == null) return;

        // Секретность теперь помечена замком у самого имени: плашка стояла
        // у правого края, рядом со временем, и два одинаковых имени — обычный
        // чат и секретный — глазом было не различить. А освободившаяся плашка
        // показывает язык перевода и в секретной переписке.
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
            // Системный формат брал язык телефона, и в английском списке
            // чатов появлялось «11 сент.». Пишем сами, по-английски.
            java.time.ZonedDateTime when = java.time.Instant.ofEpochMilli(millis)
                    .atZone(java.time.ZoneId.systemDefault());
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(when.getZone());
            String pattern;
            if (DateUtils.isToday(millis)) {
                pattern = "HH:mm";
            } else if (when.getYear() == now.getYear()) {
                pattern = "d MMM";
            } else {
                pattern = "d MMM yyyy";
            }
            return when.format(java.time.format.DateTimeFormatter
                    .ofPattern(pattern, java.util.Locale.ENGLISH));
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
        ImageView lastStatusView, lastPhotoView;
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
            lastStatusView = itemView.findViewById(R.id.last_status_view);
            lastPhotoView = itemView.findViewById(R.id.last_photo_view);
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
