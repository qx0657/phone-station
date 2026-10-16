package dev.phonestation.adbkeep;

import java.nio.charset.StandardCharsets;

/** 设备密钥诊断行。不包含密钥或签名，也不接入现有工具。 */
final class DeviceKeyReport {
    static final byte[] MESSAGE = "phone-station link-v2 device-key".getBytes(StandardCharsets.UTF_8);

    interface Signer {
        byte[] sign(byte[] message) throws Exception;
    }

    interface Slot {
        Signer open() throws Exception;
    }

    interface Clock {
        long nanoTime();
    }

    private DeviceKeyReport() {}

    static String line(Slot strongBox, Slot keystore, Clock clock) {
        boolean backed;
        Signer signer;
        try {
            signer = strongBox.open();
            backed = true;
        } catch (Exception unavailable) {
            backed = false;
            try {
                signer = keystore.open();
            } catch (Exception failed) {
                return "deviceKey unavailable";
            }
        }
        if (signer == null) {
            return "deviceKey unavailable";
        }
        long start = clock.nanoTime();
        try {
            if (signer.sign(MESSAGE) == null) {
                return "deviceKey unavailable";
            }
        } catch (Exception failed) {
            return "deviceKey unavailable";
        }
        long elapsed = clock.nanoTime() - start;
        long millis = elapsed < 0 ? 0 : elapsed / 1_000_000L;
        return "deviceKey strongBox=" + backed + " signMs=" + millis;
    }
}
