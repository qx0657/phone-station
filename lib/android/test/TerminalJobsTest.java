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
        // Revoking a connection while an open is queued must prevent binding.
        CountDownLatch occupied = new CountDownLatch(1), unblock = new CountDownLatch(1);
        jobs.start("d".repeat(32), "shell", Json.obj(), () -> { occupied.countDown(); unblock.await(2, TimeUnit.SECONDS); return Json.obj(); });
        expect(occupied.await(1, TimeUnit.SECONDS));
        java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        String revoked = "e".repeat(32);
        terminals.open(revoked, 80, 24, () -> { if (!allowed.get()) { throw new FileFailure("远程权限已撤销"); } });
        allowed.set(false); unblock.countDown();
        for (int i = 0; i < 300 && !jobs.status(revoked).get("state").string().equals("result_unknown"); i++) { Thread.sleep(5); }
        expect(jobs.status(revoked).get("state").string().equals("result_unknown"));
        expect(starts.get() == 1);
        // The saved ID stays consumed after permissions are restored.
        allowed.set(true); terminals.open(revoked, 80, 24); expect(starts.get() == 1);
        String direct = "f".repeat(32);
        AtomicInteger directStarts = new AtomicInteger();
        TerminalJobs linked = new TerminalJobs(jobs, (request, start) -> {
            if (start) { directStarts.incrementAndGet(); }
            return TerminalJobs.ended(request.get("sessionId").string(), "running", "");
        });
        expect(linked.openNow(direct, 80, 24, () -> {}).get("state").string().equals("running"));
        expect(jobs.status(direct).get("state").string().equals("missing"));
        expect(directStarts.get() == 1);
        expect(linked.readNow(direct, 0).get("state").string().equals("running"));
        expect(linked.inputNow(direct, 1, "03").get("state").string().equals("running"));
        TerminalJobs afterRestart = new TerminalJobs(jobs, (request, start) -> { throw new AssertionError("restarted"); });
        expect(afterRestart.readNow(direct, 0).get("state").string().equals("lost"));
        expect(linked.closeNow(direct).get("state").string().equals("running"));
        expect(linked.readNow(direct, 0).get("state").string().equals("lost"));
        testReadback(jobs);
        jobs.close();
        System.out.println("TerminalJobsTest ok");
    }
    private static void testReadback(OperationJobs jobs) {
        AtomicInteger inputs = new AtomicInteger(), reads = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean delayed = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean lost = new java.util.concurrent.atomic.AtomicBoolean(false);
        TerminalJobs terminal = new TerminalJobs(jobs, (request, start) -> {
            String op = request.get("op").string();
            if (op.equals("input")) {
                inputs.incrementAndGet();
                if (lost.get()) { return TerminalJobs.ended("a".repeat(32), "lost", ""); }
            }
            long offset = request.get("offset") == null ? 0 : request.get("offset").longValue();
            boolean empty = op.equals("read") && reads.incrementAndGet() == 1 && delayed.get();
            return TerminalJobs.ended("a".repeat(32), "running", "").put("nextInputSequence", inputs.get())
                    .put("offset", offset).put("nextOffset", empty ? offset : offset + 1).put("hex", empty ? "" : "61");
        });
        String id = "a".repeat(32);
        expect(terminal.openNow(id, 80, 24, () -> {}).get("inputReadback").boolValue());
        Json reply = terminal.inputReadNow(id, 0, "61", 7);
        expect(reply.get("inputReadback").boolValue() && reply.get("offset").longValue() == 7);
        expect(reply.get("hex").string().equals("61") && inputs.get() == 1 && reads.get() == 1);
        delayed.set(true); reads.set(0);
        terminal.inputReadNow(id, 1, "61", 8);
        expect(inputs.get() == 2 && reads.get() == 2);
        lost.set(true); reads.set(0);
        expect(terminal.inputReadNow(id, 2, "61", 9).get("state").string().equals("lost"));
        expect(inputs.get() == 3 && reads.get() == 0);
        try { terminal.inputReadNow(id, 3, "61", -1); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { expect(inputs.get() == 3); }
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
