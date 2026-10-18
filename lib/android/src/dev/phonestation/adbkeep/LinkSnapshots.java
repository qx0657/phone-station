package dev.phonestation.adbkeep;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 链路状态主题的正文。只包含电量、授权和能力，不含令牌。 */
final class LinkSnapshots {
    private LinkSnapshots() {}

    static byte[] device(int battery, boolean charging, String network, boolean screenOn, boolean adb, boolean adbWifi) {
        return device(battery, charging, network, screenOn, adb, adbWifi, null);
    }

    /** issues 为 null 时不写这个字段，旧快照仍能让菜单栏继续轮询。 */
    static byte[] device(int battery, boolean charging, String network, boolean screenOn, boolean adb, boolean adbWifi, Json issues) {
        if (battery < 0 || battery > 100) {
            throw new IllegalArgumentException("device");
        }
        if (!"wifi".equals(network) && !"cellular".equals(network) && !"none".equals(network)) {
            throw new IllegalArgumentException("device");
        }
        Json body = Json.obj()
                .put("batteryPercent", battery)
                .put("charging", charging)
                .put("network", network)
                .put("screenOn", screenOn)
                .put("adb", adb)
                .put("adbWifi", adbWifi);
        if (issues != null) {
            body.put("issues", issues);
        }
        return body.emit().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] grants(boolean granted) {
        return Json.obj().put("granted", granted).emit().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] capabilities(boolean shizuku, boolean verified, Map<String, Boolean> features) {
        Json selected = Json.obj();
        for (String key : FeaturePolicy.KEYS) {
            Boolean value = features == null ? null : features.get(key);
            selected.put(key, value != null && value);
        }
        return Json.obj()
                .put("shizuku", shizuku)
                .put("verified", verified)
                .put("features", selected)
                .emit()
                .getBytes(StandardCharsets.UTF_8);
    }
}
