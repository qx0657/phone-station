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
    private static final String PERMISSION_REVISION = "permission_revision";
    private static final String KEY_ALIAS = "phone_station_relay_v1";
    private static final String KEYSTORE = "AndroidKeyStore";

    private RemoteStore() {}

    static boolean enabled(Context context) {
        return prefs(context).getBoolean(ENABLED, false);
    }

    static boolean setEnabled(Context context, boolean enabled) {
        SharedPreferences.Editor editor = prefs(context).edit().putBoolean(ENABLED, enabled);
        if (enabled != enabled(context)) { editor.putString(PERMISSION_REVISION, java.util.UUID.randomUUID().toString()); }
        boolean saved = editor.commit();
        if (!enabled) { PhoneRelayClient.revokeActive(); ShizukuScreen.close(); }
        publishState(context);
        return saved;
    }

    static String permissionRevision(Context context) { return prefs(context).getString(PERMISSION_REVISION, ""); }

    static void invalidate(Context context) {
        prefs(context).edit().putString(PERMISSION_REVISION, java.util.UUID.randomUUID().toString()).commit();
        PhoneRelayClient.revokeActive(); ShizukuScreen.close();
    }

    static boolean permission(Context context, String scope) { return prefs(context).getBoolean("allow_" + scope, false); }

    static String permissionSummary(Context context) {
        int count = 0;
        for (String scope : RemotePolicy.SCOPES) { if (permission(context, scope)) { count++; } }
        return RemotePermissionInfo.summary(count);
    }

    static RemotePolicy policy(Context context) {
        java.util.Set<String> grants = new java.util.HashSet<>();
        for (String scope : RemotePolicy.SCOPES) { if (permission(context, scope)) { grants.add(scope); } }
        return new RemotePolicy(grants);
    }

    // No MCP or pairing broadcast can grant these permissions; only the phone UI calls this.
    static boolean setPermission(Context context, String scope, boolean allowed) {
        if (!java.util.Arrays.asList(RemotePolicy.SCOPES).contains(scope)) { throw new IllegalArgumentException("未知远程权限"); }
        if (permission(context, scope) == allowed) { return true; }
        boolean saved = prefs(context).edit().putBoolean("allow_" + scope, allowed)
                .putString(PERMISSION_REVISION, java.util.UUID.randomUUID().toString()).commit();
        PhoneRelayClient.revokeActive();
        // Screen streams always use the relay, including opens submitted over adb.
        ShizukuScreen.close();
        return saved;
    }

    static String endpoint(Context context) {
        return prefs(context).getString(ENDPOINT, "");
    }

    static String pin(Context context) {
        return prefs(context).getString(PIN, "");
    }

    static boolean configured(Context context) {
        return RelayProfile.normalizeEndpoint(endpoint(context)) != null
                && pin(context).matches("[0-9a-f]{64}")
                && !prefs(context).getString(TOKEN, "").isEmpty();
    }

    static boolean configure(Context context, String endpoint, String pin, String token) {
        endpoint = RelayProfile.normalizeEndpoint(endpoint);
        if (RelayProfile.validationMessage(endpoint, pin, token,
                endpoint(context), pin(context), configured(context)) != null) {
            return false;
        }
        try {
            String encrypted = token.isEmpty() ? encryptedToken(context) : encrypt(token.toLowerCase(Locale.US));
            boolean changed = !endpoint.equals(endpoint(context)) || !pin.equalsIgnoreCase(pin(context)) || !token.isEmpty();
            SharedPreferences.Editor editor = prefs(context).edit();
            if (changed) {
                for (String scope : RemotePolicy.SCOPES) { editor.remove("allow_" + scope); }
                editor.putString(PERMISSION_REVISION, java.util.UUID.randomUUID().toString());
            }
            boolean saved = editor
                    .putString(ENDPOINT, endpoint)
                    .putString(PIN, pin.toLowerCase(Locale.US))
                    .putString(TOKEN, encrypted)
                    .putBoolean(ENABLED, true)
                    .commit();
            if (changed) { PhoneRelayClient.revokeActive(); ShizukuScreen.close(); }
            if (!saved) { return false; }
            publishState(context);
            return true;
        } catch (GeneralSecurityException error) {
            return false;
        }
    }

    static boolean forget(Context context) {
        boolean saved = prefs(context).edit().clear().commit();
        PhoneRelayClient.revokeActive();
        ShizukuScreen.close();
        if (!saved) { return false; }
        publishState(context);
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // Forgetting the profile succeeds even if the key was already removed.
        }
        return true;
    }

    static String encryptedToken(Context context) {
        return prefs(context).getString(TOKEN, "");
    }

    static String decryptToken(String value) throws GeneralSecurityException {
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
