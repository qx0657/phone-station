package dev.phonestation.adbkeep;

final class JsonTest {
    public static void main(String[] args) {
        Json value = Json.parse("{\"a\":\"手机\",\"b\":1,\"c\":[true,null],\"d\":\"a\\\"b\\\\c\\n\"}");
        expect("手机", value.get("a").string());
        expect(1, value.get("b").longValue());
        expect(true, value.get("c").array().get(0).boolValue());
        expect(true, value.get("c").array().get(1).isNull());
        expect("a\"b\\c\n", value.get("d").string());
        expect(value.emit(), Json.parse(value.emit()).emit());
        mustThrow("{\"a\":1} x");
        mustThrow("");
        System.out.println("JsonTest ok");
    }

    private static void expect(String want, String got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void expect(long want, long got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void expect(boolean want, boolean got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void mustThrow(String text) {
        try {
            Json.parse(text);
        } catch (IllegalArgumentException error) {
            return;
        }
        throw new AssertionError("parsed " + text);
    }
}
