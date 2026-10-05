package dev.phonestation.adbkeep;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class FeaturePolicyTest {
    public static void main(String[] args) {
        Map<String, Boolean> selections = new HashMap<>();
        for (String key : FeaturePolicy.KEYS) { selections.put(key, true); }
        String[] calls = {"station_file_read_bytes", "station_file_delete", "station_file_open", "station_notify",
                "station_clipboard_exchange", "station_notification_poll", "station_screen_open", "station_screen_capture_start",
                "station_shell_start", "station_terminal_input", "station_torch", "station_stay_awake"};
        for (String tool : calls) {
            check(FeaturePolicy.allows(true, selections, tool), tool);
            check(!FeaturePolicy.allows(false, selections, tool), "master must deny " + tool);
            Map<String, Boolean> disabled = new HashMap<>(selections);
            disabled.put(FeaturePolicy.feature(tool), false);
            check(!FeaturePolicy.allows(true, disabled, tool), "child must deny " + tool);
        }
        selections.put("alerts", false);
        check(FeaturePolicy.allows(true, selections, "station_notification_poll"), "directions are independent");
        selections.put("notifications", false);
        selections.put("alerts", true);
        check(FeaturePolicy.allows(true, selections, "station_notify"), "reverse direction remains enabled");
        check(!FeaturePolicy.allows(true, selections, "station_new_unknown_tool"), "unknown tool denies by default");
        check(FeaturePolicy.allows(false, selections, "station_screen_close"), "cleanup is always possible");
        check(FeaturePolicy.allows(false, selections, "station_controls_status"), "metadata is safe");
        RemotePolicy remote = new RemotePolicy(Set.of());
        check(!remote.allows("station_notify"), "feature on must not grant remote access");
        check(FeaturePolicy.allows(true, selections, "station_notify"), "remote permission and intent remain independent");
        PermissionCopy.Board inactive = PermissionCopy.relevant(PermissionCopy.present(false, false, false, false, false, false, true), Set.of());
        check(inactive.missing == 0, "disabled features must not require permissions");
        PermissionCopy.Board alertOnly = PermissionCopy.relevant(PermissionCopy.present(false, false, false, false, false, false, true), Set.of("通知"));
        check(alertOnly.missing == 1 && alertOnly.rows.length == 1, "only notification permission for alerts");
        System.out.println("FeaturePolicyTest passed");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
