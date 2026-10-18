package dev.phonestation.adbkeep;

/** 手机服务把配对台交给回环上的 /v2/pair。界面读同一份。 */
final class LinkPairing {
    static volatile LinkPairDesk desk;

    private LinkPairing() {}

    static void install(LinkPairDesk value) {
        desk = value;
    }

    static String offer(byte[] body) throws java.io.IOException {
        LinkPairDesk current = desk;
        if (current == null) {
            throw new IllegalStateException("pair");
        }
        return current.accept(body);
    }
}
