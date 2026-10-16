package dev.phonestation.adbkeep;

/** 设备密钥诊断行：StrongBox 失败时改用普通 Keystore，输出不含密钥。 */
final class DeviceKeyReportTest {
    public static void main(String[] args) {
        byte[] secret = "SECRET-SIGNATURE".getBytes();
        String strong = DeviceKeyReport.line(() -> message -> secret, () -> {
            throw new IllegalStateException("unused");
        }, clock(0, 3_000_000));
        expect("deviceKey strongBox=true signMs=3", strong);
        expect(!strong.contains("SECRET"), strong);

        String fallback = DeviceKeyReport.line(() -> {
            throw new IllegalStateException("private-key-bytes");
        }, () -> message -> secret, clock(5, 5));
        expect("deviceKey strongBox=false signMs=0", fallback);
        expect(!fallback.contains("private-key"), fallback);

        String missing = DeviceKeyReport.line(() -> {
            throw new IllegalStateException("private-key-bytes");
        }, () -> {
            throw new IllegalStateException("private-key-bytes");
        }, clock(0, 0));
        expect("deviceKey unavailable", missing);

        String signFails = DeviceKeyReport.line(() -> message -> {
            throw new IllegalStateException("private-key-bytes");
        }, () -> message -> secret, clock(0, 1));
        expect("deviceKey unavailable", signFails);

        String backwards = DeviceKeyReport.line(() -> message -> secret, () -> {
            throw new IllegalStateException("unused");
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
