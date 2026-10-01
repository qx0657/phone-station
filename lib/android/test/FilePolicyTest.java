package dev.phonestation.adbkeep;

import java.nio.file.Paths;

final class FilePolicyTest {
    public static void main(String[] args) {
        FilePolicy policy = new FilePolicy(Paths.get("/tmp/store"), "Android/data/pkg");
        expect("/tmp/store", policy.lexical("").toString());
        expect("/tmp/store/Download/a", policy.lexical("Download/a").toString());
        expect("/tmp/store/a", policy.lexical("/tmp/store/../store/a").toString());
        expect("/tmp/store/Android/data/pkg2", policy.lexical("Android/data/pkg2").toString());
        mustThrow(policy, "/tmp/store-evil", "不在内部存储");
        mustThrow(policy, "/tmp/store/../../etc", "不在内部存储");
        mustThrow(policy, "Android/data/pkg/files", "不开放");
        mustThrow(policy, "/tmp/store/Android/data/pkg", "不开放");
        expect(true, policy.broadDirectory(policy.lexical("")));
        expect(true, policy.broadDirectory(policy.lexical("Android")));
        expect(true, policy.broadDirectory(policy.lexical("Android/data")));
        expect(false, policy.broadDirectory(policy.lexical("Android/data/pkg2")));
        System.out.println("FilePolicyTest ok");
    }

    private static void expect(String want, String got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void expect(boolean want, boolean got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void mustThrow(FilePolicy policy, String path, String part) {
        try {
            policy.lexical(path);
        } catch (FileFailure error) {
            if (error.getMessage().contains(part)) {
                return;
            }
            throw new AssertionError(error.getMessage());
        }
        throw new AssertionError("allowed " + path);
    }
}
