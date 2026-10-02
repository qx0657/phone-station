package dev.phonestation.adbkeep;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 排空两个输出流，达到保留上限后继续读，避免命令被管道堵住。 */
final class ShellRunner {
    static final String WRAPPER = "printf '%s\\n' \"$$\"; exec /system/bin/sh -c \"$1\"";

    interface GroupKiller {
        void kill(long pid);
    }

    private ShellRunner() {}

    static Json run(Process process, ShellRequest request, GroupKiller killer)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(request.timeoutMs);
        Capture capture = new Capture(request.maxOutputBytes);
        CountDownLatch header = new CountDownLatch(1);
        Drain out = new Drain(process.getInputStream(), capture, true, header);
        Drain err = new Drain(process.getErrorStream(), capture, false, null);
        Thread stdout = drain(out, "station-shell-stdout");
        Thread stderr = drain(err, "station-shell-stderr");
        boolean timedOut = false;
        try {
            // 无交互输入；cat/read 等命令立即收到 EOF。
            process.getOutputStream().close();
            if (!header.await(remaining(deadline), TimeUnit.NANOSECONDS)) {
                timedOut = true;
            } else if (out.headerError != null) {
                throw new IOException("shell 进程没有返回有效的进程组");
            } else {
                timedOut = !process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS);
            }
            // 正常结束也清理同一进程组中的后台子进程，不留下持续运行的任务。
            if (out.pid > 1) {
                killer.kill(out.pid);
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            boolean drained = join(stdout, 1_000) & join(stderr, 1_000);
            Json result = Json.obj()
                    .put("stdout", capture.text(true))
                    .put("stderr", capture.text(false))
                    .put("timedOut", timedOut)
                    .put("outputTruncated", capture.truncated || !drained)
                    .put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            result.put("exitCode", process.isAlive() ? Json.nul() : Json.num(process.exitValue()));
            if (out.readError || err.readError) {
                result.put("outputIncomplete", true);
            }
            return result;
        } finally {
            if (out.pid > 1) {
                killer.kill(out.pid);
            }
            process.destroyForcibly();
            close(process.getInputStream());
            close(process.getErrorStream());
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static Thread drain(Drain reader, String name) {
        Thread thread = new Thread(reader, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static boolean join(Thread thread, long millis) throws InterruptedException {
        thread.join(millis);
        return !thread.isAlive();
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {}
    }

    private static final class Capture {
        private final int limit;
        private int kept;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        volatile boolean truncated;

        Capture(int limit) {
            this.limit = limit;
        }

        synchronized void add(boolean stdout, byte[] data, int size) {
            int count = Math.min(size, limit - kept);
            (stdout ? out : err).write(data, 0, count);
            kept += count;
            truncated |= count < size;
        }

        synchronized String text(boolean stdout) {
            return new String((stdout ? out : err).toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class Drain implements Runnable {
        private final InputStream input;
        private final Capture capture;
        private final boolean stdout;
        private final CountDownLatch header;
        volatile long pid;
        volatile IOException headerError;
        volatile boolean readError;

        Drain(InputStream input, Capture capture, boolean stdout, CountDownLatch header) {
            this.input = input;
            this.capture = capture;
            this.stdout = stdout;
            this.header = header;
        }

        @Override
        public void run() {
            try {
                if (header != null) {
                    readPid();
                    header.countDown();
                }
                byte[] data = new byte[4_096];
                int count;
                while ((count = input.read(data)) != -1) {
                    capture.add(stdout, data, count);
                }
            } catch (IOException error) {
                readError = true;
                if (header != null && pid == 0) {
                    headerError = error;
                }
            } finally {
                if (header != null) {
                    header.countDown();
                }
            }
        }

        private void readPid() throws IOException {
            long value = 0;
            for (int n = 0; n < 10; n++) {
                int c = input.read();
                if (c == '\n' && value > 1 && value <= Integer.MAX_VALUE) {
                    pid = value;
                    return;
                }
                if (c < '0' || c > '9') {
                    break;
                }
                value = value * 10 + c - '0';
            }
            throw new IOException("无效的进程组");
        }
    }
}
