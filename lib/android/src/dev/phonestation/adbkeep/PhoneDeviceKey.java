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

/** Keystore 里的 P-256 设备密钥。优先 StrongBox。只给受 DUMP 保护的诊断读取，不接入现有工具。 */
final class PhoneDeviceKey {
    static final String ALIAS = "phone-station-device-p256";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final Object LOCK = new Object();

    private PhoneDeviceKey() {}

    static String diagnostic() {
        synchronized (LOCK) {
            try {
                return DeviceKeyReport.line(PhoneDeviceKey::openStrongBox, PhoneDeviceKey::openKeystore, System::nanoTime);
            } catch (RuntimeException error) {
                return "deviceKey unavailable";
            }
        }
    }

    private static DeviceKeyReport.Signer openStrongBox() throws Exception {
        PrivateKey existing = load();
        if (existing != null) {
            if (!strongBox(existing)) {
                throw new GeneralSecurityException("strongbox");
            }
            return message -> sign(existing, message);
        }
        return message -> sign(generate(true), message);
    }

    private static DeviceKeyReport.Signer openKeystore() throws Exception {
        PrivateKey existing = load();
        if (existing != null) {
            return message -> sign(existing, message);
        }
        return message -> sign(generate(false), message);
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

    private static boolean strongBox(PrivateKey key) {
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

    private static byte[] sign(PrivateKey key, byte[] message) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(message);
        return signature.sign();
    }
}
