package dev.phonestation.adbkeep;

import java.nio.charset.StandardCharsets;

final class ShellRunnerTest {
    public static void main(String[] args) throws Exception {
        Json result = run("printf '手机\\n'; printf '错误\\n' >&2; exit 7", 2_000, 1024);
        expect("手机\n", result.get("stdout").string());
        expect("错误\n", result.get("stderr").string());
        expect(7, result.get("exitCode").longValue());
        expect(false, result.get("timedOut").boolValue());
        expect(false, result.get("outputTruncated").boolValue());

        // 两个管道都远超保留上限，仍要排空，否则 waitFor 会被输出阻塞。
        Json large = run("i=0; while [ $i -lt 10000 ]; do printf 'abcdefghij'; printf '0123456789' >&2; i=$((i+1)); done", 5_000, 127);
        int bytes = large.get("stdout").string().getBytes(StandardCharsets.UTF_8).length
                + large.get("stderr").string().getBytes(StandardCharsets.UTF_8).length;
        expect(127, bytes);
        expect(0, large.get("exitCode").longValue());
        expect(true, large.get("outputTruncated").boolValue());
        expect(false, large.get("timedOut").boolValue());

        Json timeout = run("printf '开始'; exec sleep 10", 100, 1024);
        expect(true, timeout.get("timedOut").boolValue());
        expect("开始", timeout.get("stdout").string());
        if (timeout.get("durationMs").longValue() > 3_000) {
            throw new AssertionError("shell timeout did not stop the process promptly");
        }

        Json eof = run("cat; printf 'EOF'", 1_000, 1024);
        expect("EOF", eof.get("stdout").string());
        expect(false, eof.get("timedOut").boolValue());

        reject("", 100, 1);
        reject(" \n ", 100, 1);
        reject("id\0", 100, 1);
        reject("x".repeat(16_385), 100, 1);
        reject("id", 99, 1);
        reject("id", 60_001, 1);
        reject("id", 100, 0);
        reject("id", 100, 65_537);
        System.out.println("ShellRunnerTest ok");
    }

    private static Json run(String command, int timeout, int bytes) throws Exception {
        ShellRequest request = new ShellRequest(command, timeout, bytes);
        Process process = new ProcessBuilder("/bin/sh", "-c",
                ShellRunner.WRAPPER.replace("/system/bin/sh", "/bin/sh"), "station-shell", command).start();
        return ShellRunner.run(process, request, pid -> {
            process.descendants().forEach(child -> child.destroyForcibly());
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        });
    }

    private static void reject(String command, int timeout, int bytes) {
        try {
            new ShellRequest(command, timeout, bytes);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid request accepted");
    }

    private static void expect(String want, String got) {
        if (!want.equals(got)) {
            throw new AssertionError("want [" + want + "] got [" + got + "]");
        }
    }

    private static void expect(long want, long got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void expect(boolean want, boolean got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }
}
