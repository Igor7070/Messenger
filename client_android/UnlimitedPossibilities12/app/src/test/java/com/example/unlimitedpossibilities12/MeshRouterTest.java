package com.example.unlimitedpossibilities12;

import static org.junit.Assert.*;

import com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshNode;
import com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshPacket;
import com.example.unlimitedpossibilities12.messenger.offline.mesh.MeshRouter;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Проверка mesh-маршрутизации на виртуальной сети: несколько узлов соединены
 * «проводами» в памяти, радио не нужно. Это ровно тот сценарий, который на
 * настоящих телефонах требует третьего аппарата: A -> B -> C.
 */
public class MeshRouterTest {

    /** Виртуальная сеть: узлы, связи и очередь доставки. */
    static class Net {
        final Map<String, Node> nodes = new HashMap<>();
        final Deque<Runnable> queue = new ArrayDeque<>();
        int packetsOnWire = 0;

        Node add(String id) { Node n = new Node(this, id); nodes.put(id, n); return n; }

        /** Соединить два узла: у каждого появляется endpointId соседа. */
        void link(Node a, Node b) {
            String epAB = "ep_" + a.id + "_" + b.id, epBA = "ep_" + b.id + "_" + a.id;
            a.links.put(epAB, b); b.links.put(epBA, a);
            a.router.onNeighborConnected(epAB);
            b.router.onNeighborConnected(epBA);
            pump();
        }

        void unlink(Node a, Node b) {
            String epAB = "ep_" + a.id + "_" + b.id, epBA = "ep_" + b.id + "_" + a.id;
            a.links.remove(epAB); b.links.remove(epBA);
            a.router.onNeighborDisconnected(epAB);
            b.router.onNeighborDisconnected(epBA);
            pump();
        }

        void pump() { while (!queue.isEmpty()) queue.poll().run(); }
    }

    static class Node implements MeshRouter.Transport, MeshRouter.Listener, MeshRouter.MeshCrypto {
        final Net net; final String id; final MeshRouter router;
        final Map<String, Node> links = new HashMap<>();    // мой endpointId соседа -> сосед
        final List<String> received = new ArrayList<>();    // "from:body"
        final List<String> discovered = new ArrayList<>();

        Node(Net net, String id) { this.net = net; this.id = id; this.router = new MeshRouter(id, "name-" + id, this, this, this); }

        @Override public void send(String endpointId, String payload) {
            Node peer = links.get(endpointId);
            if (peer == null) return; // связь порвана
            net.packetsOnWire++;
            // endpointId, под которым сосед знает нас
            String reverse = "ep_" + peer.id + "_" + id;
            net.queue.add(() -> peer.router.onReceived(reverse, payload));
        }
        @Override public void onMeshMessage(String fromNodeId, String fromName, String body) { received.add(fromNodeId + ":" + body); }
        @Override public void onNodeDiscovered(MeshNode node) { if (!discovered.contains(node.nodeId)) discovered.add(node.nodeId); }
        @Override public void onNodeLost(String nodeId) {}
        // «шифрование» в тесте: помечаем получателя, чтобы проверить адресность
        @Override public String myPublicKey() { return "key-" + id; }
        @Override public String encryptFor(String recipientPublicKey, String plain) { return recipientPublicKey + "|" + plain; }
        @Override public String decrypt(String senderPublicKey, String cipher) {
            String[] p = cipher.split("\\|", 2);
            return p.length == 2 && p[0].equals(myPublicKey()) ? p[1] : null;
        }
    }

    @Test
    public void helloExchangeMakesNeighborsKnown() {
        Net net = new Net();
        Node a = net.add("A"), b = net.add("B");
        net.link(a, b);
        assertTrue(a.discovered.contains("B"));
        assertTrue(b.discovered.contains("A"));
        assertTrue(a.router.isMeshNeighbor("ep_A_B"));
    }

    @Test
    public void messageIsRelayedThroughMiddleNode() {
        Net net = new Net();
        Node a = net.add("A"), b = net.add("B"), c = net.add("C");
        net.link(a, b);
        net.link(b, c);
        // C узнал об A через объявление, переданное B
        assertTrue("C must learn about A via B", c.discovered.contains("A"));
        assertTrue("A must learn about C via B", a.discovered.contains("C"));

        assertTrue(a.router.sendTo("C", "42|hello through B"));
        net.pump();

        assertEquals(1, c.received.size());
        assertEquals("A:42|hello through B", c.received.get(0));
        assertTrue("B must not read the message", b.received.isEmpty());
    }

    @Test
    public void replyUsesLearnedRouteWithoutFlooding() {
        Net net = new Net();
        Node a = net.add("A"), b = net.add("B"), c = net.add("C"), d = net.add("D");
        net.link(a, b); net.link(b, c); net.link(b, d);
        a.router.sendTo("C", "1|hi"); net.pump();
        int before = net.packetsOnWire;
        c.router.sendTo("A", "2|hi back"); net.pump();
        // Ответ идёт C->B->A: ровно 2 пакета, D ничего не получает
        assertEquals(2, net.packetsOnWire - before);
        assertEquals("C:2|hi back", a.received.get(0));
    }

    @Test
    public void loopsDoNotEchoForever() {
        Net net = new Net();
        Node a = net.add("A"), b = net.add("B"), c = net.add("C");
        net.link(a, b); net.link(b, c); net.link(c, a); // треугольник
        a.router.sendTo("C", "3|triangle"); net.pump();
        assertEquals(1, c.received.size()); // доставлено один раз, не дублируется
        assertTrue(net.packetsOnWire < 50);  // и не гуляет по кругу
    }

    @Test
    public void ttlStopsAtChainLimit() {
        Net net = new Net();
        List<Node> chain = new ArrayList<>();
        for (int i = 0; i < 9; i++) chain.add(net.add("N" + i));
        for (int i = 0; i < 8; i++) net.link(chain.get(i), chain.get(i + 1));
        Node first = chain.get(0);
        // Объявления доходят ровно на DEFAULT_TTL прыжков: N6 (6 прыжков) знает N0, N7 — уже нет
        assertTrue(chain.get(MeshRouter.DEFAULT_TTL).discovered.contains("N0"));
        assertFalse(chain.get(MeshRouter.DEFAULT_TTL + 1).discovered.contains("N0"));
        assertFalse(chain.get(8).discovered.contains("N0"));
        assertFalse("unknown node has no key -> cannot encrypt -> false", first.router.sendTo("N8", "x"));
    }

    @Test
    public void unknownRecipientIsRejected() {
        Net net = new Net();
        Node a = net.add("A"), b = net.add("B");
        net.link(a, b);
        assertFalse(a.router.sendTo("Z", "1|nobody"));
    }

    @Test
    public void linkLossInvalidatesRouteAndFallsBackToFlood() {
        Net net = new Net();
        Node a = net.add("A"), b = net.add("B"), c = net.add("C"), d = net.add("D");
        net.link(a, b); net.link(b, c); net.link(a, d); net.link(d, c); // два пути A->C
        a.router.sendTo("C", "1|via b"); net.pump();
        net.unlink(a, b);
        assertTrue(a.router.sendTo("C", "2|via d")); net.pump();
        assertEquals("A:2|via d", c.received.get(1));
    }

    @Test
    public void plainStringsAreNotMeshPackets() {
        Net net = new Net();
        Node a = net.add("A");
        assertFalse(a.router.onReceived("ep", "123|plain chat message"));
        assertFalse(a.router.onReceived("ep", "___FILE___id|photo.jpg"));
        assertTrue(a.router.onReceived("ep", MeshRouter.PREFIX + "{broken"));
    }

    @Test
    public void packetRoundTrip() {
        MeshPacket p = MeshPacket.data("id1", "A", "Alice", "C", 5, "cipher==");
        MeshPacket q = MeshPacket.decode(p.encode());
        assertNotNull(q);
        assertEquals("A", q.src); assertEquals("C", q.dst); assertEquals(5, q.ttl); assertEquals("cipher==", q.body);
        assertEquals("Alice", q.srcName);
    }
}
