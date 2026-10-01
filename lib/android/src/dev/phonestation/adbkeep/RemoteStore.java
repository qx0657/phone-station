package dev.phonestation.adbkeep;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Remote relay profile. Bearer material is AES-GCM encrypted under an Android Keystore key. */
final class RemoteStore {
    private static final String PREFS = "phone_relay";
    private static final String ENABLED = "enabled";
    private static final String ENDPOINT = "endpoint";
    private static final String PIN = "pin";
    private static final String TOKEN = "token";
    private static final String KEY_ALIAS = "phone_station_relay_v1";
    private static final String KEYSTORE = "AndroidKeyStore";

    private RemoteStore() {}

    static boolean enabled(Context context) {
        return prefs(context).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(ENABLED, enabled).commit();
        publishState(context);
    }

    static String endpoint(Context context) {
        return prefs(context).getString(ENDPOINT, "");
    }

    static String pin(Context context) {
        return prefs(context).getString(PIN, "");
    }

    static boolean configured(Context context) {
        return endpoint(context).startsWith("https://")
                && pin(context).matches("[0-9a-f]{64}")
                && !prefs(context).getString(TOKEN, "").isEmpty();
    }

    static boolean configure(Context context, String endpoint, String pin, String token) {
        if (endpoint == null || !endpoint.matches("https://[^/]+(:[0-9]{1,5})?/?")
                || pin == null || !pin.matches("(?i)[0-9a-f]{64}")
                || token == null || !token.matches("(?i)[0-9a-f]{64}")) {
            return false;
        }
        try {
            String encrypted = encrypt(token.toLowerCase(Locale.US));
            prefs(context).edit()
                    .putString(ENDPOINT, endpoint.replaceAll("/$", ""))
                    .putString(PIN, pin.toLowerCase(Locale.US))
                    .putString(TOKEN, encrypted)
                    .putBoolean(ENABLED, true)
                    .commit();
            publishState(context);
            return true;
        } catch (GeneralSecurityException error) {
            return false;
        }
    }

    static void forget(Context context) {
        prefs(context).edit().clear().commit();
        publishState(context);
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // Forgetting the profile succeeds even if the key was already removed.
        }
    }

    static String token(Context context) throws GeneralSecurityException {
        String value = prefs(context).getString(TOKEN, "");
        if (value.isEmpty()) {
            throw new GeneralSecurityException("remote profile is not configured");
        }
        byte[] packed;
        try {
            packed = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new GeneralSecurityException("remote profile is invalid", error);
        }
        if (packed.length <= 12) {
            throw new GeneralSecurityException("remote profile is invalid");
        }
        byte[] iv = Arrays.copyOf(packed, 12);
        byte[] body = Arrays.copyOfRange(packed, 12, packed.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
    }

    private static String encrypt(String value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != 12) {
            throw new GeneralSecurityException("Android Keystore returned an invalid GCM IV");
        }
        byte[] body = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(body, 0, packed, iv.length, body.length);
        return Base64.getEncoder().encodeToString(packed);
    }

    private static SecretKey getKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
            java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) {
                return (SecretKey) existing;
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        } catch (java.io.IOException error) {
            throw new GeneralSecurityException("Android Keystore is unavailable", error);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void publishState(Context context) {
        try {
            Settings.Global.putInt(context.getContentResolver(), "phonestation_remote",
                    enabled(context) && configured(context) ? 1 : 0);
        } catch (RuntimeException ignored) {
            // The UI reads app preferences directly; this bit only confirms adb pairing.
        }
    }
}
