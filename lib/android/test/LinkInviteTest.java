package dev.phonestation.adbkeep;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import dev.phonestation.adbkeep.link.LinkAuth;
import dev.phonestation.adbkeep.link.LinkInvite;
import dev.phonestation.adbkeep.link.LinkQr;
import dev.phonestation.adbkeep.link.LinkSafety;
import dev.phonestation.adbkeep.link.LocalLink;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

/** 邀请文本、二维码解码、本地配对和撤销。不连接手机，不拨公网。 */
final class LinkInviteTest {
    public static void main(String[] args) throws Exception {
        String text = LinkInvite.text(
                "https://192.0.2.8:24443",
                "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
                "11111111111111111111111111111111",
                "22222222222222222222222222222222",
                "AQID",
                "phone");
        LinkInvite parsed = LinkInvite.parse(text);
        check("phone".equals(parsed.kind) && text.equals(parsed.text()), "round trip");
        try {
            LinkInvite.parse(text + "\n");
            check(false, "extra line");
        } catch (IllegalArgumentException expected) {
            check(true, "extra");
        }
        byte[] id = new byte[16];
        byte[] key = new byte[16];
        Arrays.fill(id, (byte) 0x11);
        Arrays.fill(key, (byte) 0x22);
        check("111111222222".equals(LinkInvite.manual(id, key)), "manual");
        byte[][] parts = LinkInvite.manualParts("111111222222");
        check(Arrays.equals(parts[0], Arrays.copyOf(id, 3)) && Arrays.equals(parts[1], Arrays.copyOf(key, 3)), "parts");
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 180, 180);
        int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                pixels[y * matrix.getWidth() + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
            }
        }
        check(text.equals(LinkQr.decode(pixels, matrix.getWidth(), matrix.getHeight())), "qr");
        String manifest = Files.readString(Path.of("lib/android/AndroidManifest.xml"));
        check(!manifest.contains("android.permission.CAMERA"), "no camera permission");
        check(manifest.contains("PairingActivity") && manifest.contains("IMAGE_CAPTURE"), "pairing entry");

        Path dir = Files.createTempDirectory("pair");
        LinkGrants grants = LinkGrants.open(dir.resolve("link-grants"));
        LocalLink.Device phone = LocalLink.Device.generate();
        LocalLink.Device mac = LocalLink.Device.generate();
        byte[] nonce = new byte[32];
        Arrays.fill(nonce, (byte) 7);
        byte[] sig = mac.signer.sign(LinkAuth.localPairMessage(mac.spki, nonce));
        String code = LinkSafety.local(phone.spki, mac.spki);
        LinkPairDesk desk = new LinkPairDesk(phone.spki, grants, ignored -> {});
        String reply = desk.accept(body(mac.spki, nonce, sig, "办公室").getBytes(StandardCharsets.UTF_8));
        check(reply.contains("\"granted\":false") && reply.contains(code), "pending");
        check(desk.pending().size() == 1, "one pending");
        try {
            desk.accept(body(mac.spki, nonce, new byte[] {1, 2, 3}, "办公室").getBytes(StandardCharsets.UTF_8));
            check(false, "bad signature");
        } catch (IllegalArgumentException expected) {
            check(true, "bad signature");
        }
        desk.allow(mac.spki);
        check(desk.pending().isEmpty() && desk.granted().size() == 1, "allowed");
        desk.revoke(mac.spki);
        check(desk.granted().isEmpty(), "revoked");

        LinkPairing.install(new LinkPairDesk(phone.spki, grants, ignored -> {}));
        McpHttp server = McpHttp.open(0, "test-token", null, null, "test");
        try {
            byte[] fresh = mac.signer.sign(LinkAuth.localPairMessage(mac.spki, nonce));
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + "/v2/pair").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setDoOutput(true);
            byte[] payload = body(mac.spki, nonce, fresh, "办公室").getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.getOutputStream().write(payload);
            check(connection.getResponseCode() == 200, "http pair");
            HttpURLConnection missing = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + "/mcp").openConnection();
            missing.setRequestMethod("POST");
            missing.setConnectTimeout(2000);
            missing.setReadTimeout(2000);
            missing.setDoOutput(true);
            missing.setFixedLengthStreamingMode(2);
            missing.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            check(missing.getResponseCode() == 404, "mcp is gone");
        } finally {
            LinkPairing.install(null);
            server.close();
        }
        System.out.println("link invite ok");
    }

    private static String body(byte[] spki, byte[] nonce, byte[] sig, String name) {
        return Json.obj()
                .put("spki", Base64.getEncoder().encodeToString(spki))
                .put("nonce", hex(nonce))
                .put("sig", hex(sig))
                .put("name", name)
                .emit();
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder();
        for (byte item : value) {
            out.append(String.format("%02x", item & 0xff));
        }
        return out.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
