package dev.phonestation.adbkeep;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/** Keystore 里的 P-256 设备密钥。打开时生成，优先 StrongBox，生成失败再试普通 Keystore。本地链路握手用它签名。 */
final class PhoneDeviceKey {
    static final String ALIAS = "phone-station-device-p256";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final Object LOCK = new Object();

    private PhoneDeviceKey() {}

    static String diagnostic() {
        synchronized (LOCK) {
            try {
                return DeviceKeyReport.line(PhoneDeviceKey::open, System::nanoTime);
            } catch (RuntimeException error) {
                return "deviceKey unavailable";
            }
        }
    }

    /** 桌面测试没有 Keystore，注入同一套 SHA256withECDSA。手机服务用 identity()。 */
    static DeviceKeyReport.Signer localSigner() throws Exception {
        synchronized (LOCK) {
            try {
                return open(true);
            } catch (Exception strongBox) {
                return open(false);
            }
        }
    }

    private static DeviceKeyReport.Signer open(boolean strongBox) throws Exception {
        PrivateKey existing = load();
        if (existing != null) {
            if (strongBox && !backedByStrongBox(existing)) {
                throw new GeneralSecurityException("strongbox");
            }
            return message -> sign(existing, message);
        }
        PrivateKey created = generate(strongBox);
        return message -> sign(created, message);
    }

    private static PrivateKey load() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        java.security.Key key = store.getKey(ALIAS, null);
        if (key instanceof PrivateKey) {
            return (PrivateKey) key;
        }
        return null;
    }

    private static PrivateKey generate(boolean strongBox) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .setIsStrongBoxBacked(strongBox)
                .build());
        return generator.generateKeyPair().getPrivate();
    }

    private static boolean backedByStrongBox(PrivateKey key) {
        if (Build.VERSION.SDK_INT < 31) {
            return false;
        }
        try {
            KeyFactory factory = KeyFactory.getInstance(key.getAlgorithm(), KEYSTORE);
            KeyInfo info = factory.getKeySpec(key, KeyInfo.class);
            return info.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_STRONGBOX;
        } catch (GeneralSecurityException error) {
            return false;
        }
    }

    static dev.phonestation.adbkeep.link.LocalLink.Device identity() throws Exception {
        synchronized (LOCK) {
            DeviceKeyReport.Signer signer;
            try {
                signer = open(true);
            } catch (Exception strongBox) {
                signer = open(false);
            }
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            java.security.cert.Certificate certificate = store.getCertificate(ALIAS);
            if (certificate == null || certificate.getPublicKey() == null) {
                throw new IllegalStateException("device key");
            }
            byte[] spki = certificate.getPublicKey().getEncoded();
            return new dev.phonestation.adbkeep.link.LocalLink.Device(
                    dev.phonestation.adbkeep.link.LinkAuth.deviceId(spki), spki, signer::sign);
        }
    }

    private static byte[] sign(PrivateKey key, byte[] message) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(message);
        return signature.sign();
    }
}
