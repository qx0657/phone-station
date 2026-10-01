package dev.phonestation.adbkeep;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** 内部存储上的路径规则。只做词法判断，不看链接，也不碰磁盘。 */
final class FilePolicy {
    final Path home;
    private final List<Path> denied;

    FilePolicy(Path home, String... denyRelative) {
        this.home = home.toAbsolutePath().normalize();
        denied = new ArrayList<Path>();
        for (int i = 0; i < denyRelative.length; i++) {
            denied.add(this.home.resolve(denyRelative[i]).normalize());
        }
    }

    Path lexical(String input) {
        String raw = input == null ? "" : input;
        if (raw.indexOf('\0') >= 0) {
            throw new FileFailure("路径里不能有空字符");
        }
        Path candidate;
        if (raw.isEmpty()) {
            candidate = home;
        } else if (raw.charAt(0) == '/') {
            candidate = Paths.get(raw);
        } else {
            candidate = home.resolve(raw);
        }
        Path normal = candidate.normalize();
        if (!normal.equals(home) && !normal.startsWith(home)) {
            throw new FileFailure("路径不在内部存储里");
        }
        for (int i = 0; i < denied.size(); i++) {
            Path deny = denied.get(i);
            if (normal.equals(deny) || normal.startsWith(deny)) {
                throw new FileFailure("这条路径不开放");
            }
        }
        return normal;
    }

    /** 删掉会一次清掉太大一块的目录。 */
    boolean broadDirectory(Path normal) {
        if (normal.equals(home)) {
            return true;
        }
        String[] locked = {"Android", "Android/data", "Android/obb"};
        for (int i = 0; i < locked.length; i++) {
            if (normal.equals(home.resolve(locked[i]).normalize())) {
                return true;
            }
        }
        return false;
    }

    List<String> deniedPaths() {
        List<String> paths = new ArrayList<String>();
        for (int i = 0; i < denied.size(); i++) {
            paths.add(denied.get(i).toString());
        }
        return paths;
    }
}

final class FileFailure extends RuntimeException {
    FileFailure(String message) {
        super(message);
    }
}
