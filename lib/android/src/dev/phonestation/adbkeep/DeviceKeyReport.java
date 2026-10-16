package dev.phonestation.adbkeep;

import java.nio.charset.StandardCharsets;

/** 设备密钥诊断行。不包含密钥或签名，也不接入现有工具。 */
final class DeviceKeyReport {
    static final byte[] MESSAGE = "phone-station link-v2 device-key".getBytes(StandardCharsets.UTF_8);

    interface Signer {
        byte[] sign(byte[] message) throws Exception;
    }

    /** 打开时生成或取出密钥。StrongBox 失败就抛异常，由诊断行改试普通 Keystore。 */
    interface Generator {
        Signer open(boolean strongBox) throws Exception;
    }

    interface Clock {
        long nanoTime();
    }

    private DeviceKeyReport() {}

    static String line(Generator generator, Clock clock) {
        boolean backed;
        Signer signer;
        try {
            signer = generator.open(true);
            backed = true;
        } catch (Exception unavailable) {
            backed = false;
            try {
                signer = generator.open(false);
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
