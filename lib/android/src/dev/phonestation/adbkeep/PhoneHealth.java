package dev.phonestation.adbkeep;

import android.content.Context;

final class PhoneHealth {
    static Json read(Context context) {
        PermissionProbe facts = PermissionProbe.read(context);
        PermissionCopy.Board permissions = PermissionCopy.present(facts.canWrite, facts.notifications,
                facts.banner, facts.storage, facts.battery, facts.alarm, KeeperStore.alertPops(context),
                ShizukuLink.read(context));
        return HealthStatus.present(permissions, ShizukuShell.status(context), SharedClipboard.health(context),
                PhoneNotifications.status(context), KeeperStore.mcpEnabled(context), FileMcpService.listening(),
                RemoteStore.enabled(context), PhoneRelayClient.connected(), PhoneRelayClient.checking(),
                PhoneRelayClient.connectionLabel(context));
    }
}
