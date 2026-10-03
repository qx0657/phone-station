package dev.phonestation.adbkeep;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** localhost 的 HTTP 边界。允许 adb 转发的端口，拒绝远端主机和跨端口网页来源。 */
final class LoopbackBoundary {
    static boolean allowed(String host, String origin) {
        URI target = authority(host);
        if (target == null) { return false; }
        if (origin == null) { return true; } // 原生 SDK 不发送 Origin。
        try {
            URI source = new URI(origin);
            if (!"http".equals(source.getScheme()) || source.getRawAuthority() == null
                    || source.getRawPath() == null || !source.getRawPath().isEmpty()
                    || source.getRawQuery() != null || source.getRawFragment() != null) { return false; }
            URI checked = authority(source.getRawAuthority());
            return checked != null && port(checked) == port(target);
        } catch (URISyntaxException invalid) { return false; }
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? 80 : uri.getPort(); }
    private static URI authority(String host) {
        if (host == null || host.isEmpty()) { return null; }
        try {
            URI uri = new URI("http://" + host);
            String name = uri.getHost();
            if (name == null || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) { return null; }
            name = name.toLowerCase(Locale.ROOT);
            if (!name.equals("localhost") && !name.equals("127.0.0.1") && !name.equals("[::1]")) { return null; }
            if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) { return null; }
            // URI 对结尾冒号有容错，HTTP Host 不接受这种歧义。
            if (host.endsWith(":")) { return null; }
            return uri;
        } catch (URISyntaxException invalid) { return null; }
    }
}
