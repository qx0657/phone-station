package dev.phonestation.adbkeep.link;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 中继 6 的设备侧握手。只连接 127.0.0.1，先核对证书名字和 SPKI 摘要，再读 hello、发 auth、等 ready。
 * 服务端是测试进程里的中继 6。FileMcpService、网关和正在运行的中继 5 不调用。
 */
public final class RemoteLink {
    private RemoteLink() {}

    public static final class Session implements AutoCloseable {
        private final LocalWire wire;
        private final boolean ready;

        Session(LocalWire wire) {
            this.wire = wire;
            this.ready = true;
        }

        public boolean ready() {
            return ready;
        }

        @Override
        public void close() {
            wire.close();
        }
    }

    /**
     * 字面 IPv4 和端口。域名、空地址和 0.0.0.0 拒绝。不打开套接字。
     * {@link #dial} 目前仍只连接 127.0.0.1。
     */
    public static void literalIPv4(String host, int port) {
        if (host == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("host");
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("host");
        }
        boolean unspecified = true;
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                throw new IllegalArgumentException("host");
            }
            int value = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    throw new IllegalArgumentException("host");
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255 || (index == 0 && value >= 224)) {
                throw new IllegalArgumentException("host");
            }
            if (value != 0) {
                unspecified = false;
            }
        }
        if (unspecified) {
            throw new IllegalArgumentException("host");
        }
    }

    /** purpose 是 main 或 media。pin 是服务器证书 SPKI 的 SHA-256。 */
    public static Session dial(LocalLink.Device self, String host, int port, byte[] pin, String purpose, String app)
            throws Exception {
        return new Session(open(self, host, port, pin, purpose, app));
    }

    /**
     * 用邀请加入中继。批准到达前一直等到 ready，然后关掉这条连接。
     * 之后的主链路仍走 {@link #connectPinned}。邀请密钥只用于 proof。
     */
    public static void join(LocalLink.Device self, String host, int port, byte[] pin, byte[] invitation, byte[] inviteKey,
            String name) throws Exception {
        if (invitation == null || invitation.length != 16 || inviteKey == null || inviteKey.length != 16 || !joinName(name)) {
            throw new IllegalArgumentException("join");
        }
        LocalWire wire = connect(self, host, port, pin, "main", "phone", false);
        try {
            finishJoin(wire, self, invitation, inviteKey, name);
        } finally {
            wire.close();
        }
    }

    /**
     * 握手之后可以 call 和 query。peer 是对端 deviceId，发出的帧写这个地址，中继按它转发。
     * 手机侧传入和本地会话同一个 {@link LinkReceipts}；Mac 侧不传回执库。
     * FileMcpService、网关和正在运行的中继 5 不调用。
     */
    public static LocalLink.Session connect(LocalLink.Device self, String host, int port, byte[] pin, String purpose,
            String app, byte[] peer, LinkReceipts receipts, Callable<byte[]> work) throws Exception {
        if (peer == null || peer.length != 16 || (receipts == null) != (work == null)) {
            throw new IllegalArgumentException("session");
        }
        if (receipts != null) {
            receipts.ensureEvents(null);
        }
        return connect(self, host, port, pin, purpose, app, peer, receipts, work, true);
    }

    /**
     * 和 {@link #connect} 相同，但允许字面 IPv4。连上的地址必须就是这个 IP，证书名和 SPKI 仍要相符。
     * 域名在打开套接字前拒绝。
     */
    public static LocalLink.Session connectPinned(LocalLink.Device self, String host, int port, byte[] pin, String purpose,
            String app, byte[] peer, LinkReceipts receipts, Callable<byte[]> work) throws Exception {
        if (peer == null || peer.length != 16 || (receipts == null) != (work == null)) {
            throw new IllegalArgumentException("session");
        }
        if (receipts != null) {
            receipts.ensureEvents(null);
        }
        return connect(self, host, port, pin, purpose, app, peer, receipts, work, false);
    }

    /** 手机侧按方法分发。允许字面 IPv4，连上的地址必须就是这个 IP。 */
    public static LocalLink.Session connectPinned(LocalLink.Device self, String host, int port, byte[] pin, String purpose,
            String app, byte[] peer, LinkReceipts receipts, LocalLink.Call call) throws Exception {
        if (peer == null || peer.length != 16 || receipts == null || call == null) {
            throw new IllegalArgumentException("session");
        }
        receipts.ensureEvents(null);
        LocalWire wire = open(self, host, port, pin, purpose, app, false);
        try {
            return LocalLink.attach(wire, false, peer, receipts, null, call, true);
        } catch (RuntimeException error) {
            wire.close();
            throw error;
        }
    }

    /** 必须加密的远程会话。握手完成前不发送应用明文。 */
    public static LocalLink.Session connectPinnedSealed(LocalLink.Device self, String host, int port, byte[] pin, String purpose,
            String app, byte[] peer, byte[] peerSpki) throws Exception {
        if (peer == null || peer.length != 16 || peerSpki == null || peerSpki.length == 0) {
            throw new IllegalArgumentException("session");
        }
        LocalWire wire = open(self, host, port, pin, purpose, app, false);
        try {
            LinkSealSession seal = LinkSealSession.required(true, self, peer, peerSpki);
            return LocalLink.attach(wire, true, peer, null, null, null, true, seal);
        } catch (RuntimeException error) {
            wire.close();
            throw error;
        }
    }

    /** 手机侧必须加密。明文应用帧拒绝，不降级。 */
    public static LocalLink.Session connectPinnedSealed(LocalLink.Device self, String host, int port, byte[] pin, String purpose,
            String app, byte[] peer, byte[] peerSpki, LinkReceipts receipts, LocalLink.Call call) throws Exception {
        if (peer == null || peer.length != 16 || peerSpki == null || peerSpki.length == 0 || receipts == null || call == null) {
            throw new IllegalArgumentException("session");
        }
        receipts.ensureEvents(null);
        LocalWire wire = open(self, host, port, pin, purpose, app, false);
        try {
            LinkSealSession seal = LinkSealSession.required(false, self, peer, peerSpki);
            return LocalLink.attach(wire, false, peer, receipts, null, call, true, seal);
        } catch (RuntimeException error) {
            wire.close();
            throw error;
        }
    }

    private static LocalLink.Session connect(LocalLink.Device self, String host, int port, byte[] pin, String purpose,
            String app, byte[] peer, LinkReceipts receipts, Callable<byte[]> work, boolean loopbackOnly) throws Exception {
        LocalWire wire = open(self, host, port, pin, purpose, app, loopbackOnly);
        try {
            return LocalLink.attach(wire, receipts == null, peer, receipts, work, true);
        } catch (RuntimeException error) {
            wire.close();
            throw error;
        }
    }

    private static LocalWire open(LocalLink.Device self, String host, int port, byte[] pin, String purpose, String app)
            throws Exception {
        return open(self, host, port, pin, purpose, app, true);
    }

    private static LocalWire open(LocalLink.Device self, String host, int port, byte[] pin, String purpose, String app,
            boolean loopbackOnly) throws Exception {
        LocalWire wire = connect(self, host, port, pin, purpose, app, loopbackOnly);
        try {
            finish(wire, self, purpose, app);
            wire.relax();
            return wire;
        } catch (Exception error) {
            wire.close();
            throw error;
        }
    }

    private static LocalWire connect(LocalLink.Device self, String host, int port, byte[] pin, String purpose, String app,
            boolean loopbackOnly) throws Exception {
        literalIPv4(host, port);
        if (loopbackOnly && !"127.0.0.1".equals(host)) {
            throw new IllegalArgumentException("host");
        }
        if (!"main".equals(purpose) && !"media".equals(purpose)) {
            throw new IllegalArgumentException("purpose");
        }
        if (app == null || app.isEmpty() || app.length() > 64 || !plain(app)) {
            throw new IllegalArgumentException("app");
        }
        if (pin == null || pin.length != 32) {
            throw new IllegalArgumentException("pin");
        }
        if (self == null || self.signer == null) {
            throw new IllegalArgumentException("device");
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trust(host, pin)}, new SecureRandom());
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        LocalWire wire = null;
        try {
            socket.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1.3"});
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 5000);
            InetAddress connected = socket.getInetAddress();
            if (connected == null || connected.isMulticastAddress()
                    || (loopbackOnly && !connected.isLoopbackAddress())
                    || (!loopbackOnly && !host.equals(connected.getHostAddress()))) {
                throw new IllegalArgumentException("host");
            }
            socket.startHandshake();
            wire = LocalWire.client(socket, host, port);
            return wire;
        } catch (Exception error) {
            if (wire != null) {
                wire.close();
            } else {
                socket.close();
            }
            throw error;
        }
    }

    private static void finish(LocalWire wire, LocalLink.Device self, String purpose, String app) throws Exception {
        Msg hello = read(wire);
        if (hello.code != null) {
            throw refusal(hello);
        }
        if (!"hello".equals(hello.type) || hello.protocol == null || hello.protocol.longValue() != 2
                || hello.stationId == null || hello.nonce == null) {
            throw new IllegalArgumentException("rejected");
        }
        byte[] transcript = LinkAuth.relayMessage(unhex(hello.stationId, 16), unhex(hello.nonce, 32), self.id, purpose);
        byte[] sig = self.signer.sign(transcript);
        if (sig == null || sig.length == 0) {
            throw new IllegalArgumentException("sign");
        }
        String json = "{\"type\":\"auth\",\"deviceId\":\"" + hex(self.id)
                + "\",\"purpose\":\"" + purpose
                + "\",\"app\":\"" + app
                + "\",\"sig\":\"" + hex(sig) + "\"}";
        wire.writeData(LinkFrame.encode(LinkFrame.KIND_LINK, 0, new byte[16], 0, 0,
                json.getBytes(StandardCharsets.UTF_8), LinkFrame.MAIN_PAYLOAD));
        Msg reply = read(wire);
        if ("error".equals(reply.type)) {
            throw refusal(reply);
        }
        if (!"ready".equals(reply.type) || reply.code != null) {
            throw new IllegalArgumentException("ready");
        }
    }

    private static void finishJoin(LocalWire wire, LocalLink.Device self, byte[] invitation, byte[] inviteKey, String name)
            throws Exception {
        Msg hello = read(wire);
        if (hello.code != null) {
            throw refusal(hello);
        }
        if (!"hello".equals(hello.type) || hello.protocol == null || hello.protocol.longValue() != 2
                || hello.stationId == null || hello.nonce == null) {
            throw new IllegalArgumentException("rejected");
        }
        byte[] station = unhex(hello.stationId, 16);
        byte[] nonce = unhex(hello.nonce, 32);
        byte[] transcript = LinkAuth.joinMessage(station, nonce, self.id, invitation, "main");
        byte[] sig = self.signer.sign(transcript);
        byte[] proof = LinkAuth.invitationProof(inviteKey, self.spki, name, "phone", "main");
        if (sig == null || sig.length == 0 || proof == null || proof.length != 32) {
            throw new IllegalArgumentException("sign");
        }
        String json = "{\"type\":\"join\",\"invitationId\":\"" + hex(invitation)
                + "\",\"publicKey\":\"" + java.util.Base64.getEncoder().encodeToString(self.spki)
                + "\",\"name\":\"" + name
                + "\",\"kind\":\"phone\",\"purpose\":\"main\",\"proof\":\"" + hex(proof)
                + "\",\"sig\":\"" + hex(sig) + "\"}";
        wire.writeData(LinkFrame.encode(LinkFrame.KIND_LINK, 0, new byte[16], 0, 0,
                json.getBytes(StandardCharsets.UTF_8), LinkFrame.MAIN_PAYLOAD));
        wire.await(25000);
        Msg reply = null;
        for (int i = 0; i < 4; i++) {
            reply = read(wire);
            if ("error".equals(reply.type)) {
                throw refusal(reply);
            }
            if ("ready".equals(reply.type) && reply.code == null) {
                return;
            }
        }
        throw new IllegalArgumentException(reply == null ? "ready" : reply.type);
    }

    private static boolean joinName(String name) {
        if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > 64) {
            return false;
        }
        for (int i = 0; i < name.length(); ) {
            int code = name.codePointAt(i);
            if (code < 0x20 || code == '"' || code == '\\') {
                return false;
            }
            i += Character.charCount(code);
        }
        return true;
    }

    private static IllegalArgumentException refusal(Msg msg) {
        if ("bad_signature".equals(msg.code) || "unknown_device".equals(msg.code) || "revoked".equals(msg.code)
                || "rejected".equals(msg.code) || "rate_limited".equals(msg.code) || "unavailable".equals(msg.code)
                || "undeliverable".equals(msg.code)) {
            return new IllegalArgumentException(msg.code);
        }
        return new IllegalArgumentException("rejected");
    }

    private static Msg read(LocalWire wire) throws IOException {
        LinkFrame frame = LinkFrame.decode(wire.readData(), LinkFrame.MAIN_PAYLOAD);
        if (frame.kind != LinkFrame.KIND_LINK || frame.payload.length == 0 || frame.payload.length > 4096) {
            throw new IllegalArgumentException("rejected");
        }
        return Msg.read(utf8(frame.payload));
    }

    private static boolean plain(String app) {
        for (int i = 0; i < app.length(); i++) {
            char c = app.charAt(i);
            if (c < 0x20 || c == '"' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    private static X509TrustManager trust(String host, byte[] pin) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("client");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("pin");
                }
                X509Certificate leaf = chain[0];
                if (!hostMatches(leaf, host)) {
                    throw new CertificateException("name");
                }
                try {
                    byte[] sum = MessageDigest.getInstance("SHA-256").digest(leaf.getPublicKey().getEncoded());
                    if (!MessageDigest.isEqual(sum, pin)) {
                        throw new CertificateException("pin");
                    }
                } catch (GeneralSecurityException error) {
                    throw new CertificateException("pin");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static boolean hostMatches(X509Certificate leaf, String host) {
        Collection<List<?>> names;
        try {
            names = leaf.getSubjectAlternativeNames();
        } catch (CertificateParsingException error) {
            return false;
        }
        if (names == null) {
            return false;
        }
        for (List<?> item : names) {
            if (item.size() < 2 || !(item.get(0) instanceof Integer) || ((Integer) item.get(0)).intValue() != 7) {
                continue;
            }
            if (host.equals(item.get(1))) {
                return true;
            }
        }
        return false;
    }

    private static String hex(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            out.append(Character.forDigit((value >> 4) & 0xf, 16)).append(Character.forDigit(value & 0xf, 16));
        }
        return out.toString();
    }

    private static byte[] unhex(String text, int size) {
        if (text == null || text.length() != size * 2 || !text.equals(text.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("rejected");
        }
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            int high = Character.digit(text.charAt(i * 2), 16);
            int low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("rejected");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
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

    private static final class Msg {
        String type;
        Long protocol;
        String stationId;
        String nonce;
        String code;

        static Msg read(String text) {
            Parser parser = new Parser(text);
            parser.skip();
            if (parser.peek() != '{') {
                throw new IllegalArgumentException("rejected");
            }
            parser.i++;
            Msg msg = new Msg();
            Set<String> seen = new HashSet<>();
            parser.skip();
            if (parser.peek() != '}') {
                while (true) {
                    String key = parser.string();
                    if (!seen.add(key) || !known(key)) {
                        throw new IllegalArgumentException("rejected");
                    }
                    parser.skip();
                    if (parser.peek() != ':') {
                        throw new IllegalArgumentException("rejected");
                    }
                    parser.i++;
                    parser.skip();
                    if ("protocol".equals(key) || "registryVersion".equals(key)) {
                        long value = parser.number();
                        if ("protocol".equals(key)) {
                            msg.protocol = Long.valueOf(value);
                        }
                    } else if ("peers".equals(key) || "devices".equals(key) || "online".equals(key)) {
                        parser.skipValue();
                    } else {
                        String value = parser.string();
                        if ("type".equals(key)) {
                            msg.type = value;
                        } else if ("stationId".equals(key)) {
                            msg.stationId = value;
                        } else if ("nonce".equals(key)) {
                            msg.nonce = value;
                        } else if ("code".equals(key)) {
                            msg.code = value;
                        }
                    }
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
            return msg;
        }

        private static boolean known(String key) {
            return "type".equals(key) || "protocol".equals(key) || "stationId".equals(key) || "nonce".equals(key)
                    || "deviceId".equals(key) || "purpose".equals(key) || "app".equals(key) || "sig".equals(key)
                    || "code".equals(key) || "registryVersion".equals(key) || "peers".equals(key)
                    || "devices".equals(key) || "online".equals(key);
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

        long number() {
            if (peek() < '0' || peek() > '9') {
                throw new IllegalArgumentException("rejected");
            }
            int start = i;
            while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                i++;
            }
            String digits = text.substring(start, i);
            if (digits.length() > 1 && digits.charAt(0) == '0') {
                throw new IllegalArgumentException("rejected");
            }
            if (digits.length() > 18) {
                throw new IllegalArgumentException("rejected");
            }
            return Long.parseLong(digits);
        }

        void skipValue() {
            char c = peek();
            if (c == '"') {
                string();
                return;
            }
            if (c == '{') {
                skipObject();
                return;
            }
            if (c == '[') {
                skipArray();
                return;
            }
            if (c >= '0' && c <= '9') {
                number();
                return;
            }
            if (text.startsWith("true", i)) {
                i += 4;
                return;
            }
            if (text.startsWith("false", i)) {
                i += 5;
                return;
            }
            if (text.startsWith("null", i)) {
                i += 4;
                return;
            }
            throw new IllegalArgumentException("rejected");
        }

        void skipObject() {
            if (peek() != '{') {
                throw new IllegalArgumentException("rejected");
            }
            i++;
            skip();
            if (peek() == '}') {
                i++;
                return;
            }
            while (true) {
                string();
                skip();
                if (peek() != ':') {
                    throw new IllegalArgumentException("rejected");
                }
                i++;
                skip();
                skipValue();
                skip();
                if (peek() == '}') {
                    i++;
                    return;
                }
                if (peek() != ',') {
                    throw new IllegalArgumentException("rejected");
                }
                i++;
                skip();
            }
        }

        void skipArray() {
            if (peek() != '[') {
                throw new IllegalArgumentException("rejected");
            }
            i++;
            skip();
            if (peek() == ']') {
                i++;
                return;
            }
            while (true) {
                skipValue();
                skip();
                if (peek() == ']') {
                    i++;
                    return;
                }
                if (peek() != ',') {
                    throw new IllegalArgumentException("rejected");
                }
                i++;
                skip();
            }
        }
    }
}
