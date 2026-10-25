package dev.phonestation.adbkeep;

final class FindPhoneVolumeTest {
    public static void main(String[] args) {
        check(FindPhoneVolume.shouldRestore(7, false, 7), "restore our temporary volume after stop or process restart");
        check(!FindPhoneVolume.shouldRestore(3, false, 7), "preserve a user's quieter volume");
        check(!FindPhoneVolume.shouldRestore(7, true, 7), "preserve a user's mute action");
        check(!FindPhoneVolume.shouldRestore(0, false, -1), "do not restore an invalid snapshot");
        check("alerts".equals(FeaturePolicy.feature("station_find_phone")), "ringing obeys the existing phone alert choice");
        check(!new RemotePolicy(java.util.Set.of()).allows("station_find_phone"), "ungranted remote cannot ring");
        check(new RemotePolicy(java.util.Set.of("controls")).allows("station_find_phone"), "controls grant includes finding phone");
        check("station_find_phone".equals(McpCatalog.legacy("device_control", Json.obj().put("action", "find_phone"))), "AI action reaches ring handler");
        check("find.phone".equals(LinkTools.method("station_find_phone")), "local and remote link share the same action");
        System.out.println("FindPhoneVolumeTest passed");
    }
    private static void check(boolean ok, String why) { if (!ok) { throw new AssertionError(why); } }
}
