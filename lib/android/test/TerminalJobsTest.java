package dev.phonestation.adbkeep;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class TerminalJobsTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("terminal-jobs");
        OperationJobs jobs = new OperationJobs(root);
        AtomicInteger starts = new AtomicInteger(), reads = new AtomicInteger();
        TerminalJobs terminals = new TerminalJobs(jobs, (request, start) -> {
            if (start) { starts.incrementAndGet(); } else { reads.incrementAndGet(); }
            return TerminalJobs.ended(request.get("sessionId").string(), request.get("op").string().equals("close") ? "closed" : "running", "");
        });
        String id = "a".repeat(32);
        expect(terminals.read(id, 0).get("state").string().equals("lost"));
        expect(starts.get() == 0 && reads.get() == 0);
        terminals.open(id, 80, 24); await(jobs, id);
        terminals.open(id, 80, 24); expect(starts.get() == 1);
        terminals.read(id, 0); terminals.input(id, 0, "03"); terminals.resize(id, 100, 30);
        expect(reads.get() == 3 && starts.get() == 1);
        try { terminals.open(id, 81, 24); throw new AssertionError("changed ID accepted"); } catch (FileFailure expected) {}
        try { terminals.input(id, -1, "03"); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
        try { terminals.input(id, 1, "zz"); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
        // Reopening the application cannot recreate a PTY from a persisted receipt.
        OperationJobs restored = new OperationJobs(root);
        TerminalJobs restarted = new TerminalJobs(restored, (request, start) -> {
            expect(!start); return TerminalJobs.ended(request.get("sessionId").string(), "lost", "");
        });
        restarted.open(id, 80, 24);
        expect(restarted.read(id, 0).get("state").string().equals("lost"));
        restored.close();
        // A close received while creation is queued prevents that later creation.
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        jobs.start("b".repeat(32), "shell", Json.obj(), () -> { entered.countDown(); release.await(2, TimeUnit.SECONDS); return Json.obj(); });
        expect(entered.await(1, TimeUnit.SECONDS));
        String queued = "c".repeat(32);
        terminals.open(queued, 80, 24);
        expect(terminals.read(queued, 0).get("state").string().equals("starting"));
        terminals.close(queued); release.countDown(); await(jobs, queued);
        expect(starts.get() == 1 && terminals.read(queued, 0).get("state").string().equals("closed"));
        jobs.close();
        System.out.println("TerminalJobsTest ok");
    }
    private static void await(OperationJobs jobs, String id) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (jobs.status(id).get("state").string().equals("completed")) { return; }
            Thread.sleep(5);
        }
        throw new AssertionError("creation did not complete");
    }
    private static void expect(boolean value) { if (!value) { throw new AssertionError(); } }
}
