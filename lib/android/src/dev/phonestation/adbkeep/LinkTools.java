package dev.phonestation.adbkeep;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** 现有工具名和链路方法的对应。方法名是 group.action，不再带 station_ 前缀。 */
final class LinkTools {
    private static final Map<String, String> TOOLS = new HashMap<>();

    static {
        for (String tool : McpProtocol.toolNames()) {
            String method = method(tool);
            if (method == null || TOOLS.put(method, tool) != null) {
                throw new IllegalStateException(tool);
            }
        }
    }

    private LinkTools() {}

    static String method(String tool) {
        if (tool == null || !tool.startsWith("station_")) {
            return null;
        }
        String rest = tool.substring("station_".length());
        int split = rest.indexOf('_');
        if (split < 0) {
            return rest.matches("[a-z]+") ? rest + ".call" : null;
        }
        if (split == 0 || split == rest.length() - 1) {
            return null;
        }
        String group = rest.substring(0, split);
        String action = rest.substring(split + 1).replace("_", "");
        if (!group.matches("[a-z]+") || !action.matches("[a-z]+")) {
            return null;
        }
        return group + "." + action;
    }

    static byte[] exec(String method, byte[] params, FileOps files, StationHost host, String version) {
        try {
            if ("meta.ping".equals(method)) {
                return "{}".getBytes(StandardCharsets.UTF_8);
            }
            if ("meta.initialize".equals(method)) {
                return McpProtocol.linkInitialize(version);
            }
            if ("meta.tools".equals(method)) {
                return McpProtocol.linkTools(host);
            }
            String tool = TOOLS.get(method);
            if (tool == null) {
                return failed("没有这个方法");
            }
            return McpProtocol.invokeLink(tool, params, files, host);
        } catch (Exception error) {
            String message = error.getMessage();
            return failed(message == null || message.isEmpty() ? "没有这个方法" : message);
        }
    }

    private static byte[] failed(String message) {
        return Json.obj()
                .put("content", Json.arr().add(Json.obj().put("type", "text").put("text", message)))
                .put("isError", true)
                .emit()
                .getBytes(StandardCharsets.UTF_8);
    }
}
