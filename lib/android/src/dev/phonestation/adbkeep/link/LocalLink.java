package dev.phonestation.adbkeep.link;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.phonestation.adbkeep.SharePaths;

/**
 * 手机回环上的 Link v2 本地链路。正式地址是 {@code ws://127.0.0.1:8765/v2/link}。
 * Mac 先发 hello，手机回 hello 和对 Mac 随机数的签名，Mac 再发 auth，手机再发 ready。
 * 测试自己监听临时端口。FileMcpService 不调用，也不改 {@code /mcp}。
 * 接入手机服务时签名用 {@code PhoneDeviceKey.localSigner()}；桌面测试没有 Keystore，注入同一套 SHA256withECDSA。
 */
public final class LocalLink {
    public static final String PATH = "/v2/link";
    public static final String ADDRESS = "ws://127.0.0.1:8765/v2/link";
    static final int FRAME_LIMIT = LinkFrame.HEADER + LinkFrame.MAIN_PAYLOAD;

    private LocalLink() {}

    /** 与 PhoneDeviceKey、Mac DeviceKey 相同的 ECDSA-SHA256 DER 签名。 */
    public interface Signer {
        byte[] sign(byte[] message) throws Exception;
    }

    /** 一次调用的方法和参数。只读与有副作用的方法都从这里进回执。 */
    public interface Call {
        byte[] run(String method, byte[] params) throws Exception;
    }

    /** 把拖放的文件写到 Download。名字是一个文件名。 */
    public interface ShareWriter {
        void write(String name, byte[] body) throws Exception;
    }

    /** 一台设备的 deviceId 和 SPKI。签名方带 Signer，授权列表里的对端不带。 */
    public static final class Device {
        public final byte[] id;
        public final byte[] spki;
        public final Signer signer;

        public Device(byte[] id, byte[] spki, Signer signer) {
            if (id == null || id.length != 16 || spki == null || spki.length == 0) {
                throw new IllegalArgumentException("device");
            }
            this.id = Arrays.copyOf(id, 16);
            this.spki = Arrays.copyOf(spki, spki.length);
            this.signer = signer;
        }

        public static Device generate() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair pair = generator.generateKeyPair();
                byte[] spki = pair.getPublic().getEncoded();
                return new Device(LinkAuth.deviceId(spki), spki, message -> {
                    Signature signature = Signature.getInstance("SHA256withECDSA");
                    signature.initSign(pair.getPrivate());
                    signature.update(message);
                    return signature.sign();
                });
            } catch (GeneralSecurityException error) {
                throw new IllegalStateException(error);
            }
        }
    }

    public static final class Result {
        public final String state;
        public final byte[] body;

        Result(String state, byte[] body) {
            this.state = state;
            this.body = body;
        }
    }

    /** 手机侧。只接受授权列表里的 Mac。 */
    public static final class Phone implements AutoCloseable {
        private final ServerSocket server;
        private final Device self;
        private final List<Device> allow;
        private final LinkReceipts receipts;
        private final Callable<byte[]> work;

        private Phone(ServerSocket server, Device self, List<Device> allow, LinkReceipts receipts, Callable<byte[]> work) {
            this.server = server;
            this.self = self;
            this.allow = allow;
            this.receipts = receipts;
            this.work = work;
        }

        public static Phone listen(Device self, List<Device> allow, LinkReceipts receipts, Callable<byte[]> work) throws IOException {
            if (self == null || self.signer == null || (receipts == null) != (work == null)) {
                throw new IllegalArgumentException("phone");
            }
            if (receipts != null) {
                receipts.ensureEvents(null);
            }
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            return new Phone(socket, self, List.copyOf(allow), receipts, work);
        }

        public int port() {
            return server.getLocalPort();
        }

        public Session accept() throws Exception {
            Socket socket = server.accept();
            LocalWire wire;
            try {
                wire = LocalWire.accept(socket);
            } catch (Exception error) {
                socket.close();
                throw error;
            }
            try {
                Session session = handshake(wire, self, allow, receipts, work);
                wire.relax();
                session.start();
                return session;
            } catch (Exception error) {
                wire.close();
                throw error;
            }
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // 关掉监听即可把 accept 解出来。
            }
        }
    }

    public static Session dial(Device self, Device phone, String host, int port) throws Exception {
        if (self == null || self.signer == null || phone == null) {
            throw new IllegalArgumentException("mac");
        }
        LocalWire wire = LocalWire.dial(host, port);
        try {
            Session session = macHandshake(wire, self, phone);
            wire.relax();
            session.start();
            return session;
        } catch (Exception error) {
            wire.close();
            throw error;
        }
    }

    /** GET /v2/link，Host 必须是回环，带 Origin 的升级拒绝。 */
    public static void admit(String method, String target, String host, boolean origin) {
        if (!"GET".equals(method)) {
            throw new IllegalArgumentException("method");
        }
        String path = target == null ? "" : target;
        String query = "";
        int mark = path.indexOf('?');
        if (mark >= 0) {
            query = path.substring(mark + 1);
            path = path.substring(0, mark);
        }
        if (!PATH.equals(path) || !query.isEmpty()) {
            throw new IllegalArgumentException("path");
        }
        if (!loopbackHost(host)) {
            throw new IllegalArgumentException("host");
        }
        if (origin) {
            throw new IllegalArgumentException("origin");
        }
    }

    public static final class Event {
        public final String type;
        public final String topic;
        public final String op;
        public final String state;
        public final String cursor;
        public final byte[] body;

        Event(String type, String topic, String op, String state, String cursor, byte[] body) {
            this.type = type;
            this.topic = topic;
            this.op = op;
            this.state = state;
            this.cursor = cursor;
            this.body = body;
        }
    }

    public static final class Subscribed {
        public final List<String> topics;
        public final List<String> rejected;
        public final List<String> reasons;

        Subscribed(List<String> topics, List<String> rejected, List<String> reasons) {
            this.topics = topics;
            this.rejected = rejected;
            this.reasons = reasons;
        }
    }

    public static final class Session implements AutoCloseable {
        private static final AtomicLong SESSIONS = new AtomicLong();
        private final LocalWire wire;
        private final boolean fromMac;
        private final byte[] peer;
        private final LinkEngine engine;
        private final LinkMux mux;
        private final LinkReceipts receipts;
        private final Callable<byte[]> work;
        private final Call calls;
        private final ArrayBlockingQueue<byte[]> results;
        private final ArrayBlockingQueue<byte[]> notices;
        private final LinkEvents events;
        private final String sessionKey;
        private final Object ioLock = new Object();
        private final boolean checkPeer;
        private LinkSealSession seal;
        private volatile ShareWriter shareWriter;
        private volatile java.util.function.Consumer<String> relayInvite;
        private String shareName;
        private int shareSize = -1;
        private int shareExpect;
        private int shareChannel;
        private final ArrayBlockingQueue<byte[]> offerReply = new ArrayBlockingQueue<>(1);
        private volatile boolean ready;
        private volatile boolean ended;

        private Session(LocalWire wire, boolean fromMac, byte[] peer, LinkReceipts receipts, Callable<byte[]> work) {
            this(wire, fromMac, peer, receipts, work, null, false);
        }

        private Session(LocalWire wire, boolean fromMac, byte[] peer, LinkReceipts receipts, Callable<byte[]> work, boolean checkPeer) {
            this(wire, fromMac, peer, receipts, work, null, checkPeer);
        }

        private Session(LocalWire wire, boolean fromMac, byte[] peer, LinkReceipts receipts, Callable<byte[]> work, Call call, boolean checkPeer) {
            long now = System.currentTimeMillis();
            this.wire = wire;
            this.fromMac = fromMac;
            this.peer = Arrays.copyOf(peer, 16);
            this.engine = new LinkEngine(fromMac, 0, 0, 0, now);
            this.engine.setPeer(this.peer);
            this.mux = new LinkMux(now, engine);
            this.receipts = receipts;
            this.work = work;
            this.calls = call != null ? call : (work == null ? null : (method, params) -> work.call());
            this.results = fromMac ? new ArrayBlockingQueue<>(8) : null;
            this.notices = fromMac ? new ArrayBlockingQueue<>(64) : null;
            this.events = receipts == null ? null : receipts.events();
            this.shareChannel = fromMac ? 7 : 8;
            this.sessionKey = Long.toHexString(SESSIONS.incrementAndGet());
            this.checkPeer = checkPeer;
            this.ready = true;
        }

        public boolean ready() {
            return ready;
        }

        public boolean ended() {
            return ended;
        }

        public byte[] peer() {
            return Arrays.copyOf(peer, 16);
        }

        public LinkEngine engine() {
            return engine;
        }

        public LinkMux mux() {
            return mux;
        }

        public Result call(String op, String method, byte[] params) throws Exception {
            if (!fromMac) {
                throw new IllegalStateException("mac");
            }
            if (ended) {
                throw new IllegalStateException("ended");
            }
            ensureSeal();
            writeMessage(Msg.call(op, method, params));
            byte[] raw = await(results, "result", 240);
            Msg msg = Msg.parse(raw);
            if (!"result".equals(msg.type) || !op.equals(msg.op)) {
                throw new IllegalStateException("result");
            }
            byte[] body = msg.result == null ? null : Base64.getDecoder().decode(msg.result);
            return new Result(msg.state, body);
        }

        /** 按原编号读回执，不提交，也不执行。 */
        public Result query(String op) throws Exception {
            if (!fromMac) {
                throw new IllegalStateException("mac");
            }
            if (ended) {
                throw new IllegalStateException("ended");
            }
            ensureSeal();
            writeMessage(Msg.query(op));
            byte[] raw = await(results, "status");
            Msg msg = Msg.parse(raw);
            if (!"status".equals(msg.type) || !op.equals(msg.op)) {
                throw new IllegalStateException("status");
            }
            byte[] body = msg.result == null ? null : Base64.getDecoder().decode(msg.result);
            return new Result(msg.state, body);
        }

        /** 把一份文件经数据帧送到手机的 Download。超过初始窗口的文件拒绝。 */
        public void share(String name, byte[] body) throws Exception {
            if (!fromMac) {
                throw new IllegalStateException("mac");
            }
            sendFile(name, body, results);
        }

        /** 手机把一份文件送给已连接的 Mac。Mac 负责落盘。 */
        public void offer(String name, byte[] body) throws Exception {
            if (fromMac) {
                throw new IllegalStateException("phone");
            }
            offerReply.clear();
            sendFile(name, body, offerReply);
        }

        public void setShareWriter(ShareWriter writer) {
            shareWriter = writer;
        }

        private void sendFile(String name, byte[] body, ArrayBlockingQueue<byte[]> replies) throws Exception {
            if (ended) {
                throw new IllegalStateException("ended");
            }
            byte[] data = body == null ? new byte[0] : body.clone();
            if (data.length > 128 * 1024 || !shareName(name)) {
                throw new IllegalArgumentException("share");
            }
            SharePaths.phoneDownload(name);
            int channel = shareChannel;
            shareChannel += 2;
            ensureSeal();
            writeMessage(Msg.share(name, data.length, channel));
            synchronized (ioLock) {
                engine.open(channel);
                int offset = 0;
                do {
                    int next = Math.min(data.length, offset + LinkFrame.MAIN_PAYLOAD);
                    boolean last = next == data.length;
                    engine.send(LinkFlow.PRIORITY_FILE, new LinkEngine.Frame(
                            LinkFrame.KIND_DATA, last ? 0x02 : 0x04, peer, channel, 0,
                            Arrays.copyOfRange(data, offset, next)));
                    flushLocked();
                    offset = next;
                } while (offset < data.length);
            }
            byte[] raw = await(replies, "shared", 5);
            Msg msg = Msg.parse(raw);
            if (!"shared".equals(msg.type) || !name.equals(msg.name) || msg.size == null || msg.size != data.length) {
                throw new IllegalStateException("share " + new String(raw, StandardCharsets.UTF_8));
            }
        }

        /** 订阅随这次会话。立即回复里的补发和 reset 留在 nextEvent。 */
        public Subscribed subscribe(List<String> topics, Map<String, String> cursors) throws Exception {
            if (!fromMac) {
                throw new IllegalStateException("mac");
            }
            if (ended) {
                throw new IllegalStateException("ended");
            }
            ensureSeal();
            writeMessage(Msg.subscribe(topics, cursors));
            Msg msg = Msg.parse(await(notices, "subscribed"));
            if (!"subscribed".equals(msg.type)) {
                throw new IllegalStateException("subscribed");
            }
            return new Subscribed(
                    msg.topics == null ? List.of() : msg.topics,
                    msg.rejected == null ? List.of() : msg.rejected,
                    msg.reasons == null ? List.of() : msg.reasons);
        }

        /** 下一条 event 或 reset。超时返回 null。 */
        public Event nextEvent(long timeoutMs) throws Exception {
            if (!fromMac) {
                throw new IllegalStateException("mac");
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                if (ended) {
                    return null;
                }
                byte[] raw = notices.poll(50, TimeUnit.MILLISECONDS);
                if (raw == null) {
                    continue;
                }
                Msg msg = Msg.parse(raw);
                if (!"event".equals(msg.type) && !"reset".equals(msg.type)) {
                    throw new IllegalStateException("event");
                }
                byte[] body = msg.body == null ? null : Base64.getDecoder().decode(msg.body);
                return new Event(msg.type, msg.topic, msg.op, msg.state, msg.cursor, body);
            }
            return null;
        }

        @Override
        public void close() {
            finish();
            wire.close();
        }

        private void finish() {
            ended = true;
            if (events != null) {
                events.unbind(sessionKey);
            }
            engine.close();
        }

        private byte[] await(ArrayBlockingQueue<byte[]> queue, String error) throws Exception {
            return await(queue, error, 5);
        }

        private byte[] await(ArrayBlockingQueue<byte[]> queue, String error, int seconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                if (ended) {
                    throw new IllegalStateException("ended");
                }
                byte[] raw = queue.poll(50, TimeUnit.MILLISECONDS);
                if (raw != null) {
                    return raw;
                }
            }
            throw new IllegalStateException(error);
        }

        private void start() {
            Thread thread = new Thread(this::pump, "local-link");
            thread.setDaemon(true);
            thread.start();
        }

        private void pump() {
            try {
                while (!ended) {
                    byte[] raw = wire.readData();
                    List<byte[]> inbound = new ArrayList<>();
                    synchronized (ioLock) {
                        if (ended) {
                            return;
                        }
                        LinkFrame frame = LinkFrame.decode(raw, LinkFrame.MAIN_PAYLOAD);
                        if (checkPeer && frame.kind != LinkFrame.KIND_LINK && !Arrays.equals(frame.peer, peer)) {
                            continue;
                        }
                        engine.accept(new LinkEngine.Frame(frame.kind, frame.flags, frame.peer, frame.channel, frame.seq, frame.payload));
                        for (LinkEngine.Chunk chunk : engine.inbox()) {
                            if (chunk.channel != 0) {
                                acceptShare(chunk.channel, chunk.payload);
                            } else {
                                inbound.add(Arrays.copyOf(chunk.payload, chunk.payload.length));
                            }
                        }
                        flushLocked();
                    }
                    for (byte[] copy : inbound) {
                        dispatch(copy);
                    }
                }
            } catch (Exception error) {
                finish();
            }
        }

        private void dispatch(byte[] copy) throws Exception {
            if (takeSeal(copy)) {
                return;
            }
            if (receipts != null) {
                handle(copy);
                return;
            }
            if (notices == null) {
                return;
            }
            Msg msg = Msg.parse(copy);
            if ("share".equals(msg.type)) {
                beginShare(msg);
                return;
            }
            if ("event".equals(msg.type) || "reset".equals(msg.type) || "subscribed".equals(msg.type)) {
                notices.offer(copy);
            } else if (results != null) {
                results.offer(copy);
            }
        }

        private void handle(byte[] payload) throws Exception {
            Msg msg = Msg.parse(payload);
            if ("shared".equals(msg.type) || "share-error".equals(msg.type)) {
                offerReply.offer(payload);
                return;
            }
            String mac = hex(peer);
            if ("subscribe".equals(msg.type)) {
                if (events == null || msg.topics == null) {
                    throw new IllegalArgumentException("subscribe");
                }
                synchronized (ioLock) {
                    List<byte[]> replies = events.subscribe(sessionKey, hex(peer), msg.topics, msg.cursors, this::sendQuiet);
                    for (byte[] reply : replies) {
                        writeLocked(reply);
                    }
                }
                return;
            }
            if ("query".equals(msg.type)) {
                if (msg.ops == null || msg.ops.isEmpty() || msg.ops.size() > 64) {
                    throw new IllegalArgumentException("query");
                }
                for (String op : msg.ops) {
                    if (op == null || !op.matches("[0-9a-f]{32}")) {
                        throw new IllegalArgumentException("query");
                    }
                    LinkReceipts.Receipt receipt = receipts.query(mac, op);
                    writeMessage(Msg.status(op, receipt.state, receipt.body));
                }
                return;
            }
            if ("share".equals(msg.type)) {
                beginShare(msg);
                return;
            }
            if ("relay-invite".equals(msg.type)) {
                java.util.function.Consumer<String> handler = relayInvite;
                if (handler != null && msg.body != null) {
                    try {
                        byte[] raw = Base64.getDecoder().decode(msg.body);
                        handler.accept(new String(raw, StandardCharsets.UTF_8));
                    } catch (IllegalArgumentException ignored) {
                        // 正文不是邀请，原来的会话继续。
                    }
                }
                return;
            }
            if (!"call".equals(msg.type)) {
                return;
            }
            if (calls == null) {
                throw new IllegalStateException("call");
            }
            byte[] params = msg.params == null ? new byte[0] : Base64.getDecoder().decode(msg.params);
            String method = msg.method;
            Call job = calls;
            LinkReceipts.Receipt receipt = receipts.submit(mac, msg.op, method, params, () -> job.run(method, params));
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while ("queued".equals(receipt.state) || "running".equals(receipt.state)) {
                if (System.nanoTime() > deadline) {
                    break;
                }
                Thread.sleep(10);
                receipt = receipts.query(mac, msg.op);
            }
            writeMessage(Msg.result(msg.op, receipt.state, receipt.body));
        }

        private void beginShare(Msg msg) {
            try {
                if (shareWriter == null || msg.name == null || msg.size == null || msg.channel == null
                        || msg.size < 0 || msg.size > 128 * 1024 || msg.channel < 7 || msg.channel > 63
                        || !shareName(msg.name) || shareName != null) {
                    throw new IllegalArgumentException("share");
                }
                SharePaths.phoneDownload(msg.name);
                shareName = msg.name;
                shareSize = msg.size;
                shareExpect = msg.channel;
            } catch (RuntimeException error) {
                shareName = null;
                replyShare(Msg.shareError());
            }
        }

        private void acceptShare(long channel, byte[] body) {
            if (shareName == null || channel != shareExpect) {
                return;
            }
            String name = shareName;
            int size = shareSize;
            shareName = null;
            byte[] data = body == null ? new byte[0] : body;
            try {
                if (data.length != size) {
                    throw new IllegalArgumentException("share");
                }
                shareWriter.write(name, data);
                replyShare(Msg.shared(name, size));
            } catch (Exception error) {
                replyShare(Msg.shareError());
            }
        }

        private void replyShare(byte[] message) {
            try {
                writeMessage(message);
            } catch (Exception ignored) {
                ended = true;
            }
        }

        private void sendQuiet(byte[] message) {
            if (ended) {
                return;
            }
            try {
                writeMessage(message);
            } catch (Exception ignored) {
                ended = true;
            }
        }

        private void writeMessage(byte[] payload) throws IOException {
            synchronized (ioLock) {
                writeLocked(payload);
            }
        }

        private void writeHandshake(byte[] payload) throws IOException {
            synchronized (ioLock) {
                if (ended) {
                    throw new IOException("ended");
                }
                engine.sendMessage(LinkFlow.PRIORITY_CONTROL, 0, LinkFrame.KIND_HANDSHAKE, payload);
                flushLocked();
            }
        }

        private void ensureSeal() throws Exception {
            if (seal == null || seal.ready()) {
                return;
            }
            if (!fromMac) {
                throw new IllegalStateException("plaintext");
            }
            writeHandshake(seal.openPayload());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!seal.ready()) {
                if (ended) {
                    throw new IllegalStateException("plaintext");
                }
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("plaintext");
                }
                Thread.sleep(10);
            }
        }

        private boolean takeSeal(byte[] payload) throws Exception {
            if (seal == null || payload == null || payload.length == 0 || payload[0] != '{') {
                return false;
            }
            String type = LinkSealSession.text(payload, "type");
            if ("open".equals(type)) {
                if (fromMac || seal.ready()) {
                    throw new IllegalArgumentException("plaintext");
                }
                writeHandshake(seal.answer(payload));
                return true;
            }
            if ("accept".equals(type)) {
                if (!fromMac || seal.ready()) {
                    throw new IllegalArgumentException("plaintext");
                }
                seal.finish(payload);
                return true;
            }
            return false;
        }

        private void installSeal(LinkSealSession codec) {
            if (!checkPeer || codec == null) {
                throw new IllegalArgumentException("local");
            }
            seal = codec;
            engine.useSeal(codec);
        }

        private void writeLocked(byte[] payload) throws IOException {
            if (ended) {
                throw new IOException("ended");
            }
            engine.sendMessage(LinkFlow.PRIORITY_CONTROL, 0, LinkFrame.KIND_MESSAGE, payload);
            flushLocked();
        }

        private void flushLocked() throws IOException {
            List<byte[]> frames = new ArrayList<>();
            LinkEngine.Frame frame;
            while ((frame = mux.pull()) != null) {
                frames.add(LinkFrame.encode(frame.kind, frame.flags, frame.peer, frame.channel, frame.seq, frame.payload, LinkFrame.MAIN_PAYLOAD));
            }
            if (engine.failed() != null) {
                throw new IOException(engine.failed().getMessage());
            }
            for (byte[] raw : frames) {
                wire.writeData(raw);
            }
        }
    }

    /** 已经握完手的连接。远程会话要核对入站帧的 peer，本地会话一对一，不核对。 */
    static Session attach(LocalWire wire, boolean fromMac, byte[] peer, LinkReceipts receipts, Callable<byte[]> work, boolean checkPeer) {
        return attach(wire, fromMac, peer, receipts, work, null, checkPeer);
    }

    static Session attach(LocalWire wire, boolean fromMac, byte[] peer, LinkReceipts receipts, Callable<byte[]> work, Call call, boolean checkPeer) {
        return attach(wire, fromMac, peer, receipts, work, call, checkPeer, null);
    }

    static Session attach(LocalWire wire, boolean fromMac, byte[] peer, LinkReceipts receipts, Callable<byte[]> work, Call call, boolean checkPeer, LinkSealSession seal) {
        Session session = new Session(wire, fromMac, peer, receipts, work, call, checkPeer);
        if (seal != null) {
            session.installSeal(seal);
        }
        session.start();
        return session;
    }

    /** McpHttp 已经读完升级头。握手失败时关掉这条连接。 */
    public static Session acceptHttp(Socket socket, InputStream incoming, Map<String, String> headers, Device self,
            List<Device> allow, LinkReceipts receipts, Call call) throws Exception {
        return acceptHttp(socket, incoming, headers, self, allow, receipts, call, null);
    }

    public static Session acceptHttp(Socket socket, InputStream incoming, Map<String, String> headers, Device self,
            List<Device> allow, LinkReceipts receipts, Call call, ShareWriter share) throws Exception {
        return acceptHttp(socket, incoming, headers, self, allow, receipts, call, share, null);
    }

    public static Session acceptHttp(Socket socket, InputStream incoming, Map<String, String> headers, Device self,
            List<Device> allow, LinkReceipts receipts, Call call, ShareWriter share,
            java.util.function.Consumer<String> relayInvite) throws Exception {
        LocalWire wire = LocalWire.fromHeaders(socket, incoming, headers);
        return acceptUpgraded(wire, self, allow, receipts, call, share, relayInvite);
    }

    /** McpHttp 已经完成升级。握手失败时关掉这条连接。 */
    public static Session acceptUpgraded(LocalWire wire, Device self, List<Device> allow, LinkReceipts receipts, Call call) throws Exception {
        return acceptUpgraded(wire, self, allow, receipts, call, null);
    }

    public static Session acceptUpgraded(LocalWire wire, Device self, List<Device> allow, LinkReceipts receipts, Call call, ShareWriter share) throws Exception {
        return acceptUpgraded(wire, self, allow, receipts, call, share, null);
    }

    public static Session acceptUpgraded(LocalWire wire, Device self, List<Device> allow, LinkReceipts receipts, Call call, ShareWriter share,
            java.util.function.Consumer<String> relayInvite) throws Exception {
        try {
            Session session = handshake(wire, self, allow, receipts, null, call);
            session.shareWriter = share;
            session.relayInvite = relayInvite;
            wire.relax();
            session.start();
            return session;
        } catch (Exception error) {
            wire.close();
            throw error;
        }
    }

    private static Session handshake(LocalWire wire, Device self, List<Device> allow, LinkReceipts receipts, Callable<byte[]> work) throws Exception {
        return handshake(wire, self, allow, receipts, work, null);
    }

    private static Session handshake(LocalWire wire, Device self, List<Device> allow, LinkReceipts receipts, Callable<byte[]> work, Call call) throws Exception {
        Msg hello = readControl(wire);
        if (!hello.isMacHello()) {
            throw new IllegalArgumentException("rejected");
        }
        Device mac = find(allow, hello.deviceId);
        if (mac == null) {
            writeControl(wire, Msg.error("unknown_device"));
            throw new IllegalArgumentException("unknown_device");
        }
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        byte[] sig = self.signer.sign(LinkAuth.localMessage(hello.deviceId, hello.nonce, self.id));
        writeControl(wire, Msg.phoneHello(self.id, nonce, sig));
        Msg auth = readControl(wire);
        if (auth.isError()) {
            throw new IllegalArgumentException(auth.code);
        }
        if (!auth.isAuth()) {
            throw new IllegalArgumentException("rejected");
        }
        if (!LinkAuth.verify(mac.spki, LinkAuth.localMessage(self.id, nonce, hello.deviceId), auth.sig)) {
            writeControl(wire, Msg.error("bad_signature"));
            throw new IllegalArgumentException("bad_signature");
        }
        writeControl(wire, Msg.ready());
        return new Session(wire, false, hello.deviceId, receipts, work, call, false);
    }

    private static Session macHandshake(LocalWire wire, Device self, Device phone) throws Exception {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        writeControl(wire, Msg.macHello(self.id, nonce));
        Msg hello = readControl(wire);
        if (hello.isError()) {
            throw new IllegalArgumentException(hello.code);
        }
        if (!hello.isPhoneHello()) {
            throw new IllegalArgumentException("rejected");
        }
        if (!Arrays.equals(hello.deviceId, phone.id)
                || !LinkAuth.verify(phone.spki, LinkAuth.localMessage(self.id, nonce, hello.deviceId), hello.sig)) {
            throw new IllegalArgumentException("bad_signature");
        }
        byte[] sig = self.signer.sign(LinkAuth.localMessage(hello.deviceId, hello.nonce, self.id));
        writeControl(wire, Msg.auth(sig));
        Msg ready = readControl(wire);
        if (ready.isError()) {
            throw new IllegalArgumentException(ready.code);
        }
        if (!ready.isReady()) {
            throw new IllegalArgumentException("ready");
        }
        return new Session(wire, true, phone.id, null, null);
    }

    private static Device find(List<Device> allow, byte[] id) {
        for (Device device : allow) {
            if (Arrays.equals(device.id, id)) {
                return device;
            }
        }
        return null;
    }

    private static Msg readControl(LocalWire wire) throws Exception {
        byte[] raw = wire.readData();
        LinkFrame frame = LinkFrame.decode(raw, LinkFrame.MAIN_PAYLOAD);
        if (frame.kind != LinkFrame.KIND_LINK || frame.payload.length == 0 || frame.payload.length > 4096) {
            throw new IllegalArgumentException("rejected");
        }
        return Msg.parse(frame.payload);
    }

    private static void writeControl(LocalWire wire, byte[] payload) throws IOException {
        wire.writeData(LinkFrame.encode(LinkFrame.KIND_LINK, 0, new byte[16], 0, 0, payload, LinkFrame.MAIN_PAYLOAD));
    }

    static boolean loopbackHost(String host) {
        if (host == null) {
            return false;
        }
        host = host.trim();
        if (host.isEmpty()) {
            return false;
        }
        if (host.charAt(0) == '[') {
            int end = host.indexOf(']');
            if (end <= 1) {
                return false;
            }
            String name = host.substring(1, end);
            String rest = host.substring(end + 1);
            if (!rest.isEmpty() && !portSuffix(rest)) {
                return false;
            }
            return "::1".equals(name) || "0:0:0:0:0:0:0:1".equals(name);
        }
        String name = host;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            name = host.substring(0, colon);
            if (!portOK(host.substring(colon + 1))) {
                return false;
            }
        }
        if ("localhost".equalsIgnoreCase(name)) {
            return true;
        }
        String[] parts = name.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] numbers = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3 || (parts[i].length() > 1 && parts[i].charAt(0) == '0')) {
                return false;
            }
            for (int c = 0; c < parts[i].length(); c++) {
                if (parts[i].charAt(c) < '0' || parts[i].charAt(c) > '9') {
                    return false;
                }
            }
            numbers[i] = Integer.parseInt(parts[i]);
            if (numbers[i] > 255) {
                return false;
            }
        }
        return numbers[0] == 127;
    }

    private static boolean portSuffix(String rest) {
        return rest.charAt(0) == ':' && portOK(rest.substring(1));
    }

    private static boolean portOK(String port) {
        if (port.isEmpty() || port.length() > 5) {
            return false;
        }
        int value = 0;
        for (int i = 0; i < port.length(); i++) {
            char c = port.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            value = value * 10 + (c - '0');
        }
        return value > 0 && value <= 65535;
    }

    private static String hex(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            out.append(Character.forDigit((value >> 4) & 0xf, 16)).append(Character.forDigit(value & 0xf, 16));
        }
        return out.toString();
    }

    private static byte[] unhex(String text, int size) {
        if (text == null || text.length() % 2 != 0 || !text.equals(text.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("rejected");
        }
        if (size > 0 && text.length() != size * 2) {
            throw new IllegalArgumentException("rejected");
        }
        if (size == 0 && (text.length() < 16 || text.length() > 160)) {
            throw new IllegalArgumentException("rejected");
        }
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(text.charAt(i * 2), 16);
            int low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("rejected");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static final class Msg {
        final String type;
        final boolean protocolSet;
        final int protocol;
        final byte[] deviceId;
        final byte[] nonce;
        final byte[] sig;
        final String code;
        final String op;
        final String method;
        final String params;
        final String state;
        final String result;
        final List<String> ops;
        final List<String> topics;
        final Map<String, String> cursors;
        final List<String> rejected;
        final List<String> reasons;
        final String topic;
        final String cursor;
        final String body;
        final String name;
        final Integer size;
        final Integer channel;

        private Msg(String type, boolean protocolSet, int protocol, byte[] deviceId, byte[] nonce, byte[] sig,
                String code, String op, String method, String params, String state, String result, List<String> ops,
                List<String> topics, Map<String, String> cursors, List<String> rejected, List<String> reasons,
                String topic, String cursor, String body, String name, Integer size, Integer channel) {
            this.type = type;
            this.protocolSet = protocolSet;
            this.protocol = protocol;
            this.deviceId = deviceId;
            this.nonce = nonce;
            this.sig = sig;
            this.code = code;
            this.op = op;
            this.method = method;
            this.params = params;
            this.state = state;
            this.result = result;
            this.ops = ops;
            this.topics = topics;
            this.cursors = cursors;
            this.rejected = rejected;
            this.reasons = reasons;
            this.topic = topic;
            this.cursor = cursor;
            this.body = body;
            this.name = name;
            this.size = size;
            this.channel = channel;
        }

        private boolean noEvent() {
            return topics == null && cursors == null && rejected == null && reasons == null
                    && topic == null && cursor == null && body == null
                    && name == null && size == null && channel == null;
        }

        boolean isMacHello() {
            return "hello".equals(type) && protocolSet && protocol == 2 && deviceId != null && nonce != null && sig == null && code == null
                    && op == null && method == null && params == null && state == null && result == null && ops == null && noEvent();
        }

        boolean isPhoneHello() {
            return "hello".equals(type) && protocolSet && protocol == 2 && deviceId != null && nonce != null && sig != null && code == null
                    && op == null && method == null && params == null && state == null && result == null && ops == null && noEvent();
        }

        boolean isAuth() {
            return "auth".equals(type) && !protocolSet && deviceId == null && nonce == null && sig != null && code == null
                    && op == null && method == null && params == null && state == null && result == null && ops == null && noEvent();
        }

        boolean isReady() {
            return "ready".equals(type) && !protocolSet && deviceId == null && nonce == null && sig == null && code == null
                    && op == null && method == null && params == null && state == null && result == null && ops == null && noEvent();
        }

        boolean isError() {
            return "error".equals(type) && !protocolSet && deviceId == null && nonce == null && sig == null && ops == null && noEvent()
                    && ("unknown_device".equals(code) || "bad_signature".equals(code));
        }

        static byte[] macHello(byte[] id, byte[] nonce) {
            return ascii("{\"type\":\"hello\",\"protocol\":2,\"deviceId\":\"" + hex(id) + "\",\"nonce\":\"" + hex(nonce) + "\"}");
        }

        static byte[] phoneHello(byte[] id, byte[] nonce, byte[] sig) {
            return ascii("{\"type\":\"hello\",\"protocol\":2,\"deviceId\":\"" + hex(id) + "\",\"nonce\":\"" + hex(nonce) + "\",\"sig\":\"" + hex(sig) + "\"}");
        }

        static byte[] auth(byte[] sig) {
            return ascii("{\"type\":\"auth\",\"sig\":\"" + hex(sig) + "\"}");
        }

        static byte[] ready() {
            return ascii("{\"type\":\"ready\"}");
        }

        static byte[] share(String name, int size, int channel) {
            return ascii("{\"type\":\"share\",\"name\":\"" + name + "\",\"size\":" + size + ",\"channel\":" + channel + "}");
        }

        static byte[] shared(String name, int size) {
            return ascii("{\"type\":\"shared\",\"name\":\"" + name + "\",\"size\":" + size + "}");
        }

        static byte[] shareError() {
            return ascii("{\"type\":\"share-error\",\"code\":\"share\"}");
        }

        static byte[] error(String code) {
            return ascii("{\"type\":\"error\",\"code\":\"" + code + "\"}");
        }

        static byte[] call(String op, String method, byte[] params) {
            return ascii("{\"type\":\"call\",\"op\":\"" + op + "\",\"method\":\"" + method + "\",\"params\":\""
                    + Base64.getEncoder().encodeToString(params == null ? new byte[0] : params) + "\"}");
        }

        static byte[] result(String op, String state, byte[] body) {
            return outcome("result", op, state, body);
        }

        static byte[] query(String op) {
            if (op == null || !op.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("query");
            }
            return ascii("{\"type\":\"query\",\"ops\":[\"" + op + "\"]}");
        }

        static byte[] status(String op, String state, byte[] body) {
            return outcome("status", op, state, body);
        }

        static byte[] subscribe(List<String> topics, Map<String, String> cursors) {
            if (topics == null || topics.isEmpty()) {
                throw new IllegalArgumentException("subscribe");
            }
            StringBuilder text = new StringBuilder();
            text.append("{\"type\":\"subscribe\",\"topics\":[");
            for (int i = 0; i < topics.size(); i++) {
                if (i > 0) {
                    text.append(',');
                }
                String topic = topics.get(i);
                if (topic == null || !topic.matches("[a-z]{1,32}")) {
                    throw new IllegalArgumentException("subscribe");
                }
                text.append('"').append(topic).append('"');
            }
            text.append(']');
            if (cursors != null && !cursors.isEmpty()) {
                text.append(",\"cursors\":{");
                boolean first = true;
                for (Map.Entry<String, String> entry : cursors.entrySet()) {
                    if (entry.getKey() == null || !entry.getKey().matches("[a-z]{1,32}")
                            || entry.getValue() == null || !entry.getValue().matches("0|[1-9][0-9]{0,17}")) {
                        throw new IllegalArgumentException("subscribe");
                    }
                    if (!first) {
                        text.append(',');
                    }
                    first = false;
                    text.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
                }
                text.append('}');
            }
            return ascii(text.append('}').toString());
        }

        private static byte[] outcome(String type, String op, String state, byte[] body) {
            String text = "{\"type\":\"" + type + "\",\"op\":\"" + op + "\",\"state\":\"" + state + "\"";
            if (body != null) {
                text += ",\"result\":\"" + Base64.getEncoder().encodeToString(body) + "\"";
            }
            return ascii(text + "}");
        }

        static Msg parse(byte[] raw) {
            String text = utf8(raw);
            if (text.length() > 8192) {
                throw new IllegalArgumentException("rejected");
            }
            Parser parser = new Parser(text);
            parser.skip();
            if (parser.peek() != '{') {
                throw new IllegalArgumentException("rejected");
            }
            parser.i++;
            Map<String, Object> fields = new LinkedHashMap<>();
            parser.skip();
            if (parser.peek() != '}') {
                while (true) {
                    String key = parser.string();
                    if (fields.containsKey(key) || !known(key)) {
                        throw new IllegalArgumentException("rejected");
                    }
                    parser.skip();
                    if (parser.peek() != ':') {
                        throw new IllegalArgumentException("rejected");
                    }
                    parser.i++;
                    parser.skip();
                    fields.put(key, parser.value());
                    parser.skip();
                    if (parser.peek() == '}') {
                        break;
                    }
                    if (parser.peek() != ',') {
                        throw new IllegalArgumentException("rejected");
                    }
                    parser.i++;
                    parser.skip();
                }
            }
            parser.i++;
            parser.skip();
            if (parser.i != text.length()) {
                throw new IllegalArgumentException("rejected");
            }
            String type = text(fields, "type");
            boolean protocolSet = fields.containsKey("protocol");
            int protocol = 0;
            if (protocolSet) {
                Object value = fields.get("protocol");
                if (!(value instanceof Integer) || ((Integer) value).intValue() != 2) {
                    throw new IllegalArgumentException("rejected");
                }
                protocol = 2;
            }
            return new Msg(type, protocolSet, protocol,
                    bytes(fields, "deviceId", 16), bytes(fields, "nonce", 32), bytes(fields, "sig", 0),
                    text(fields, "code"), text(fields, "op"), text(fields, "method"), text(fields, "params"),
                    text(fields, "state"), text(fields, "result"), strings(fields, "ops"),
                    strings(fields, "topics"), maps(fields, "cursors"), strings(fields, "rejected"), strings(fields, "reasons"),
                    text(fields, "topic"), text(fields, "cursor"), text(fields, "body"),
                    text(fields, "name"), number(fields, "size"), number(fields, "channel"));
        }

        private static boolean known(String key) {
            return "type".equals(key) || "protocol".equals(key) || "deviceId".equals(key) || "nonce".equals(key)
                    || "sig".equals(key) || "code".equals(key) || "op".equals(key) || "method".equals(key)
                    || "params".equals(key) || "state".equals(key) || "result".equals(key) || "ops".equals(key)
                    || "topics".equals(key) || "cursors".equals(key) || "rejected".equals(key) || "reasons".equals(key)
                    || "topic".equals(key) || "cursor".equals(key) || "body".equals(key)
                    || "name".equals(key) || "size".equals(key) || "channel".equals(key);
        }

        private static Integer number(Map<String, Object> fields, String key) {
            if (!fields.containsKey(key)) {
                return null;
            }
            Object value = fields.get(key);
            if (!(value instanceof Integer)) {
                throw new IllegalArgumentException("rejected");
            }
            return (Integer) value;
        }

        private static Map<String, String> maps(Map<String, Object> fields, String key) {
            if (!fields.containsKey(key)) {
                return null;
            }
            Object value = fields.get(key);
            if (!(value instanceof Map)) {
                throw new IllegalArgumentException("rejected");
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                    throw new IllegalArgumentException("rejected");
                }
                out.put((String) entry.getKey(), (String) entry.getValue());
            }
            return out;
        }

        private static List<String> strings(Map<String, Object> fields, String key) {
            if (!fields.containsKey(key)) {
                return null;
            }
            Object value = fields.get(key);
            if (!(value instanceof List)) {
                throw new IllegalArgumentException("rejected");
            }
            List<String> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException("rejected");
                }
                out.add((String) item);
            }
            return out;
        }

        private static String text(Map<String, Object> fields, String key) {
            if (!fields.containsKey(key)) {
                return null;
            }
            Object value = fields.get(key);
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("rejected");
            }
            return (String) value;
        }

        private static byte[] bytes(Map<String, Object> fields, String key, int size) {
            String text = text(fields, key);
            if (text == null) {
                return null;
            }
            return unhex(text, size);
        }

        private static byte[] ascii(String text) {
            return text.getBytes(StandardCharsets.US_ASCII);
        }
    }

    private static boolean shareName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c > 0x7e || c == '"' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    private static String utf8(byte[] raw) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("rejected");
        }
    }

    private static final class Parser {
        final String text;
        int i;

        Parser(String text) {
            this.text = text;
        }

        void skip() {
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                    return;
                }
                i++;
            }
        }

        char peek() {
            if (i >= text.length()) {
                throw new IllegalArgumentException("rejected");
            }
            return text.charAt(i);
        }

        Object value() {
            char c = peek();
            if (c == '"') {
                return string();
            }
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c >= '0' && c <= '9') {
                int start = i;
                while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                    i++;
                }
                String number = text.substring(start, i);
                if (number.length() > 1 && number.charAt(0) == '0') {
                    throw new IllegalArgumentException("rejected");
                }
                return Integer.valueOf(number);
            }
            throw new IllegalArgumentException("rejected");
        }

        Map<String, String> object() {
            if (peek() != '{') {
                throw new IllegalArgumentException("rejected");
            }
            i++;
            Map<String, String> out = new LinkedHashMap<>();
            skip();
            if (peek() == '}') {
                i++;
                return out;
            }
            while (true) {
                String key = string();
                if (out.containsKey(key)) {
                    throw new IllegalArgumentException("rejected");
                }
                skip();
                if (peek() != ':') {
                    throw new IllegalArgumentException("rejected");
                }
                i++;
                skip();
                out.put(key, string());
                skip();
                if (peek() == '}') {
                    i++;
                    return out;
                }
                if (peek() != ',') {
                    throw new IllegalArgumentException("rejected");
                }
                i++;
                skip();
            }
        }

        List<String> array() {
            if (peek() != '[') {
                throw new IllegalArgumentException("rejected");
            }
            i++;
            List<String> out = new ArrayList<>();
            skip();
            if (peek() == ']') {
                i++;
                return out;
            }
            while (true) {
                out.add(string());
                skip();
                if (peek() == ']') {
                    i++;
                    return out;
                }
                if (peek() != ',') {
                    throw new IllegalArgumentException("rejected");
                }
                i++;
                skip();
            }
        }

        String string() {
            if (peek() != '"') {
                throw new IllegalArgumentException("rejected");
            }
            i++;
            StringBuilder out = new StringBuilder();
            while (i < text.length()) {
                char c = text.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\' || c < 0x20) {
                    throw new IllegalArgumentException("rejected");
                }
                out.append(c);
            }
            throw new IllegalArgumentException("rejected");
        }
    }
}
