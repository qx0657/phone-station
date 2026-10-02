package dev.phonestation.adbkeep;

/** 仅在本次进程中记录升级助手的启动标记，普通打开和旧任务不能确认新的自动恢复。 */
final class InstallRecovery {
    static final String EXTRA = "phone_station_install_job";
    private static volatile String serviceJob = "";
    private static volatile String activityJob = "";

    static void service(String job) { if (valid(job)) { serviceJob = job; } }
    static void activity(String job) { if (valid(job)) { activityJob = job; } }
    static boolean serviceFor(String job) { return valid(job) && job.equals(serviceJob); }
    static boolean activityFor(String job) { return valid(job) && job.equals(activityJob); }

    private static boolean valid(String job) { return job != null && job.matches("[0-9a-f]{32}"); }
    private InstallRecovery() {}
}
