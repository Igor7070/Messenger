package com.example.unlimitedpossibilities12.messenger.offline.mesh;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.unlimitedpossibilities12.messenger.SecretText;

import java.util.UUID;

/**
 * ТЕСТОВЫЙ узел-«призрак» для проверки пересылки без третьего телефона.
 *
 * Живёт в памяти этого телефона и подключён к его маршрутизатору «проводом»,
 * как обычный сосед — только без радио. Для второго телефона неотличим от
 * третьего аппарата за спиной у первого: виден как «… · via mesh (2 hops)»,
 * принимает адресованные ему пакеты (расшифровывает своим ключом) и отвечает
 * эхом через хозяина. Включается тумблером в диалоге mesh; по умолчанию
 * выключен и не создаётся вовсе. Удалить после проверки на настоящем третьем
 * телефоне.
 */
public final class MeshTestNode {
    private static final String TAG = "MeshTestNode";
    /** endpointId, под которым призрак известен маршрутизатору хозяина. */
    public static final String GHOST_ENDPOINT = "ghost:relay-test";
    private static final String HOST_ENDPOINT = "host";

    private final MeshRouter host;
    private final MeshRouter ghost;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public MeshTestNode(Context ctx, MeshRouter host) {
        this.host = host;
        MeshSodiumCrypto crypto = new MeshSodiumCrypto(ctx.getApplicationContext(), "offline_mesh_test");
        this.ghost = new MeshRouter(crypto.nodeId(), "Relay-test",
                // призрак -> хозяин: доставляем в маршрутизатор хозяина как от соседа GHOST_ENDPOINT
                (endpointId, payload) -> handler.post(() -> host.onReceived(GHOST_ENDPOINT, payload)),
                new MeshRouter.Listener() {
                    @Override public void onMeshMessage(String fromNodeId, String fromName, String body) {
                        // Пришло через хозяина и расшифровано ключом призрака — отвечаем эхом.
                        // Служебное (подтверждения доставки, правки, удаления) не эхоим:
                        // иначе эхо на подтверждение порождает новое подтверждение — и так без конца.
                        String text = body;
                        int bar = body.indexOf('|');
                        if (bar > 0) text = body.substring(bar + 1);
                        if (body.startsWith("___") || text.startsWith("___")) {
                            Log.d(TAG, "GHOST ignores service message: " + body.substring(0, Math.min(24, body.length())));
                            return;
                        }
                        String shown = SecretText.parse(text).text;
                        Log.d(TAG, "GHOST got from " + fromName + ": " + shown + " -> echo");
                        String reply = UUID.randomUUID() + "|Relay OK, " + fromName + ": \"" + shown
                                + "\" reached me through " + host.myNodeId().substring(0, 6) + "…";
                        handler.postDelayed(() -> ghost.sendTo(fromNodeId, reply), 300);
                    }
                    @Override public void onNodeDiscovered(MeshNode node) {
                        Log.d(TAG, "GHOST knows node " + node.name + " hops=" + node.hops);
                    }
                    @Override public void onNodeLost(String nodeId) {}
                }, crypto);
    }

    /** Соединить призрака с хозяином: оба представляются друг другу как соседи. */
    public void start() {
        Log.d(TAG, "GHOST start: nodeId=" + ghost.myNodeId());
        host.onNeighborConnected(GHOST_ENDPOINT);
        ghost.onNeighborConnected(HOST_ENDPOINT);
    }

    public void stop() {
        host.onNeighborDisconnected(GHOST_ENDPOINT);
        Log.d(TAG, "GHOST stopped");
    }

    /** Хозяин -> призрак: то, что маршрутизатор хозяина «отправил» на GHOST_ENDPOINT. */
    public void receiveFromHost(String payload) {
        handler.post(() -> ghost.onReceived(HOST_ENDPOINT, payload));
    }
}
