package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkAuth;
import dev.phonestation.adbkeep.link.LocalLink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 已允许的 Mac 公钥。一行一个 SPKI 的 Base64，不含私钥。打不开就谁也不放行。 */
final class LinkGrants {
    private final Path file;

    private LinkGrants(Path file) {
        this.file = file;
    }

    static LinkGrants open(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.write(file, new byte[0], StandardOpenOption.CREATE_NEW);
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return new LinkGrants(file);
    }

    List<LocalLink.Device> devices() {
        try {
            List<LocalLink.Device> allowed = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                byte[] spki = Base64.getDecoder().decode(trimmed);
                allowed.add(new LocalLink.Device(LinkAuth.deviceId(spki), spki, null));
            }
            return allowed;
        } catch (IOException | RuntimeException error) {
            return List.of();
        }
    }

    void grant(byte[] spki) throws IOException {
        String line = Base64.getEncoder().encodeToString(spki);
        for (LocalLink.Device existing : devices()) {
            if (java.util.Arrays.equals(existing.spki, spki)) {
                return;
            }
        }
        Files.write(file, (line + "\n").getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    void revoke(byte[] spki) throws IOException {
        if (spki == null || spki.length == 0) {
            throw new IllegalArgumentException("device");
        }
        List<String> kept = new ArrayList<>();
        boolean found = false;
        for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            byte[] existing = Base64.getDecoder().decode(trimmed);
            if (java.util.Arrays.equals(existing, spki)) {
                found = true;
                continue;
            }
            kept.add(trimmed);
        }
        if (!found) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (String line : kept) {
            body.append(line).append('\n');
        }
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(temp, body.toString().getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
}
