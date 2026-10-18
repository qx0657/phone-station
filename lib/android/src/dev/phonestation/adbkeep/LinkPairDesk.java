package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkAuth;
import dev.phonestation.adbkeep.link.LinkSafety;
import dev.phonestation.adbkeep.link.LocalLink;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 手机上的待确认配对和已授权 Mac。允许之后才写入授权文件。 */
final class LinkPairDesk {
    static final class Pending {
        final byte[] spki;
        final String name;
        final String code;

        Pending(byte[] spki, String name, String code) {
            this.spki = spki;
            this.name = name;
            this.code = code;
        }
    }

    private final byte[] phoneSpki;
    private final LinkGrants grants;
    private final Consumer<byte[]> onRevoke;
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    LinkPairDesk(byte[] phoneSpki, LinkGrants grants, Consumer<byte[]> onRevoke) {
        if (phoneSpki == null || phoneSpki.length == 0 || grants == null) {
            throw new IllegalArgumentException("pair");
        }
        this.phoneSpki = phoneSpki.clone();
        this.grants = grants;
        this.onRevoke = onRevoke;
    }

    String accept(byte[] body) throws IOException {
        if (body == null || body.length == 0 || body.length > 2048) {
            throw new IllegalArgumentException("pair");
        }
        String text;
        try {
            text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("pair");
        }
        Json object = Json.parse(text);
        java.util.Set<String> keys = object.isObject() ? object.keys() : java.util.Set.of();
        if (!keys.equals(java.util.Set.of("spki", "nonce", "sig", "name"))
                && !keys.equals(java.util.Set.of("spki", "nonce", "sig"))) {
            throw new IllegalArgumentException("pair");
        }
        byte[] spki = Base64.getDecoder().decode(object.get("spki").string());
        byte[] nonce = hex(object.get("nonce").string(), 32);
        byte[] sig = hex(object.get("sig").string(), 0);
        String name = object.has("name") ? object.get("name").string() : "Mac";
        if (!nameOk(name) || spki.length == 0 || spki.length > 400 || sig.length == 0 || sig.length > 120) {
            throw new IllegalArgumentException("pair");
        }
        if (!LinkAuth.verify(spki, LinkAuth.localPairMessage(spki, nonce), sig)) {
            throw new IllegalArgumentException("pair");
        }
        String code = LinkSafety.local(phoneSpki, spki);
        boolean granted = false;
        for (LocalLink.Device existing : grants.devices()) {
            if (java.util.Arrays.equals(existing.spki, spki)) {
                granted = true;
            }
        }
        if (!granted) {
            pending.put(Base64.getEncoder().encodeToString(spki), new Pending(spki.clone(), name, code));
        }
        return Json.obj()
                .put("granted", granted)
                .put("code", code)
                .put("spki", Base64.getEncoder().encodeToString(phoneSpki))
                .emit();
    }

    List<Pending> pending() {
        return new ArrayList<>(pending.values());
    }

    List<LocalLink.Device> granted() {
        return grants.devices();
    }

    void allow(byte[] spki) throws IOException {
        grants.grant(spki);
        pending.remove(Base64.getEncoder().encodeToString(spki));
        LinkLiveEvents.state("grants", macHex(spki), LinkSnapshots.grants(true));
    }

    void deny(byte[] spki) {
        pending.remove(Base64.getEncoder().encodeToString(spki));
    }

    void revoke(byte[] spki) throws IOException {
        grants.revoke(spki);
        pending.remove(Base64.getEncoder().encodeToString(spki));
        LinkLiveEvents.state("grants", macHex(spki), LinkSnapshots.grants(false));
        LinkLiveEvents.flush();
        if (onRevoke != null) {
            onRevoke.accept(LinkAuth.deviceId(spki));
        }
    }

    byte[] phoneSpki() {
        return phoneSpki.clone();
    }

    private static String macHex(byte[] spki) {
        byte[] id = LinkAuth.deviceId(spki);
        StringBuilder out = new StringBuilder(id.length * 2);
        for (byte value : id) {
            out.append(Character.forDigit((value >> 4) & 0xf, 16));
            out.append(Character.forDigit(value & 0xf, 16));
        }
        return out.toString();
    }

    private static boolean nameOk(String name) {
        if (name == null || name.isEmpty() || name.length() > 40) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char value = name.charAt(i);
            if (value < 0x20 || value == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hex(String value, int exact) {
        if (value == null || (exact > 0 && value.length() != exact * 2) || !value.matches("[0-9a-fA-F]+") || value.length() % 2 != 0) {
            throw new IllegalArgumentException("pair");
        }
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
