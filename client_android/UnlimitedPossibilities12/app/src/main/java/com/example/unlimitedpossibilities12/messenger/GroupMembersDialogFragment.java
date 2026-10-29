package com.example.unlimitedpossibilities12.messenger;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.Chat;
import com.example.unlimitedpossibilities12.api.ChatMember;
import com.example.unlimitedpossibilities12.api.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Участники группы: список с ролями, добавление, исключение, приглашения и
 * выход. Набор действий зависит от роли, и ровно так же его проверяет сервер —
 * прятать кнопки одного недостаточно, запрос можно послать и без них.
 *
 * Разметка собирается кодом: строк немного, а экранов с таким устройством в
 * приложении больше нет, отдельный XML здесь только добавил бы файлов.
 */
public class GroupMembersDialogFragment extends DialogFragment {
    private static final String TAG = "GroupMembers";
    private static final String ARG_CHAT_ID = "chatId";
    private static final String ARG_CHAT_NAME = "chatName";
    private static final String ARG_USER_ID = "userId";
    private static final String ARG_PUBLIC = "isPublic";
    /** Ссылку открывает веб-клиент: один адрес годится и для телефона, и для браузера. */
    private static final String INVITE_BASE = "https://igor7070.github.io/Messenger/#/invite/";

    private long chatId;
    private String chatName;
    private long currentUserId;
    private boolean isPublic;

    private ApiService apiService;
    /** Выбор файла из галереи для картинки группы. */
    private androidx.activity.result.ActivityResultLauncher<String> pickImage;
    private LinearLayout container;
    private final List<ChatMember> members = new ArrayList<>();
    private List<User> allUsers = new ArrayList<>();

    public static GroupMembersDialogFragment newInstance(Chat chat, long currentUserId) {
        GroupMembersDialogFragment fragment = new GroupMembersDialogFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_CHAT_ID, chat.getId());
        args.putString(ARG_CHAT_NAME, chat.getName());
        args.putLong(ARG_USER_ID, currentUserId);
        args.putBoolean(ARG_PUBLIC, chat.isPublicGroup());
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Регистрировать выбор файла можно только до показа окна
        pickImage = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAvatar(uri); });
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Bundle args = getArguments() != null ? getArguments() : new Bundle();
        chatId = args.getLong(ARG_CHAT_ID);
        chatName = args.getString(ARG_CHAT_NAME, "Group");
        currentUserId = args.getLong(ARG_USER_ID);
        isPublic = args.getBoolean(ARG_PUBLIC, true);
        apiService = ApiClient.getApiService();

        container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        container.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(container);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(chatName)
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        loadMembers();
        return dialog;
    }

    private void loadMembers() {
        apiService.getChatMembers(chatId).enqueue(new Callback<List<ChatMember>>() {
            @Override
            public void onResponse(Call<List<ChatMember>> call, Response<List<ChatMember>> response) {
                members.clear();
                if (response.isSuccessful() && response.body() != null) {
                    members.addAll(response.body());
                } else {
                    Log.e(TAG, "Failed to load members: HTTP " + response.code());
                }
                loadUsers();
            }

            @Override
            public void onFailure(Call<List<ChatMember>> call, Throwable t) {
                Log.e(TAG, "Error loading members: " + t.getMessage());
                showMessage("Could not load the member list");
            }
        });
    }

    /** Список пользователей нужен только тем, кто может добавлять. */
    private void loadUsers() {
        if (!canManage()) {
            render();
            return;
        }
        apiService.getUsers().enqueue(new Callback<List<User>>() {
            @Override
            public void onResponse(Call<List<User>> call, Response<List<User>> response) {
                if (response.isSuccessful() && response.body() != null) allUsers = response.body();
                render();
            }

            @Override
            public void onFailure(Call<List<User>> call, Throwable t) {
                render();
            }
        });
    }

    private ChatMember me() {
        for (ChatMember member : members) {
            if (member.getUserId() == currentUserId) return member;
        }
        return null;
    }

    private boolean isOwner() {
        ChatMember me = me();
        return me != null && me.isOwner();
    }

    private boolean canManage() {
        ChatMember me = me();
        // Чат из прежних времён: участников нет ни у кого, ограничивать некого
        return members.isEmpty() || (me != null && me.canManageMembers());
    }

    private void render() {
        if (!isAdded() || container == null) return;
        container.removeAllViews();

        if (members.isEmpty()) {
            container.addView(note("This group was created before members existed — anyone can see it."));
        }

        for (ChatMember member : members) {
            container.addView(memberRow(member));
        }

        if (canManage()) {
            container.addView(actionButton("Change group picture", v -> {
                if (pickImage != null) pickImage.launch("image/*");
            }));
            container.addView(actionButton("Add someone", v -> showAddDialog()));
            container.addView(actionButton("Create invite link (7 days)", v -> createInvite()));
        }

        if (isOwner()) {
            CheckBox publicBox = new CheckBox(requireContext());
            publicBox.setText("Public group — anyone can find and read it");
            publicBox.setChecked(isPublic);
            publicBox.setOnCheckedChangeListener((button, checked) -> setPublic(checked));
            container.addView(publicBox);
        }

        if (me() != null) {
            Button leave = actionButton("Leave group", v -> confirmLeave());
            leave.setTextColor(Color.parseColor("#C62828"));
            container.addView(leave);
        }
    }

    private View memberRow(ChatMember member) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView name = new TextView(requireContext());
        String suffix = member.getUserId() == currentUserId ? " (you)" : "";
        name.setText((member.getUsername() != null ? member.getUsername() : "User " + member.getUserId())
                + suffix + " · " + member.roleLabel());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams nameParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(name, nameParams);

        if (isOwner() && !member.isOwner()) {
            row.addView(smallButton(member.isAdmin() ? "Unadmin" : "Admin", v ->
                    setRole(member, member.isAdmin() ? "MEMBER" : "ADMIN")));
            row.addView(smallButton("Owner", v -> confirmTransfer(member)));
        }
        if (canManage() && !member.isOwner() && member.getUserId() != currentUserId) {
            Button remove = smallButton("Remove", v -> removeMember(member));
            remove.setTextColor(Color.parseColor("#C62828"));
            row.addView(remove);
        }
        return row;
    }

    // ---------- действия ----------

    private void showAddDialog() {
        List<User> candidates = new ArrayList<>();
        for (User user : allUsers) {
            boolean already = false;
            for (ChatMember member : members) {
                if (member.getUserId() == user.getId()) { already = true; break; }
            }
            if (!already) candidates.add(user);
        }
        if (candidates.isEmpty()) {
            showMessage("Everyone is already in this group");
            return;
        }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) names[i] = candidates.get(i).getUsername();
        new AlertDialog.Builder(requireContext())
                .setTitle("Add to the group")
                .setItems(names, (dialog, which) -> addMember(candidates.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addMember(User user) {
        apiService.addChatMember(chatId, user.getId()).enqueue(simple("Added to the group"));
    }

    private void removeMember(ChatMember member) {
        apiService.removeChatMember(chatId, member.getUserId()).enqueue(simple("Removed from the group"));
    }

    private void setRole(ChatMember member, String role) {
        Map<String, String> body = new HashMap<>();
        body.put("role", role);
        apiService.setMemberRole(chatId, member.getUserId(), body)
                .enqueue(simple("ADMIN".equals(role) ? "Now an admin" : "No longer an admin"));
    }

    private void confirmTransfer(ChatMember member) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Hand over the group")
                .setMessage("Make " + member.getUsername() + " the owner? You stay as an admin.")
                .setPositiveButton("Hand over", (d, w) ->
                        apiService.transferChatOwnership(chatId, member.getUserId())
                                .enqueue(simple("Ownership transferred")))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmLeave() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Leave group")
                .setMessage("Leave \"" + chatName + "\"?")
                .setPositiveButton("Leave", (d, w) -> apiService.leaveChat(chatId)
                        .enqueue(new Callback<Void>() {
                            @Override
                            public void onResponse(Call<Void> call, Response<Void> response) {
                                showMessage(response.isSuccessful() ? "You left the group" : "Could not leave");
                                dismissAllowingStateLoss();
                            }

                            @Override
                            public void onFailure(Call<Void> call, Throwable t) {
                                showMessage("Could not leave");
                            }
                        }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setPublic(boolean value) {
        Map<String, Object> body = new HashMap<>();
        body.put("isPublic", value);
        apiService.updateChat(chatId, body).enqueue(new Callback<Chat>() {
            @Override
            public void onResponse(Call<Chat> call, Response<Chat> response) {
                if (response.isSuccessful()) {
                    isPublic = value;
                    showMessage(value ? "The group is now public" : "The group is now private");
                } else {
                    showMessage("The server refused this change");
                }
            }

            @Override
            public void onFailure(Call<Chat> call, Throwable t) {
                showMessage("Could not change this");
            }
        });
    }

    /**
     * Отправка выбранной картинки. Содержимое читаем через ContentResolver:
     * галерея отдаёт content://-адрес, из которого обычного файла не получить.
     */
    private void uploadAvatar(android.net.Uri uri) {
        try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
            if (in == null) {
                showMessage("Could not read the picture");
                return;
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
            byte[] bytes = buffer.toByteArray();

            String type = requireContext().getContentResolver().getType(uri);
            if (type == null) type = "image/*";
            String extension = type.contains("png") ? ".png" : type.contains("webp") ? ".webp" : ".jpg";
            okhttp3.RequestBody body = okhttp3.RequestBody.create(bytes, okhttp3.MediaType.parse(type));
            okhttp3.MultipartBody.Part part =
                    okhttp3.MultipartBody.Part.createFormData("avatar", "group" + extension, body);

            apiService.uploadChatAvatar(chatId, part).enqueue(new Callback<Chat>() {
                @Override
                public void onResponse(Call<Chat> call, Response<Chat> response) {
                    showMessage(response.isSuccessful()
                            ? "Group picture updated"
                            : (response.code() == 403 ? "You are not allowed to do that"
                                                      : "The server refused the picture"));
                }

                @Override
                public void onFailure(Call<Chat> call, Throwable t) {
                    Log.e(TAG, "Avatar upload failed: " + t.getMessage());
                    showMessage("Could not upload the picture");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Avatar read failed: " + e.getMessage());
            showMessage("Could not read the picture");
        }
    }

    private void createInvite() {
        Map<String, Object> body = new HashMap<>();
        body.put("ttlHours", 168); // неделя
        body.put("maxUses", 0);    // без предела
        apiService.createChatInvite(chatId, body).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    showMessage("Could not create the invite");
                    return;
                }
                Object token = response.body().get("token");
                if (token == null) return;
                shareInvite(INVITE_BASE + token);
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                showMessage("Could not create the invite");
            }
        });
    }

    /** Ссылку кладём в буфер и сразу предлагаем, чем поделиться. */
    private void shareInvite(String link) {
        if (!isAdded()) return;
        ClipboardManager clipboard =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Invite link", link));
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, link);
        startActivity(Intent.createChooser(share, "Invite to " + chatName));
        showMessage("Invite link copied");
    }

    private Callback<Void> simple(String successText) {
        return new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    showMessage(successText);
                    loadMembers();
                } else {
                    showMessage(response.code() == 403
                            ? "You are not allowed to do that"
                            : "The server refused this action");
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                showMessage("Could not reach the server");
            }
        };
    }

    // ---------- мелочи разметки ----------

    private TextView note(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        Button button = new Button(requireContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private Button smallButton(String text, View.OnClickListener listener) {
        Button button = actionButton(text, listener);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showMessage(String text) {
        if (isAdded()) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }
}
