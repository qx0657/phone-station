package dev.phonestation.adbkeep;

/** 同时在 MCP 边界和特权进程里检查，输出总量限制也用于保护 Binder。 */
final class ShellRequest {
    static final int DEFAULT_TIMEOUT_MS = 10_000;
    static final int MAX_TIMEOUT_MS = 60_000;
    static final int DEFAULT_OUTPUT_BYTES = 32_768;
    static final int MAX_OUTPUT_BYTES = 65_536;

    final String command;
    final int timeoutMs;
    final int maxOutputBytes;

    ShellRequest(String command, int timeoutMs, int maxOutputBytes) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("需要非空的 command");
        }
        if (command.length() > 16_384 || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("command 最多 16384 字，不能包含空字符");
        }
        if (timeoutMs < 100 || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("timeoutMs 要在 100 到 60000 之间");
        }
        if (maxOutputBytes < 1 || maxOutputBytes > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("maxOutputBytes 要在 1 到 65536 之间");
        }
        this.command = command;
        this.timeoutMs = timeoutMs;
        this.maxOutputBytes = maxOutputBytes;
    }
}
