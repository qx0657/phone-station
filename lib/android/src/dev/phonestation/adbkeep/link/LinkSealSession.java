package dev.phonestation.adbkeep.link;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/** 远程会话的端到端加密。本地链路不安装。必须加密时明文帧直接拒绝。 */
public final class LinkSealSession implements LinkEngine.Codec {
    private final boolean fromMac;
    private final LocalLink.Device self;
    private final byte[] macId;
    private final byte[] phoneId;
    private final byte[] peerSpki;
    private final LinkSealPolicy choice = new LinkSealPolicy(true, false);
    private final LinkSealPolicy sendWin = new LinkSealPolicy(true, false);
    private final LinkSealPolicy recvWin = new LinkSealPolicy(true, false);
    private final Object lock = new Object();
    private byte[] sendKey;
    private byte[] recvKey;
    private String sendDir;
    private String recvDir;
    private byte[] ephPkcs8;
    private byte[] ephSpki;
    private byte[] nonce;
    private volatile boolean ready;

    private LinkSealSession(boolean fromMac, LocalLink.Device self, byte[] macId, byte[] phoneId, byte[] peerSpki) {
        this.fromMac = fromMac;
        this.self = self;
        this.macId = macId;
        this.phoneId = phoneId;
        this.peerSpki = peerSpki;
    }

    public static LinkSealSession required(boolean fromMac, LocalLink.Device self, byte[] peerId, byte[] peerSpki) {
        if (self == null || self.signer == null || peerId == null || peerId.length != 16 || peerSpki == null || peerSpki.length == 0) {
            throw new IllegalArgumentException("device");
        }
        byte[] macId = fromMac ? self.id : peerId;
        byte[] phoneId = fromMac ? peerId : self.id;
        return new LinkSealSession(fromMac, self, Arrays.copyOf(macId, 16), Arrays.copyOf(phoneId, 16), Arrays.copyOf(peerSpki, peerSpki.length));
    }

    public boolean ready() {
        return ready;
    }

    public byte[] openPayload() throws Exception {
        synchronized (lock) {
            if (!fromMac || sendKey != null || ephPkcs8 != null) {
                throw new IllegalArgumentException("plaintext");
            }
            KeyPair pair = ephemeral();
            ephPkcs8 = pair.getPrivate().getEncoded();
            ephSpki = pair.getPublic().getEncoded();
            nonce = random();
            byte[] sig = self.signer.sign(openMessage(macId, phoneId, ephSpki, nonce));
            return json("open", ephSpki, nonce, sig);
        }
    }

    public byte[] answer(byte[] raw) throws Exception {
        synchronized (lock) {
            if (fromMac || sendKey != null) {
                throw new IllegalArgumentException("plaintext");
            }
            byte[] macEph = field(raw, "eph");
            byte[] macNonce = field(raw, "nonce");
            byte[] sig = field(raw, "sig");
            if (macEph == null || macNonce == null || macNonce.length != 32 || sig == null || !"open".equals(text(raw, "type"))) {
                throw new IllegalArgumentException("bad_signature");
            }
            if (!LinkAuth.verify(peerSpki, openMessage(macId, phoneId, macEph, macNonce), sig)) {
                throw new IllegalArgumentException("bad_signature");
            }
            KeyPair pair = ephemeral();
            byte[] phoneNonce = random();
            byte[] phoneSpki = pair.getPublic().getEncoded();
            byte[] acceptSig = self.signer.sign(acceptMessage(macId, phoneId, macEph, macNonce, phoneSpki, phoneNonce));
            install(pair.getPrivate().getEncoded(), macEph, macNonce, phoneNonce);
            return json("accept", phoneSpki, phoneNonce, acceptSig);
        }
    }

    public void finish(byte[] raw) throws Exception {
        synchronized (lock) {
            if (!fromMac || sendKey != null || ephPkcs8 == null) {
                throw new IllegalArgumentException("plaintext");
            }
            byte[] phoneEph = field(raw, "eph");
            byte[] phoneNonce = field(raw, "nonce");
            byte[] sig = field(raw, "sig");
            if (phoneEph == null || phoneNonce == null || phoneNonce.length != 32 || sig == null || !"accept".equals(text(raw, "type"))) {
                throw new IllegalArgumentException("bad_signature");
            }
            if (!LinkAuth.verify(peerSpki, acceptMessage(macId, phoneId, ephSpki, nonce, phoneEph, phoneNonce), sig)) {
                throw new IllegalArgumentException("bad_signature");
            }
            install(ephPkcs8, phoneEph, nonce, phoneNonce);
            ephPkcs8 = null;
        }
    }

    @Override
    public LinkEngine.Frame seal(LinkEngine.Frame frame) {
        synchronized (lock) {
            if (sendKey == null) {
                throw new IllegalArgumentException("plaintext");
            }
            sendWin.accept(frame.seq, System.currentTimeMillis());
            int flags = frame.flags | 0x01;
            byte[] body = LinkSeal.seal(sendKey, LinkSeal.nonce(sendDir, frame.seq), frame.payload,
                    LinkSeal.aad(frame.kind, flags, frame.channel, frame.seq));
            return new LinkEngine.Frame(frame.kind, flags, frame.peer, frame.channel, frame.seq, body);
        }
    }

    @Override
    public LinkEngine.Frame open(LinkEngine.Frame frame) {
        synchronized (lock) {
            choice.admit(frame.flags);
            if (recvKey == null) {
                throw new IllegalArgumentException("plaintext");
            }
            recvWin.accept(frame.seq, System.currentTimeMillis());
            byte[] body = LinkSeal.open(recvKey, LinkSeal.nonce(recvDir, frame.seq), frame.payload,
                    LinkSeal.aad(frame.kind, frame.flags, frame.channel, frame.seq));
            return new LinkEngine.Frame(frame.kind, frame.flags & ~0x01, frame.peer, frame.channel, frame.seq, body);
        }
    }

    private void install(byte[] ownPkcs8, byte[] peerEph, byte[] macNonce, byte[] phoneNonce) {
        byte[] shared = LinkSeal.sharedSecret(ownPkcs8, peerEph);
        byte[] salt = concat(macNonce, phoneNonce);
        sendDir = fromMac ? "mac-to-phone" : "phone-to-mac";
        recvDir = fromMac ? "phone-to-mac" : "mac-to-phone";
        sendKey = LinkSeal.deriveKey(shared, salt, LinkSeal.directionInfo(sendDir, macId, phoneId));
        recvKey = LinkSeal.deriveKey(shared, salt, LinkSeal.directionInfo(recvDir, macId, phoneId));
        ready = true;
    }

    static byte[] openMessage(byte[] macId, byte[] phoneId, byte[] spki, byte[] nonce) {
        return concat(concat(concat(concat(label("phone-station link-v2 seal-open"), macId), phoneId), spki), nonce);
    }

    static byte[] acceptMessage(byte[] macId, byte[] phoneId, byte[] macEph, byte[] macNonce, byte[] phoneEph, byte[] phoneNonce) {
        return concat(concat(concat(concat(concat(concat(label("phone-station link-v2 seal-accept"), macId), phoneId), macEph), macNonce), phoneEph), phoneNonce);
    }

    static String text(byte[] json, String key) {
        String body = new String(json, StandardCharsets.US_ASCII);
        String needle = "\"" + key + "\":\"";
        int at = body.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int start = at + needle.length();
        int end = body.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return body.substring(start, end);
    }

    private static byte[] field(byte[] json, String key) {
        String value = text(json, key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static byte[] json(String type, byte[] spki, byte[] nonce, byte[] sig) {
        String body = "{\"type\":\"" + type
                + "\",\"eph\":\"" + Base64.getEncoder().encodeToString(spki)
                + "\",\"nonce\":\"" + Base64.getEncoder().encodeToString(nonce)
                + "\",\"sig\":\"" + Base64.getEncoder().encodeToString(sig) + "\"}";
        return body.getBytes(StandardCharsets.US_ASCII);
    }

    private static KeyPair ephemeral() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] random() {
        byte[] nonce = new byte[32];
        new java.security.SecureRandom().nextBytes(nonce);
        return nonce;
    }

    private static byte[] label(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
