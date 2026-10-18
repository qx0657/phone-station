package dev.phonestation.adbkeep.link;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 认证原文与签名核对。手机服务不调用；中继 6 的 Go 测试服务器使用同一组字节。 */
public final class LinkAuth {
    private LinkAuth() {}

    public static byte[] deviceId(byte[] spki) {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(spki);
            return Arrays.copyOf(sum, 16);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    public static byte[] relayMessage(byte[] stationId, byte[] nonce, byte[] deviceId, String purpose) {
        if (stationId.length != 16 || nonce.length != 32 || deviceId.length != 16
                || !("main".equals(purpose) || "media".equals(purpose))) {
            throw new IllegalArgumentException("length");
        }
        byte[] label = "phone-station link-v2 auth".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] purposeBytes = purpose.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return join(label, stationId, nonce, deviceId, purposeBytes);
    }

    public static byte[] localMessage(byte[] peerId, byte[] peerNonce, byte[] ownId) {
        if (peerId.length != 16 || peerNonce.length != 32 || ownId.length != 16) {
            throw new IllegalArgumentException("length");
        }
        byte[] label = "phone-station link-v2 local-auth".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return join(label, peerId, peerNonce, ownId);
    }

    public static byte[] localPairMessage(byte[] spki, byte[] nonce) {
        if (spki.length == 0 || nonce.length != 32) {
            throw new IllegalArgumentException("length");
        }
        byte[] label = "phone-station link-v2 local-pair".getBytes(StandardCharsets.US_ASCII);
        return join(label, spki, nonce);
    }

    public static byte[] enrollMessage(byte[] stationId, byte[] nonce, byte[] deviceId, byte[] code, String purpose) {
        if (stationId.length != 16 || nonce.length != 32 || deviceId.length != 16 || code.length != 16 || !purposeOk(purpose)) {
            throw new IllegalArgumentException("length");
        }
        byte[] label = "phone-station link-v2 enroll".getBytes(StandardCharsets.US_ASCII);
        return join(label, stationId, nonce, deviceId, code, purpose.getBytes(StandardCharsets.US_ASCII));
    }

    public static byte[] joinMessage(byte[] stationId, byte[] nonce, byte[] deviceId, byte[] invitationId, String purpose) {
        if (stationId.length != 16 || nonce.length != 32 || deviceId.length != 16 || invitationId.length != 16 || !purposeOk(purpose)) {
            throw new IllegalArgumentException("length");
        }
        byte[] label = "phone-station link-v2 join".getBytes(StandardCharsets.US_ASCII);
        return join(label, stationId, nonce, deviceId, invitationId, purpose.getBytes(StandardCharsets.US_ASCII));
    }

    public static byte[] approveMessage(byte[] stationId, byte[] invitationId, byte[] deviceId) {
        if (stationId.length != 16 || invitationId.length != 16 || deviceId.length != 16) {
            throw new IllegalArgumentException("length");
        }
        byte[] label = "phone-station link-v2 approve".getBytes(StandardCharsets.US_ASCII);
        return join(label, stationId, invitationId, deviceId);
    }

    public static byte[] invitationProof(byte[] key, byte[] spki, String name, String kind, String purpose) {
        if (key.length != 16 || spki.length == 0 || !proofNameOk(name) || !("mac".equals(kind) || "phone".equals(kind)) || !purposeOk(purpose)) {
            throw new IllegalArgumentException("length");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(spki);
            mac.update(name.getBytes(StandardCharsets.UTF_8));
            mac.update(new byte[] {0});
            mac.update(kind.getBytes(StandardCharsets.US_ASCII));
            mac.update(new byte[] {0});
            mac.update(purpose.getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    public static boolean verify(byte[] spki, byte[] message, byte[] der) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(publicKey(spki));
            signature.update(message);
            return signature.verify(der);
        } catch (GeneralSecurityException error) {
            return false;
        }
    }

    static PublicKey publicKey(byte[] spki) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
    }

    private static boolean purposeOk(String purpose) {
        return "main".equals(purpose) || "media".equals(purpose);
    }

    private static boolean proofNameOk(String name) {
        int count = name.codePointCount(0, name.length());
        if (count < 1 || count > 64) {
            return false;
        }
        for (int i = 0; i < name.length(); ) {
            int code = name.codePointAt(i);
            if (Character.isISOControl(code)) {
                return false;
            }
            i += Character.charCount(code);
        }
        return true;
    }

    private static byte[] join(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] out = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
