package com.example.unlimitedpossibilities12.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.Chat;
import com.example.unlimitedpossibilities12.api.ChatMember;
import com.example.unlimitedpossibilities12.api.User;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Сведения о группе.
 *
 * Своего экрана у группы не было вовсе: нажатие на название в переписке не
 * делало ничего, а участники, приглашения, картинка группы и выход жили в
 * окне, собранном кодом и доступном только долгим нажатием по строке в
 * списке чатов. Здесь тот же порядок, что в профиле человека: снимок во
 * весь верх, название и число участников поверх него, ряд плиток, карточка
 * с участниками.
 *
 * Права проверяет сервер; кнопки мы лишь прячем, чтобы не предлагать того,
 * что всё равно не выйдет.
 */
public class GroupProfileActivity extends AppCompatActivity {

    private static final String TAG = "GroupProfile";
    /** Ссылку открывает веб-клиент: один адрес годится и телефону, и браузеру. */
    private static final String INVITE_BASE = "https://igor7070.github.io/Messenger/#/invite/";
    private static final String AVATAR_BASE = "https://unlimitedpossibilities12.org";

    /** Та же палитра кружков, что в списках чатов и людей. */
    private static final int[] AVATAR_COLORS = {
            0xFF6366F1, 0xFF0D9488, 0xFF8B5CF6, 0xFF0EA5E9, 0xFFDB2777, 0xFF475569
    };

    private ApiService apiService;
    private long chatId;
    private String chatName = "Group";
    private int currentUserId = -1;
    private boolean isPublic;

    private final List<ChatMember> members = new ArrayList<>();
    private List<User> allUsers = new ArrayList<>();

    private ImageView avatarImage, menuButton;
    private TextView avatarText, nameView, subtitleView;
    private LinearLayout membersContainer, actionsRow;
    private View addMemberTile, inviteTile;

    private ActivityResultLauncher<String> pickImage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_profile);

        apiService = ApiClient.getApiService();

        chatId = getIntent().getLongExtra("chatId", -1);
        chatName = getIntent().getStringExtra("chatName");
        if (chatName == null) chatName = "Group";
        currentUserId = getIntent().getIntExtra("currentUserId", -1);
        if (currentUserId == -1) {
            // Из переписки идентификатор не передают — берём тот, под которым вошли
            currentUserId = getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);
        }

        pickImage = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAvatar(uri); });

        bindViews();
        loadChat();
        loadMembers();
    }

    private void bindViews() {
        avatarImage = findViewById(R.id.group_avatar_image);
        avatarText = findViewById(R.id.group_avatar_text);
        nameView = findViewById(R.id.group_name_text_view);
        subtitleView = findViewById(R.id.group_subtitle_text_view);
        membersContainer = findViewById(R.id.members_container);
        actionsRow = findViewById(R.id.actions_row);
        addMemberTile = findViewById(R.id.add_member_button);
        inviteTile = findViewById(R.id.invite_link_button);
        menuButton = findViewById(R.id.menu_button);

        nameView.setText(chatName);
        showAvatar(null);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        menuButton.setOnClickListener(v -> showMenu());

        findViewById(R.id.open_chat_button).setOnClickListener(v -> openChat());
        findViewById(R.id.group_call_button).setOnClickListener(v -> startGroupCall());
        addMemberTile.setOnClickListener(v -> showAddDialog());
        inviteTile.setOnClickListener(v -> createInvite());

        // До загрузки участников неизвестно, что человеку можно
        addMemberTile.setVisibility(View.GONE);
        inviteTile.setVisibility(View.GONE);
    }

    // ---------- загрузка ----------

    private void loadChat() {
        apiService.getChatById(chatId).enqueue(new Callback<Chat>() {
            @Override
            public void onResponse(Call<Chat> call, Response<Chat> response) {
                if (isFinishing() || isDestroyed()) return;
                if (response.isSuccessful() && response.body() != null) {
                    Chat chat = response.body();
                    if (chat.getName() != null) chatName = chat.getName();
                    isPublic = Boolean.TRUE.equals(chat.getIsPublic());
                    nameView.setText(chatName);
                    showAvatar(chat.getAvatar());
                    showSubtitle();
                } else {
                    Log.e(TAG, "Failed to load chat: HTTP " + response.code());
                }
            }

            @Override
            public void onFailure(Call<Chat> call, Throwable t) {
                Log.e(TAG, "Error loading chat: " + t.getMessage());
            }
        });
    }

    private void loadMembers() {
        apiService.getChatMembers(chatId).enqueue(new Callback<List<ChatMember>>() {
            @Override
            public void onResponse(Call<List<ChatMember>> call, Response<List<ChatMember>> response) {
                if (isFinishing() || isDestroyed()) return;
                members.clear();
                if (response.isSuccessful() && response.body() != null) {
                    members.addAll(response.body());
                } else {
                    Log.e(TAG, "Failed to load members: HTTP " + response.code());
                }
                showSubtitle();
                renderMembers();
                loadUsers();
            }

            @Override
            public void onFailure(Call<List<ChatMember>> call, Throwable t) {
                Log.e(TAG, "Error loading members: " + t.getMessage());
                toast("Could not load the member list");
            }
        });
    }

    /** Список людей нужен только тем, кто может добавлять. */
    private void loadUsers() {
        if (!canManage()) return;
        apiService.getUsers().enqueue(new Callback<List<User>>() {
            @Override
            public void onResponse(Call<List<User>> call, Response<List<User>> response) {
                if (response.isSuccessful() && response.body() != null) allUsers = response.body();
            }

            @Override
            public void onFailure(Call<List<User>> call, Throwable t) {
                Log.e(TAG, "Error loading users: " + t.getMessage());
            }
        });
    }

    // ---------- показ ----------

    private void showAvatar(@Nullable String avatarPath) {
        String letter = chatName == null || chatName.trim().isEmpty()
                ? "?" : chatName.trim().substring(0, 1).toUpperCase(Locale.getDefault());
        avatarText.setText(letter);

        if (avatarPath == null || avatarPath.trim().isEmpty()) {
            avatarImage.setImageDrawable(null);
            avatarText.setVisibility(View.VISIBLE);
            return;
        }

        String url = AVATAR_BASE + avatarPath + "?t=" + System.currentTimeMillis();
        Glide.with(this)
                .load(url)
                .centerCrop()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<Drawable> target, boolean isFirstResource) {
                        // Файл мог пропасть с сервера — тогда буква названия
                        if (isFinishing() || isDestroyed()) return true;
                        avatarImage.setImageDrawable(null);
                        avatarText.setVisibility(View.VISIBLE);
                        return true;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model,
                                                   Target<Drawable> target, DataSource dataSource,
                                                   boolean isFirstResource) {
                        if (!isFinishing() && !isDestroyed()) avatarText.setVisibility(View.GONE);
                        return false;
                    }
                })
                .into(avatarImage);
    }

    private void showSubtitle() {
        String count = members.isEmpty()
                ? "Members are not listed"
                : getResources().getQuantityString(R.plurals.group_members_count,
                        members.size(), members.size());
        subtitleView.setText(count + " · " + getString(isPublic ? R.string.group_public : R.string.group_private));
    }

    private void renderMembers() {
        membersContainer.removeAllViews();

        boolean manage = canManage();
        addMemberTile.setVisibility(manage ? View.VISIBLE : View.GONE);
        inviteTile.setVisibility(manage ? View.VISIBLE : View.GONE);

        if (members.isEmpty()) {
            TextView note = new TextView(this);
            note.setText("This group was created before members were kept, so there is no list.");
            note.setTextColor(0xFF94A3B8);
            note.setTextSize(14);
            int pad = dp(16);
            note.setPadding(pad, dp(4), pad, dp(10));
            membersContainer.addView(note);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (ChatMember member : members) {
            membersContainer.addView(memberRow(inflater, member));
        }
    }

    private View memberRow(LayoutInflater inflater, ChatMember member) {
        View row = inflater.inflate(R.layout.item_group_member, membersContainer, false);

        ImageView image = row.findViewById(R.id.member_avatar_image);
        TextView letterView = row.findViewById(R.id.member_avatar_text);
        TextView name = row.findViewById(R.id.member_name);
        TextView role = row.findViewById(R.id.member_role);
        ImageView menu = row.findViewById(R.id.member_menu);

        String username = member.getUsername() != null ? member.getUsername() : "User " + member.getUserId();
        boolean isMe = member.getUserId() == currentUserId;
        name.setText(isMe ? username + " (you)" : username);
        role.setText(member.isOwner() ? "owner" : member.isAdmin() ? "admin" : "member");
        role.setTextColor(member.canManageMembers() ? 0xFFA5B4FC : 0xFF94A3B8);

        String letter = username.isEmpty() ? "?" : username.substring(0, 1).toUpperCase(Locale.getDefault());
        letterView.setText(letter);
        letterView.setBackgroundTintList(ColorStateList.valueOf(colorFor(username)));

        if (member.getAvatar() != null && !member.getAvatar().isEmpty()) {
            Glide.with(this)
                    .load(AVATAR_BASE + member.getAvatar())
                    .circleCrop()
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                    Target<Drawable> target, boolean isFirstResource) {
                            image.setImageDrawable(null);
                            letterView.setVisibility(View.VISIBLE);
                            return true;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model,
                                                       Target<Drawable> target, DataSource dataSource,
                                                       boolean isFirstResource) {
                            letterView.setVisibility(View.GONE);
                            return false;
                        }
                    })
                    .into(image);
            letterView.setVisibility(View.GONE);
        } else {
            image.setImageDrawable(null);
            letterView.setVisibility(View.VISIBLE);
        }

        boolean actionsAvailable = canManage() && !isMe;
        menu.setVisibility(actionsAvailable ? View.VISIBLE : View.GONE);
        if (actionsAvailable) {
            menu.setOnClickListener(v -> showMemberMenu(member));
            row.setOnClickListener(v -> showMemberMenu(member));
        } else if (!isMe) {
            // Обычный участник всё равно может открыть человека
            row.setOnClickListener(v -> openMemberProfile(member));
        }
        return row;
    }

    private void openMemberProfile(ChatMember member) {
        Intent intent = new Intent(this, UserProfileActivity.class);
        intent.putExtra("currentUserId", currentUserId);
        intent.putExtra("userId", (int) member.getUserId());
        startActivity(intent);
    }

    // ---------- действия над группой ----------

    private void openChat() {
        Intent intent = new Intent(this, GroupChatsActivity.class);
        intent.putExtra("chatId", chatId);
        intent.putExtra("chatName", chatName);
        startActivity(intent);
        finish();
    }

    private void startGroupCall() {
        if (currentUserId == -1) {
            toast("You need to log in to start a call");
            return;
        }
        ArrayList<String> ids = new ArrayList<>();
        for (ChatMember member : members) {
            if (member.getUserId() != currentUserId) ids.add(String.valueOf(member.getUserId()));
        }
        if (ids.isEmpty()) {
            toast("There is no one to call in this group");
            return;
        }

        String username = getSharedPreferences("prefs", MODE_PRIVATE).getString("username", "Unknown");
        Intent intent = new Intent(this, GroupCallActivity.class);
        intent.putStringArrayListExtra("selectedUsersIds", ids);
        intent.putExtra("initiatorId", currentUserId);
        intent.putExtra("initiatorName", username);
        intent.putExtra("roomId", UUID.randomUUID().toString());
        intent.putExtra("isInitiator", true);
        startActivity(intent);
    }

    private void showMenu() {
        List<String> actions = new ArrayList<>();
        // Картинку и название меняют те же, кто управляет участниками;
        // остальным не предлагаем того, что сервер всё равно отклонит
        if (canManage()) {
            actions.add("Change group picture");
            actions.add("Rename group");
        }
        if (isOwner()) actions.add(isPublic ? "Make the group private" : "Make the group public");
        if (me() != null) actions.add("Leave group");
        if (isOwner() || members.isEmpty()) actions.add("Delete group");

        String[] items = actions.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle(chatName)
                .setItems(items, (dialog, which) -> {
                    String action = items[which];
                    switch (action) {
                        case "Change group picture":
                            pickImage.launch("image/*");
                            break;
                        case "Rename group":
                            showRenameDialog();
                            break;
                        case "Leave group":
                            confirmLeave();
                            break;
                        case "Delete group":
                            confirmDelete();
                            break;
                        default:
                            setPublic(!isPublic);
                            break;
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showMemberMenu(ChatMember member) {
        List<String> actions = new ArrayList<>();
        actions.add("Open profile");
        if (isOwner() && !member.isOwner()) {
            actions.add(member.isAdmin() ? "Remove admin rights" : "Make admin");
            actions.add("Hand over the group");
        }
        if (!member.isOwner()) actions.add("Remove from group");

        String[] items = actions.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle(member.getUsername())
                .setItems(items, (dialog, which) -> {
                    String action = items[which];
                    switch (action) {
                        case "Open profile":
                            openMemberProfile(member);
                            break;
                        case "Make admin":
                            setRole(member, "ADMIN");
                            break;
                        case "Remove admin rights":
                            setRole(member, "MEMBER");
                            break;
                        case "Hand over the group":
                            confirmTransfer(member);
                            break;
                        default:
                            removeMember(member);
                            break;
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showRenameDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(chatName);
        input.setSelection(chatName.length());
        input.setTextColor(0xFFE2E8F0);
        input.setHintTextColor(0xFF64748B);
        int pad = dp(20);
        input.setPadding(pad, dp(12), pad, dp(12));

        new MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle("Rename group")
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("The name cannot be empty");
                        return;
                    }
                    rename(name);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void rename(String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        apiService.updateChat(chatId, body).enqueue(new Callback<Chat>() {
            @Override
            public void onResponse(Call<Chat> call, Response<Chat> response) {
                if (response.isSuccessful()) {
                    chatName = name;
                    nameView.setText(name);
                    toast("Group renamed");
                } else {
                    toast(response.code() == 403 ? "You are not allowed to do that"
                            : "The server refused this change");
                }
            }

            @Override
            public void onFailure(Call<Chat> call, Throwable t) {
                toast("Could not reach the server");
            }
        });
    }

    private void setPublic(boolean value) {
        Map<String, Object> body = new HashMap<>();
        body.put("isPublic", value);
        apiService.updateChat(chatId, body).enqueue(new Callback<Chat>() {
            @Override
            public void onResponse(Call<Chat> call, Response<Chat> response) {
                if (response.isSuccessful()) {
                    isPublic = value;
                    showSubtitle();
                    toast(value ? "The group is now public" : "The group is now private");
                } else {
                    toast("The server refused this change");
                }
            }

            @Override
            public void onFailure(Call<Chat> call, Throwable t) {
                toast("Could not reach the server");
            }
        });
    }

    private void confirmLeave() {
        new MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle("Leave group")
                .setMessage("Leave \"" + chatName + "\"?")
                .setPositiveButton("Leave", (dialog, which) -> apiService.leaveChat(chatId)
                        .enqueue(new Callback<Void>() {
                            @Override
                            public void onResponse(Call<Void> call, Response<Void> response) {
                                toast(response.isSuccessful() ? "You left the group" : "Could not leave");
                                if (response.isSuccessful()) finish();
                            }

                            @Override
                            public void onFailure(Call<Void> call, Throwable t) {
                                toast("Could not leave");
                            }
                        }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Удаление стирает переписку у всех участников — спрашиваем прямо. */
    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle("Delete group")
                .setMessage("Delete \"" + chatName + "\" for everyone? The messages cannot be brought back.")
                .setPositiveButton("Delete", (dialog, which) -> apiService.deleteChat(chatId)
                        .enqueue(new Callback<Void>() {
                            @Override
                            public void onResponse(Call<Void> call, Response<Void> response) {
                                if (response.isSuccessful()) {
                                    toast("Group deleted");
                                    finish();
                                } else {
                                    toast(response.code() == 403 ? "Only the owner can delete this group"
                                            : "The server refused this action");
                                }
                            }

                            @Override
                            public void onFailure(Call<Void> call, Throwable t) {
                                toast("Could not reach the server");
                            }
                        }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------- участники ----------

    private void showAddDialog() {
        List<User> candidates = new ArrayList<>();
        for (User user : allUsers) {
            boolean already = false;
            for (ChatMember member : members) {
                if (member.getUserId() == user.getId()) {
                    already = true;
                    break;
                }
            }
            if (!already) candidates.add(user);
        }
        if (candidates.isEmpty()) {
            toast("Everyone is already in this group");
            return;
        }

        String[] names = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) names[i] = candidates.get(i).getUsername();
        new MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle("Add to the group")
                .setItems(names, (dialog, which) ->
                        apiService.addChatMember(chatId, candidates.get(which).getId())
                                .enqueue(simple("Added to the group")))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void removeMember(ChatMember member) {
        apiService.removeChatMember(chatId, member.getUserId())
                .enqueue(simple("Removed from the group"));
    }

    private void setRole(ChatMember member, String role) {
        Map<String, String> body = new HashMap<>();
        body.put("role", role);
        apiService.setMemberRole(chatId, member.getUserId(), body)
                .enqueue(simple("ADMIN".equals(role) ? "Now an admin" : "No longer an admin"));
    }

    private void confirmTransfer(ChatMember member) {
        new MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle("Hand over the group")
                .setMessage("Make " + member.getUsername() + " the owner? You stay as an admin.")
                .setPositiveButton("Hand over", (dialog, which) ->
                        apiService.transferChatOwnership(chatId, member.getUserId())
                                .enqueue(simple("Ownership transferred")))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void createInvite() {
        Map<String, Object> body = new HashMap<>();
        body.put("ttlHours", 168); // неделя
        body.put("maxUses", 0);    // без предела
        apiService.createChatInvite(chatId, body).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    toast("Could not create the invite");
                    return;
                }
                Object token = response.body().get("token");
                if (token != null) shareInvite(INVITE_BASE + token);
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                toast("Could not create the invite");
            }
        });
    }

    /** Ссылку кладём в буфер и сразу предлагаем, чем поделиться. */
    private void shareInvite(String link) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Invite link", link));

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, link);
        startActivity(Intent.createChooser(share, "Invite to " + chatName));
        toast("Invite link copied");
    }

    /**
     * Картинка группы. Содержимое читаем через ContentResolver: галерея отдаёт
     * content://-адрес, из которого обычного файла не получить.
     */
    private void uploadAvatar(Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                toast("Could not read the picture");
                return;
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
            byte[] bytes = buffer.toByteArray();

            String type = getContentResolver().getType(uri);
            if (type == null) type = "image/*";
            String extension = type.contains("png") ? ".png" : type.contains("webp") ? ".webp" : ".jpg";
            okhttp3.RequestBody body = okhttp3.RequestBody.create(bytes, okhttp3.MediaType.parse(type));
            okhttp3.MultipartBody.Part part =
                    okhttp3.MultipartBody.Part.createFormData("avatar", "group" + extension, body);

            apiService.uploadChatAvatar(chatId, part).enqueue(new Callback<Chat>() {
                @Override
                public void onResponse(Call<Chat> call, Response<Chat> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        toast("Group picture updated");
                        showAvatar(response.body().getAvatar());
                    } else {
                        toast(response.code() == 403 ? "You are not allowed to do that"
                                : "The server refused the picture");
                    }
                }

                @Override
                public void onFailure(Call<Chat> call, Throwable t) {
                    Log.e(TAG, "Avatar upload failed: " + t.getMessage());
                    toast("Could not upload the picture");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Avatar read failed: " + e.getMessage());
            toast("Could not read the picture");
        }
    }

    // ---------- мелочи ----------

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

    private Callback<Void> simple(String successText) {
        return new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    toast(successText);
                    loadMembers();
                } else {
                    toast(response.code() == 403 ? "You are not allowed to do that"
                            : "The server refused this action");
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                toast("Could not reach the server");
            }
        };
    }

    private int colorFor(String name) {
        if (name == null || name.isEmpty()) return AVATAR_COLORS[0];
        int hash = 0;
        for (int i = 0; i < name.length(); i++) hash = hash * 31 + name.charAt(i);
        return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        if (!isFinishing() && !isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
