package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkAuth;
import dev.phonestation.adbkeep.link.LinkFrame;
import dev.phonestation.adbkeep.link.LinkSafety;
import dev.phonestation.adbkeep.link.LinkSeal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/** Go 与 Java 读同一份链路向量。 */
final class LinkVectorTest {
    public static void main(String[] args) throws Exception {
        Json file = Json.parse(Files.readString(vectors()));
        if (file.get("mainPayload").longValue() != LinkFrame.MAIN_PAYLOAD
                || file.get("mediaPayload").longValue() != LinkFrame.MEDIA_PAYLOAD) {
            throw new AssertionError("payload limits");
        }
        for (Json item : file.get("frames").array()) {
            byte[] raw = hex(item.get("hex").string());
            int limit = (int) item.get("limit").longValue();
            if (item.has("reject")) {
                try {
                    LinkFrame.decode(raw, limit);
                    throw new AssertionError(item.get("name").string());
                } catch (IllegalArgumentException error) {
                    if (!item.get("reject").string().equals(error.getMessage())) {
                        throw new AssertionError(item.get("name").string());
                    }
                }
                continue;
            }
            LinkFrame frame = LinkFrame.decode(raw, limit);
            if (frame.kind != number(item, "kind")
                    || frame.flags != number(item, "flags")
                    || !Arrays.equals(frame.peer, hex(text(item, "peer")))
                    || frame.channel != number(item, "channel")
                    || frame.seq != number(item, "seq")
                    || !Arrays.equals(frame.payload, hex(text(item, "payload")))) {
                throw new AssertionError(item.get("name").string());
            }
            byte[] again = LinkFrame.encode(frame.kind, frame.flags, frame.peer, frame.channel, frame.seq, frame.payload, limit);
            if (!Arrays.equals(again, raw)) {
                throw new AssertionError(item.get("name").string() + " encode");
            }
        }
        for (Json item : file.get("auth").array()) {
            byte[] spki = hex(item.get("spki").string());
            byte[] message = "relay".equals(item.get("kind").string())
                    ? LinkAuth.relayMessage(hex(item.get("stationId").string()), hex(item.get("nonce").string()), hex(item.get("deviceId").string()), item.get("purpose").string())
                    : LinkAuth.localMessage(hex(item.get("peerId").string()), hex(item.get("nonce").string()), hex(item.get("ownId").string()));
            byte[] identity = "relay".equals(item.get("kind").string()) ? hex(item.get("deviceId").string()) : hex(item.get("ownId").string());
            if (!Arrays.equals(LinkAuth.deviceId(spki), identity)) {
                throw new AssertionError(item.get("name").string() + " device");
            }
            if (LinkAuth.verify(spki, message, hex(item.get("sig").string())) != item.get("ok").boolValue()) {
                throw new AssertionError(item.get("name").string() + " signature");
            }
        }
        for (Json item : file.get("safety").array()) {
            String code = LinkSafety.code(hex(item.get("inviterSpki").string()), hex(item.get("deviceSpki").string()), hex(item.get("invitationId").string()), hex(item.get("relaySpki").string()));
            if (!code.equals(item.get("code").string()) || code.length() != 6) {
                throw new AssertionError(item.get("name").string());
            }
        }
        for (Json item : file.get("seal").array()) {
            byte[] shared = LinkSeal.sharedSecret(hex(item.get("macPkcs8").string()), hex(item.get("phoneSpki").string()));
            byte[] other = LinkSeal.sharedSecret(hex(item.get("phonePkcs8").string()), hex(item.get("macSpki").string()));
            if (!Arrays.equals(shared, other) || !Arrays.equals(shared, hex(item.get("shared").string()))) {
                throw new AssertionError(item.get("name").string() + " shared");
            }
            byte[] info = LinkSeal.directionInfo(item.get("direction").string(), hex(item.get("macId").string()), hex(item.get("phoneId").string()));
            byte[] salt = join(hex(item.get("macNonce").string()), hex(item.get("phoneNonce").string()));
            byte[] key = LinkSeal.deriveKey(shared, salt, info);
            byte[] nonce = LinkSeal.nonce(item.get("direction").string(), item.get("seq").longValue());
            byte[] aad = LinkSeal.aad((int) item.get("kind").longValue(), (int) item.get("flags").longValue(), item.get("channel").longValue(), item.get("seq").longValue());
            if (!Arrays.equals(aad, hex(item.get("aad").string()))) {
                throw new AssertionError(item.get("name").string() + " aad");
            }
            byte[] plain = hex(item.get("plaintext").string());
            byte[] sealed = LinkSeal.seal(key, nonce, plain, aad);
            byte[] opened = LinkSeal.open(key, nonce, hex(item.get("ciphertext").string()), aad);
            if (!Arrays.equals(sealed, hex(item.get("ciphertext").string())) || !Arrays.equals(opened, plain)) {
                throw new AssertionError(item.get("name").string() + " seal");
            }
            byte[] broken = Arrays.copyOf(sealed, sealed.length);
            broken[broken.length - 1] ^= 0x01;
            try {
                LinkSeal.open(key, nonce, broken, aad);
                throw new AssertionError(item.get("name").string() + " tamper");
            } catch (IllegalArgumentException expected) {
                if (!"open".equals(expected.getMessage())) {
                    throw new AssertionError(item.get("name").string() + " tamper");
                }
            }
        }
        checkTranscripts();
        System.out.println("link vectors ok");
    }

    private static void checkTranscripts() throws Exception {
        byte[] station = fill(16, 0x11);
        byte[] nonce = fill(32, 0x22);
        byte[] device = fill(16, 0x33);
        byte[] code = fill(16, 0x44);
        byte[] invitation = fill(16, 0x55);
        if (!Arrays.equals(sha256(LinkAuth.enrollMessage(station, nonce, device, code, "main")), hex("28b265654ea39c8988ce7515423255e3295b4e86ebff878c48473487de36002f"))
                || !Arrays.equals(sha256(LinkAuth.joinMessage(station, nonce, device, invitation, "main")), hex("e47093674462494c7f377d111faf45bd8a25e6b7be7dda25d84402d6812fced3"))
                || !Arrays.equals(sha256(LinkAuth.approveMessage(station, invitation, device)), hex("26e8fd0aaf218a1e8063941858db263430729067dd2ddfd727176484f0ce1e97"))
                || !Arrays.equals(LinkAuth.invitationProof(fill(16, 0x66), fill(4, 0x77), "n", "phone", "main"), hex("fbe5926156331eed23633774e48e7b2b213f4618762151325811bbca517cb5d3"))) {
            throw new AssertionError("transcript");
        }
        try {
            LinkAuth.enrollMessage(station, nonce, device, code, "side");
            throw new AssertionError("purpose");
        } catch (IllegalArgumentException expected) {
            if (!"length".equals(expected.getMessage())) {
                throw new AssertionError("purpose");
            }
        }
    }

    private static byte[] fill(int size, int value) {
        byte[] out = new byte[size];
        Arrays.fill(out, (byte) value);
        return out;
    }

    private static byte[] sha256(byte[] body) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(body);
    }

    private static Path vectors() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("lib/link/testdata/vectors.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到链路向量");
    }

    private static long number(Json item, String key) {
        Json value = item.get(key);
        return value == null ? 0 : value.longValue();
    }

    private static String text(Json item, String key) {
        Json value = item.get(key);
        return value == null ? "" : value.string();
    }

    private static byte[] hex(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("hex");
        }
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] join(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
