package dev.phonestation.adbkeep;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Streamable HTTP 上的 JSON-RPC。工具失败写在结果里，不写成协议错误。 */
final class McpProtocol {
    private McpProtocol() {}

    static Reply handle(
            String body, String authorization, String token, FileOps files, StationHost host, String version) {
        if (!authorized(authorization, token)) {
            return new Reply(401, Json.obj().put("error", "需要 Authorization: Bearer").emit());
        }
        Json message;
        try {
            message = Json.parse(body);
        } catch (IllegalArgumentException error) {
            return rpcError(null, -32700, "JSON 无法解析");
        }
        Json id;
        try {
            id = message.get("id");
        } catch (IllegalArgumentException error) {
            return rpcError(null, -32600, "请求要是对象");
        }
        if (id == null || id.isNull()) {
            return new Reply(202, "");
        }
        String method;
        try {
            if (message.get("method") == null) {
                return rpcError(id, -32600, "请求不完整");
            }
            method = message.get("method").string();
        } catch (IllegalArgumentException error) {
            return rpcError(id, -32600, "请求不完整");
        }
        Json params = message.get("params");
        if (params != null && params.isNull()) {
            params = null;
        }
        try {
            if ("initialize".equals(method)) {
                return rpc(id, initialize(version));
            }
            if ("tools/list".equals(method)) {
                return rpc(id, Json.obj().put("tools", tools()));
            }
            if ("tools/call".equals(method)) {
                return rpc(id, call(params, files, host));
            }
            if ("ping".equals(method)) {
                return rpc(id, Json.obj());
            }
            return rpcError(id, -32601, "没有这个方法");
        } catch (IllegalArgumentException error) {
            return rpcError(id, -32602, error.getMessage());
        } catch (RuntimeException error) {
            error.printStackTrace();
            return rpcError(id, -32603, "内部错误");
        }
    }

    static boolean authorized(String header, String token) {
        if (header == null || token == null || token.isEmpty()) {
            return false;
        }
        String prefix = "Bearer ";
        if (header.length() != prefix.length() + token.length()) {
            return false;
        }
        if (!header.regionMatches(0, prefix, 0, prefix.length())) {
            return false;
        }
        byte[] got = header.substring(prefix.length()).getBytes(StandardCharsets.UTF_8);
        byte[] want = token.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(got, want);
    }

    private static Json initialize(String version) {
        return Json.obj()
                .put("protocolVersion", "2025-03-26")
                .put("capabilities", Json.obj().put("tools", Json.obj()))
                .put("serverInfo", Json.obj().put("name", "手机工位").put("version", version));
    }

    private static Json call(Json params, FileOps files, StationHost host) {
        if (params == null || params.get("name") == null) {
            throw new IllegalArgumentException("需要工具名");
        }
        String name = params.get("name").string();
        Json args = params.get("arguments");
        if (args == null || args.isNull()) {
            args = Json.obj();
        }
        try {
            return toolOk(dispatch(name, args, files, host));
        } catch (FileFailure error) {
            return toolError(error.getMessage());
        } catch (IllegalArgumentException error) {
            return toolError(error.getMessage());
        }
    }

    private static Json dispatch(String name, Json args, FileOps files, StationHost host) {
        if ("station_file_access_policy".equals(name)) {
            return files.policy();
        }
        if ("station_file_list".equals(name)) {
            return files.list(path(args));
        }
        if ("station_file_stat".equals(name)) {
            return files.stat(required(args, "path"));
        }
        if ("station_file_read_text".equals(name)) {
            return files.readText(
                    required(args, "path"),
                    optionalLong(args, "startLine", 1),
                    optionalInt(args, "limit", 200));
        }
        if ("station_file_read_bytes".equals(name)) {
            return files.readBytes(
                    required(args, "path"),
                    optionalLong(args, "offset", 0),
                    optionalInt(args, "length", FileOps.BYTE_PAGE));
        }
        if ("station_file_search".equals(name)) {
            return files.search(
                    path(args),
                    required(args, "name"),
                    optionalInt(args, "maxResults", 100));
        }
        if ("station_file_search_text".equals(name)) {
            return files.searchText(
                    required(args, "path"),
                    required(args, "query"),
                    optionalInt(args, "maxMatches", 20),
                    optionalInt(args, "context", 2));
        }
        if ("station_file_write_text".equals(name)) {
            return files.writeText(required(args, "path"), required(args, "text"), version(args));
        }
        if ("station_file_replace_text".equals(name)) {
            return files.replaceText(
                    required(args, "path"),
                    required(args, "find"),
                    required(args, "replace"),
                    version(args));
        }
        if ("station_file_append_text".equals(name)) {
            return files.appendText(required(args, "path"), required(args, "text"), version(args));
        }
        if ("station_file_write_bytes".equals(name)) {
            return files.writeBytes(required(args, "path"), required(args, "hex"), version(args));
        }
        if ("station_file_patch_bytes".equals(name)) {
            Json patches = args.get("patches");
            if (patches == null) {
                throw new IllegalArgumentException("需要 patches");
            }
            return files.patchBytes(required(args, "path"), patches.array(), version(args));
        }
        if ("station_file_append_bytes".equals(name)) {
            return files.appendBytes(required(args, "path"), required(args, "hex"), version(args));
        }
        if ("station_file_truncate_bytes".equals(name)) {
            Json size = args.get("size");
            if (size == null) {
                throw new IllegalArgumentException("需要 size");
            }
            return files.truncateBytes(required(args, "path"), size.longValue(), version(args));
        }
        if ("station_file_copy".equals(name)) {
            return files.copy(required(args, "from"), required(args, "to"));
        }
        if ("station_file_move".equals(name)) {
            return files.move(required(args, "from"), required(args, "to"));
        }
        if ("station_file_create_directory".equals(name)) {
            return files.createDirectory(required(args, "path"));
        }
        if ("station_file_delete".equals(name)) {
            return files.delete(required(args, "path"));
        }
        if ("station_file_delete_directory".equals(name)) {
            Json recursive = args.get("recursive");
            if (recursive == null) {
                throw new IllegalArgumentException("需要 recursive");
            }
            return files.deleteDirectory(required(args, "path"), recursive.boolValue());
        }
        if ("station_storage_summary".equals(name)) {
            return files.summary();
        }
        if ("station_device_status".equals(name)) {
            return host(host).status();
        }
        if ("station_stay_awake".equals(name)) {
            return host(host).stayAwake(requiredBool(args, "on"));
        }
        if ("station_notify".equals(name)) {
            String mode = optional(args, "mode");
            if (mode == null || mode.isEmpty()) {
                mode = "replace";
            }
            if (!"replace".equals(mode) && !"stack".equals(mode)) {
                throw new IllegalArgumentException("方式是 replace 或 stack");
            }
            String title = required(args, "title");
            String text = required(args, "text");
            if (title.length() > 200) {
                throw new IllegalArgumentException("标题太长");
            }
            if (text.length() > 4000) {
                throw new IllegalArgumentException("内容太长");
            }
            AlertNote note = AlertNote.parse(title, text, mode, optional(args, "agent"), 0, 0);
            if (note == null) {
                throw new IllegalArgumentException("标题和内容不能是空的");
            }
            return host(host).notify(note.title, note.text, note.stack, note.agent);
        }
        if ("station_clipboard_set".equals(name)) {
            String text = required(args, "text");
            if (text.isEmpty()) {
                throw new IllegalArgumentException("需要 text");
            }
            if (text.length() > 100000) {
                throw new IllegalArgumentException("文字太长");
            }
            return host(host).clipboard(text);
        }
        if ("station_file_open".equals(name)) {
            return host(host).open(required(args, "path"));
        }
        throw new IllegalArgumentException("没有这个工具");
    }

    private static StationHost host(StationHost host) {
        if (host == null) {
            throw new FileFailure("这台环境没有这项");
        }
        return host;
    }

    private static Json tools() {
        Json tools = Json.arr();
        tools.add(tool(
                "station_file_access_policy",
                "内部存储的根、可写范围，以及不开放的目录。动文件之前先调这个。",
                true,
                false,
                schema(new String[0], Json.obj())));
        tools.add(tool(
                "station_file_list",
                "列出一个目录的直接内容。path 空着表示内部存储根。不进入链接。",
                true,
                false,
                schema(new String[0], Json.obj().put("path", text("目录。空字符串表示根。")))));
        tools.add(tool(
                "station_file_stat",
                "类型、字节大小、修改时间，以及之后写入要用的 targetVersion。",
                true,
                false,
                schema(new String[] {"path"}, Json.obj().put("path", text("文件或目录。")))));
        tools.add(tool(
                "station_file_read_text",
                "按行读文本，换行原样保留。startLine 从 1 开始。太大的文件改用字节工具。",
                true,
                false,
                schema(new String[] {"path"}, Json.obj()
                        .put("path", text("文件。"))
                        .put("startLine", integer("从第几行开始，默认 1。", 1, 1000000000L))
                        .put("limit", integer("最多读多少行，默认 200，最大 2000。", 1, 2000)))));
        tools.add(tool(
                "station_file_read_bytes",
                "按偏移读原始字节，返回大写十六进制。一页最多 65536 字节。",
                true,
                false,
                schema(new String[] {"path"}, Json.obj()
                        .put("path", text("文件。"))
                        .put("offset", integer("起始字节，默认 0。", 0, Long.MAX_VALUE))
                        .put("length", integer("最多读多少字节，默认并最大 65536。", 1, FileOps.BYTE_PAGE)))));
        tools.add(tool(
                "station_file_search",
                "在目录下按文件名找普通文件。name 是区分大小写的子串。不进压缩包，不跟随链接。",
                true,
                false,
                schema(new String[] {"name"}, Json.obj()
                        .put("path", text("从哪个目录开始。空字符串表示根。"))
                        .put("name", text("文件名里要包含的文字。"))
                        .put("maxResults", integer("最多返回多少个，默认 100，最大 500。", 1, 500)))));
        tools.add(tool(
                "station_file_search_text",
                "在一个文本文件里按字面量查找，带回行号和前后文。不使用正则。",
                true,
                false,
                schema(new String[] {"path", "query"}, Json.obj()
                        .put("path", text("文件。"))
                        .put("query", text("要找的文字。"))
                        .put("maxMatches", integer("最多几处，默认 20，最大 100。", 1, 100))
                        .put("context", integer("前后各带几行，默认 2，最大 5。", 0, 5)))));
        tools.add(tool(
                "station_file_write_text",
                "把整个文件写成 UTF-8。新建时不要带 targetVersion；覆盖已有文件时必须带上一次读到的 targetVersion。",
                false,
                true,
                schema(new String[] {"path", "text"}, Json.obj()
                        .put("path", text("文件。上一级目录要已存在。"))
                        .put("text", text("新的全文。"))
                        .put("targetVersion", text("已有文件的 targetVersion。新建时不传。")))));
        tools.add(tool(
                "station_file_replace_text",
                "在整个原文上做不重叠的字面替换，按原来的编码写回。写进去的内容不会再被搜到。",
                false,
                true,
                schema(new String[] {"path", "find", "replace", "targetVersion"}, Json.obj()
                        .put("path", text("文件。"))
                        .put("find", text("被替换的文字。"))
                        .put("replace", text("替换成的文字。"))
                        .put("targetVersion", text("上一次读到的 targetVersion。")))));
        tools.add(tool(
                "station_file_append_text",
                "按文件现有编码在末尾追加，不转码，也不额外加换行。",
                false,
                true,
                schema(new String[] {"path", "text", "targetVersion"}, Json.obj()
                        .put("path", text("文件。"))
                        .put("text", text("追加的文字。"))
                        .put("targetVersion", text("上一次读到的 targetVersion。")))));
        tools.add(tool(
                "station_file_write_bytes",
                "用十六进制覆盖整个文件，或新建。十六进制用大小写均可，不要空格。",
                false,
                true,
                schema(new String[] {"path", "hex"}, Json.obj()
                        .put("path", text("文件。上一级目录要已存在。"))
                        .put("hex", text("文件的全部字节。"))
                        .put("targetVersion", text("已有文件的 targetVersion。新建时不传。")))));
        tools.add(tool(
                "station_file_patch_bytes",
                "对已有文件打 1 到 200 处等长补丁。每一处用同样长度的字节换掉偏移上的原字节。",
                false,
                true,
                schema(new String[] {"path", "patches", "targetVersion"}, Json.obj()
                        .put("path", text("文件。"))
                        .put("patches", Json.obj()
                                .put("type", "array")
                                .put("description", "1 到 200 处。每处有 offset 和等长的 hex。")
                                .put("items", Json.obj()
                                        .put("type", "object")
                                        .put("required", Json.arr().add(Json.str("offset")).add(Json.str("hex")))
                                        .put("properties", Json.obj()
                                                .put("offset", integer("起始字节。", 0, Long.MAX_VALUE))
                                                .put("hex", text("等长的新字节。")))))
                        .put("targetVersion", text("上一次读到的 targetVersion。")))));
        tools.add(tool(
                "station_file_append_bytes",
                "在文件末尾追加字节。hex 是大写或小写十六进制，不要空格。",
                false,
                true,
                schema(new String[] {"path", "hex", "targetVersion"}, Json.obj()
                        .put("path", text("文件。"))
                        .put("hex", text("追加的字节。"))
                        .put("targetVersion", text("上一次读到的 targetVersion。")))));
        tools.add(tool(
                "station_file_truncate_bytes",
                "保留开头的 size 个字节，丢掉尾部。不会把文件撑大。",
                false,
                true,
                schema(new String[] {"path", "size", "targetVersion"}, Json.obj()
                        .put("path", text("文件。"))
                        .put("size", integer("保留的字节数。", 0, Long.MAX_VALUE))
                        .put("targetVersion", text("上一次读到的 targetVersion。")))));
        tools.add(tool(
                "station_file_copy",
                "复制一个普通文件。目标的上一级目录要已存在，目标本身不能已存在。",
                false,
                false,
                schema(new String[] {"from", "to"}, Json.obj()
                        .put("from", text("源文件。"))
                        .put("to", text("新文件。")))));
        tools.add(tool(
                "station_file_move",
                "移动或改名。目标不能已存在。目录不会并进已有目录。",
                false,
                true,
                schema(new String[] {"from", "to"}, Json.obj()
                        .put("from", text("原来的文件或目录。"))
                        .put("to", text("新位置。")))));
        tools.add(tool(
                "station_file_create_directory",
                "创建目录。缺的上一级会一起建。已经是目录时不变。",
                false,
                false,
                schema(new String[] {"path"}, Json.obj().put("path", text("目录。")))));
        tools.add(tool(
                "station_file_delete",
                "永久删除一个普通文件，不进回收站。",
                false,
                true,
                schema(new String[] {"path"}, Json.obj().put("path", text("文件。")))));
        tools.add(tool(
                "station_file_delete_directory",
                "永久删除一个目录，不进回收站。recursive 为 true 时连里面的内容一起删。不能删内部存储根，也不能删 Android、Android/data、Android/obb 这三层。",
                false,
                true,
                schema(new String[] {"path", "recursive"}, Json.obj()
                        .put("path", text("目录。"))
                        .put("recursive", Json.obj()
                                .put("type", "boolean")
                                .put("description", "true 时删除其中的文件和子目录。")))));
        tools.add(tool(
                "station_storage_summary",
                "内部存储的剩余空间，以及截图、相机、下载、文档、电影、录音这几个目录各占多少。单个目录最多看 20000 项，超出时 truncated 为 true。",
                true,
                false,
                schema(new String[0], Json.obj())));
        tools.add(tool(
                "station_device_status",
                "只读：电量、是否在充电、响铃模式、Wi-Fi 是否连着、USB 调试和无线调试开关、息屏时间和充电时常亮，以及剩余空间。不读 Wi-Fi 名字。",
                true,
                false,
                schema(new String[0], Json.obj())));
        tools.add(tool(
                "station_stay_awake",
                "保持亮屏的开或关。on 为 true 时息屏设为 2147483647 毫秒，充电（交流电、USB、无线充）时不熄屏。false 时息屏恢复为 60 秒，并关掉充电时常亮。",
                false,
                false,
                schema(new String[] {"on"}, Json.obj().put("on", bool("true 打开，false 恢复。")))));
        tools.add(tool(
                "station_notify",
                "发一条会话提醒。下拉通知和铃声与 notify.sh 相同：通知本身不发声，铃声走媒体音量。mode 省略时是 replace，原地更新同一条。stack 另发一条。agent 只认 Grok、Claude、Codex。",
                false,
                false,
                schema(new String[] {"title", "text"}, Json.obj()
                        .put("title", text("标题。不能是空的，最多 200 字。"))
                        .put("text", text("内容。不能是空的，最多 4000 字。"))
                        .put("mode", text("replace 或 stack。省略时是 replace。"))
                        .put("agent", text("Grok、Claude 或 Codex。省略或认不出就没有右侧图标。")))));
        tools.add(tool(
                "station_clipboard_set",
                "把一段文字放进手机剪贴板，盖掉原来的内容。最多 100000 字。",
                false,
                true,
                schema(new String[] {"text"}, Json.obj().put("text", text("要放进去的文字。")))));
        tools.add(tool(
                "station_file_open",
                "用系统查看器打开一个已有的普通文件。打不开时在下拉栏留一条，点一下打开。不改文件。",
                false,
                false,
                schema(new String[] {"path"}, Json.obj().put("path", text("文件。")))));
        return tools;
    }

    private static Json tool(
            String name, String description, boolean readOnly, boolean destructive, Json inputSchema) {
        return Json.obj()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", inputSchema)
                .put("annotations", Json.obj()
                        .put("readOnlyHint", readOnly)
                        .put("destructiveHint", destructive));
    }

    private static Json schema(String[] required, Json properties) {
        Json schema = Json.obj().put("type", "object").put("properties", properties);
        if (required.length > 0) {
            Json names = Json.arr();
            for (int i = 0; i < required.length; i++) {
                names.add(Json.str(required[i]));
            }
            schema.put("required", names);
        }
        return schema;
    }

    private static Json text(String description) {
        return Json.obj().put("type", "string").put("description", description);
    }

    private static Json bool(String description) {
        return Json.obj().put("type", "boolean").put("description", description);
    }

    private static Json integer(String description, long min, long max) {
        return Json.obj()
                .put("type", "integer")
                .put("description", description)
                .put("minimum", min)
                .put("maximum", max);
    }

    private static Json toolOk(Json payload) {
        return Json.obj().put("content", Json.arr().add(textContent(payload.emit())));
    }

    private static Json toolError(String message) {
        return Json.obj()
                .put("content", Json.arr().add(textContent(Json.obj().put("error", message).emit())))
                .put("isError", true);
    }

    private static Json textContent(String text) {
        return Json.obj().put("type", "text").put("text", text);
    }

    private static String path(Json args) {
        String value = optional(args, "path");
        return value == null ? "" : value;
    }

    private static String required(Json args, String key) {
        String value = optional(args, key);
        if (value == null) {
            throw new IllegalArgumentException("需要 " + key);
        }
        return value;
    }

    private static String optional(Json args, String key) {
        Json value = args.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.string();
    }

    private static String version(Json args) {
        String value = optional(args, "targetVersion");
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value;
    }

    private static boolean requiredBool(Json args, String key) {
        Json value = args.get(key);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("需要 " + key);
        }
        return value.boolValue();
    }

    private static long optionalLong(Json args, String key, long fallback) {
        Json value = args.get(key);
        if (value == null || value.isNull()) {
            return fallback;
        }
        return value.longValue();
    }

    private static int optionalInt(Json args, String key, int fallback) {
        long value = optionalLong(args, key, fallback);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw new IllegalArgumentException(key + " 超出范围");
        }
        return (int) value;
    }

    private static Reply rpc(Json id, Json result) {
        return new Reply(200, Json.obj()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
                .emit());
    }

    private static Reply rpcError(Json id, int code, String message) {
        return new Reply(200, Json.obj()
                .put("jsonrpc", "2.0")
                .put("id", id == null ? Json.nul() : id)
                .put("error", Json.obj().put("code", code).put("message", message))
                .emit());
    }

    static final class Reply {
        final int status;
        final String body;

        Reply(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

}
