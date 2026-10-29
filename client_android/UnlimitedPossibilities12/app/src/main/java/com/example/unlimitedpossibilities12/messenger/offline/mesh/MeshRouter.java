package com.example.unlimitedpossibilities12.messenger.offline.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Маршрутизация сообщений через соседние телефоны (mesh поверх Nearby).
 *
 * Чистая логика без Android и без радио — транспорт и шифрование подставляются
 * через интерфейсы, поэтому класс целиком проверяется юнит-тестами на JVM.
 *
 * Принцип: у каждого узла стабильный nodeId (не зависит от endpointId Nearby,
 * который меняется от сессии к сессии). Пакет несёт src/dst/ttl. Если dst —
 * прямой сосед, пакет уходит ему; если известен следующий прыжок — ему; иначе
 * рассылается всем соседям (flood). Каждый узел помнит id уже виденных пакетов
 * и не пересылает их повторно — так гасятся петли. Из каждого пришедшего пакета
 * узел запоминает «src достижим через того соседа, от кого пакет пришёл».
 *
 * Тело адресного пакета шифруется для получателя ({@link MeshCrypto}), поэтому
 * промежуточные телефоны видят только маршрутные поля.
 */
public final class MeshRouter {

    /** Транспорт: отправка байтов/строки конкретному соседу по его endpointId. */
    public interface Transport {
        void send(String endpointId, String payload);
    }

    /** Получатель доставленных нам сообщений и событий о новых узлах. */
    public interface Listener {
        /** Сообщение адресовано нам. fromNodeId — автор, body — исходная строка чата. */
        void onMeshMessage(String fromNodeId, String fromName, String body);
        /** Узел стал известен (напрямую или через соседей). */
        void onNodeDiscovered(MeshNode node);
        /** Узел (прямой сосед) отключился. */
        void onNodeLost(String nodeId);
    }

    /** Шифрование тела пакета «для узла»; в тестах подставляется тождественное. */
    public interface MeshCrypto {
        String myPublicKey();
        /** Возвращает шифртекст (строка) или null при ошибке. */
        String encryptFor(String recipientPublicKey, String plain);
        /** Возвращает открытый текст или null, если не наш/подделан. */
        String decrypt(String senderPublicKey, String cipher);
    }

    public static final int DEFAULT_TTL = 6;
    public static final int PROTOCOL_VERSION = 1;
    /** Префикс всех mesh-пакетов в строковом канале Nearby. */
    public static final String PREFIX = "___MESH___";
    private static final int SEEN_LIMIT = 1000;
    /** Виртуальный endpointId для узлов, к которым нет прямого соединения. */
    public static final String VIRTUAL_PREFIX = "mesh:";

    private final String myNodeId;
    private String myName;
    private final Transport transport;
    private final Listener listener;
    private final MeshCrypto crypto;

    /** endpointId прямого соседа -> его nodeId (заполняется по HELLO). */
    private final Map<String, String> neighborByEndpoint = new HashMap<>();
    /** nodeId -> описание узла (имя, ключ), всё, что знаем о сети. */
    private final Map<String, MeshNode> nodes = new HashMap<>();
    /** nodeId -> endpointId соседа, через которого этот узел достижим. */
    private final Map<String, String> nextHop = new HashMap<>();
    /** Уже виденные id пакетов (с ограничением размера). */
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > SEEN_LIMIT; }
    });

    public MeshRouter(String myNodeId, String myName, Transport transport, Listener listener, MeshCrypto crypto) {
        this.myNodeId = myNodeId;
        this.myName = myName;
        this.transport = transport;
        this.listener = listener;
        this.crypto = crypto;
    }

    public String myNodeId() { return myNodeId; }
    public void setMyName(String name) { this.myName = name; }

    // ---------------- соседи ----------------

    /** Вызывать при установлении прямого соединения: представляемся соседу. */
    public void onNeighborConnected(String endpointId) {
        transport.send(endpointId, MeshPacket.hello(myNodeId, myName, crypto.myPublicKey(), PROTOCOL_VERSION).encode());
    }

    /** Вызывать при разрыве прямого соединения. */
    public void onNeighborDisconnected(String endpointId) {
        String nodeId = neighborByEndpoint.remove(endpointId);
        // Маршруты через этот endpoint больше не действительны
        nextHop.values().removeIf(endpointId::equals);
        if (nodeId != null) listener.onNodeLost(nodeId);
    }

    /** Все прямые mesh-соседи (endpointId). */
    public Set<String> neighborEndpoints() { return new LinkedHashSet<>(neighborByEndpoint.keySet()); }

    public boolean isMeshNeighbor(String endpointId) { return neighborByEndpoint.containsKey(endpointId); }

    /** Известные узлы кроме нас. */
    public List<MeshNode> knownNodes() {
        List<MeshNode> out = new ArrayList<>();
        for (MeshNode n : nodes.values()) if (!n.nodeId.equals(myNodeId)) out.add(n);
        return out;
    }

    public MeshNode node(String nodeId) { return nodes.get(nodeId); }

    /** Достижим ли узел (есть сосед, через которого его можно послать). */
    public boolean isReachable(String nodeId) {
        if (directEndpointOf(nodeId) != null) return true;
        MeshNode n = nodes.get(nodeId);
        return n != null && n.hops <= DEFAULT_TTL && !neighborByEndpoint.isEmpty();
    }

    private String directEndpointOf(String nodeId) {
        for (Map.Entry<String, String> e : neighborByEndpoint.entrySet()) {
            if (e.getValue().equals(nodeId)) return e.getKey();
        }
        return null;
    }

    // ---------------- отправка ----------------

    /**
     * Отправить строку чата узлу. Возвращает false, если узел неизвестен
     * (нет ключа для шифрования) или нет ни одного соседа.
     */
    public boolean sendTo(String dstNodeId, String body) {
        MeshNode dst = nodes.get(dstNodeId);
        if (dst == null || dst.publicKey == null) return false;
        String cipher = crypto.encryptFor(dst.publicKey, body);
        if (cipher == null) return false;
        MeshPacket p = MeshPacket.data(newPacketId(), myNodeId, myName, dstNodeId, DEFAULT_TTL, cipher);
        seen.add(p.id); // своё эхо не пересылать
        return route(p, null);
    }

    private boolean route(MeshPacket p, String exceptEndpoint) {
        String direct = directEndpointOf(p.dst);
        if (direct != null && !direct.equals(exceptEndpoint)) { transport.send(direct, p.encode()); return true; }
        String hop = nextHop.get(p.dst);
        if (hop != null && neighborByEndpoint.containsKey(hop) && !hop.equals(exceptEndpoint)) {
            transport.send(hop, p.encode());
            return true;
        }
        return flood(p, exceptEndpoint);
    }

    private boolean flood(MeshPacket p, String exceptEndpoint) {
        boolean sent = false;
        for (String ep : neighborByEndpoint.keySet()) {
            if (ep.equals(exceptEndpoint)) continue;
            transport.send(ep, p.encode());
            sent = true;
        }
        return sent;
    }

    // ---------------- приём ----------------

    /**
     * Обработать строку, пришедшую от соседа. Возвращает true, если это был
     * mesh-пакет (и дальше его передавать не нужно), false — обычная строка чата.
     */
    public boolean onReceived(String fromEndpoint, String raw) {
        if (raw == null || !raw.startsWith(PREFIX)) return false;
        MeshPacket p = MeshPacket.decode(raw);
        if (p == null) return true; // битый пакет — молча выбрасываем

        if (p.isHello()) {
            handleHello(fromEndpoint, p);
            return true;
        }

        if (!seen.add(p.id)) return true; // уже видели — петля/дубликат

        // Учимся маршруту к автору (если он не прямой сосед)
        if (!p.src.equals(myNodeId) && directEndpointOf(p.src) == null) {
            nextHop.put(p.src, fromEndpoint);
        }

        if (p.isAnnounce()) {
            // Объявление узла: запоминаем (или обновляем, если путь короче) и передаём дальше
            int hops = DEFAULT_TTL - p.ttl + 1;
            MeshNode n = nodes.get(p.src);
            if (n == null || !n.direct && (hops < n.hops || (n.publicKey == null && p.srcKey != null))) {
                n = new MeshNode(p.src, p.srcName, p.srcKey, false, hops);
                nodes.put(p.src, n);
                listener.onNodeDiscovered(n);
            }
            if (p.ttl > 1) flood(p.withTtl(p.ttl - 1), fromEndpoint);
            return true;
        }

        if (myNodeId.equals(p.dst)) {
            MeshNode src = nodes.get(p.src);
            String plain = crypto.decrypt(src != null ? src.publicKey : p.srcKey, p.body);
            if (plain != null) listener.onMeshMessage(p.src, p.srcName, plain);
            return true;
        }

        // Не нам — пересылаем, пока жив TTL
        if (p.ttl > 1) route(p.withTtl(p.ttl - 1), fromEndpoint);
        return true;
    }

    private void handleHello(String fromEndpoint, MeshPacket p) {
        boolean isNew = !neighborByEndpoint.containsKey(fromEndpoint);
        neighborByEndpoint.put(fromEndpoint, p.src);
        nextHop.remove(p.src); // прямой сосед — маршрут не нужен
        MeshNode n = new MeshNode(p.src, p.srcName, p.srcKey, true);
        nodes.put(p.src, n);
        listener.onNodeDiscovered(n);

        if (isNew) {
            // Отвечаем своим HELLO (сосед мог подключиться первым), затем
            // объявляем сеть друг другу: наши известные узлы — ему, себя — всем.
            transport.send(fromEndpoint, MeshPacket.hello(myNodeId, myName, crypto.myPublicKey(), PROTOCOL_VERSION).encode());
            announceSelf();
            for (MeshNode known : new ArrayList<>(nodes.values())) {
                if (known.nodeId.equals(myNodeId) || known.nodeId.equals(p.src)) continue;
                int ttl = DEFAULT_TTL - known.hops; // у соседа узел окажется на прыжок дальше
                if (ttl < 1) continue;              // за пределами досягаемости — не объявляем
                MeshPacket a = MeshPacket.announce(newPacketId(), known.nodeId, known.name, known.publicKey, ttl);
                seen.add(a.id);
                transport.send(fromEndpoint, a.encode());
            }
        }
    }

    /** Объявить себя всей сети (при подключении соседа и при смене имени). */
    public void announceSelf() {
        MeshPacket a = MeshPacket.announce(newPacketId(), myNodeId, myName, crypto.myPublicKey(), DEFAULT_TTL);
        seen.add(a.id);
        flood(a, null);
    }

    private static String newPacketId() {
        return UUID.randomUUID().toString().substring(0, 13);
    }
}
