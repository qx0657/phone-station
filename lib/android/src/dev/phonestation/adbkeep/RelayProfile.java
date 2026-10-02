package dev.phonestation.adbkeep;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/** HTTPS relay addresses, including default ports and reverse-proxy base paths. */
final class RelayProfile {
    private static final Pattern ENDPOINT = Pattern.compile(
            "https://(?:[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]+\\])"
            + "(?::[0-9]{1,5})?(?:/[A-Za-z0-9._~/-]*)?");

    private RelayProfile() {}

    static String validationMessage(String endpoint, String pin, String token,
            String savedEndpoint, String savedPin, boolean hasCredential) {
        String normalized = normalizeEndpoint(endpoint);
        if (normalized == null) { return "请输入有效的 HTTPS 中继地址，可包含端口和路径。"; }
        if (pin == null || !pin.matches("(?i)[0-9a-f]{64}")) {
            return "证书指纹需要 64 位十六进制的 SPKI SHA-256。";
        }
        if (token == null || token.isEmpty()) {
            return hasCredential && normalized.equals(savedEndpoint) && pin.equalsIgnoreCase(savedPin)
                    ? null : "首次配置或更换地址、指纹时，需要填写手机令牌。";
        }
        return token.matches("(?i)[0-9a-f]{64}") ? null : "手机令牌需要 64 位十六进制字符。";
    }

    static String normalizeEndpoint(String value) {
        if (value == null) { return null; }
        String endpoint = value.trim();
        if (!ENDPOINT.matcher(endpoint).matches()) { return null; }
        try {
            URI uri = new URI(endpoint);
            if (uri.getHost() == null || uri.getPort() == 0 || uri.getPort() > 65535) { return null; }
            for (String segment : uri.getPath().split("/")) {
                if (".".equals(segment) || "..".equals(segment)) { return null; }
            }
            return endpoint.replaceAll("/+$", "");
        } catch (URISyntaxException error) {
            return null;
        }
    }
}
