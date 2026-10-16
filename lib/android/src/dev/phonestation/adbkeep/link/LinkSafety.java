package dev.phonestation.adbkeep.link;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** 配对安全码。不接入现有通道。 */
public final class LinkSafety {
    private LinkSafety() {}

    public static String code(byte[] inviterSpki, byte[] deviceSpki, byte[] invitationId, byte[] relaySpki) {
        if (inviterSpki.length == 0 || deviceSpki.length == 0 || invitationId.length != 16 || relaySpki.length == 0) {
            throw new IllegalArgumentException("length");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("ps-sas-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update(inviterSpki);
            digest.update(deviceSpki);
            digest.update(invitationId);
            digest.update(relaySpki);
            byte[] sum = digest.digest();
            long value = ((sum[0] & 0xffL) << 24) | ((sum[1] & 0xffL) << 16) | ((sum[2] & 0xffL) << 8) | (sum[3] & 0xffL);
            return String.format("%06d", value % 1000000L);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }
}
