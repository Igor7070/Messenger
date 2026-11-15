package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import retrofit2.Callback;
import retrofit2.Response;

/**
 * Список звонков.
 *
 * Прежде строка была карточкой с тенью: самым крупным в ней стояло слово
 * «Completed», имя собеседника пряталось подписью под аватаром, вид звонка
 * печатался прямо из базы («Voice_with_translate Call»), а дата всегда шла
 * полностью, даже у сегодняшнего звонка. Теперь строка плоская, как в чатах
 * и контактах: имя, под ним направление и вид звонка, время у края,
 * пропущенные — красным, а дни разделены заголовками.
 */
public class CallsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_DATE = 0;
    private static final int TYPE_CALL = 1;

    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_MISSED = 0xFFF87171;

    /** Та же палитра кружков, что в списках чатов и людей. */
    private static final int[] AVATAR_COLORS = {
            0xFF6366F1, 0xFF0D9488, 0xFF8B5CF6, 0xFF0EA5E9, 0xFFDB2777, 0xFF475569
    };

    private List<Call> calls = new ArrayList<>();
    private List<User> users = new ArrayList<>();
    /** Что показываем: заголовок дня (String) либо сам звонок (Call). */
    private final List<Object> rows = new ArrayList<>();

    private int currentUserId;
    private String currentUsername;
    private final OnCallActionListener listener;
    private boolean isSelectionMode = false;
    /** Выделение держим по номерам звонков: позиции в списке сдвигаются. */
    private final Set<Long> selectedCallIds = new HashSet<>();
    private final View actionBar;
    private final ImageView deleteSelectedButton;
    private final ApiService apiService;

    public interface OnCallActionListener {
        void onDeleteCall(long callId);

        void onRedialCall(Call call);

        void onDeleteSelectedCalls(List<Long> callIds);
    }

    public CallsAdapter(int currentUserId, String currentUsername, OnCallActionListener listener,
                        View actionBar, ApiService apiService) {
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
            listener.onDeleteSelectedCalls(new ArrayList<>(selectedCallIds));
            exitSelectionMode();
        });
    }

    public void updateCalls(List<Call> newCalls, List<User> users) {
        this.calls = new ArrayList<>(newCalls);
        this.users = new ArrayList<>(users);
        selectedCallIds.clear();
        isSelectionMode = false;
        updateActionBarVisibility();
        rebuild();
    }

    public boolean isSelectionMode() {
        return isSelectionMode;
    }

    public void exitSelectionMode() {
        isSelectionMode = false;
        selectedCallIds.clear();
        updateActionBarVisibility();
        notifyDataSetChanged();
    }

    public void updateUser(int newUserId, String newUsername) {
        this.currentUserId = newUserId;
        this.currentUsername = newUsername;
    }

    private void updateActionBarVisibility() {
        boolean deleting = isSelectionMode && !selectedCallIds.isEmpty();
        actionBar.setVisibility(deleting ? View.VISIBLE : View.GONE);

        // Пока выделяем звонки, кнопка «позвонить» уступает место корзине:
        // иначе две круглые кнопки стоят одна над другой
        View root = actionBar.getRootView();
        View newCallButton = root.findViewById(R.id.create_group_call_button);
        View callsContainer = root.findViewById(R.id.calls_container);
        boolean onCallsTab = callsContainer != null && callsContainer.getVisibility() == View.VISIBLE;
        if (newCallButton != null && (deleting || onCallsTab)) {
            newCallButton.setVisibility(deleting ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /** Собирает показываемый список: над каждой группой звонков — свой день. */
    private void rebuild() {
        rows.clear();
        String currentHeader = null;
        for (Call call : calls) {
            String header = dateHeader(parseTimestamp(call.getTimestamp()));
            if (header != null && !header.equals(currentHeader)) {
                rows.add(header);
                currentHeader = header;
            }
            rows.add(call);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof Call ? TYPE_CALL : TYPE_DATE;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DATE) {
            return new DateViewHolder(inflater.inflate(R.layout.item_call_date, parent, false));
        }
        return new CallViewHolder(inflater.inflate(R.layout.call_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        Object row = rows.get(position);

        if (row instanceof String) {
            ((DateViewHolder) viewHolder).date.setText((String) row);
            return;
        }

        final Call call = (Call) row;
        final CallViewHolder holder = (CallViewHolder) viewHolder;
        final Context context = holder.itemView.getContext();

        boolean isGroup = "group".equals(call.getCallType());
        boolean missed = "missed".equals(call.getStatus()) || "rejected".equals(call.getStatus());

        // Имя: для группового звонка заголовком идёт сам звонок, а имена — ниже
        holder.name.setText(isGroup ? "Group call" : usernameOf(otherUserId(call)));
        holder.subtitle.setText(subtitleFor(call, isGroup, missed));
        holder.subtitle.setTextColor(missed ? COLOR_MISSED : COLOR_MUTED);

        holder.directionArrow.setImageResource("outgoing".equals(call.getDirection())
                ? R.drawable.ic_call_made : R.drawable.ic_call_received);
        holder.directionArrow.setColorFilter(missed ? COLOR_MISSED : COLOR_MUTED);

        holder.time.setText(timeLabel(parseTimestamp(call.getTimestamp())));

        bindAvatars(holder, call, isGroup);

        holder.checkmark.setVisibility(
                isSelectionMode && selectedCallIds.contains(call.getId()) ? View.VISIBLE : View.GONE);
        holder.itemView.setSelected(selectedCallIds.contains(call.getId()));

        holder.redialButton.setVisibility(isSelectionMode ? View.GONE : View.VISIBLE);
        holder.redialButton.setOnClickListener(v -> redial(context, call));

        holder.itemView.setOnClickListener(v -> {
            if (isSelectionMode) {
                toggleSelection(call, position);
                return;
            }
            if (isGroup) {
                redial(context, call);
                return;
            }
            // Обычный звонок: открываем человека, как из списка людей
            Intent intent = new Intent(context, UserProfileActivity.class);
            intent.putExtra("currentUserId", currentUserId);
            intent.putExtra("userId", otherUserId(call));
            context.startActivity(intent);
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!isSelectionMode) {
                isSelectionMode = true;
                toggleSelection(call, position);
                notifyDataSetChanged();
                return true;
            }
            return false;
        });
    }

    // ---------- содержимое строки ----------

    /** «Outgoing · Voice call», «Missed · Video call», имена для группового. */
    private String subtitleFor(Call call, boolean isGroup, boolean missed) {
        String direction = missed
                ? ("rejected".equals(call.getStatus()) ? "Declined" : "Missed")
                : ("outgoing".equals(call.getDirection()) ? "Outgoing" : "Incoming");

        if (isGroup) {
            return direction + " · " + participantNames(call);
        }
        return direction + " · " + callTypeLabel(call.getCallType());
    }

    /** Вид звонка по-человечески: в базе лежит «voice_with_translate». */
    private String callTypeLabel(String callType) {
        if (callType == null) return "Call";
        switch (callType) {
            case "voice":
                return "Voice call";
            case "video":
                return "Video call";
            case "voice_with_translate":
            case "translated":
            case "translate":
                return "Translated call";
            case "secure":
            case "encrypted":
                return "Encrypted call";
            case "group":
                return "Group call";
            default:
                return capitalize(callType.replace('_', ' ')) + " call";
        }
    }

    /** Кого звали: до трёх имён, остальные — числом. */
    private String participantNames(Call call) {
        List<String> names = new ArrayList<>();
        List<String> participants = call.getParticipants();
        if (participants == null) return "";

        for (String id : participants) {
            if (String.valueOf(currentUserId).equals(id)) continue;
            names.add(usernameOf(parseId(id)));
        }
        if (names.isEmpty()) return "";
        if (names.size() <= 3) return android.text.TextUtils.join(", ", names);

        return android.text.TextUtils.join(", ", names.subList(0, 3))
                + " and " + (names.size() - 3) + " more";
    }

    private void bindAvatars(CallViewHolder holder, Call call, boolean isGroup) {
        List<Integer> ids = new ArrayList<>();
        if (isGroup) {
            if (call.getParticipants() != null) {
                for (String id : call.getParticipants()) {
                    if (String.valueOf(currentUserId).equals(id)) continue;
                    ids.add(parseId(id));
                }
            }
        } else {
            ids.add(otherUserId(call));
        }

        int first = ids.isEmpty() ? -1 : ids.get(0);
        loadAvatar(holder.avatarImage, holder.avatarText, first);

        // Второй кружок позади первого — только когда звавших было больше одного
        if (ids.size() > 1) {
            holder.avatarBack.setVisibility(View.VISIBLE);
            loadAvatar(holder.avatarBack, null, ids.get(1));
        } else {
            holder.avatarBack.setVisibility(View.GONE);
        }
    }

    /**
     * Снимок, а если его нет или он не открылся — буква имени на цветном
     * кружке. Для заднего кружка подписи нет, там остаётся цветной круг.
     */
    private void loadAvatar(ImageView image, @Nullable TextView letterView, int userId) {
        User user = getUserById(userId);
        String username = user != null && user.getUsername() != null ? user.getUsername() : "?";
        String letter = username.isEmpty() ? "?" : username.substring(0, 1).toUpperCase(Locale.getDefault());
        int color = colorFor(username);

        if (letterView != null) {
            letterView.setText(letter);
            letterView.setBackgroundTintList(ColorStateList.valueOf(color));
        } else {
            image.setBackgroundTintList(ColorStateList.valueOf(color));
        }

        if (user != null && user.getAvatar() != null && !user.getAvatar().isEmpty()) {
            String avatarUrl = "https://unlimitedpossibilities12.org" + user.getAvatar();
            Glide.with(image.getContext())
                    .load(avatarUrl)
                    .circleCrop()
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                    Target<Drawable> target, boolean isFirstResource) {
                            image.setImageDrawable(null);
                            if (letterView != null) letterView.setVisibility(View.VISIBLE);
                            return true;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model,
                                                       Target<Drawable> target, DataSource dataSource,
                                                       boolean isFirstResource) {
                            if (letterView != null) letterView.setVisibility(View.GONE);
                            return false;
                        }
                    })
                    .into(image);
            if (letterView != null) letterView.setVisibility(View.GONE);
        } else {
            image.setImageDrawable(null);
            if (letterView != null) letterView.setVisibility(View.VISIBLE);
        }
    }

    // ---------- выделение ----------

    private void toggleSelection(Call call, int position) {
        if (selectedCallIds.contains(call.getId())) {
            selectedCallIds.remove(call.getId());
        } else {
            selectedCallIds.add(call.getId());
        }
        notifyItemChanged(position);
        updateActionBarVisibility();
        if (selectedCallIds.isEmpty() && isSelectionMode) {
            isSelectionMode = false;
            notifyDataSetChanged();
        }
    }

    // ---------- повторный звонок ----------

    private void redial(Context context, Call call) {
        if (currentUserId == -1) {
            Toast.makeText(context, "You need to log in to make a call", Toast.LENGTH_SHORT).show();
            Log.d("CallsAdapter", "Unauthorized user attempted to redial call: id=" + call.getId());
            return;
        }

        if ("group".equals(call.getCallType())) {
            List<String> participantIds = new ArrayList<>(call.getParticipants());
            participantIds.remove(String.valueOf(currentUserId));
            if (participantIds.isEmpty()) {
                Toast.makeText(context, "No participants available for group call", Toast.LENGTH_SHORT).show();
                return;
            }

            String roomId = UUID.randomUUID().toString();
            Intent intent = new Intent(context, GroupCallActivity.class);
            intent.putStringArrayListExtra("selectedUsersIds", new ArrayList<>(participantIds));
            intent.putExtra("initiatorId", currentUserId);
            intent.putExtra("initiatorName", currentUsername);
            intent.putExtra("roomId", roomId);
            intent.putExtra("isInitiator", true);
            context.startActivity(intent);
            Toast.makeText(context, "Starting group call...", Toast.LENGTH_SHORT).show();
            Log.d("CallsAdapter", "Initiating group call with roomId: " + roomId);
            return;
        }

        int recipientId = otherUserId(call);
        String recipientName = usernameOf(recipientId);
        String roomId = UUID.randomUUID().toString();
        String originalCallType = call.getCallType();
        final String callType = "voice_with_translate".equals(originalCallType)
                || "translated".equals(originalCallType) ? "translate" : originalCallType;

        CallRequest requestBody = new CallRequest(currentUserId, recipientId, roomId, recipientName, callType);
        Log.d("CallsAdapter", "Initiating " + callType + " call with roomId: " + roomId);

        apiService.initiateCall(requestBody).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(retrofit2.Call<Void> call2, Response<Void> response) {
                if (response.isSuccessful()) {
                    Intent intent;
                    switch (callType) {
                        case "video":
                            intent = new Intent(context, VideoCallActivity.class);
                            break;
                        case "translate":
                            intent = new Intent(context, VoiceCallWithTranslateActivity.class);
                            break;
                        case "voice":
                            intent = new Intent(context, VoiceCallActivity.class);
                            break;
                        default:
                            intent = new Intent(context, VoiceCallActivity.class);
                            Log.w("CallsAdapter", "Unknown call type: " + callType
                                    + ", defaulting to VoiceCallActivity for roomId: " + roomId);
                            break;
                    }
                    intent.putExtra("currentUserId", String.valueOf(currentUserId));
                    intent.putExtra("recipientId", String.valueOf(recipientId));
                    intent.putExtra("roomId", roomId);
                    intent.putExtra("callerName", currentUsername);
                    intent.putExtra("recipientName", recipientName);
                    intent.putExtra("isCaller", true);
                    context.startActivity(intent);
                    Toast.makeText(context, "Calling " + recipientName + "...", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "Failed to initiate " + callType + " call: "
                            + response.code(), Toast.LENGTH_SHORT).show();
                    Log.e("CallsAdapter", "Failed to initiate " + callType + " call: " + response.code());
                }
            }

            @Override
            public void onFailure(retrofit2.Call<Void> call2, Throwable t) {
                Toast.makeText(context, "Error initiating " + callType + " call: "
                        + t.getMessage(), Toast.LENGTH_SHORT).show();
                Log.e("CallsAdapter", "Error initiating " + callType + " call: " + t.getMessage());
            }
        });
    }

    // ---------- мелочи ----------

    private int otherUserId(Call call) {
        String id = "outgoing".equals(call.getDirection()) ? call.getReceiver() : call.getCaller();
        return parseId(id);
    }

    private int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (Exception e) {
            return -1;
        }
    }

    private String usernameOf(int id) {
        User user = getUserById(id);
        return user != null && user.getUsername() != null ? user.getUsername() : "Unknown";
    }

    private User getUserById(int id) {
        for (User user : users) {
            if (user.getId() == id) return user;
        }
        return null;
    }

    private int colorFor(String name) {
        if (name == null || name.isEmpty()) return AVATAR_COLORS[0];
        int hash = 0;
        for (int i = 0; i < name.length(); i++) hash = hash * 31 + name.charAt(i);
        return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /** В строке только часы: какой это день, говорит заголовок над группой. */
    private String timeLabel(Date date) {
        if (date == null) return "";
        return new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(date);
    }

    /** Заголовок дня: «Today», «Yesterday», день недели, затем полная дата. */
    private String dateHeader(Date date) {
        if (date == null) return null;

        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTime(date);

        if (isSameDay(date, now.getTime())) return "Today";

        now.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(date, now.getTime())) return "Yesterday";

        now.add(Calendar.DAY_OF_YEAR, -5);
        if (date.after(now.getTime())) {
            return new SimpleDateFormat("EEEE", Locale.ENGLISH).format(date);
        }

        Calendar today = Calendar.getInstance();
        if (then.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
            return new SimpleDateFormat("d MMMM", Locale.ENGLISH).format(date);
        }
        return new SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(date);
    }

    private boolean isSameDay(Date left, Date right) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        a.setTime(left);
        b.setTime(right);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** Сервер отдаёт время и с долями секунды, и без них. */
    private Date parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return null;

        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(true);
                Date date = format.parse(timestamp);
                if (date != null) return date;
            } catch (Exception ignored) {
            }
        }
        Log.e("CallsAdapter", "Error parsing timestamp: " + timestamp);
        return null;
    }

    static class CallViewHolder extends RecyclerView.ViewHolder {
        ImageView avatarImage, avatarBack, directionArrow, redialButton, checkmark;
        TextView avatarText, name, subtitle, time;

        CallViewHolder(View itemView) {
            super(itemView);
            avatarImage = itemView.findViewById(R.id.call_avatar_image);
            avatarBack = itemView.findViewById(R.id.call_avatar_back);
            avatarText = itemView.findViewById(R.id.call_avatar_text);
            directionArrow = itemView.findViewById(R.id.call_direction_arrow);
            redialButton = itemView.findViewById(R.id.call_redial_button);
            checkmark = itemView.findViewById(R.id.selection_checkmark);
            name = itemView.findViewById(R.id.call_name);
            subtitle = itemView.findViewById(R.id.call_subtitle);
            time = itemView.findViewById(R.id.call_time);
        }
    }

    static class DateViewHolder extends RecyclerView.ViewHolder {
        TextView date;

        DateViewHolder(View itemView) {
            super(itemView);
            date = itemView.findViewById(R.id.call_date_text_view);
        }
    }
}
