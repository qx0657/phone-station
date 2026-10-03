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
        if (message.isArray()) {
            java.util.List<Json> batch = message.array();
            if (batch.isEmpty() || batch.size() > 64) { return rpcError(null, -32600, "批量请求需要 1 到 64 项"); }
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Json item : batch) {
                if (item.isObject() && item.get("method") != null && validID(item.get("id"))
                        && !ids.add(normalizedID(item.get("id")))) {
                    return rpcError(null, -32600, "批量请求编号不能重复");
                }
            }
            Json replies = Json.arr();
            for (Json item : batch) {
                Reply reply = message(item, files, host, version);
                if (!reply.body.isEmpty()) { replies.add(Json.parse(reply.body)); }
            }
            return replies.array().isEmpty() ? new Reply(202, "") : new Reply(200, replies.emit());
        }
        return message(message, files, host, version);
    }

    private static boolean validID(Json id) { return id != null && (id.isString() || id.isInteger()); }

    private static String normalizedID(Json id) {
        String value = id.emit();
        return "-0".equals(value) ? "0" : value;
    }

    private static Reply message(Json message, FileOps files, StationHost host, String version) {
        if (!message.isObject()) { return rpcError(null, -32600, "请求要是对象"); }
        Json id = message.get("id");
        Json safeID = validID(id) ? id : null;
        Json rpcVersion = message.get("jsonrpc");
        if (rpcVersion == null || !rpcVersion.isString() || !"2.0".equals(rpcVersion.string())) {
            return rpcError(safeID, -32600, "需要 JSON-RPC 2.0");
        }
        Json methodValue = message.get("method");
        if (methodValue == null) {
            // 服务不发起请求，合法客户端应答只接受并忽略，不能解释成工具调用。
            if (!validID(id) || message.has("result") == message.has("error")) { return rpcError(safeID, -32600, "无效应答"); }
            Json error = message.get("error");
            if (error != null && (!error.isObject() || error.get("code") == null || !error.get("code").isInteger()
                    || error.get("message") == null || !error.get("message").isString())) { return rpcError(safeID, -32600, "无效错误应答"); }
            if (message.has("result") && !message.get("result").isObject()) { return rpcError(safeID, -32600, "应答结果要是对象"); }
            return new Reply(202, "");
        }
        if (!methodValue.isString() || methodValue.string().isEmpty() || message.has("result") || message.has("error")) {
            return rpcError(safeID, -32600, "请求不完整");
        }
        if (message.has("id") && !validID(id)) { return rpcError(null, -32600, "请求 id 需要字符串或整数，不能为 null"); }
        Json params = message.get("params");
        if (params != null && !params.isObject()) { return rpcError(safeID, -32602, "params 需要对象"); }
        if (!message.has("id")) { return new Reply(202, ""); }
        String method = methodValue.string();
        try {
            if ("initialize".equals(method)) {
                return rpc(id, initialize(version));
            }
            if ("tools/list".equals(method)) {
                return rpc(id, Json.obj().put("tools", tools()));
            }
            if ("tools/call".equals(method)) {
                // 文件事务跨通道串行；主机状态与后台任务查询不占文件锁。
                String name = params == null || params.get("name") == null ? "" : params.get("name").string();
                if (name.startsWith("station_file_") || name.equals("station_storage_summary")) {
                    synchronized (FileOps.TRANSACTIONS) { return rpc(id, call(params, files, host)); }
                }
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
        if ("station_shell_start".equals(name)) {
            return host(host).shellStart(required(args, "jobId"), new ShellRequest(required(args, "command"),
                    optionalInt(args, "timeoutMs", ShellRequest.DEFAULT_TIMEOUT_MS),
                    optionalInt(args, "maxOutputBytes", ShellRequest.DEFAULT_OUTPUT_BYTES)));
        }
        if ("station_screen_capture_start".equals(name)) { return host(host).captureStart(required(args, "jobId"), required(args, "requestId")); }
        if ("station_operation_status".equals(name)) { return host(host).operationStatus(required(args, "jobId")); }
        if ("station_controls_status".equals(name)) { return host(host).controlsStatus(); }
        if ("station_screen_capture".equals(name)) { return host(host).screenCapture(required(args, "requestId")); }
        if ("station_screen_capture_status".equals(name)) { return ScreenCapture.status(files, required(args, "requestId")); }
        if ("station_screen_capture_release".equals(name)) { return ScreenCapture.release(files, required(args, "requestId")); }
        if ("station_torch".equals(name)) { return host(host).torch(requiredBool(args, "on")); }
        if ("station_notification_status".equals(name)) { return host.notificationStatus(); }
        if ("station_notification_configure".equals(name)) {
            return host.notificationConfigure(Json.obj().put("enabled", requiredBool(args, "enabled")));
        }
        if ("station_notification_poll".equals(name)) { return host.notificationPoll(args); }
        if ("station_notification_icon".equals(name)) {
            Json pkg = args.get("packageName");
            return host.notificationIcon(pkg == null || pkg.isNull() ? "" : pkg.string());
        }
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
        if ("station_shell_status".equals(name)) {
            return host(host).shellStatus();
        }
        if ("station_shell_exec".equals(name)) {
            return host(host).shellExecute(new ShellRequest(required(args, "command"),
                    optionalInt(args, "timeoutMs", ShellRequest.DEFAULT_TIMEOUT_MS),
                    optionalInt(args, "maxOutputBytes", ShellRequest.DEFAULT_OUTPUT_BYTES)));
        }
        if ("station_stay_awake".equals(name)) {
            return host(host).stayAwake(requiredBool(args, "on"));
        }
        if ("station_notify".equals(name)) {
            String title = required(args, "title");
            String text = required(args, "text");
            if (title.length() > 200) {
                throw new IllegalArgumentException("标题太长");
            }
            if (text.length() > 4000) {
                throw new IllegalArgumentException("内容太长");
            }
            if (title.isEmpty() || text.isEmpty()) {
                throw new IllegalArgumentException("标题和内容不能是空的");
            }
            // 旧客户端传来的 mode 不再决定显示方式，统一由手机设置决定。
            return host(host).notify(title, text, AlertNote.knownAgent(optional(args, "agent")),
                    optional(args, "sound"));
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
        if ("station_clipboard_get".equals(name)) { return host(host).clipboardGet(); }
        if ("station_clipboard_state".equals(name)) { return host(host).clipboardState(args); }
        if ("station_clipboard_configure".equals(name)) { return host(host).clipboardConfigure(args); }
        if ("station_clipboard_exchange".equals(name)) { return host(host).clipboardExchange(args); }
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
                "只读：连接类型、电量、是否在充电、响铃模式、Wi-Fi 是否连着、USB 调试和无线调试开关、息屏时间和充电时常亮，以及剩余空间。不读 Wi-Fi 名字。",
                true,
                false,
                schema(new String[0], Json.obj())));
        tools.add(tool(
                "station_stay_awake",
                "保持亮屏的开或关。on 为 true 时息屏设为 2147483647 毫秒，充电（交流电、USB、无线充）时不熄屏。false 恢复开启前保存的设置，保留用户中途手动改过的值；旧版无记录常亮回退到 60 秒/充电掩码 0。",
                false,
                false,
                schema(new String[] {"on"}, Json.obj().put("on", bool("true 打开，false 恢复。")))));
        tools.add(tool("station_controls_status",
                "只读：已验证机型、保持亮屏和手电筒实际状态，以及远程截屏、亮屏和手电筒是否可用。可能连接只读的 Shizuku 手电筒状态服务，不开灯、不截屏、不申请权限。",
                true, false, schema(new String[0], Json.obj())));
        tools.add(tool("station_screen_capture",
                "通过已运行且授权的 Shizuku 截取当前画面，返回临时 PNG 路径、大小、targetVersion 与 SHA-256。仅在用户要求截屏时调用；安全界面仍可能是黑屏。requestId 为新的 UUID，已有编号拒绝重拍。文件最多 32 MiB，用文件读取工具下载后调用 release；结果未知时先按该编号核实文件，不重拍。",
                false, false, schema(new String[] {"requestId"}, Json.obj().put("requestId", text("本次截图的 UUID，不得重用。")))));
        tools.add(tool("station_screen_capture_status", "按原截图 UUID 查询已有 PNG，用于应答丢失后的恢复，不重新截屏。",
                true, false, schema(new String[] {"requestId"}, Json.obj().put("requestId", text("原截图 UUID。")))));
        tools.add(tool("station_screen_capture_release",
                "删除本次 UUID 对应的临时截图，不进回收站。只用于下载完成后清理本次截图。",
                false, true, schema(new String[] {"requestId"}, Json.obj().put("requestId", text("已下载截图的 UUID。")))));
        tools.add(tool("station_torch",
                "通过已授权的 Shizuku 持续控制手电筒，返回实际状态。on 为 true 开灯，false 关灯。仅在用户要求时调用。手机工位或 Shizuku 停止、升级后灯会关闭，不自动重开；断线后只核实状态，不重放开关。",
                false, false, schema(new String[] {"on"}, Json.obj().put("on", bool("true 开灯，false 关灯。")))));
        tools.add(tool(
                "station_shell_status",
                "只读：Shizuku 是否安装、运行及授权，shell/root 身份和不可用时的处理方法。不启动服务，不弹授权框。",
                true,
                false,
                schema(new String[0], Json.obj())));
        tools.add(tool("station_shell_start", "提交 Shizuku shell 后台任务。jobId 必须新建并预先保存；同编号同参数去重，不同参数拒绝。丢失应答后只查询原编号，不重做。",
                false, true, schema(new String[] {"jobId", "command"}, Json.obj()
                        .put("jobId", text("32 位小写十六进制唯一编号。"))
                        .put("command", text("用户要求执行的手机 shell 命令。"))
                        .put("timeoutMs", integer("超时毫秒数。", 100, 60000))
                        .put("maxOutputBytes", integer("总输出保留上限。", 1, 65536)))));
        tools.add(tool("station_screen_capture_start", "提交一次截图后台任务，立即返回任务回执，不等待 screencap。丢失应答后只查询原编号或原截图，不重拍。",
                false, false, schema(new String[] {"jobId", "requestId"}, Json.obj()
                        .put("jobId", text("32 位小写十六进制唯一任务编号。"))
                        .put("requestId", text("截图 UUID。")))));
        tools.add(tool("station_operation_status", "只读查询原任务状态与短期结果。应用重起后未完成任务为结果未知，不重新执行。",
                true, false, schema(new String[] {"jobId"}, Json.obj().put("jobId", text("原任务编号。")))));
        tools.add(tool(
                "station_shell_exec",
                "通过已授权的 Shizuku 13+ 执行手机 shell 命令，本地和远程均可用。"
                        + "可修改系统或删除文件；只执行用户要求的操作。"
                        + "返回 stdout、stderr、exitCode、timedOut、outputTruncated、uid 和 identity。"
                        + "默认 10 秒，最多 60 秒；输出按 UTF-8 返回，总共最多保留 65536 字节。"
                        + "无交互输入；结束或超时会清理同一进程组，不保留后台任务。"
                        + "超时或连接中断时操作可能已生效，不要自动重做。",
                false,
                true,
                schema(new String[] {"command"}, Json.obj()
                        .put("command", text("交给 /system/bin/sh -c 的命令，最多 16384 字。"))
                        .put("timeoutMs", integer("超时毫秒数；默认 10000。", 100, 60000))
                        .put("maxOutputBytes", integer("stdout 和 stderr 合计保留的字节数；默认 32768。", 1, 65536)))));
        tools.add(tool(
                "station_notify",
                "发一条会话提醒。显示最新一条或逐条保留，由手机工位的设置 → 通知 → 显示方式决定。通知本身不发声，铃声走媒体音量。agent 只认 Grok、Claude、Codex。",
                false,
                false,
                schema(new String[] {"title", "text"}, Json.obj()
                        .put("title", text("标题。不能是空的，最多 200 字。"))
                        .put("text", text("内容。不能是空的，最多 4000 字。"))
                        .put("agent", text("Grok、Claude 或 Codex。省略或认不出时左侧仍是手机工位。"))
                        .put("sound", text("可选的手机铃声文件路径或 content URI；省略时沿用应用里的铃声选择。")))));
        tools.add(tool(
                "station_clipboard_set",
                "把一段文字放进手机剪贴板，盖掉原来的内容。最多 100000 字。",
                false,
                true,
                schema(new String[] {"text"}, Json.obj().put("text", text("要放进去的文字。")))));
        tools.add(tool("station_clipboard_get", "读取手机当前文字剪贴板。后台读取需要已启动并授权的 Shizuku；敏感内容与锁屏时不返回文字。",
                true, false, schema(new String[] {}, Json.obj())));
        tools.add(tool("station_clipboard_state", "读取共享设置、两端预览和 Mac 在线状态。refresh 为 true 时只读刷新手机当前内容，不更新 Mac 版本也不写剪贴板。",
                true, false, schema(new String[] {}, Json.obj().put("refresh", bool("只读刷新手机当前内容。")))));
        tools.add(tool("station_clipboard_configure", "开启或关闭剪贴板共享及自动双向同步。关闭共享会清除内存预览并停止后台剪贴板服务。",
                false, false, schema(new String[] {}, Json.obj().put("shared", bool("是否共享。"))
                        .put("automatic", bool("是否自动双向同步。"))
                        .put("images", bool("是否将新复制的 Mac 图片保存到手机相册，默认关闭。")))));
        tools.add(tool("station_clipboard_exchange", "手机工位 Mac 客户端的剪贴板交换：共享关闭时不读；自动同步开启且版本一致时可写手机剪贴板。首次连接只建立基线。",
                false, true, schema(new String[] {}, Json.obj()
                        .put("clientId", text("Mac 本次运行的客户端标识。"))
                        .put("macVersion", text("Mac 剪贴板变化标识。"))
                        .put("macKind", text("text、image、empty、unsupported、sensitive、oversize。"))
                        .put("macText", text("macKind 为 text 时的文字，最多 100000 字。"))
                        .put("macImage", text("macKind 为 image 时可提供 PNG Base64，最多 4 MB。仅图片同步开启且新复制时保存到相册；不回传图片。"))
                        .put("phoneVersion", text("上次收到的手机版本，首次连接或长时间断线时省略。"))
                        .put("resume", bool("只读核对后恢复 30 秒内的新复制；未知结果的旧请求不得重放。")))));
        tools.add(tool(
                "station_file_open",
                "用系统查看器打开一个已有的普通文件。打不开时在下拉栏留一条，点一下打开。不改文件。",
                false,
                false,
                schema(new String[] {"path"}, Json.obj().put("path", text("文件。")))));
        tools.add(tool("station_notification_status", "只读：手机通知同步开关、通知使用权、监听状态、已选应用数量和 Mac 在线状态。不返回通知内容，也不建立接收会话。",
                true, false, schema(new String[] {}, Json.obj())));
        tools.add(tool("station_notification_icon", "只读：返回已选择应用的 96 像素 PNG 图标，不读取通知正文。需要已开启同步并授予通知使用权；未选择的应用返回 available: false。省略包名时返回第一个已选应用，供横幅预览。",
                true, false, schema(new String[] {}, Json.obj().put("packageName", text("手机端已选择的应用包名；预览时可省略。")))));
        tools.add(tool("station_notification_configure", "开启或关闭手机通知到 Mac 的同步。应用白名单只能在手机设置中选择；不授予系统通知使用权。关闭或改变选择会清除待收内容。",
                false, false, schema(new String[] {"enabled"}, Json.obj().put("enabled", bool("是否同步手机通知。")))));
        tools.add(tool("station_notification_poll", "Mac 客户端接收已选应用的新通知。只返回在线接收会话中的短期内容；首次连接、断线和设置变化只建立基线，不补发旧通知。会更新接收会话，不作为只读探测，也不自动重放。",
                false, false, schema(new String[] {"clientId"}, Json.obj()
                        .put("clientId", text("Mac 本次运行的客户端标识，最多 128 字。"))
                        .put("cursor", text("上次成功处理的 cursor；首次或断线后省略。")))));
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
