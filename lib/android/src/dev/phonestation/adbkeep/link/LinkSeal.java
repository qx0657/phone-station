package dev.phonestation.adbkeep.link;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 端到端密钥与 AES-GCM。不接入现有通道。 */
public final class LinkSeal {
    private LinkSeal() {}

    public static byte[] sharedSecret(byte[] privatePkcs8, byte[] peerSpki) {
        try {
            PrivateKey own = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privatePkcs8));
            PublicKey peer = LinkAuth.publicKey(peerSpki);
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(own);
            agreement.doPhase(peer, true);
            byte[] secret = agreement.generateSecret();
            if (secret.length > 32) {
                throw new IllegalArgumentException("length");
            }
            if (secret.length == 32) {
                return secret;
            }
            byte[] padded = new byte[32];
            System.arraycopy(secret, 0, padded, 32 - secret.length, secret.length);
            return padded;
        } catch (GeneralSecurityException error) {
            throw new IllegalArgumentException("key");
        }
    }

    public static byte[] deriveKey(byte[] secret, byte[] salt, byte[] info) {
        if (secret.length != 32 || salt.length == 0) {
            throw new IllegalArgumentException("length");
        }
        byte[] prk = hmac(salt, secret);
        byte[] block = Arrays.copyOf(info, info.length + 1);
        block[info.length] = 0x01;
        return hmac(prk, block);
    }

    public static byte[] directionInfo(String direction, byte[] macId, byte[] phoneId) {
        if (macId.length != 16 || phoneId.length != 16) {
            throw new IllegalArgumentException("length");
        }
        String label = "mac-to-phone".equals(direction) ? "phone-station link-v2 mac-to-phone"
                : "phone-to-mac".equals(direction) ? "phone-station link-v2 phone-to-mac" : "";
        if (label.isEmpty()) {
            throw new IllegalArgumentException("direction");
        }
        byte[] text = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = Arrays.copyOf(text, text.length + 32);
        System.arraycopy(macId, 0, out, text.length, 16);
        System.arraycopy(phoneId, 0, out, text.length + 16, 16);
        return out;
    }

    public static byte[] nonce(String direction, long seq) {
        int constant = "mac-to-phone".equals(direction) ? 1 : "phone-to-mac".equals(direction) ? 2 : 0;
        if (constant == 0) {
            throw new IllegalArgumentException("direction");
        }
        byte[] out = new byte[12];
        out[3] = (byte) constant;
        for (int i = 11; i >= 4; i--) {
            out[i] = (byte) seq;
            seq >>>= 8;
        }
        return out;
    }

    public static byte[] aad(int kind, int flags, long channel, long seq) {
        byte[] out = new byte[16];
        out[0] = 2;
        out[1] = (byte) kind;
        out[2] = (byte) flags;
        for (int i = 7; i >= 4; i--) {
            out[i] = (byte) channel;
            channel >>>= 8;
        }
        for (int i = 15; i >= 8; i--) {
            out[i] = (byte) seq;
            seq >>>= 8;
        }
        return out;
    }

    public static byte[] seal(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        return cipher(Cipher.ENCRYPT_MODE, key, nonce, plaintext, aad);
    }

    public static byte[] open(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) {
        return cipher(Cipher.DECRYPT_MODE, key, nonce, ciphertext, aad);
    }

    private static byte[] cipher(int mode, byte[] key, byte[] nonce, byte[] input, byte[] aad) {
        try {
            if (key.length != 32 || nonce.length != 12) {
                throw new IllegalArgumentException("length");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException error) {
            throw new IllegalArgumentException("open");
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }
}
