package com.example.unlimitedpossibilities12.messenger.offline;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.unlimitedpossibilities12.R;

import java.util.ArrayList;
import java.util.List;

public class OfflineModeActivity extends AppCompatActivity {

    private NearbyManager nearbyManager;
    private OfflineRecentChats recentChats;
    private OfflinePreferences offlinePreferences;
    private OfflineAvatarStorage avatarStorage;

    private ImageView offlineModeButton;
    private TextView nicknameTextView;
    private ImageView avatarImageView;

    // Основной список найденных устройств
    private RecyclerView recyclerView;
    private OfflineUsersAdapter adapter;
    private final List<NearbyUser> foundDevices = new ArrayList<>();

    // Список недавних чатов
    private RecyclerView recentChatsRecyclerView;
    private OfflineRecentChatsAdapter recentAdapter;
    private final List<OfflineRecentChats.RecentChat> recentChatList = new ArrayList<>();

    private boolean isAdvertising = false;
    private boolean isDiscovering = false;

    private static final int PICK_AVATAR_REQUEST = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_mode);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        TextView title = findViewById(R.id.toolbar_title);
        title.setText("Messenger UP12");

        offlineModeButton = findViewById(R.id.offline_mode_button);
        nicknameTextView = findViewById(R.id.tv_nickname);
        avatarImageView = findViewById(R.id.iv_avatar);

        // RecyclerView
        recyclerView = findViewById(R.id.offline_users_recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new OfflineUsersAdapter(foundDevices, this::onDeviceClicked, avatarStorage);
        recyclerView.setAdapter(adapter);

        recentChatsRecyclerView = findViewById(R.id.recent_chats_recycler);
        recentChatsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        recentAdapter = new OfflineRecentChatsAdapter(recentChatList, new OfflineRecentChatsAdapter.OnRecentChatClickListener() {
            @Override public void onRecentChatClick(OfflineRecentChats.RecentChat chat) { onRecentChatClicked(chat); }
            @Override public void onRecentChatLongClick(OfflineRecentChats.RecentChat chat) { showRecentChatMenu(chat); }
        });
        recentChatsRecyclerView.setAdapter(recentAdapter);

        // Инициализация
        offlinePreferences = new OfflinePreferences(this);
        avatarStorage = new OfflineAvatarStorage(this);
        nearbyManager = new NearbyManager(this);
        recentChats = new OfflineRecentChats(this);

        // Подключаем хранилище аватаров
        nearbyManager.setAvatarStorage(avatarStorage);

        updateNicknameDisplay();
        updateAvatarDisplay();

        nearbyManager.setOnDeviceFoundListener(this::onDeviceFound);
        nearbyManager.setOnConnectionListener(this::onConnectionEstablished);
        nearbyManager.setOnIncomingConnectionListener(this::onIncomingConnection);

        // === ГЛОБАЛЬНЫЙ LISTENER ДЛЯ АВАТАРОВ И СООБЩЕНИЙ ВНЕ ЧАТА ===
        modeListener = new NearbyManager.OnMessageReceivedListener() {
            @Override
            public void onMessageReceived(String endpointId, String text) {
                // Чат с этим собеседником не открыт — сохраняем сообщение в историю,
                // чтобы оно не потерялось (актуально для mesh: сообщение может прийти
                // от узла, с которым мы ни разу не соединялись напрямую).
                if (text == null || text.startsWith("___")) return; // служебные — только в открытом чате
                String messageId = null, body = text;
                int bar = text.indexOf('|');
                if (bar > 0) { messageId = text.substring(0, bar); body = text.substring(bar + 1); }
                OfflineMessage msg = new OfflineMessage(body, false, messageId);
                new OfflineChatStorage(OfflineModeActivity.this).saveMessage(endpointId, msg);
                String name = displayNameFor(endpointId);
                recentChats.addRecentChat(endpointId, name);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    loadRecentChats();
                    Toast.makeText(OfflineModeActivity.this, "New message from " + name, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onFileReceived(String endpointId, Uri fileUri) {
                // Можно оставить пустым
            }

            @Override
            public void onAvatarReceived(String endpointId, Uri avatarUri) {
                if (avatarUri == null) return;

                // Защита от краша
                if (isDestroyed() || isFinishing()) {
                    return;
                }

                avatarStorage.saveAvatar(endpointId, avatarUri);

                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        updateUserAvatar(endpointId, avatarUri);
                    }
                });
            }
        };
        NearbyManager.setGlobalMessageListener(modeListener);

        // Кнопки
        // Mesh — иконка в тулбаре, настройки в диалоге (экран остаётся прежним)
        ImageView meshButton = findViewById(R.id.mesh_settings_button);
        meshButton.setOnClickListener(v -> showMeshDialog());
        updateMeshIconTint();
        NearbyManager.setPeerIdentityListener((endpointId, peerId, peerName) -> runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) return;
            rebindRecentChats(endpointId, peerName);
        }));
        NearbyManager.setMeshNodesListener(new NearbyManager.OnMeshNodesListener() {
            @Override public void onMeshNodeDiscovered(com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshNode node) {
                runOnUiThread(() -> syncMeshNodes());
            }
            @Override public void onMeshNodeLost(String nodeId) {
                runOnUiThread(() -> syncMeshNodes());
            }
        });

        findViewById(R.id.btn_advertise).setOnClickListener(v -> toggleAdvertising());
        findViewById(R.id.btn_discover).setOnClickListener(v -> toggleDiscovery());

        offlineModeButton.setOnClickListener(v -> finish());

        // Клик по нику/аватару
        View nicknameContainer = findViewById(R.id.nickname_container);
        if (nicknameContainer != null) {
            nicknameContainer.setOnClickListener(v -> showChangeNicknameDialog());
        } else if (nicknameTextView != null) {
            nicknameTextView.setOnClickListener(v -> showChangeNicknameDialog());
        }
        if (avatarImageView != null) {
            avatarImageView.setOnClickListener(v -> showChangeNicknameDialog());
        }

        checkAndRequestPermissions();
        loadRecentChats();
    }

    private void updateUserAvatar(String endpointId, Uri avatarUri) {
        for (NearbyUser user : foundDevices) {
            if (user.getEndpointId().equals(endpointId)) {
                user.setAvatarUri(avatarUri);
                adapter.notifyDataSetChanged();
                return;
            }
        }
    }

    private void updateNicknameDisplay() {
        if (nicknameTextView != null) {
            String currentNick = offlinePreferences.getNickname();
            nicknameTextView.setText(currentNick);
        }
    }

    private void updateAvatarDisplay() {
        if (avatarImageView == null) return;

        Uri avatarUri = offlinePreferences.getAvatarUri();
        if (avatarUri != null) {
            Glide.with(this)
                    .load(avatarUri)
                    .circleCrop()
                    .placeholder(R.drawable.default_avatar_offline)
                    .into(avatarImageView);
        } else {
            avatarImageView.setImageResource(R.drawable.default_avatar_offline);
        }
    }

    private void showChangeNicknameDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(offlinePreferences.getNickname());
        input.setPadding(60, 40, 60, 40);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF94A3B8);
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF334155));

        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Your nickname")
                .setView(input)
                .setPositiveButton("Save nickname", (dialogInterface, which) -> {
                    String newNick = input.getText().toString().trim();
                    if (!newNick.isEmpty()) {
                        offlinePreferences.setNickname(newNick);
                        nearbyManager.setUserNickname(newNick);
                        updateNicknameDisplay();
                        Toast.makeText(this, "Nickname updated", Toast.LENGTH_SHORT).show();

                        if (isAdvertising) {
                            nearbyManager.stopAdvertising();
                            nearbyManager.startAdvertising();
                        }
                    }
                })
                .setNeutralButton("Upload avatar", (dialogInterface, which) -> pickAvatar())
                .setNegativeButton("Cancel", null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_dark_background);
        }

        dialog.setOnShowListener(dialogInterface -> {
            TextView titleView = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
            if (titleView != null) titleView.setTextColor(0xFFFFFFFF);

            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(0xFF4ADE80);
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                    .setTextColor(0xFF60A5FA);
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(0xFF94A3B8);
        });

        dialog.show();
    }

    private void pickAvatar() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_AVATAR_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_AVATAR_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri avatarUri = data.getData();
            if (avatarUri != null) {
                offlinePreferences.setAvatarUri(avatarUri);
                nearbyManager.setUserAvatarUri(avatarUri);
                updateAvatarDisplay();
                Toast.makeText(this, "Avatar updated", Toast.LENGTH_SHORT).show();

                // === ИСПРАВЛЕННЫЙ ВЫЗОВ ===
                nearbyManager.updateMyAvatar(avatarUri);
            }
        }
    }

    private void loadRecentChats() {
        recentChatList.clear();
        recentChatList.addAll(recentChats.getRecentChats());
        recentAdapter.notifyDataSetChanged();

        boolean hasRecent = !recentChatList.isEmpty();
        findViewById(R.id.recent_chats_section).setVisibility(hasRecent ? View.VISIBLE : View.GONE);
    }

    /** Долгое нажатие на недавний чат: убрать из списка или удалить вместе с историей. */
    private void showRecentChatMenu(OfflineRecentChats.RecentChat chat) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(chat.name)
                .setItems(new String[]{"Remove from recent", "Delete chat and history"}, (d, which) -> {
                    if (which == 0) {
                        recentChats.removeRecentChat(chat.endpointId);
                        Toast.makeText(this, "Removed from recent", Toast.LENGTH_SHORT).show();
                    } else {
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Delete chat with " + chat.name + "?")
                                .setMessage("All messages of this offline chat will be deleted from this phone.")
                                .setPositiveButton("Delete", (d2, w2) -> {
                                    new OfflineChatStorage(this).clearChat(chat.endpointId);
                                    recentChats.removeRecentChat(chat.endpointId);
                                    loadRecentChats();
                                    Toast.makeText(this, "Chat deleted", Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                        return;
                    }
                    loadRecentChats();
                })
                .show();
    }

    private void onRecentChatClicked(OfflineRecentChats.RecentChat chat) {
        // Nearby выдаёт новый endpointId при каждом подключении, поэтому запись в
        // «недавних» может указывать на несуществующее соединение. Ищем этого же
        // собеседника среди найденных сейчас устройств и переносим на него чат.
        if (nearbyManager.isConnectedTo(chat.endpointId)) {
            openChat(chat.endpointId, chat.name);
            return;
        }
        NearbyUser live = null;
        for (NearbyUser u : foundDevices) {
            if (u.getEndpointId().equals(chat.endpointId)) { live = u; break; }
            if (u.getName() != null && u.getName().equals(chat.name)) live = u; // тот же собеседник, новое соединение
        }
        if (live == null) {
            Toast.makeText(this, chat.name + " is not nearby — messages will be queued", Toast.LENGTH_SHORT).show();
            openChat(chat.endpointId, chat.name);
            return;
        }
        String newEp = live.getEndpointId();
        if (!newEp.equals(chat.endpointId)) {
            new OfflineChatStorage(this).mergeChat(chat.endpointId, newEp);
            new OfflineOutbox(this).reassign(chat.endpointId, newEp);
            recentChats.replaceEndpoint(chat.endpointId, newEp, chat.name);
            loadRecentChats();
        }
        if (nearbyManager.isConnectedTo(newEp)) {
            openChat(newEp, chat.name);
        } else {
            // Соединение поднимется само; чат откроется по событию onConnectionEstablished
            Toast.makeText(this, "Reconnecting to " + chat.name + "…", Toast.LENGTH_SHORT).show();
            pendingReconnectEndpoint = newEp;
            pendingReconnectName = chat.name;
            nearbyManager.requestConnection(newEp);
        }
    }

    private void toggleAdvertising() {
        if (isAdvertising) {
            nearbyManager.stopAdvertising();
            isAdvertising = false;
            Toast.makeText(this, "Visibility stopped", Toast.LENGTH_SHORT).show();
        } else {
            nearbyManager.startAdvertising();
            isAdvertising = true;
            Toast.makeText(this, "Visible to nearby devices as " + offlinePreferences.getNickname(), Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleDiscovery() {
        if (isDiscovering) {
            nearbyManager.stopDiscovery();
            isDiscovering = false;
            Toast.makeText(this, "Search stopped", Toast.LENGTH_SHORT).show();
        } else {
            nearbyManager.startDiscovery();
            isDiscovering = true;
            Toast.makeText(this, "Searching for devices...", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkAndRequestPermissions() {
        String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_STATE
                } :
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_STATE
                };

        if (!hasPermissions(permissions)) {
            ActivityCompat.requestPermissions(this, permissions, 1001);
        } else {
            startOfflineMode();
        }
    }

    private boolean hasPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }

            if (allGranted) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show();
                startOfflineMode();
            } else {
                Toast.makeText(this, "Offline mode needs all permissions to work", Toast.LENGTH_LONG).show();
            }
        }
    }

    private NearbyManager.OnMessageReceivedListener modeListener;

    private void updateMeshIconTint() {
        ImageView icon = findViewById(R.id.mesh_settings_button);
        if (icon != null) icon.setColorFilter(android.graphics.Color.parseColor(NearbyManager.isMeshEnabled() ? "#67E8F9" : "#94A3B8"));
    }

    private void showMeshDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_mesh_settings, null);
        android.widget.Switch sw = v.findViewById(R.id.switch_mesh);
        sw.setChecked(NearbyManager.isMeshEnabled());
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this).setView(v).create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        sw.setOnCheckedChangeListener((btn, on) -> {
            nearbyManager.setMeshEnabled(on);
            // Стратегия Nearby меняется — полный перезапуск (соединения рвутся,
            // устройства найдутся заново уже в новом режиме)
            nearbyManager.restartWithCurrentStrategy(isAdvertising, isDiscovering);
            foundDevices.clear();
            adapter.notifyDataSetChanged();
            startMeshSync();
            updateMeshIconTint();
            Toast.makeText(this, on ? "Mesh relay on (beta)" : "Mesh relay off", Toast.LENGTH_SHORT).show();
        });
        android.widget.Switch swTest = v.findViewById(R.id.switch_test_node);
        swTest.setChecked(NearbyManager.isTestNodeEnabled());
        swTest.setOnCheckedChangeListener((btn, on) -> {
            if (on && !NearbyManager.isMeshEnabled()) {
                Toast.makeText(this, "Turn on mesh relay first", Toast.LENGTH_SHORT).show();
                swTest.setChecked(false);
                return;
            }
            nearbyManager.setTestNodeEnabled(on);
            Toast.makeText(this, on ? "Virtual relay node started" : "Virtual relay node stopped", Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.close_button).setOnClickListener(x -> dialog.dismiss());
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Вернулись из чата: чат снимает свой слушатель, ставим обратно свой
        if (modeListener != null) NearbyManager.setGlobalMessageListener(modeListener);
        startMeshSync();
    }

    // Список устройств и состояние сети синхронизируем по таймеру: событие
    // «узел найден» может прийти, пока экран не виден, а узел — стать
    // недостижимым без отдельного события (посредник ушёл из радиуса).
    private final android.os.Handler meshSyncHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable meshSyncTask = new Runnable() {
        @Override public void run() {
            syncMeshNodes();
            meshSyncHandler.postDelayed(this, 2000);
        }
    };

    private void startMeshSync() {
        meshSyncHandler.removeCallbacks(meshSyncTask);
        meshSyncHandler.post(meshSyncTask);
    }

    private void syncMeshNodes() {
        if (isDestroyed() || isFinishing()) return;
        nearbyManager.flushOutbox(); // накопленное уходит, как только собеседник доступен
        boolean changed = false;

        // Ушедшие/выключенные — убрать
        for (java.util.Iterator<NearbyUser> it = foundDevices.iterator(); it.hasNext(); ) {
            NearbyUser u = it.next();
            if (!com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshNode.isVirtual(u.getEndpointId())) continue;
            if (!NearbyManager.isMeshEnabled() || !nearbyManager.isConnectedTo(u.getEndpointId())) {
                it.remove();
                changed = true;
            }
        }
        // Новые — добавить
        if (NearbyManager.isMeshEnabled()) {
            for (com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshNode n : NearbyManager.meshRemoteNodes()) {
                String ep = n.virtualEndpointId();
                boolean present = false;
                for (NearbyUser u : foundDevices) if (u.getEndpointId().equals(ep)) { present = true; break; }
                if (!present) {
                    foundDevices.add(new NearbyUser(ep, n.name + "  ·  via mesh (" + n.hops + " hops)"));
                    changed = true;
                }
            }
        }
        if (changed) adapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        meshSyncHandler.removeCallbacks(meshSyncTask);
    }

    private String displayNameFor(String endpointId) {
        for (NearbyUser u : foundDevices) if (u.getEndpointId().equals(endpointId)) return u.getName();
        for (OfflineRecentChats.RecentChat c : recentChatList) if (c.endpointId.equals(endpointId)) return c.name;
        String mesh = NearbyManager.meshNodeName(endpointId);
        return mesh != null ? mesh : "Peer";
    }

    private void startOfflineMode() {
        nearbyManager.startAdvertising();
        nearbyManager.startDiscovery();
        isAdvertising = true;
        isDiscovering = true;
    }

    private void onDeviceFound(String endpointId, String name) {
        runOnUiThread(() -> {
            // Проверяем, есть ли уже такой пользователь
            for (NearbyUser user : foundDevices) {
                if (user.getEndpointId().equals(endpointId)) {
                    // Обновляем аватар на случай, если он уже был получен ранее
                    Uri savedAvatar = avatarStorage.getAvatar(endpointId);
                    if (savedAvatar != null) {
                        user.setAvatarUri(savedAvatar);
                    }
                    adapter.notifyDataSetChanged();
                    return;
                }
            }

            // Новый пользователь
            NearbyUser newUser = new NearbyUser(endpointId, name);

            Uri savedAvatar = avatarStorage.getAvatar(endpointId);
            if (savedAvatar != null) {
                newUser.setAvatarUri(savedAvatar);
            }

            foundDevices.add(newUser);
            adapter.notifyDataSetChanged();
        });
    }

    private void onDeviceClicked(NearbyUser user) {
        if (nearbyManager.isConnectedTo(user.getEndpointId())) {
            openChat(user.getEndpointId(), user.getName());
            return;
        }

        Toast.makeText(this, "Sending connection request...", Toast.LENGTH_SHORT).show();
        nearbyManager.requestConnection(user.getEndpointId());
    }

    private void openChat(String endpointId, String name) {
        recentChats.addRecentChat(endpointId, name);

        Intent intent = new Intent(this, OfflineChatActivity.class);
        intent.putExtra(OfflineChatActivity.EXTRA_ENDPOINT_ID, endpointId);
        intent.putExtra(OfflineChatActivity.EXTRA_USER_NAME, name);
        startActivity(intent);
    }

    /** endpointId, к которому переподключаемся по нажатию в «недавних». */
    private String pendingReconnectEndpoint;
    private String pendingReconnectName;

    /**
     * Собеседник подключился под новым endpointId: переносим на него историю,
     * очередь и запись в «недавних», иначе неотправленное так и останется
     * висеть на несуществующем соединении.
     */
    private void rebindRecentChats(String newEndpointId, String name) {
        String peerId = NearbyManager.peerIdOf(newEndpointId);
        if (peerId != null) recentChats.setPeerId(newEndpointId, peerId);
        if (name == null && peerId == null) return;
        for (OfflineRecentChats.RecentChat c : recentChats.getRecentChats()) {
            if (c.endpointId.equals(newEndpointId)) continue;
            if (com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshNode.isVirtual(c.endpointId)) continue;
            // По ключу — надёжно (собеседник мог сменить имя); по имени — для старых записей без ключа
            boolean sameByKey = peerId != null && peerId.equals(c.peerId);
            boolean sameByName = c.peerId == null && name != null && name.equals(c.name);
            if (!sameByKey && !sameByName) continue;
            new OfflineChatStorage(this).mergeChat(c.endpointId, newEndpointId);
            new OfflineOutbox(this).reassign(c.endpointId, newEndpointId);
            recentChats.replaceEndpoint(c.endpointId, newEndpointId, name != null ? name : c.name, peerId);
            loadRecentChats();
            nearbyManager.flushOutbox(); // накопленное в старом чате уходит сразу
            break;
        }
    }

    private void onConnectionEstablished(String endpointId) {
        runOnUiThread(() -> {
            Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show();
            rebindRecentChats(endpointId, NearbyManager.endpointName(endpointId));
            nearbyManager.flushOutbox(); // накопленное для этого собеседника
            if (endpointId.equals(pendingReconnectEndpoint)) {
                String name = pendingReconnectName;
                pendingReconnectEndpoint = null;
                pendingReconnectName = null;
                openChat(endpointId, name);
            }
        });
    }

    private void onIncomingConnection(String endpointId, String name) {
        runOnUiThread(() -> {
            Toast.makeText(this, "Incoming chat from " + name, Toast.LENGTH_LONG).show();
            rebindRecentChats(endpointId, name); // тот же собеседник мог прийти с новым endpointId
            recentChats.addRecentChat(endpointId, name);
            openChat(endpointId, name);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nearbyManager != null) {
            nearbyManager.stopAdvertising();
            nearbyManager.stopDiscovery();
        }
    }
}