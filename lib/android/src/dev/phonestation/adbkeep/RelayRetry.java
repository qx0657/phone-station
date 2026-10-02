package dev.phonestation.adbkeep;

/** Network changes wake a retry even when they arrive just before the wait starts. */
final class RelayRetry {
    private long revision;

    synchronized long revision() {
        return revision;
    }

    synchronized void changed() {
        revision++;
        notifyAll();
    }

    synchronized void pause(long milliseconds, long attemptedRevision) {
        long deadline = System.nanoTime() + milliseconds * 1_000_000L;
        while (revision == attemptedRevision) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            try {
                wait(Math.max(1L, remaining / 1_000_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
