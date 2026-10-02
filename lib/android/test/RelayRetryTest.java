package dev.phonestation.adbkeep;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class RelayRetryTest {
    public static void main(String[] args) throws Exception {
        RelayRetry retry = new RelayRetry();
        long old = retry.revision();
        retry.changed();
        long start = System.nanoTime();
        retry.pause(30_000L, old);
        if (System.nanoTime() - start > TimeUnit.SECONDS.toNanos(1)) {
            throw new AssertionError("network change before backoff was lost");
        }
        CountDownLatch waiting = new CountDownLatch(1);
        long current = retry.revision();
        Thread worker = new Thread(() -> {
            waiting.countDown();
            retry.pause(30_000L, current);
        });
        worker.start();
        if (!waiting.await(1L, TimeUnit.SECONDS)) { throw new AssertionError("worker not started"); }
        retry.changed();
        worker.join(1_000L);
        if (worker.isAlive()) { worker.interrupt(); throw new AssertionError("network change did not wake backoff"); }
        start = System.nanoTime();
        retry.pause(30L, retry.revision());
        if (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(20)) {
            throw new AssertionError("unchanged failed network must still back off");
        }
        System.out.println("RelayRetryTest ok");
    }
}
