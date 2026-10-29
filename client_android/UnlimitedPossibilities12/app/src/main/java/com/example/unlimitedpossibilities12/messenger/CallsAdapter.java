package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.Call;
import com.example.unlimitedpossibilities12.api.CallRequest;
import com.example.unlimitedpossibilities12.api.User;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import retrofit2.Callback;
import retrofit2.Response;

public class CallsAdapter extends RecyclerView.Adapter<CallsAdapter.CallViewHolder> {
    private List<Call> calls = new ArrayList<>();
    private List<User> users = new ArrayList<>();
    private int currentUserId;
    private String currentUsername;
    private final OnCallActionListener listener;
    private boolean isSelectionMode = false;
    private final Set<Integer> selectedPositions = new HashSet<>();
    private View actionBar;
    private ImageView deleteSelectedButton;
    private final ApiService apiService;

    public interface OnCallActionListener {
        void onDeleteCall(long callId);
        void onRedialCall(Call call);
        void onDeleteSelectedCalls(List<Long> callIds);
    }

    public CallsAdapter(int currentUserId, String currentUsername, OnCallActionListener listener, View actionBar, ApiService apiService) {
        this.currentUserId = currentUserId;
        this.currentUsername = currentUsername;
        this.listener = listener;
        this.actionBar = actionBar;
        this.apiService = apiService;
        this.deleteSelectedButton = actionBar.findViewById(R.id.delete_selected_button);
        setupActionBar();
    }

    private void setupActionBar() {
        deleteSelectedButton.setOnClickListener(v -> {
            List<Long> selectedCallIds = new ArrayList<>();
            for (int position : selectedPositions) {
                if (position < calls.size()) {
                    selectedCallIds.add(calls.get(position).getId());
                }
            }
            listener.onDeleteSelectedCalls(selectedCallIds);
            exitSelectionMode();
        });
    }

    public void updateCalls(List<Call> newCalls, List<User> users) {
        this.calls = new ArrayList<>(newCalls);
        this.users = new ArrayList<>(users);
        selectedPositions.clear();
        isSelectionMode = false;
        updateActionBarVisibility();
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return isSelectionMode;
    }

    public void exitSelectionMode() {
        isSelectionMode = false;
        selectedPositions.clear();
        updateActionBarVisibility();
        notifyDataSetChanged();
    }

    public void updateUser(int newUserId, String newUsername) {
        this.currentUserId = newUserId;
        this.currentUsername = newUsername;
    }

    private void updateActionBarVisibility() {
        actionBar.setVisibility(isSelectionMode && !selectedPositions.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @NonNull
    @Override
    public CallViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.call_item, parent, false);
        return new CallViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CallViewHolder holder, int position) {
        Call call = calls.get(position);
        boolean isGroup = call.getCallType().equals("group");

        // Установка участников
        holder.participantsContainer.removeAllViews();
        if (isGroup) {
            List<String> participantIds = call.getParticipants();
            int participantsPerRow = 2;

            for (int i = 0; i < participantIds.size(); i += participantsPerRow) {
                LinearLayout row = new LinearLayout(holder.itemView.getContext());
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 0, 0, 4);

                for (int j = i; j < Math.min(i + participantsPerRow, participantIds.size()); j++) {
                    User user = getUserById(Integer.parseInt(participantIds.get(j)));
                    addParticipantView(row, user, j == 0 && isSelectionMode && selectedPositions.contains(position));
                }

                holder.participantsContainer.addView(row);
            }
        } else {
            int otherUserId = call.getDirection().equals("outgoing") ? Integer.parseInt(call.getReceiver()) : Integer.parseInt(call.getCaller());
            User user = getUserById(otherUserId);
            addParticipantView(holder.participantsContainer, user, isSelectionMode && selectedPositions.contains(position));
        }

        // Установка стрелки направления
        holder.directionArrow.setImageResource(call.getDirection().equals("outgoing") ?
                android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float);
        holder.directionArrow.setColorFilter(call.getDirection().equals("outgoing") ?
                holder.itemView.getContext().getResources().getColor(R.color.call_outgoing_arrow) :
                holder.itemView.getContext().getResources().getColor(R.color.call_incoming_arrow));

        // Установка деталей звонка
        holder.status.setText(capitalize(call.getStatus()));
        holder.callType.setText(capitalize(call.getCallType()) + " Call");
        holder.timestamp.setText(formatTimestamp(call.getTimestamp()));

        // Обработчики
        holder.redialButton.setOnClickListener(v -> {
            if (isSelectionMode) {
                toggleSelection(position, holder);
            } else {
                if (currentUserId == -1) {
                    Toast.makeText(holder.itemView.getContext(), "You need to log in to make a call", Toast.LENGTH_SHORT).show();
                    Log.d("CallsAdapter", "Unauthorized user attempted to redial call: id=" + call.getId());
                    return;
                }

                if (call.getCallType().equals("group")) {
                    // Групповой вызов
                    List<String> participantIds = new ArrayList<>(call.getParticipants());
                    participantIds.remove(String.valueOf(currentUserId)); // Удаляем текущего пользователя
                    if (participantIds.isEmpty()) {
                        Toast.makeText(holder.itemView.getContext(), "No participants available for group call", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String roomId = UUID.randomUUID().toString();
                    Intent intent = new Intent(holder.itemView.getContext(), GroupCallActivity.class);
                    intent.putStringArrayListExtra("selectedUsersIds", new ArrayList<>(participantIds));
                    intent.putExtra("initiatorId", currentUserId);
                    intent.putExtra("initiatorName", currentUsername);
                    intent.putExtra("roomId", roomId);
                    intent.putExtra("isInitiator", true);
                    holder.itemView.getContext().startActivity(intent);
                    Toast.makeText(holder.itemView.getContext(), "Starting group call...", Toast.LENGTH_SHORT).show();
                    Log.d("CallsAdapter", "Initiating group call with roomId: " + roomId);
                } else {
                    // Индивидуальный вызов
                    int recipientId = call.getDirection().equals("outgoing") ? Integer.parseInt(call.getReceiver()) : Integer.parseInt(call.getCaller());
                    User recipient = getUserById(recipientId);
                    String recipientName = recipient != null ? recipient.getUsername() : "Unknown";
                    String roomId = UUID.randomUUID().toString();
                    String originalCallType = call.getCallType();
                    final String callType = "voice_with_translate".equals(originalCallType) || "translated".equals(originalCallType) ? "translate" : originalCallType;
                    if ("voice_with_translate".equals(originalCallType) || "translated".equals(originalCallType)) {
                        Log.d("CallsAdapter", "Converted callType from " + originalCallType + " to translate for call id: " + call.getId());
                    }
                    CallRequest requestBody = new CallRequest(currentUserId, recipientId, roomId, recipientName, callType);
                    Log.d("CallsAdapter", "Initiating " + callType + " call with roomId: " + roomId);

                    apiService.initiateCall(requestBody).enqueue(new Callback<Void>() {
                        @Override
                        public void onResponse(retrofit2.Call<Void> call, Response<Void> response) {
                            if (response.isSuccessful()) {
                                Intent intent;
                                switch (callType) {
                                    case "video":
                                        intent = new Intent(holder.itemView.getContext(), VideoCallActivity.class);
                                        Log.d("CallsAdapter", "Starting VideoCallActivity for roomId: " + roomId);
                                        break;
                                    case "translate":
                                        intent = new Intent(holder.itemView.getContext(), VoiceCallWithTranslateActivity.class);
                                        Log.d("CallsAdapter", "Starting VoiceCallWithTranslateActivity for roomId: " + roomId);
                                        break;
                                    case "voice":
                                        intent = new Intent(holder.itemView.getContext(), VoiceCallActivity.class);
                                        Log.d("CallsAdapter", "Starting VoiceCallActivity for roomId: " + roomId);
                                        break;
                                    default:
                                        intent = new Intent(holder.itemView.getContext(), VoiceCallActivity.class);
                                        Log.w("CallsAdapter", "Unknown call type: " + callType + ", defaulting to VoiceCallActivity for roomId: " + roomId);
                                        break;
                                }
                                intent.putExtra("currentUserId", String.valueOf(currentUserId));
                                intent.putExtra("recipientId", String.valueOf(recipientId));
                                intent.putExtra("roomId", roomId);
                                intent.putExtra("callerName", currentUsername);
                                intent.putExtra("recipientName", recipientName);
                                intent.putExtra("isCaller", true);
                                holder.itemView.getContext().startActivity(intent);
                                Toast.makeText(holder.itemView.getContext(), "Calling " + recipientName + "...", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(holder.itemView.getContext(), "Failed to initiate " + callType + " call: " + response.code(), Toast.LENGTH_SHORT).show();
                                Log.e("CallsAdapter", "Failed to initiate " + callType + " call: " + response.code());
                            }
                        }

                        @Override
                        public void onFailure(retrofit2.Call<Void> call, Throwable t) {
                            Toast.makeText(holder.itemView.getContext(), "Error initiating " + callType + " call: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                            Log.e("CallsAdapter", "Error initiating " + callType + " call: " + t.getMessage());
                        }
                    });
                }
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!isSelectionMode) {
                isSelectionMode = true;
                toggleSelection(position, holder);
                updateActionBarVisibility();
                notifyDataSetChanged();
                return true;
            }
            return false;
        });

        holder.itemView.setOnClickListener(v -> {
            if (isSelectionMode) {
                toggleSelection(position, holder);
            }
        });
    }

    private void toggleSelection(int position, CallViewHolder holder) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        notifyItemChanged(position);
        updateActionBarVisibility();
    }

    private void addParticipantView(LinearLayout container, User user, boolean showCheckmark) {
        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        View participantView = inflater.inflate(R.layout.participant_item_call, container, false);

        ImageView avatarImage = participantView.findViewById(R.id.participant_avatar_image);
        TextView avatarText = participantView.findViewById(R.id.participant_avatar_text);
        TextView name = participantView.findViewById(R.id.participant_name);
        ImageView checkmark = participantView.findViewById(R.id.selection_checkmark);

        String username = user != null ? user.getUsername() : "Unknown";
        name.setText(username);

        if (user != null && user.getAvatar() != null && !user.getAvatar().isEmpty()) {
            String avatarUrl = "https://unlimitedpossibilities12.org" + user.getAvatar();
            Log.d("CallsAdapter", "Loading avatar for user " + username + ": " + avatarUrl);
            avatarImage.setVisibility(View.VISIBLE);
            avatarText.setVisibility(View.GONE);
            Glide.with(container.getContext())
                    .load(avatarUrl)
                    .circleCrop()
                    .placeholder(R.drawable.circle_background)
                    .error(R.drawable.circle_background)
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                            Log.e("CallsAdapter", "Glide load failed for URL: " + avatarUrl + ", error: " + (e != null ? e.getMessage() : "unknown"));
                            avatarImage.setVisibility(View.GONE);
                            avatarText.setVisibility(View.VISIBLE);
                            avatarText.setText(username.charAt(0) + "");
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                            Log.d("CallsAdapter", "Glide load successful for URL: " + avatarUrl);
                            return false;
                        }
                    })
                    .into(avatarImage);
        } else {
            Log.d("CallsAdapter", "No avatar for user " + username + ", showing initial");
            avatarImage.setVisibility(View.GONE);
            avatarText.setVisibility(View.VISIBLE);
            avatarText.setText(username.charAt(0) + "");
            avatarText.setBackgroundTintList(ColorStateList.valueOf(generateColorForUser(user != null ? user.getId() : 0)));
        }

        checkmark.setVisibility(showCheckmark ? View.VISIBLE : View.GONE);
        container.addView(participantView);
    }

    private int generateColorForUser(int userId) {
        int[] colors = {
                Color.parseColor("#3F51B5"), // Синий
                Color.parseColor("#FF5722"), // Оранжевый
                Color.parseColor("#4CAF50"), // Зеленый
                Color.parseColor("#E91E63")  // Розовый
        };
        return colors[Math.abs(userId % colors.length)];
    }

    private User getUserById(int id) {
        for (User user : users) {
            if (user.getId() == id) {
                Log.d("CallsAdapter", "Found user: " + user.getUsername() + ", Avatar: " + user.getAvatar());
                return user;
            }
        }
        Log.w("CallsAdapter", "User not found for id: " + id + ", returning Unknown user");
        return new User(id, "Unknown", null);
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private String formatTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            Log.e("CallsAdapter", "Timestamp is null or empty");
            return "Unknown";
        }

        Log.d("CallsAdapter", "Parsing timestamp: " + timestamp);
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.US);
            inputFormat.setLenient(true);
            Date date = inputFormat.parse(timestamp);

            if (date == null) {
                Log.e("CallsAdapter", "Failed to parse timestamp: " + timestamp);
                return "Unknown";
            }

            SimpleDateFormat outputFormat = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US);
            return outputFormat.format(date);
        } catch (Exception e) {
            Log.e("CallsAdapter", "Error parsing timestamp: " + timestamp + ", error: " + e.getMessage());
            try {
                SimpleDateFormat altInputFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                altInputFormat.setLenient(true);
                Date date = altInputFormat.parse(timestamp);
                SimpleDateFormat outputFormat = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());
                return outputFormat.format(date);
            } catch (Exception altE) {
                Log.e("CallsAdapter", "Error parsing alternative timestamp: " + timestamp + ", error: " + altE.getMessage());
                return "Unknown";
            }
        }
    }

    @Override
    public int getItemCount() {
        return calls.size();
    }

    static class CallViewHolder extends RecyclerView.ViewHolder {
        LinearLayout participantsContainer;
        ImageView directionArrow, redialButton;
        TextView status, callType, timestamp;

        CallViewHolder(View itemView) {
            super(itemView);
            participantsContainer = itemView.findViewById(R.id.call_participants_container);
            directionArrow = itemView.findViewById(R.id.call_direction_arrow);
            redialButton = itemView.findViewById(R.id.call_redial_button);
            status = itemView.findViewById(R.id.call_status);
            callType = itemView.findViewById(R.id.call_type);
            timestamp = itemView.findViewById(R.id.call_timestamp);
        }
    }
}
