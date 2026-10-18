package dev.phonestation.adbkeep.link;

import java.util.Base64;

/** 二维码和本地链路携带的邀请文本。中继看不到密钥。12 位码只含编号和密钥各自的前 3 字节。 */
public final class LinkInvite {
    public final String endpoint;
    public final String pin;
    public final String id;
    public final String key;
    public final String spki;
    public final String kind;

    private LinkInvite(String endpoint, String pin, String id, String key, String spki, String kind) {
        this.endpoint = endpoint;
        this.pin = pin;
        this.id = id;
        this.key = key;
        this.spki = spki;
        this.kind = kind;
    }

    public static String text(String endpoint, String pin, String id, String key, String spki, String kind) {
        LinkInvite item = new LinkInvite(endpoint, pin, id, key, spki, kind);
        item.check();
        return "ps-invite-v1\n" + endpoint + "\n" + pin + "\n" + id + "\n" + key + "\n" + spki + "\n" + kind + "\n";
    }

    public static LinkInvite parse(String text) {
        if (text == null || text.length() > 1200 || !text.endsWith("\n")) {
            throw new IllegalArgumentException("invite");
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length != 7 || !"ps-invite-v1".equals(lines[0])) {
            throw new IllegalArgumentException("invite");
        }
        LinkInvite item = new LinkInvite(lines[1], lines[2], lines[3], lines[4], lines[5], lines[6]);
        item.check();
        return item;
    }

    public static String manual(byte[] id, byte[] key) {
        if (id.length != 16 || key.length != 16) {
            throw new IllegalArgumentException("length");
        }
        return hex(id, 3) + hex(key, 3);
    }

    public static byte[][] manualParts(String code) {
        if (code == null || !code.matches("[0-9a-f]{12}")) {
            throw new IllegalArgumentException("code");
        }
        byte[] raw = new byte[6];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(code.substring(i * 2, i * 2 + 2), 16);
        }
        return new byte[][] {new byte[] {raw[0], raw[1], raw[2]}, new byte[] {raw[3], raw[4], raw[5]}};
    }

    private void check() {
        if (!endpoint.startsWith("https://") || endpoint.length() < "https://a".length() || endpoint.length() > 200
                || endpoint.chars().anyMatch(ch -> ch <= ' ')) {
            throw new IllegalArgumentException("invite");
        }
        hexExact(pin, 32);
        hexExact(id, 16);
        hexExact(key, 16);
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(spki);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invite");
        }
        if (raw.length == 0 || raw.length > 400 || !spki.equals(Base64.getEncoder().encodeToString(raw))) {
            throw new IllegalArgumentException("invite");
        }
        if (!"phone".equals(kind) && !"mac".equals(kind)) {
            throw new IllegalArgumentException("invite");
        }
    }

    private static void hexExact(String value, int bytes) {
        if (value == null || value.length() != bytes * 2 || !value.matches("[0-9a-f]{" + (bytes * 2) + "}")) {
            throw new IllegalArgumentException("invite");
        }
    }

    private static String hex(byte[] value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append(String.format("%02x", value[i] & 0xff));
        }
        return out.toString();
    }

    public String text() {
        return text(endpoint, pin, id, key, spki, kind);
    }
}
