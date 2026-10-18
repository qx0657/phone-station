package dev.phonestation.adbkeep.link;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** 配对安全码。没有中继时，邀请方是手机，邀请编号是 16 个 0，中继位置再用一次手机公钥。 */
public final class LinkSafety {
    private LinkSafety() {}

    public static String local(byte[] phoneSpki, byte[] macSpki) {
        byte[] invitation = new byte[16];
        return code(phoneSpki, macSpki, invitation, phoneSpki);
    }

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
