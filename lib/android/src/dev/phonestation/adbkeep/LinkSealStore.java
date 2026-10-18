package dev.phonestation.adbkeep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 必须加密的 Mac。文件不存在表示这一对仍走明文。读失败时不拨远程，避免降级。 */
final class LinkSealStore {
    private LinkSealStore() {}

    /** 0 关闭，1 必须加密，-1 文件在但读不了。 */
    static int mode(Path dir, byte[] id) {
        if (dir == null || id == null || id.length != 16) {
            return 0;
        }
        Path file = dir.resolve("link-seal");
        if (!Files.exists(file)) {
            return 0;
        }
        String hex = hex(id);
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
                if (hex.equals(line.trim())) {
                    return 1;
                }
            }
            return 0;
        } catch (IOException error) {
            return -1;
        }
    }

    private static String hex(byte[] id) {
        char[] out = new char[id.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < id.length; i++) {
            int value = id[i] & 0xff;
            out[i * 2] = digits[value >>> 4];
            out[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(out);
    }
}
