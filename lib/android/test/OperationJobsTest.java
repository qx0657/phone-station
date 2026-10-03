package dev.phonestation.adbkeep;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class OperationJobsTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("operation-jobs");
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        OperationJobs jobs = new OperationJobs(root, clock::get);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        String id = "a".repeat(32), queuedID = "b".repeat(32);
        Json input = Json.obj().put("command", "test");
        jobs.start(id, "shell", input, () -> {
            calls.incrementAndGet(); entered.countDown(); release.await(2, TimeUnit.SECONDS);
            return Json.obj().put("stdout", "done");
        });
        if (!entered.await(1, TimeUnit.SECONDS)) { throw new AssertionError("not started"); }
        for (int i = 0; i < 30; i++) {
            jobs.start(id, "shell", input, () -> { throw new AssertionError("replayed"); });
        }
        expect(calls.get() == 1);
        expect(jobs.status(id).get("state").string().equals("running"));
        // A new process has only persisted facts. It cannot execute an old request on query or resubmit.
        OperationJobs restarted = new OperationJobs(root);
        expect(restarted.status(id).get("state").string().equals("result_unknown"));
        expect(restarted.start(id, "shell", input, () -> { throw new AssertionError("restarted replay"); })
                .get("state").string().equals("result_unknown"));
        restarted.close();
        try { jobs.start(id, "shell", Json.obj().put("command", "different"), () -> Json.obj()); throw new AssertionError(); }
        catch (FileFailure expected) {}
        jobs.start(queuedID, "shell", input, () -> { throw new AssertionError("expired mutation ran"); });
        clock.addAndGet(OperationJobs.QUEUE_MS + 1);
        expect(jobs.status(queuedID).get("state").string().equals("expired"));
        release.countDown();
        for (int i = 0; i < 100 && !jobs.status(id).get("state").string().equals("completed"); i++) { Thread.sleep(5); }
        expect(jobs.status(id).get("result").get("stdout").string().equals("done"));
        jobs.close();
        OperationJobs restored = new OperationJobs(root);
        expect(restored.status(id).get("state").string().equals("completed"));
        expect(restored.status(queuedID).get("state").string().equals("expired"));
        expect(Files.getPosixFilePermissions(root.resolve(id + ".json")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        expect(!Files.readString(root.resolve(id + ".json")).contains("command"));
        restored.close();
        System.out.println("OperationJobsTest ok");
    }
    private static void expect(boolean value) { if (!value) { throw new AssertionError(); } }
}
