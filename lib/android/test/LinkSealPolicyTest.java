package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkEngine;
import dev.phonestation.adbkeep.link.LinkFrame;
import dev.phonestation.adbkeep.link.LinkSeal;
import dev.phonestation.adbkeep.link.LinkSealPolicy;
import dev.phonestation.adbkeep.link.LinkSealSession;
import dev.phonestation.adbkeep.link.LocalLink;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 必须加密拒绝明文，本地链路不加密，重放和换钥。不连接手机。 */
final class LinkSealPolicyTest {
    public static void main(String[] args) throws Exception {
        new LinkSealPolicy(false, false).admit(0);
        try {
            new LinkSealPolicy(true, false).admit(0);
            throw new AssertionError("plaintext");
        } catch (IllegalArgumentException expected) {
            if (!"plaintext".equals(expected.getMessage())) {
                throw new AssertionError(expected.getMessage());
            }
        }
        byte[] secret = "media-plain-secret".getBytes(StandardCharsets.UTF_8);
        try {
            LinkSealPolicy.admitApplication(true, 0, LinkFrame.KIND_MESSAGE, secret);
            throw new AssertionError("plaintext");
        } catch (IllegalArgumentException expected) {
            if (!"plaintext".equals(expected.getMessage())) {
                throw new AssertionError(expected.getMessage());
            }
        }
        byte[] kept = LinkSealPolicy.admitApplication(true, 0x01, LinkFrame.KIND_DATA, secret);
        if (!Arrays.equals(kept, secret)) {
            throw new AssertionError("sealed");
        }
        new LinkSealPolicy(true, false).admit(0x01);
        new LinkSealPolicy(true, true).admit(0);
        try {
            new LinkSealPolicy(false, true).admit(0x01);
            throw new AssertionError("local");
        } catch (IllegalArgumentException expected) {
            if (!"local".equals(expected.getMessage())) {
                throw new AssertionError(expected.getMessage());
            }
        }
        LinkSealPolicy window = new LinkSealPolicy(true, false);
        long now = 1_700_000_000_000L;
        window.accept(1, now);
        try {
            window.accept(1, now);
            throw new AssertionError("replay");
        } catch (IllegalArgumentException expected) {
            if (!"replay".equals(expected.getMessage())) {
                throw new AssertionError(expected.getMessage());
            }
        }
        window.accept(2, now);
        window.setFrames(1L << 32);
        try {
            window.accept(3, now);
            throw new AssertionError("rekey frames");
        } catch (IllegalArgumentException expected) {
            if (!"rekey".equals(expected.getMessage())) {
                throw new AssertionError(expected.getMessage());
            }
        }
        LinkSealPolicy aged = new LinkSealPolicy(true, false);
        aged.accept(1, now);
        aged.setStart(now - 24L * 60L * 60L * 1000L);
        try {
            aged.accept(2, now);
            throw new AssertionError("rekey age");
        } catch (IllegalArgumentException expected) {
            if (!"rekey".equals(expected.getMessage())) {
                throw new AssertionError(expected.getMessage());
            }
        }
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        byte[] nonce = LinkSeal.nonce("mac-to-phone", 9);
        byte[] aad = LinkSeal.aad(3, 0x01, 0, 9);
        byte[] sealed = LinkSeal.seal(key, nonce, "hello link".getBytes(java.nio.charset.StandardCharsets.US_ASCII), aad);
        sealed[sealed.length - 1] ^= 1;
        try {
            LinkSeal.open(key, nonce, sealed, aad);
            throw new AssertionError("tamper");
        } catch (IllegalArgumentException expected) {
            if (!"open".equals(expected.getMessage())) {
                throw new AssertionError(expected.getMessage());
            }
        }
        sessionRoundTrip();
        store();
        System.out.println("link seal policy ok");
    }

    private static void sessionRoundTrip() throws Exception {
        LocalLink.Device mac = LocalLink.Device.generate();
        LocalLink.Device phone = LocalLink.Device.generate();
        LinkSealSession macSeal = LinkSealSession.required(true, mac, phone.id, phone.spki);
        LinkSealSession phoneSeal = LinkSealSession.required(false, phone, mac.id, mac.spki);
        LinkEngine macEngine = new LinkEngine(true, 0, 0, 0, 1);
        LinkEngine phoneEngine = new LinkEngine(false, 0, 0, 0, 1);
        macEngine.setPeer(phone.id);
        phoneEngine.setPeer(mac.id);
        macEngine.useSeal(macSeal);
        phoneEngine.useSeal(phoneSeal);
        macEngine.sendMessage(0, 0, LinkFrame.KIND_HANDSHAKE, macSeal.openPayload());
        phoneEngine.accept(macEngine.pull());
        phoneEngine.sendMessage(0, 0, LinkFrame.KIND_HANDSHAKE, phoneSeal.answer(phoneEngine.inbox().get(0).payload));
        macEngine.accept(phoneEngine.pull());
        macSeal.finish(macEngine.inbox().get(0).payload);
        byte[] secret = "seal-body-9f3a".getBytes(StandardCharsets.US_ASCII);
        macEngine.sendMessage(0, 0, LinkFrame.KIND_MESSAGE, secret);
        LinkEngine.Frame sealed = macEngine.pull();
        if (sealed == null || new String(sealed.payload, StandardCharsets.US_ASCII).contains("seal-body-9f3a")) {
            throw new AssertionError("visible");
        }
        phoneEngine.accept(sealed);
        if (!Arrays.equals(secret, phoneEngine.inbox().get(0).payload)) {
            throw new AssertionError("opened");
        }
        LinkEngine bare = new LinkEngine(true, 0, 0, 0, 1);
        bare.useSeal(LinkSealSession.required(true, mac, phone.id, phone.spki));
        bare.sendMessage(0, 0, LinkFrame.KIND_MESSAGE, secret);
        if (bare.pull() != null || bare.failed() == null || !"plaintext".equals(bare.failed().getMessage())) {
            throw new AssertionError("plaintext");
        }
    }

    private static void store() throws Exception {
        Path dir = Files.createTempDirectory("seal-store");
        byte[] id = new byte[16];
        id[0] = 1;
        if (LinkSealStore.mode(null, id) != 0 || LinkSealStore.mode(dir, id) != 0) {
            throw new AssertionError("default");
        }
        Files.writeString(dir.resolve("link-seal"), "01000000000000000000000000000000\n");
        if (LinkSealStore.mode(dir, id) != 1) {
            throw new AssertionError("required");
        }
        Files.writeString(dir.resolve("link-seal"), "not-a-device\n");
        if (LinkSealStore.mode(dir, id) != 0) {
            throw new AssertionError("other");
        }
    }
}
