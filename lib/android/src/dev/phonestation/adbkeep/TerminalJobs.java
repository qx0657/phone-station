package dev.phonestation.adbkeep;

import java.util.HashSet;
import java.util.Set;

/** Durable creation receipt plus a live backend. Reads never bind/start a service. */
final class TerminalJobs {
    interface Backend { Json call(Json request, boolean start); }
    private final OperationJobs jobs;
    private final Backend backend;
    private final Set<String> cancelled = new HashSet<>();

    TerminalJobs(OperationJobs jobs, Backend backend) { this.jobs = jobs; this.backend = backend; }

    Json open(String id, int columns, int rows) {
        OperationJobs.validate(id); dimensions(columns, rows);
        return jobs.start(id, "terminal", Json.obj().put("columns", columns).put("rows", rows), () -> {
            synchronized (this) {
                if (cancelled.contains(id)) { return ended(id, "closed", "会话已关闭"); }
                return backend.call(request("open", id).put("columns", columns).put("rows", rows), true);
            }
        });
    }

    synchronized Json read(String id, long offset) {
        if (offset < 0) { throw new IllegalArgumentException("offset 不能为负数"); }
        Json receipt = receipt(id);
        String state = receipt.get("state").string();
        if (state.equals("queued") || state.equals("running")) { return ended(id, "starting", "正在创建终端"); }
        if (state.equals("completed")) {
            Json result = receipt.get("result");
            if (result != null && !result.get("state").string().equals("running")) { return result; }
            return backend.call(request("read", id).put("offset", offset), false);
        }
        return ended(id, "lost", "原会话未确认或已结束，不会重新创建");
    }

    synchronized Json input(String id, long sequence, String hex) {
        if (sequence < 0 || hex == null || hex.isEmpty() || hex.length() > 8192 || !hex.matches("(?:[0-9a-fA-F]{2})+")) {
            throw new IllegalArgumentException("输入需要非负序号和 1–4096 字节的十六进制");
        }
        requireCreated(id);
        return backend.call(request("input", id).put("sequence", sequence).put("hex", hex), false);
    }

    synchronized Json resize(String id, int columns, int rows) {
        dimensions(columns, rows); requireCreated(id);
        return backend.call(request("resize", id).put("columns", columns).put("rows", rows), false);
    }

    synchronized Json close(String id) {
        Json receipt = receipt(id);
        if (receipt.get("kind") == null) { return ended(id, "lost", "原会话已不存在"); }
        cancelled.add(id);
        return backend.call(request("close", id), false);
    }

    private void requireCreated(String id) {
        Json receipt = receipt(id);
        if (!receipt.get("state").string().equals("completed") || receipt.get("result") == null
                || !receipt.get("result").get("state").string().equals("running")) {
            throw new FileFailure("原终端尚未确认或已结束，请查询同一会话");
        }
    }
    private Json receipt(String id) {
        Json receipt = jobs.status(id);
        if (receipt.get("kind") != null && !receipt.get("kind").string().equals("terminal")) {
            throw new FileFailure("这个编号不是终端会话");
        }
        return receipt;
    }
    static Json ended(String id, String state, String reason) {
        return Json.obj().put("sessionId", id).put("state", state).put("reason", reason);
    }
    static Json request(String op, String id) { return Json.obj().put("op", op).put("sessionId", id); }
    static void dimensions(int columns, int rows) {
        if (columns < 20 || columns > 500 || rows < 5 || rows > 200) { throw new IllegalArgumentException("终端尺寸超出范围"); }
    }
}
