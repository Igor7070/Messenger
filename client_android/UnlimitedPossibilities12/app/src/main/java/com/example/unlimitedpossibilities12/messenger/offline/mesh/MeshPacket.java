package com.example.unlimitedpossibilities12.messenger.offline.mesh;

/**
 * Конверт mesh-пакета. Строка: {@link MeshRouter#PREFIX} + поля через U+001F.
 *
 * Типы: HELLO (представление прямому соседу, не пересылается), ANNOUNCE
 * (объявление узла всей сети, пересылается с убыванием ttl), DATA (адресное
 * сообщение, тело зашифровано для получателя).
 *
 * Формат — собственный текстовый, чтобы класс работал и в JVM-тестах без Android.
 */
public final class MeshPacket {
    public static final String T_HELLO = "hello";
    public static final String T_ANNOUNCE = "announce";
    public static final String T_DATA = "data";

    public final String type;
    public final String id;       // уникальный id пакета (для дедупликации); у HELLO пустой
    public final String src;      // nodeId автора
    public final String srcName;  // отображаемое имя автора
    public final String srcKey;   // публичный ключ автора (base64)
    public final String dst;      // nodeId получателя (DATA) или null
    public final int ttl;
    public final String body;     // шифртекст (DATA) или null
    public final int version;

    private MeshPacket(String type, String id, String src, String srcName, String srcKey,
                       String dst, int ttl, String body, int version) {
        this.type = type; this.id = id; this.src = src; this.srcName = srcName; this.srcKey = srcKey;
        this.dst = dst; this.ttl = ttl; this.body = body; this.version = version;
    }

    public static MeshPacket hello(String src, String name, String key, int version) {
        return new MeshPacket(T_HELLO, "", src, name, key, null, 1, null, version);
    }

    public static MeshPacket announce(String id, String src, String name, String key, int ttl) {
        return new MeshPacket(T_ANNOUNCE, id, src, name, key, null, ttl, null, MeshRouter.PROTOCOL_VERSION);
    }

    public static MeshPacket data(String id, String src, String name, String dst, int ttl, String cipher) {
        return new MeshPacket(T_DATA, id, src, name, null, dst, ttl, cipher, MeshRouter.PROTOCOL_VERSION);
    }

    public boolean isHello() { return T_HELLO.equals(type); }
    public boolean isAnnounce() { return T_ANNOUNCE.equals(type); }

    public MeshPacket withTtl(int newTtl) {
        return new MeshPacket(type, id, src, srcName, srcKey, dst, newTtl, body, version);
    }

    /**
     * Компактный формат без JSON-библиотеки (org.json в JVM-тестах — заглушка):
     * поля через U+001F, порядок фиксирован. В имени этот символ заменяется пробелом.
     */
    private static final char SEP = (char) 0x1F;

    public String encode() {
        StringBuilder sb = new StringBuilder(MeshRouter.PREFIX);
        sb.append(type).append(SEP)
          .append(nz(id)).append(SEP)
          .append(nz(src)).append(SEP)
          .append(nz(srcName).replace(SEP, ' ')).append(SEP)
          .append(nz(srcKey)).append(SEP)
          .append(nz(dst)).append(SEP)
          .append(ttl).append(SEP)
          .append(version).append(SEP)
          .append(nz(body));
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String orNull(String s) { return s == null || s.isEmpty() ? null : s; }

    /** null, если строка не разбирается. */
    public static MeshPacket decode(String raw) {
        if (raw == null || !raw.startsWith(MeshRouter.PREFIX)) return null;
        try {
            String[] f = raw.substring(MeshRouter.PREFIX.length()).split(String.valueOf(SEP), -1);
            if (f.length < 9) return null;
            String type = orNull(f[0]), src = orNull(f[2]);
            if (type == null || src == null) return null;
            return new MeshPacket(type, nz(f[1]), src, orNull(f[3]), orNull(f[4]), orNull(f[5]),
                    Integer.parseInt(f[6]), orNull(f[8]), Integer.parseInt(f[7]));
        } catch (Exception e) {
            return null;
        }
    }
}
