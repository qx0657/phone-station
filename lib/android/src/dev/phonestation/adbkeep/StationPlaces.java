package dev.phonestation.adbkeep;

/** 内部存储里约定的目录。路径是相对 Home 的，不看这些目录在不在。 */
final class StationPlaces {
    static final String INBOX = "Download/手机工位/inbox";
    static final String OUTBOX = "Download/手机工位/outbox";

    final String name;
    final String relative;

    private StationPlaces(String name, String relative) {
        this.name = name;
        this.relative = relative;
    }

    static StationPlaces[] standard() {
        return new StationPlaces[] {
            new StationPlaces("截图", "Pictures/Screenshots"),
            new StationPlaces("相机", "DCIM/Camera"),
            new StationPlaces("下载", "Download"),
            new StationPlaces("文档", "Documents"),
            new StationPlaces("电影", "Movies"),
            new StationPlaces("录音", "Sounds"),
        };
    }
}
