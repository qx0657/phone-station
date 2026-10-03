package dev.phonestation.adbkeep;

/** 文件以外、要在手机应用进程里做的事。电脑上的测试换一个假的。 */
interface StationHost {
    Json status();

    Json stayAwake(boolean on);

    Json controlsStatus();

    Json screenCapture(String requestId);

    Json torch(boolean on);

    Json notify(String title, String text, String agent, String sound);

    Json clipboard(String text);

    Json clipboardGet();

    Json clipboardState(Json args);

    Json clipboardConfigure(Json args);

    Json clipboardExchange(Json args);

    Json notificationStatus();

    Json notificationConfigure(Json args);

    Json notificationPoll(Json args);

    Json notificationIcon(String packageName);

    Json open(String path);

    Json shellStatus();

    Json shellExecute(ShellRequest request);

    default Json shellStart(String jobId, ShellRequest request) { throw new FileFailure("请更新手机工位以支持后台任务"); }
    default Json captureStart(String jobId, String requestId) { throw new FileFailure("请更新手机工位以支持后台任务"); }
    default Json operationStatus(String jobId) { throw new FileFailure("请更新手机工位以支持后台任务"); }
    default Json terminalOpen(String id, int columns, int rows) { throw new FileFailure("请更新手机工位以支持远程终端"); }
    default Json terminalRead(String id, long offset) { throw new FileFailure("请更新手机工位以支持远程终端"); }
    default Json terminalInput(String id, long sequence, String hex) { throw new FileFailure("请更新手机工位以支持远程终端"); }
    default Json terminalResize(String id, int columns, int rows) { throw new FileFailure("请更新手机工位以支持远程终端"); }
    default Json terminalClose(String id) { throw new FileFailure("请更新手机工位以支持远程终端"); }
}
