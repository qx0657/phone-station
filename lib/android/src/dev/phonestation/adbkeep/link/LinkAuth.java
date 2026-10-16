package dev.phonestation.adbkeep.link;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/** 认证原文与签名核对。不接入现有通道。 */
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
