package dev.phonestation.adbkeep;

import android.content.Context;

final class PhoneHealth {
    static Json read(Context context) {
        if (!StationFeatures.master(context)) { return Json.obj().put("issues", Json.arr()).put("stationEnabled", false); }
        PermissionProbe facts = PermissionProbe.read(context);
        PermissionCopy.Board permissions = FeatureReadiness.board(context, "");
        return HealthStatus.present(permissions, ShizukuShell.status(context), SharedClipboard.health(context),
                PhoneNotifications.status(context).put("remoteBlocked", HostProbe.remoteOnly(context) && !RemoteStore.permission(context, "personal")), KeeperStore.mcpEnabled(context), FileMcpService.listening() || PhoneRelayClient.connected(),
                RemoteStore.enabled(context), PhoneRelayClient.connected(), PhoneRelayClient.checking(),
                PhoneRelayClient.connectionLabel(context));
    }
}
