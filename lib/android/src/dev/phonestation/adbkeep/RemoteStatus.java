package dev.phonestation.adbkeep;

import android.content.Context;

/** 把链路主会话和旧令牌客户端收成界面上的一项远程状态。 */
final class RemoteStatus {
    private RemoteStatus() {}

    static boolean connected(Context context) {
        return RemoteChannel.connected(RemoteStore.enabled(context), FileMcpService.linkRemoteActive(),
                FileMcpService.linkRemoteOnline(), PhoneRelayClient.connected());
    }

    static boolean checking(Context context) {
        return RemoteChannel.checking(RemoteStore.enabled(context), FileMcpService.linkRemoteActive(),
                PhoneRelayClient.checking());
    }

    static String label(Context context) {
        return RemoteChannel.label(StationFeatures.master(context), RemoteStore.configured(context),
                RemoteStore.enabled(context), FileMcpService.linkRemoteActive(),
                FileMcpService.linkRemoteOnline(), PhoneRelayClient.connectionLabel(context));
    }
}
