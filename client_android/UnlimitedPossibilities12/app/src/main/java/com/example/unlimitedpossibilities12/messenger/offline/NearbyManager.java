package com.example.unlimitedpossibilities12.messenger.offline;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.*;

import com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshNode;
import com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshRouter;
import com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshSodiumCrypto;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class NearbyManager {

    private static final String TAG = "NearbyManager";
    private static final String SERVICE_ID = "com.unlimitedpossibilities12.offline";

    private static OnMessageReceivedListener globalMessageListener;
    private static OnAudioChunkListener globalAudioChunkListener;

    private final Context context;
    private final ConnectionsClient connectionsClient;

    private String userNickname;
    private Uri userAvatarUri;

    private static final List<String> activeConnections = new ArrayList<>();
    /** endpointId -> имя собеседника: Nearby меняет endpointId каждое подключение. */
    private static final java.util.Map<String, String> endpointNames = new java.util.HashMap<>();

    // ===== MESH (пересылка через соседей) =====
    // Один маршрутизатор на приложение: экраны создают свои NearbyManager, а
    // соединения Nearby и сеть — общие. Включается переключателем в офлайн-режиме;
    // по умолчанию выключен, и тогда поведение в точности прежнее.
    private static MeshRouter mesh;
    private static MeshSodiumCrypto meshCrypto;
    private static boolean meshEnabled;
    private static ConnectionsClient meshClient;
    private static Context appContext;
    private static OnMeshNodesListener meshNodesListener;


    private OnDeviceFoundListener deviceFoundListener;
    private OnConnectionListener connectionListener;
    private OnIncomingConnectionListener incomingConnectionListener;
    private OnConnectionSuccessListener connectionSuccessListener;

    private OfflineAvatarStorage avatarStorage;

    public NearbyManager(Context context) {
        this.context = context;
        this.connectionsClient = Nearby.getConnectionsClient(context);

        OfflinePreferences prefs = new OfflinePreferences(context);
        this.userNickname = prefs.getNickname();
        this.userAvatarUri = prefs.getAvatarUri();

        meshEnabled = prefs.isMeshEnabled();
        meshClient = connectionsClient;
        appContext = context.getApplicationContext();
        if (mesh == null) {
            meshCrypto = new MeshSodiumCrypto(context.getApplicationContext());
            mesh = new MeshRouter(meshCrypto.nodeId(), userNickname,
                    (endpointId, payload) -> {
                        if (com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshTestNode.GHOST_ENDPOINT.equals(endpointId)) {
                            if (testNode != null) testNode.receiveFromHost(payload); // тестовый узел в памяти
                            return;
                        }
                        if (meshClient != null) meshClient.sendPayload(endpointId, Payload.fromBytes(payload.getBytes()));
                    },
                    new MeshRouter.Listener() {
                        @Override public void onMeshMessage(String fromNodeId, String fromName, String body) {
                            // Сообщение из сети приходит в чат под виртуальным endpointId "mesh:<nodeId>"
                            Log.d(TAG, "MESH: message from " + fromName + " (" + fromNodeId + ")");
                            String virtualEp = MeshRouter.VIRTUAL_PREFIX + fromNodeId;
                            if (body.startsWith(ACK_PREFIX)) {
                                String ackId = body.substring(ACK_PREFIX.length());
                                new OfflineChatStorage(appContext).updateStatus(virtualEp, ackId, OfflineMessage.STATUS_DELIVERED);
                                if (statusListener != null) statusListener.onMessageStatusChanged(virtualEp, ackId, OfflineMessage.STATUS_DELIVERED);
                                return;
                            }
                            if (!body.startsWith("___") && body.contains("|") && meshEnabled) {
                                mesh.sendTo(fromNodeId, ACK_PREFIX + body.substring(0, body.indexOf('|')));
                            }
                            if (globalMessageListener != null) {
                                globalMessageListener.onMessageReceived(virtualEp, body);
                            }
                        }
                        @Override public void onNodeDiscovered(MeshNode node) {
                            Log.d(TAG, "MESH: node " + node.name + " hops=" + node.hops + (node.direct ? " (direct)" : ""));
                            if (meshNodesListener != null) meshNodesListener.onMeshNodeDiscovered(node);
                        }
                        @Override public void onNodeLost(String nodeId) {
                            if (meshNodesListener != null) meshNodesListener.onMeshNodeLost(nodeId);
                        }
                    }, meshCrypto);
        } else {
            mesh.setMyName(userNickname);
        }
    }

    // ====================== MESH API ======================

    public static boolean isMeshEnabled() { return meshEnabled; }

    /** Включить/выключить пересылку. Требует перезапуска advertising/discovery (другая стратегия Nearby). */
    public void setMeshEnabled(boolean enabled) {
        if (enabled == meshEnabled) return;
        new OfflinePreferences(context).setMeshEnabled(enabled);
        meshEnabled = enabled;
        if (!enabled) setTestNodeEnabled(false);
    }

    /**
     * Nearby не даёт сменить стратегию (POINT_TO_POINT <-> CLUSTER), пока есть
     * соединения или активный поиск на старой — отвечает
     * STATUS_ALREADY_HAVE_ACTIVE_STRATEGY. Поэтому при переключении режима
     * всё останавливаем полностью, рвём соединения и стартуем заново.
     */
    public void restartWithCurrentStrategy(boolean advertise, boolean discover) {
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectionsClient.stopAllEndpoints();
        for (String ep : new ArrayList<>(activeConnections)) mesh.onNeighborDisconnected(ep);
        activeConnections.clear();
        // Сервису нужно время, чтобы отпустить старую стратегию
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (advertise) startAdvertising();
            if (discover) startDiscovery();
        }, 1200);
    }

    public static void setMeshNodesListener(OnMeshNodesListener listener) { meshNodesListener = listener; }

    // ---- ТЕСТ: виртуальный узел-ретранслятор (удалить после проверки на третьем телефоне) ----
    private static com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshTestNode testNode;

    public static boolean isTestNodeEnabled() { return testNode != null; }

    public void setTestNodeEnabled(boolean enabled) {
        if (enabled && testNode == null && meshEnabled) {
            testNode = new com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshTestNode(context.getApplicationContext(), mesh);
            testNode.start();
        } else if (!enabled && testNode != null) {
            testNode.stop();
            testNode = null;
        }
    }

    /** Узлы, известные через сеть (кроме прямых соседей — те и так в списке устройств). */
    public static List<MeshNode> meshRemoteNodes() {
        List<MeshNode> out = new ArrayList<>();
        if (mesh == null || !meshEnabled) return out;
        for (MeshNode n : mesh.knownNodes()) if (!n.direct) out.add(n);
        return out;
    }

    public static String meshNodeName(String virtualEndpointId) {
        if (mesh == null) return null;
        MeshNode n = mesh.node(MeshNode.nodeIdOf(virtualEndpointId));
        return n != null ? n.name : null;
    }

    private Strategy strategy() {
        // CLUSTER — много соседей (нужен для пересылки), но только Bluetooth;
        // POINT_TO_POINT — один собеседник, зато Wi-Fi Direct: быстрые файлы и звонок.
        return meshEnabled ? Strategy.P2P_CLUSTER : Strategy.P2P_POINT_TO_POINT;
    }

    public static void setGlobalMessageListener(OnMessageReceivedListener listener) {
        globalMessageListener = listener;
    }

    public static void setGlobalAudioChunkListener(OnAudioChunkListener listener) {
        globalAudioChunkListener = listener;
    }

    // ====================== РЕКЛАМИРОВАТЬ СЕБЯ ======================
    public void startAdvertising() {
        AdvertisingOptions options = new AdvertisingOptions.Builder()
                .setStrategy(strategy())
                .build();

        connectionsClient.startAdvertising(
                        userNickname, SERVICE_ID, connectionLifecycleCallback, options)
                .addOnSuccessListener(unused -> Log.d(TAG, "Реклама запущена"))
                .addOnFailureListener(e -> Log.e(TAG, "Ошибка рекламы", e));
    }

    public void stopAdvertising() {
        connectionsClient.stopAdvertising();
    }

    public void startDiscovery() {
        DiscoveryOptions options = new DiscoveryOptions.Builder()
                .setStrategy(strategy())
                .build();

        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                .addOnSuccessListener(unused -> Log.d(TAG, "Поиск запущен"))
                .addOnFailureListener(e -> Log.e(TAG, "Ошибка поиска", e));
    }

    public void stopDiscovery() {
        connectionsClient.stopDiscovery();
    }

    public void requestConnection(String endpointId) {
        if (MeshNode.isVirtual(endpointId)) {
            // Узел за соседями: прямого соединения нет и не нужно — он достижим через сеть
            if (isConnectedTo(endpointId)) {
                if (connectionListener != null) connectionListener.onConnected(endpointId);
                if (connectionSuccessListener != null) connectionSuccessListener.onConnectionSuccess(endpointId);
            }
            return;
        }
        connectionsClient.requestConnection(userNickname, endpointId, connectionLifecycleCallback);
    }

    /** @return true, если сообщение ушло в транспорт; false — собеседник недоступен. */
    public boolean sendMessage(String endpointId, String text) {
        if (text == null || text.isEmpty()) return false;
        if (MeshNode.isVirtual(endpointId)) {
            // Через сеть: шифруется для получателя и уходит соседям
            boolean ok = meshEnabled && mesh.sendTo(MeshNode.nodeIdOf(endpointId), text);
            if (!ok) Log.w(TAG, "MESH: узел недоступен, сообщение в очередь: " + endpointId);
            return ok;
        }
        if (!activeConnections.contains(endpointId)) {
            Log.w(TAG, "Нет соединения, сообщение в очередь: " + endpointId);
            return false;
        }
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(text.getBytes()));
        return true;
    }

    // ====================== ОЧЕРЕДЬ И ПОДТВЕРЖДЕНИЯ ======================

    /** Префикс подтверждения доставки: получатель отвечает им на каждое текстовое сообщение. */
    public static final String ACK_PREFIX = "___ACK___";
    /**
     * Представление при соединении: постоянный идентификатор устройства и имя.
     * Nearby выдаёт новый endpointId каждое подключение, а имя пользователь
     * может сменить — по ключу собеседник опознаётся в любом случае.
     */
    public static final String PEER_ID_PREFIX = "___PEERID___";

    private static final java.util.Map<String, String> peerIdByEndpoint = new java.util.HashMap<>();
    private static OnPeerIdentityListener peerIdentityListener;

    public static void setPeerIdentityListener(OnPeerIdentityListener listener) { peerIdentityListener = listener; }

    /** Постоянный идентификатор собеседника, если он успел представиться. */
    public static String peerIdOf(String endpointId) { return peerIdByEndpoint.get(endpointId); }

    /** Наш постоянный идентификатор. */
    public static String myPeerId() { return meshCrypto != null ? meshCrypto.nodeId() : null; }

    private static OnMessageStatusListener statusListener;

    public static void setMessageStatusListener(OnMessageStatusListener listener) { statusListener = listener; }

    /**
     * Отправляет всё, что ждало в очереди и стало доступным. Вызывается при
     * соединении, при появлении узла в сети и по таймеру с экрана офлайн-режима.
     */
    public void flushOutbox() {
        OfflineOutbox outbox = new OfflineOutbox(context);
        OfflineChatStorage storage = new OfflineChatStorage(context);
        for (OfflineOutbox.Item item : outbox.pending()) {
            if (!isConnectedTo(item.endpointId)) continue;
            if (sendMessage(item.endpointId, item.payload)) {
                outbox.remove(item.messageId);
                storage.updateStatus(item.endpointId, item.messageId, OfflineMessage.STATUS_SENT);
                Log.d(TAG, "Очередь: отправлено " + item.messageId + " -> " + item.endpointId);
                if (statusListener != null) {
                    statusListener.onMessageStatusChanged(item.endpointId, item.messageId, OfflineMessage.STATUS_SENT);
                }
            }
        }
    }

    /** Подтверждение доставки отправителю (работает и напрямую, и через сеть). */
    private void sendAck(String endpointId, String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        sendMessage(endpointId, ACK_PREFIX + messageId);
    }

    public void sendFile(String endpointId, Payload payload) {
        if (endpointId == null || payload == null) return;
        if (MeshNode.isVirtual(endpointId)) {
            Log.w(TAG, "MESH: файлы и аудио через сеть пока не передаются");
            return;
        }

        connectionsClient.sendPayload(endpointId, payload)
                .addOnSuccessListener(unused -> {
                    // Не спамим лог на каждый аудио-чанк
                    if (payload.getType() != Payload.Type.BYTES) {
                        Log.d(TAG, "Файл успешно отправлен");
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "Ошибка отправки payload", e));
    }

    // ====================== ОТПРАВКА АВАТАРА ======================
    public void sendAvatar(String endpointId) {
        if (MeshNode.isVirtual(endpointId)) return;
        Log.d(TAG, "sendAvatar() вызван → " + endpointId + " | URI=" + (userAvatarUri != null));

        if (userAvatarUri == null || avatarStorage == null) {
            Log.d(TAG, "Аватар не установлен или хранилище не подключено");
            return;
        }

        Uri fileUri = avatarStorage.prepareAvatarForSending(userAvatarUri);
        if (fileUri == null) {
            Log.e(TAG, "Не удалось подготовить файл аватара");
            return;
        }

        File file = new File(fileUri.getPath());
        if (!file.exists()) {
            Log.e(TAG, "Файл аватара не существует: " + file.getAbsolutePath());
            return;
        }

        try {
            // 1. Сначала отправляем специальную метку
            sendMessage(endpointId, "___AVATAR___");

            // 2. Сразу после метки отправляем сам файл аватара
            Payload payload = Payload.fromFile(file);
            connectionsClient.sendPayload(endpointId, payload)
                    .addOnSuccessListener(unused -> Log.d(TAG, "Аватар успешно отправлен"))
                    .addOnFailureListener(e -> Log.e(TAG, "Ошибка отправки аватара", e));

        } catch (Exception e) {
            Log.e(TAG, "Ошибка при отправке аватара", e);
        }
    }

    public void sendAvatarToAllConnected() {
        Log.d(TAG, "sendAvatarToAllConnected() — подключено устройств: " + activeConnections.size());
        for (String endpointId : new ArrayList<>(activeConnections)) {
            sendAvatar(endpointId);
        }
    }

    /**
     * Новый метод: обновить аватар + перезапустить рекламу
     */
    public void updateMyAvatar(Uri newAvatarUri) {
        this.userAvatarUri = newAvatarUri;
        Log.d(TAG, "updateMyAvatar: аватар обновлён, перезапускаем advertising");

        // Перезапускаем рекламу, чтобы другие устройства переобнаружили нас
        stopAdvertising();
        startAdvertising();

        // Отправляем новый аватар уже подключённым
        sendAvatarToAllConnected();
    }

    public void connectToExistingDevice(String endpointId) {
        Log.d(TAG, "Попытка восстановить соединение с: " + endpointId);
        requestConnection(endpointId);
    }

    // ====================== CALLBACKS ======================
    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            if (info.getEndpointName() != null) endpointNames.put(endpointId, info.getEndpointName());
            Log.d(TAG, "onEndpointFound: " + endpointId + " (" + info.getEndpointName() + ")");

            if (deviceFoundListener != null) {
                deviceFoundListener.onDeviceFound(endpointId, info.getEndpointName());
            }

            if (isConnectedTo(endpointId)) {
                Log.d(TAG, "Устройство уже подключено — отправляем аватар");
                sendAvatar(endpointId);
            }
        }

        @Override
        public void onEndpointLost(String endpointId) {
            Log.d(TAG, "onEndpointLost: " + endpointId);
        }
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            if (info.getEndpointName() != null) endpointNames.put(endpointId, info.getEndpointName());
            connectionsClient.acceptConnection(endpointId, payloadCallback);
            if (incomingConnectionListener != null) {
                incomingConnectionListener.onIncomingConnection(endpointId, info.getEndpointName());
            }
        }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution resolution) {
            if (resolution.getStatus().isSuccess()) {
                Log.d(TAG, "Соединение УСТАНОВЛЕНО с " + endpointId);

                if (!activeConnections.contains(endpointId)) {
                    activeConnections.add(endpointId);
                }

                sendAvatar(endpointId);
                if (meshEnabled) mesh.onNeighborConnected(endpointId); // представиться: HELLO
                // Представляемся постоянным идентификатором — независимо от mesh
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                        sendMessage(endpointId, PEER_ID_PREFIX + myPeerId() + "|" + userNickname), 600);
                new Handler(Looper.getMainLooper()).postDelayed(NearbyManager.this::flushOutboxSafely, 1500); // отправить накопленное

                // Дополнительная отправка через 800мс для надёжности
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isConnectedTo(endpointId)) {
                        sendAvatar(endpointId);
                    }
                }, 800);

                if (connectionListener != null) connectionListener.onConnected(endpointId);
                if (connectionSuccessListener != null) connectionSuccessListener.onConnectionSuccess(endpointId);

            } else {
                Log.e(TAG, "Ошибка соединения: " + resolution.getStatus());
            }
        }

        @Override
        public void onDisconnected(String endpointId) {
            Log.d(TAG, "Соединение разорвано: " + endpointId);
            activeConnections.remove(endpointId);
            mesh.onNeighborDisconnected(endpointId);
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        private boolean expectingAvatar = false;   // флаг "следующий файл — это аватар"

        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                byte[] bytes = payload.asBytes();
                if (bytes == null || bytes.length == 0) return;

                // ===== АУДИО-ЧАНК ЗВОНКА =====
                if (bytes[0] == OfflineCallManager.AUDIO_MARKER) {
                    if (globalAudioChunkListener != null) {
                        globalAudioChunkListener.onAudioChunk(endpointId, bytes);
                    }
                    return;
                }

                // ===== ОБЫЧНЫЙ ТЕКСТ =====
                String text = new String(bytes);

                // ===== ПРЕДСТАВЛЕНИЕ СОБЕСЕДНИКА =====
                if (text.startsWith(PEER_ID_PREFIX)) {
                    String data = text.substring(PEER_ID_PREFIX.length());
                    int bar = data.indexOf('|');
                    String peerId = bar > 0 ? data.substring(0, bar) : data;
                    String peerName = bar > 0 ? data.substring(bar + 1) : null;
                    peerIdByEndpoint.put(endpointId, peerId);
                    if (peerName != null) endpointNames.put(endpointId, peerName);
                    Log.d(TAG, "Собеседник представился: " + peerName + " (" + peerId + ")");
                    if (peerIdentityListener != null) peerIdentityListener.onPeerIdentified(endpointId, peerId, peerName);
                    return;
                }

                // ===== ПОДТВЕРЖДЕНИЕ ДОСТАВКИ =====
                if (text.startsWith(ACK_PREFIX)) {
                    String ackId = text.substring(ACK_PREFIX.length());
                    Log.d(TAG, "Доставлено: " + ackId);
                    new OfflineChatStorage(context).updateStatus(endpointId, ackId, OfflineMessage.STATUS_DELIVERED);
                    if (statusListener != null) {
                        statusListener.onMessageStatusChanged(endpointId, ackId, OfflineMessage.STATUS_DELIVERED);
                    }
                    return;
                }

                // ===== MESH-ПАКЕТ =====
                // При выключенном mesh пакеты соседей просто отбрасываются — в чат не попадают
                if (text.startsWith(MeshRouter.PREFIX)) {
                    if (meshEnabled) mesh.onReceived(endpointId, text);
                    return;
                }
                Log.d(TAG, "Получено текстовое сообщение: " + text);

                if ("___AVATAR___".equals(text)) {
                    expectingAvatar = true;
                    Log.d(TAG, "Получена метка ___AVATAR___ → следующий файл будет аватаром");
                    return; // выходим, не передаём дальше
                }

                // Подтверждаем получение обычного сообщения (не служебной команды)
                if (!text.startsWith("___") && text.contains("|")) {
                    sendAck(endpointId, text.substring(0, text.indexOf('|')));
                }

                if (globalMessageListener != null) {
                    globalMessageListener.onMessageReceived(endpointId, text);
                }
            }
            else if (payload.getType() == Payload.Type.FILE) {
                Uri fileUri = payload.asFile().asUri();
                Log.d(TAG, "Получен файл от " + endpointId);

                if (expectingAvatar && avatarStorage != null) {
                    Log.d(TAG, "→ Это АВАТАР (по метке)");
                    avatarStorage.saveAvatar(endpointId, fileUri);

                    if (globalMessageListener != null) {
                        globalMessageListener.onAvatarReceived(endpointId, fileUri);
                    }
                    expectingAvatar = false; // сбрасываем флаг
                } else {
                    Log.d(TAG, "→ Это ОБЫЧНЫЙ ФАЙЛ");
                    if (globalMessageListener != null) {
                        globalMessageListener.onFileReceived(endpointId, fileUri);
                    }
                }
            }
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
            // Можно добавить прогресс позже
        }
    };

    private void flushOutboxSafely() {
        try { flushOutbox(); } catch (Exception e) { Log.e(TAG, "Очередь: ошибка отправки", e); }
    }

    public static String endpointName(String endpointId) { return endpointNames.get(endpointId); }

    public boolean isConnectedTo(String endpointId) {
        if (MeshNode.isVirtual(endpointId)) {
            return meshEnabled && mesh.isReachable(MeshNode.nodeIdOf(endpointId));
        }
        return activeConnections.contains(endpointId);
    }

    public static OnMessageReceivedListener getGlobalMessageListener() { return globalMessageListener; }

    // ====================== SETTERS ======================
    public void setUserNickname(String nickname) {
        this.userNickname = nickname;
        if (mesh != null) { mesh.setMyName(nickname); if (meshEnabled) mesh.announceSelf(); }
        Log.d(TAG, "Никнейм изменён на: " + nickname);
    }

    public void setUserAvatarUri(Uri avatarUri) {
        this.userAvatarUri = avatarUri;
        Log.d(TAG, "Аватар установлен: " + (avatarUri != null ? avatarUri.toString() : "null"));
    }

    public void setOnDeviceFoundListener(OnDeviceFoundListener listener) {
        this.deviceFoundListener = listener;
    }

    public void setOnConnectionListener(OnConnectionListener listener) {
        this.connectionListener = listener;
    }

    public void setOnIncomingConnectionListener(OnIncomingConnectionListener listener) {
        this.incomingConnectionListener = listener;
    }

    public void setOnConnectionSuccessListener(OnConnectionSuccessListener listener) {
        this.connectionSuccessListener = listener;
    }

    public void setAvatarStorage(OfflineAvatarStorage storage) {
        this.avatarStorage = storage;
        Log.d(TAG, "Хранилище аватаров подключено");
    }

    // ====================== ИНТЕРФЕЙСЫ ======================
    public interface OnDeviceFoundListener {
        void onDeviceFound(String endpointId, String name);
    }

    public interface OnConnectionListener {
        void onConnected(String endpointId);
    }

    public interface OnIncomingConnectionListener {
        void onIncomingConnection(String endpointId, String name);
    }

    public interface OnConnectionSuccessListener {
        void onConnectionSuccess(String endpointId);
    }

    public interface OnMessageReceivedListener {
        void onMessageReceived(String endpointId, String text);
        void onFileReceived(String endpointId, Uri fileUri);
        void onAvatarReceived(String endpointId, Uri avatarUri);
    }

    public interface OnAudioChunkListener {
        void onAudioChunk(String endpointId, byte[] data);
    }

    public interface OnPeerIdentityListener {
        void onPeerIdentified(String endpointId, String peerId, String name);
    }

    public interface OnMessageStatusListener {
        void onMessageStatusChanged(String endpointId, String messageId, int status);
    }

    public interface OnMeshNodesListener {
        void onMeshNodeDiscovered(MeshNode node);
        void onMeshNodeLost(String nodeId);
    }

}
