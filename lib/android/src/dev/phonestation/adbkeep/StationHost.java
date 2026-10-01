package dev.phonestation.adbkeep;

/** 文件以外、要在手机应用进程里做的事。电脑上的测试换一个假的。 */
interface StationHost {
    Json status();

    Json stayAwake(boolean on);

    Json notify(String title, String text, boolean stack, String agent);

    Json clipboard(String text);

    Json open(String path);
}
