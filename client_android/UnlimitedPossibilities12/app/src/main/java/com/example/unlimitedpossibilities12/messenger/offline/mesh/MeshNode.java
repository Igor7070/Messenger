package com.example.unlimitedpossibilities12.messenger.offline.mesh;

/** Известный узел mesh-сети. */
public final class MeshNode {
    public final String nodeId;
    public final String name;
    public final String publicKey; // base64, может быть null у старых/битых объявлений
    public final boolean direct;   // прямой сосед (есть Nearby-соединение)
    public final int hops;         // сколько прыжков до узла (1 = прямой сосед)

    public MeshNode(String nodeId, String name, String publicKey, boolean direct) {
        this(nodeId, name, publicKey, direct, direct ? 1 : MeshRouter.DEFAULT_TTL);
    }

    public MeshNode(String nodeId, String name, String publicKey, boolean direct, int hops) {
        this.nodeId = nodeId;
        this.name = name != null ? name : nodeId;
        this.publicKey = publicKey;
        this.direct = direct;
        this.hops = hops;
    }

    /** Виртуальный endpointId, под которым узел живёт в экранах чата и хранилище. */
    public String virtualEndpointId() { return MeshRouter.VIRTUAL_PREFIX + nodeId; }

    public static boolean isVirtual(String endpointId) {
        return endpointId != null && endpointId.startsWith(MeshRouter.VIRTUAL_PREFIX);
    }

    public static String nodeIdOf(String virtualEndpointId) {
        return isVirtual(virtualEndpointId) ? virtualEndpointId.substring(MeshRouter.VIRTUAL_PREFIX.length()) : null;
    }
}
