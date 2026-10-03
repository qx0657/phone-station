package dev.phonestation.adbkeep;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 内部存储上的普通文件。请求串行处理。删除不进回收站，也不跟随链接。 */
final class FileOps {
    // 所有通道、所有 FileOps 实例共享事务锁；版本校验和落盘不能分开。
    static final Object TRANSACTIONS = new Object();
    static final int BYTE_PAGE = 65536;
    static final long TEXT_LIMIT = 8L * 1024L * 1024L;
    private static final int SEARCH_VISITS = 20000;
    private static final int SCAN_LIMIT = 64;
    private static final int LINE_CLIP = 500;
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    /** 写入、删除、移动或复制之后，把变更过的路径交给媒体库。 */
    interface MediaNotice {
        void changed(List<String> paths);

        MediaNotice NONE = new MediaNotice() {
            @Override
            public void changed(List<String> paths) {}
        };
    }

    private final FilePolicy policy;
    private MediaNotice media = MediaNotice.NONE;

    FileOps(Path home, String... denyRelative) {
        policy = new FilePolicy(home, denyRelative);
    }

    void setMediaNotice(MediaNotice notice) {
        media = notice == null ? MediaNotice.NONE : notice;
    }

    /** 给打开文件用。必须是普通文件，并且过路径规则。 */
    Path openable(String input) {
        return regular(input);
    }

    /** 和清单里的包名一致。换包名时这两处目录名要一起改。 */
    static FileOps device() {
        return new FileOps(
                Paths.get("/storage/emulated/0"),
                "Android/data/dev.phonestation.adbkeep",
                "Android/obb/dev.phonestation.adbkeep");
    }

    Json policy() {
        Json rules = Json.arr().add(Json.obj().put("path", "/").put("access", "read_write"));
        Json denied = Json.arr();
        List<String> paths = policy.deniedPaths();
        for (int i = 0; i < paths.size(); i++) {
            denied.add(Json.str(paths.get(i)));
        }
        Json limits = Json.arr();
        limits.add(Json.str("只开放内部存储。别的应用的私有目录不在这里。"));
        limits.add(Json.str("别的应用的 Android/data 和 Android/obb，系统可能直接拒绝。"));
        limits.add(Json.str("不跟随链接。删除不进回收站。"));
        limits.add(Json.str("写入、删除、移动和复制之后会请媒体库扫描变更的路径。"));
        limits.add(Json.str("交接目录不会自动创建。"));
        return Json.obj()
                .put("home", policy.home.toString())
                .put("rules", rules)
                .put("denied", denied)
                .put("places", places())
                .put("handoff", Json.obj()
                        .put("inbox", place(StationPlaces.INBOX))
                        .put("outbox", place(StationPlaces.OUTBOX)))
                .put("limits", limits);
    }

    Json summary() {
        Json volume = volume();
        Json rows = Json.arr();
        StationPlaces[] known = StationPlaces.standard();
        for (int i = 0; i < known.length; i++) {
            rows.add(measure(known[i]));
        }
        return Json.obj()
                .put("home", policy.home.toString())
                .put("freeBytes", volume.get("freeBytes").longValue())
                .put("totalBytes", volume.get("totalBytes").longValue())
                .put("places", rows);
    }

    Json volume() {
        File store = policy.home.toFile();
        long total = store.getTotalSpace();
        long free = store.getUsableSpace();
        if (total <= 0) {
            throw new FileFailure("读不了剩余空间");
        }
        return Json.obj().put("freeBytes", free).put("totalBytes", total);
    }

    Json list(String input) {
        Path path = directory(input);
        List<Row> rows = new ArrayList<Row>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path child : stream) {
                rows.add(row(child));
            }
        } catch (AccessDeniedException error) {
            throw new FileFailure("系统不允许访问这个路径");
        } catch (IOException error) {
            throw io(error);
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row left, Row right) {
                return left.name.compareTo(right.name);
            }
        });
        Json entries = Json.arr();
        for (int i = 0; i < rows.size(); i++) {
            entries.add(rows.get(i).json);
        }
        return Json.obj().put("path", path.toString()).put("entries", entries);
    }

    Json stat(String input) {
        Path path = resolve(input);
        return described(path);
    }

    Json readText(String input, long startLine, int limit) {
        if (startLine < 1) {
            throw new FileFailure("startLine 从 1 开始");
        }
        if (limit < 1 || limit > 2000) {
            throw new FileFailure("limit 要在 1 和 2000 之间");
        }
        Path path = regular(input);
        Decoded decoded = decode(readLimited(path));
        String text = decoded.text;
        int index = 0;
        long line = 1;
        while (line < startLine && index < text.length()) {
            int newline = text.indexOf('\n', index);
            if (newline < 0) {
                index = text.length();
                break;
            }
            index = newline + 1;
            line++;
        }
        int taken = 0;
        int end = index;
        while (taken < limit && end < text.length()) {
            int newline = text.indexOf('\n', end);
            if (newline < 0) {
                end = text.length();
                taken++;
                break;
            }
            end = newline + 1;
            taken++;
        }
        Json out = described(path);
        out.put("encoding", decoded.charset.name());
        out.put("startLine", startLine);
        out.put("text", text.substring(index, end));
        if (end < text.length()) {
            out.put("nextLine", startLine + taken);
        }
        return out;
    }

    Json readBytes(String input, long offset, int length) {
        if (offset < 0) {
            throw new FileFailure("offset 不能是负数");
        }
        if (length < 1 || length > BYTE_PAGE) {
            throw new FileFailure("length 要在 1 和 65536 之间");
        }
        Path path = regular(input);
        BasicFileAttributes attrs = attributes(path);
        if (offset > attrs.size()) {
            throw new FileFailure("偏移超出文件");
        }
        int want = (int) Math.min(length, attrs.size() - offset);
        byte[] data = new byte[want];
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            data = Arrays.copyOf(data, data.length - buffer.remaining());
        } catch (IOException error) {
            throw io(error);
        }
        Json out = described(path);
        out.put("offset", offset);
        out.put("length", data.length);
        out.put("hex", toHex(data));
        return out;
    }

    Json search(String input, String name, int maxResults) {
        if (name == null || name.isEmpty()) {
            throw new FileFailure("要给出文件名里的一段文字");
        }
        if (maxResults < 1 || maxResults > 500) {
            throw new FileFailure("maxResults 要在 1 和 500 之间");
        }
        final Path start = directory(input);
        final List<String> matches = new ArrayList<String>();
        final int[] visits = new int[] {0};
        final boolean[] truncated = new boolean[] {false};
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!countVisit(visits, truncated, matches.size() >= maxResults)) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    try {
                        policy.lexical(dir.toString());
                    } catch (FileFailure denied) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!countVisit(visits, truncated, matches.size() >= maxResults)) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        policy.lexical(file.toString());
                    } catch (FileFailure denied) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path filename = file.getFileName();
                    if (filename != null && filename.toString().contains(name)) {
                        matches.add(file.toString());
                        if (matches.size() >= maxResults) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            throw io(error);
        }
        Json found = Json.arr();
        for (int i = 0; i < matches.size(); i++) {
            found.add(Json.str(matches.get(i)));
        }
        Json out = Json.obj().put("path", start.toString()).put("matches", found);
        if (truncated[0]) {
            out.put("truncated", true);
        }
        return out;
    }

    Json searchText(String input, String query, int maxMatches, int context) {
        if (query == null || query.isEmpty()) {
            throw new FileFailure("要给出要找的文字");
        }
        if (maxMatches < 1 || maxMatches > 100) {
            throw new FileFailure("maxMatches 要在 1 和 100 之间");
        }
        if (context < 0 || context > 5) {
            throw new FileFailure("context 要在 0 和 5 之间");
        }
        Path path = regular(input);
        Decoded decoded = decode(readLimited(path));
        String text = decoded.text;
        String[] lines = splitLines(text);
        Json matches = Json.arr();
        int from = 0;
        while (matches.array().size() < maxMatches) {
            int at = text.indexOf(query, from);
            if (at < 0) {
                break;
            }
            int lineIndex = lineIndexAt(text, at);
            Json hit = Json.obj().put("line", lineIndex + 1L);
            hit.put("before", clipLines(lines, lineIndex - context, lineIndex));
            Json line = Json.obj();
            line.put("text", clip(lines[lineIndex], line));
            if (line.has("truncated")) {
                hit.put("truncated", true);
            }
            hit.put("lineText", line.get("text").string());
            hit.put("after", clipLines(lines, lineIndex + 1, lineIndex + 1 + context));
            matches.add(hit);
            from = at + query.length();
        }
        Json out = described(path);
        out.put("encoding", decoded.charset.name());
        out.put("matches", matches);
        return out;
    }

    Json writeText(String input, String text, String version) {
        if (text == null) {
            throw new FileFailure("需要文本");
        }
        Path path = resolve(input);
        byte[] body = encodeLimited(text, StandardCharsets.UTF_8, TEXT_LIMIT);
        replaceWhole(path, body, version);
        return scanned(described(path), filesForScan(path));
    }

    Json replaceText(String input, String find, String replacement, String version) {
        if (find == null || find.isEmpty()) {
            throw new FileFailure("要给出被替换的文字");
        }
        if (replacement == null) {
            throw new FileFailure("需要文本");
        }
        Path path = regular(input);
        requireVersion(path, version);
        Decoded decoded = decode(readLimited(path));
        String text = decoded.text;
        LimitedBytes out = new LimitedBytes(TEXT_LIMIT);
        out.write(decoded.bom, 0, decoded.bom.length);
        int count = 0;
        try (Writer writer = new OutputStreamWriter(out, decoded.charset)) {
            int from = 0;
            while (from <= text.length()) {
                int at = text.indexOf(find, from);
                if (at < 0) { break; }
                writer.write(text, from, at - from);
                writer.write(replacement);
                from = at + find.length();
                count++;
            }
            if (count == 0) { throw new FileFailure("没有匹配"); }
            writer.write(text, from, text.length() - from);
        } catch (IOException error) { throw io(error); }
        replaceWhole(path, out.bytes(), version);
        Json described = described(path);
        described.put("replacements", count);
        return scanned(described, filesForScan(path));
    }

    Json appendText(String input, String text, String version) {
        if (text == null) {
            throw new FileFailure("需要文本");
        }
        Path path = regular(input);
        requireVersion(path, version);
        Decoded decoded = decode(readLimited(path));
        byte[] extra = encodeLimited(text, decoded.charset, TEXT_LIMIT - attributes(path).size());
        requireVersion(path, version);
        appendBytes(path, extra);
        return scanned(described(path), filesForScan(path));
    }

    Json writeBytes(String input, String hex, String version) {
        Path path = resolve(input);
        replaceWhole(path, parseHex(hex), version);
        return scanned(described(path), filesForScan(path));
    }

    Json patchBytes(String input, List<Json> patches, String version) {
        if (patches == null || patches.isEmpty() || patches.size() > 200) {
            throw new FileFailure("补丁要有 1 到 200 处");
        }
        Path path = regular(input);
        requireVersion(path, version);
        long size = attributes(path).size();
        List<Patch> items = new ArrayList<Patch>();
        for (int i = 0; i < patches.size(); i++) {
            Json patch = patches.get(i);
            if (patch == null || patch.get("offset") == null || patch.get("hex") == null) {
                throw new FileFailure("补丁要有 offset 和 hex");
            }
            long offset = patch.get("offset").longValue();
            byte[] data = parseHex(patch.get("hex").string());
            if (data.length == 0) {
                throw new FileFailure("补丁不能是空的");
            }
            if (offset < 0 || data.length > size - offset) {
                throw new FileFailure("补丁超出文件末尾");
            }
            items.add(new Patch(offset, data));
        }
        Collections.sort(items, new Comparator<Patch>() {
            @Override
            public int compare(Patch left, Patch right) {
                return Long.compare(left.offset, right.offset);
            }
        });
        long end = 0;
        for (int i = 0; i < items.size(); i++) {
            Patch patch = items.get(i);
            if (patch.offset < end) {
                throw new FileFailure("补丁重叠了");
            }
            end = patch.offset + patch.data.length;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            for (int i = 0; i < items.size(); i++) {
                Patch patch = items.get(i);
                channel.write(ByteBuffer.wrap(patch.data), patch.offset);
            }
        } catch (IOException error) {
            throw io(error);
        }
        return scanned(described(path), filesForScan(path));
    }

    Json appendBytes(String input, String hex, String version) {
        Path path = regular(input);
        requireVersion(path, version);
        appendBytes(path, parseHex(hex));
        return scanned(described(path), filesForScan(path));
    }

    Json truncateBytes(String input, long size, String version) {
        if (size < 0) {
            throw new FileFailure("size 不能是负数");
        }
        Path path = regular(input);
        requireVersion(path, version);
        long current = attributes(path).size();
        if (size > current) {
            throw new FileFailure("不能把文件撑大");
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(size);
        } catch (IOException error) {
            throw io(error);
        }
        return scanned(described(path), filesForScan(path));
    }

    Json copy(String fromInput, String toInput) {
        Path from = regular(fromInput);
        Path to = resolve(toInput);
        if (exists(to)) {
            throw new FileFailure("目标已存在");
        }
        ensureParent(to);
        try {
            Files.copy(from, to);
        } catch (IOException error) {
            throw io(error);
        }
        return scanned(described(to), filesForScan(to));
    }

    Json move(String fromInput, String toInput) {
        Path from = resolve(fromInput);
        Path to = resolve(toInput);
        if (!exists(from)) {
            throw new FileFailure("没有这个文件");
        }
        if (from.equals(to)) {
            throw new FileFailure("目标和原来是同一个");
        }
        if (to.startsWith(from)) {
            throw new FileFailure("不能把目录移进自己里面");
        }
        if (exists(to)) {
            throw new FileFailure("目标已存在");
        }
        ensureParent(to);
        ScanList before = filesForScan(from);
        try {
            Files.move(from, to);
        } catch (IOException error) {
            throw io(error);
        }
        return scanned(described(to), relocated(from, to, before));
    }

    Json createDirectory(String input) {
        Path path = resolve(input);
        if (exists(path)) {
            if (!attributes(path).isDirectory()) {
                throw new FileFailure("这不是目录");
            }
            return Json.obj().put("path", path.toString()).put("created", false);
        }
        try {
            Files.createDirectories(path);
        } catch (IOException error) {
            throw io(error);
        }
        return Json.obj().put("path", path.toString()).put("created", true);
    }

    Json delete(String input) {
        Path path = resolve(input);
        BasicFileAttributes attrs = attributes(path);
        if (attrs.isDirectory()) {
            throw new FileFailure("这是目录");
        }
        if (!attrs.isRegularFile()) {
            throw new FileFailure("这不是普通文件");
        }
        ScanList scan = filesForScan(path);
        try {
            Files.delete(path);
        } catch (IOException error) {
            throw io(error);
        }
        return scanned(Json.obj().put("path", path.toString()).put("deleted", true), scan);
    }

    Json deleteDirectory(String input, boolean recursive) {
        Path path = directory(input);
        if (policy.broadDirectory(path)) {
            throw new FileFailure("不能删除这一层目录");
        }
        if (!recursive && !isEmpty(path)) {
            throw new FileFailure("目录不是空的");
        }
        ScanList scan = filesForScan(path);
        try {
            if (recursive) {
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException error)
                            throws IOException {
                        if (error != null) {
                            throw error;
                        }
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                Files.delete(path);
            }
        } catch (IOException error) {
            throw io(error);
        }
        return scanned(Json.obj().put("path", path.toString()).put("deleted", true), scan);
    }

    private Json places() {
        Json rows = Json.arr();
        StationPlaces[] known = StationPlaces.standard();
        for (int i = 0; i < known.length; i++) {
            rows.add(Json.obj().put("name", known[i].name).put("path", place(known[i].relative)));
        }
        return rows;
    }

    private String place(String relative) {
        return policy.home.resolve(relative).normalize().toString();
    }

    private Json measure(StationPlaces known) {
        Path path = policy.home.resolve(known.relative).normalize();
        Json row = Json.obj().put("name", known.name).put("path", path.toString());
        if (!exists(path)) {
            return row.put("exists", false).put("bytes", 0).put("files", 0).put("truncated", false);
        }
        try {
            BasicFileAttributes attrs = attributes(path);
            if (attrs.isSymbolicLink()) {
                return row.put("exists", true).put("readable", false).put("bytes", 0).put("files", 0).put("truncated", false);
            }
            if (attrs.isRegularFile()) {
                return row.put("exists", true).put("bytes", attrs.size()).put("files", 1).put("truncated", false);
            }
            if (!attrs.isDirectory()) {
                return row.put("exists", true).put("bytes", 0).put("files", 0).put("truncated", false);
            }
            final long[] bytes = new long[] {0};
            final int[] files = new int[] {0};
            final int[] visits = new int[] {0};
            final boolean[] truncated = new boolean[] {false};
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes dirAttrs) {
                    if (!countVisit(visits, truncated, false)) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (dirAttrs.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    try {
                        policy.lexical(dir.toString());
                    } catch (FileFailure denied) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes fileAttrs) {
                    if (!countVisit(visits, truncated, false)) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (fileAttrs.isSymbolicLink() || !fileAttrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    bytes[0] += fileAttrs.size();
                    files[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
            return row.put("exists", true)
                    .put("bytes", bytes[0])
                    .put("files", files[0])
                    .put("truncated", truncated[0]);
        } catch (FileFailure denied) {
            return row.put("exists", true).put("readable", false).put("bytes", 0).put("files", 0).put("truncated", false);
        } catch (IOException error) {
            throw io(error);
        } catch (RuntimeException error) {
            return row.put("exists", true).put("readable", false).put("bytes", 0).put("files", 0).put("truncated", false);
        }
    }

    private Json scanned(Json result, ScanList scan) {
        if (!scan.paths.isEmpty()) {
            media.changed(scan.paths);
        }
        result.put("mediaScan", !scan.paths.isEmpty());
        if (scan.truncated) {
            result.put("mediaScanTruncated", true);
        }
        return result;
    }

    private ScanList filesForScan(Path path) {
        List<String> paths = new ArrayList<String>();
        if (!exists(path)) {
            push(paths, path.toString());
            return new ScanList(paths, false);
        }
        BasicFileAttributes attrs = attributes(path);
        if (attrs.isSymbolicLink()) {
            return new ScanList(paths, false);
        }
        if (attrs.isRegularFile()) {
            push(paths, path.toString());
            return new ScanList(paths, false);
        }
        if (!attrs.isDirectory()) {
            return new ScanList(paths, false);
        }
        boolean truncated = walkFiles(path, paths);
        if (push(paths, path.toString())) {
            truncated = true;
        }
        return new ScanList(paths, truncated);
    }

    private ScanList relocated(Path from, Path to, ScanList before) {
        List<String> notice = new ArrayList<String>();
        boolean truncated = before.truncated;
        for (int i = 0; i < before.paths.size(); i++) {
            String old = before.paths.get(i);
            if (push(notice, old, SCAN_LIMIT * 2)) {
                truncated = true;
                break;
            }
            String next = old.equals(from.toString())
                    ? to.toString()
                    : to.resolve(from.relativize(Paths.get(old))).toString();
            if (push(notice, next, SCAN_LIMIT * 2)) {
                truncated = true;
                break;
            }
        }
        return new ScanList(notice, truncated);
    }

    private boolean walkFiles(final Path root, final List<String> out) {
        final boolean[] truncated = new boolean[] {false};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    try {
                        policy.lexical(dir.toString());
                    } catch (FileFailure denied) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (push(out, file.toString())) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            throw io(error);
        }
        return truncated[0];
    }

    private static boolean push(List<String> out, String path) {
        return push(out, path, SCAN_LIMIT);
    }

    private static boolean push(List<String> out, String path, int limit) {
        if (out.size() >= limit) {
            return true;
        }
        out.add(path);
        return false;
    }

    private Path resolve(String input) {
        Path normal = policy.lexical(input);
        Path cursor = policy.home;
        for (Path part : policy.home.relativize(normal)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new FileFailure("不跟随链接");
            }
        }
        return normal;
    }

    private Path regular(String input) {
        Path path = resolve(input);
        BasicFileAttributes attrs = attributes(path);
        if (!attrs.isRegularFile()) {
            throw new FileFailure("这不是普通文件");
        }
        return path;
    }

    private Path directory(String input) {
        Path path = resolve(input);
        BasicFileAttributes attrs = attributes(path);
        if (!attrs.isDirectory()) {
            throw new FileFailure("这不是目录");
        }
        return path;
    }

    private void requireVersion(Path path, String version) {
        if (version == null || version.isEmpty()) {
            throw new FileFailure("要带 targetVersion");
        }
        String current = targetVersion(path, attributes(path));
        if (!current.equals(version)) {
            throw new FileFailure("文件已变化，先重新读取。当前 targetVersion 是 " + current);
        }
    }

    private void replaceWhole(Path path, byte[] content, String version) {
        boolean existing = exists(path);
        if (existing) {
            if (!attributes(path).isRegularFile()) {
                throw new FileFailure("这不是普通文件");
            }
            requireVersion(path, version);
        } else if (version != null) {
            throw new FileFailure("文件还不存在，不要带 targetVersion");
        } else {
            ensureParent(path);
        }
        Path temp = tempBeside(path);
        try {
            Files.write(temp, content);
            if (existing) {
                requireVersion(path, version);
            }
            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw io(error);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 覆盖已经完成时临时文件不在了。
            }
        }
    }

    private void appendBytes(Path path, byte[] extra) {
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(extra);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException error) {
            throw io(error);
        }
    }

    private static final class LimitedBytes extends OutputStream {
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private final long limit;
        LimitedBytes(long limit) { this.limit = limit; }
        @Override public void write(int value) { check(1); data.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) {
            check(length);
            data.write(bytes, offset, length);
        }
        private void check(int length) {
            if ((long) data.size() + length > limit) {
                throw new FileFailure("文本结果超过 8 MiB，用字节工具；原文件未修改");
            }
        }
        byte[] bytes() { return data.toByteArray(); }
    }

    private static byte[] encodeLimited(String text, Charset charset, long budget) {
        LimitedBytes bytes = new LimitedBytes(budget);
        try (Writer writer = new OutputStreamWriter(bytes, charset)) { writer.write(text); }
        catch (IOException error) { throw io(error); }
        return bytes.bytes();
    }

    private byte[] readLimited(Path path) {
        if (attributes(path).size() > TEXT_LIMIT) { throw new FileFailure("文件太大，用字节工具"); }
        // 文件在初次 stat 后被外部增大，也不能让 readAllBytes 无限制分配。
        LimitedBytes out = new LimitedBytes(TEXT_LIMIT);
        try (InputStream input = Files.newInputStream(path, NOFOLLOW)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) { out.write(buffer, 0, count); }
            return out.bytes();
        } catch (IOException error) { throw io(error); }
    }

    private BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        } catch (AccessDeniedException error) {
            throw new FileFailure("系统不允许访问这个路径");
        } catch (NoSuchFileException error) {
            throw new FileFailure("没有这个文件");
        } catch (IOException error) {
            throw io(error);
        }
    }

    private Json described(Path path) {
        BasicFileAttributes attrs = attributes(path);
        String type;
        if (attrs.isSymbolicLink()) {
            type = "symlink";
        } else if (attrs.isDirectory()) {
            type = "directory";
        } else if (attrs.isRegularFile()) {
            type = "file";
        } else {
            type = "other";
        }
        return Json.obj()
                .put("path", path.toString())
                .put("type", type)
                .put("size", attrs.size())
                .put("mtime", attrs.lastModifiedTime().toMillis())
                .put("targetVersion", targetVersion(path, attrs));
    }

    private static String targetVersion(Path path, BasicFileAttributes attrs) {
        if (!attrs.isRegularFile()) {
            return attrs.lastModifiedTime() + ":" + attrs.size();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 包括文件身份；同内容的文件替换也使旧版本失效。
            digest.update((String.valueOf(attrs.fileKey()) + ":" + attrs.creationTime()
                    + ":" + attrs.lastModifiedTime()).getBytes(StandardCharsets.UTF_8));
            try (InputStream input = Files.newInputStream(path, NOFOLLOW)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return "sha256:" + toHex(digest.digest());
        } catch (IOException error) {
            throw io(error);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Row row(Path child) {
        Path name = child.getFileName();
        String label = name == null ? "" : name.toString();
        Json json = Json.obj().put("name", label);
        try {
            BasicFileAttributes attrs = attributes(child);
            if (attrs.isSymbolicLink()) {
                json.put("type", "symlink");
            } else if (attrs.isDirectory()) {
                json.put("type", "directory");
            } else if (attrs.isRegularFile()) {
                json.put("type", "file");
            } else {
                json.put("type", "other");
            }
            json.put("size", attrs.size());
            json.put("mtime", attrs.lastModifiedTime().toMillis());
        } catch (FileFailure denied) {
            json.put("type", "other");
        }
        return new Row(label, json);
    }

    private void ensureParent(Path path) {
        Path parent = path.getParent();
        if (parent == null || !exists(parent) || !attributes(parent).isDirectory()) {
            throw new FileFailure("上一级目录不存在");
        }
    }

    private static boolean exists(Path path) {
        return Files.exists(path, NOFOLLOW);
    }

    private boolean isEmpty(Path path) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            return !stream.iterator().hasNext();
        } catch (IOException error) {
            throw io(error);
        }
    }

    private Path tempBeside(Path path) {
        Path name = path.getFileName();
        String leaf = name == null ? "file" : name.toString();
        for (int i = 0; i < 8; i++) {
            Path temp = path.resolveSibling(leaf + ".phonestation-" + Long.toHexString(System.nanoTime()));
            if (!exists(temp)) {
                return temp;
            }
        }
        throw new FileFailure("暂时写不了这个文件");
    }

    private static boolean countVisit(int[] visits, boolean[] truncated, boolean full) {
        visits[0]++;
        if (full || visits[0] > SEARCH_VISITS) {
            truncated[0] = true;
            return false;
        }
        return true;
    }

    private static Decoded decode(byte[] data) {
        if (data.length >= 3
                && (data[0] & 0xff) == 0xef
                && (data[1] & 0xff) == 0xbb
                && (data[2] & 0xff) == 0xbf) {
            return decoded(data, 3, StandardCharsets.UTF_8);
        }
        if (data.length >= 2 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xfe) {
            return decoded(data, 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xff) == 0xfe && (data[1] & 0xff) == 0xff) {
            return decoded(data, 2, StandardCharsets.UTF_16BE);
        }
        if (roundtrip(data, StandardCharsets.UTF_8)) {
            return new Decoded(new String(data, StandardCharsets.UTF_8), new byte[0], StandardCharsets.UTF_8);
        }
        Charset gb18030 = gb18030();
        if (gb18030 != null && roundtrip(data, gb18030)) {
            return new Decoded(new String(data, gb18030), new byte[0], gb18030);
        }
        throw new FileFailure("这个文件不是文本，用字节工具");
    }

    private static Decoded decoded(byte[] data, int bomLength, Charset charset) {
        byte[] body = Arrays.copyOfRange(data, bomLength, data.length);
        if (!roundtrip(body, charset)) {
            throw new FileFailure("这个文件不是文本，用字节工具");
        }
        byte[] bom = Arrays.copyOf(data, bomLength);
        return new Decoded(new String(body, charset), bom, charset);
    }

    private static boolean roundtrip(byte[] data, Charset charset) {
        return Arrays.equals(data, new String(data, charset).getBytes(charset));
    }

    private static Charset gb18030() {
        try {
            return Charset.forName("GB18030");
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    static byte[] parseHex(String hex) {
        if (hex == null) {
            throw new FileFailure("需要十六进制");
        }
        int length = hex.length();
        if ((length & 1) != 0) {
            throw new FileFailure("十六进制长度要是偶数");
        }
        byte[] out = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            int high = hexDigit(hex.charAt(i));
            int low = hexDigit(hex.charAt(i + 1));
            if (high < 0 || low < 0) {
                throw new FileFailure("十六进制里有非法字符");
            }
            out[i / 2] = (byte) ((high << 4) | low);
        }
        return out;
    }

    static String toHex(byte[] data) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xff;
            out[i * 2] = digits[value >>> 4];
            out[i * 2 + 1] = digits[value & 0xf];
        }
        return new String(out);
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static byte[] concat(byte[] prefix, byte[] body) {
        byte[] out = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(body, 0, out, prefix.length, body.length);
        return out;
    }

    private static String[] splitLines(String text) {
        if (text.isEmpty()) {
            return new String[] {""};
        }
        List<String> lines = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(stripCr(text.substring(start, i)));
                start = i + 1;
            }
        }
        if (start < text.length() || text.charAt(text.length() - 1) == '\n') {
            if (start <= text.length()) {
                lines.add(stripCr(text.substring(start)));
            }
        }
        return lines.toArray(new String[lines.size()]);
    }

    private static String stripCr(String line) {
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    private static int lineIndexAt(String text, int at) {
        int line = 0;
        for (int i = 0; i < at && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static Json clipLines(String[] lines, int from, int to) {
        Json arr = Json.arr();
        int start = Math.max(0, from);
        int end = Math.min(lines.length, to);
        for (int i = start; i < end; i++) {
            Json line = Json.obj();
            arr.add(Json.str(clip(lines[i], line)));
        }
        return arr;
    }

    private static String clip(String line, Json marker) {
        if (line.length() <= LINE_CLIP) {
            return line;
        }
        marker.put("truncated", true);
        return line.substring(0, LINE_CLIP);
    }

    private static FileFailure io(IOException error) {
        if (error instanceof AccessDeniedException) {
            return new FileFailure("系统不允许访问这个路径");
        }
        if (error instanceof NoSuchFileException) {
            return new FileFailure("没有这个文件");
        }
        if (error instanceof FileAlreadyExistsException) {
            return new FileFailure("目标已存在");
        }
        return new FileFailure("文件操作失败");
    }

    private static final class Decoded {
        final String text;
        final byte[] bom;
        final Charset charset;

        Decoded(String text, byte[] bom, Charset charset) {
            this.text = text;
            this.bom = bom;
            this.charset = charset;
        }
    }

    private static final class ScanList {
        final List<String> paths;
        final boolean truncated;

        ScanList(List<String> paths, boolean truncated) {
            this.paths = paths;
            this.truncated = truncated;
        }
    }

    private static final class Patch {
        final long offset;
        final byte[] data;

        Patch(long offset, byte[] data) {
            this.offset = offset;
            this.data = data;
        }
    }

    private static final class Row {
        final String name;
        final Json json;

        Row(String name, Json json) {
            this.name = name;
            this.json = json;
        }
    }
}
