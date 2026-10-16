package dev.phonestation.adbkeep;

import java.security.GeneralSecurityException;

/** 设备密钥诊断行：StrongBox 失败时改用普通 Keystore，输出不含密钥。 */
final class DeviceKeyReportTest {
    public static void main(String[] args) {
        byte[] secret = "SECRET-SIGNATURE".getBytes();
        String strong = DeviceKeyReport.line(strongBox -> {
            if (!strongBox) {
                throw new IllegalStateException("unused");
            }
            return message -> secret;
        }, clock(0, 3_000_000));
        expect("deviceKey strongBox=true signMs=3", strong);
        expect(!strong.contains("SECRET"), strong);

        String fallback = DeviceKeyReport.line(strongBox -> {
            if (strongBox) {
                throw new IllegalStateException("private-key-bytes");
            }
            return message -> secret;
        }, clock(5, 5));
        expect("deviceKey strongBox=false signMs=0", fallback);
        expect(!fallback.contains("private-key"), fallback);

        int[] strongOpens = {0};
        int[] plainOpens = {0};
        String generated = DeviceKeyReport.line(strongBox -> {
            if (strongBox) {
                strongOpens[0]++;
                throw new GeneralSecurityException("strongbox-generate");
            }
            plainOpens[0]++;
            return message -> secret;
        }, clock(0, 4_000_000));
        expect("deviceKey strongBox=false signMs=4", generated);
        expect(!generated.contains("strongbox-generate"), generated);
        expect(strongOpens[0] == 1 && plainOpens[0] == 1, "StrongBox 生成失败时回退");

        String missing = DeviceKeyReport.line(strongBox -> {
            throw new IllegalStateException("private-key-bytes");
        }, clock(0, 0));
        expect("deviceKey unavailable", missing);

        String signFails = DeviceKeyReport.line(strongBox -> {
            if (!strongBox) {
                return message -> secret;
            }
            return message -> {
                throw new IllegalStateException("private-key-bytes");
            };
        }, clock(0, 1));
        expect("deviceKey unavailable", signFails);

        String backwards = DeviceKeyReport.line(strongBox -> {
            if (!strongBox) {
                throw new IllegalStateException("unused");
            }
            return message -> secret;
        }, clock(10, 4));
        expect("deviceKey strongBox=true signMs=0", backwards);
        System.out.println("device key report ok");
    }

    private static DeviceKeyReport.Clock clock(long start, long end) {
        return new DeviceKeyReport.Clock() {
            private int calls;

            public long nanoTime() {
                long value = calls == 0 ? start : end;
                calls++;
                return value;
            }
        };
    }

    private static void expect(String want, String got) {
        if (!want.equals(got)) {
            throw new AssertionError(want + " != " + got);
        }
    }

    private static void expect(boolean value, String detail) {
        if (!value) {
            throw new AssertionError(detail);
        }
    }
}
