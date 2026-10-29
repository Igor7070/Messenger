package com.example.unlimitedpossibilities12.messenger.offline;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.unlimitedpossibilities12.R;

import java.util.List;

public class OfflineRecentChatsAdapter extends RecyclerView.Adapter<OfflineRecentChatsAdapter.ViewHolder> {

    private final List<OfflineRecentChats.RecentChat> chats;
    private final OnRecentChatClickListener listener;

    public interface OnRecentChatClickListener {
        void onRecentChatClick(OfflineRecentChats.RecentChat chat);
        /** Долгое нажатие — меню удаления. */
        default void onRecentChatLongClick(OfflineRecentChats.RecentChat chat) {}
    }

    public OfflineRecentChatsAdapter(List<OfflineRecentChats.RecentChat> chats, OnRecentChatClickListener listener) {
        this.chats = chats;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recent_chat, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        OfflineRecentChats.RecentChat chat = chats.get(position);
        holder.nameText.setText(chat.name);
        holder.itemView.setOnClickListener(v -> listener.onRecentChatClick(chat));
        holder.itemView.setOnLongClickListener(v -> { listener.onRecentChatLongClick(chat); return true; });
    }

    @Override
    public int getItemCount() {
        return chats.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView nameText;

        ViewHolder(View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.recent_chat_name);
        }
    }
}
