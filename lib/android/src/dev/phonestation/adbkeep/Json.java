package dev.phonestation.adbkeep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 够用的 JSON。对象保留写入顺序。数字先按原文存，取整数时再解析。 */
final class Json {
    private enum Kind { NUL, BOOL, NUM, STR, ARR, OBJ }

    private final Kind kind;
    private final Object value;

    private Json(Kind kind, Object value) {
        this.kind = kind;
        this.value = value;
    }

    static Json nul() {
        return new Json(Kind.NUL, null);
    }

    static Json bool(boolean v) {
        return new Json(Kind.BOOL, Boolean.valueOf(v));
    }

    static Json num(long v) {
        return new Json(Kind.NUM, Long.toString(v));
    }

    static Json str(String v) {
        if (v == null) {
            throw new IllegalArgumentException("字符串是空的");
        }
        return new Json(Kind.STR, v);
    }

    static Json arr() {
        return new Json(Kind.ARR, new ArrayList<Json>());
    }

    static Json obj() {
        return new Json(Kind.OBJ, new LinkedHashMap<String, Json>());
    }

    Json add(Json item) {
        array().add(item);
        return this;
    }

    Json put(String key, Json item) {
        map().put(key, item);
        return this;
    }

    Json put(String key, String v) {
        return put(key, str(v));
    }

    Json put(String key, long v) {
        return put(key, num(v));
    }

    Json put(String key, boolean v) {
        return put(key, bool(v));
    }

    boolean isObject() { return kind == Kind.OBJ; }
    boolean isArray() { return kind == Kind.ARR; }
    boolean isString() { return kind == Kind.STR; }
    boolean isInteger() { return kind == Kind.NUM && ((String) value).matches("-?(0|[1-9][0-9]*)"); }

    boolean isNull() {
        return kind == Kind.NUL;
    }

    String string() {
        if (kind != Kind.STR) {
            throw new IllegalArgumentException("需要字符串");
        }
        return (String) value;
    }

    long longValue() {
        if (kind != Kind.NUM) {
            throw new IllegalArgumentException("需要整数");
        }
        String lex = (String) value;
        for (int i = 0; i < lex.length(); i++) {
            char c = lex.charAt(i);
            if (c == '.' || c == 'e' || c == 'E') {
                throw new IllegalArgumentException("需要整数");
            }
        }
        try {
            return Long.parseLong(lex);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("需要整数");
        }
    }

    boolean boolValue() {
        if (kind != Kind.BOOL) {
            throw new IllegalArgumentException("需要布尔值");
        }
        return ((Boolean) value).booleanValue();
    }

    List<Json> array() {
        if (kind != Kind.ARR) {
            throw new IllegalArgumentException("需要数组");
        }
        @SuppressWarnings("unchecked")
        List<Json> list = (List<Json>) value;
        return list;
    }

    Json get(String key) {
        if (kind != Kind.OBJ) {
            throw new IllegalArgumentException("需要对象");
        }
        return map().get(key);
    }

    boolean has(String key) {
        return get(key) != null;
    }

    String emit() {
        StringBuilder out = new StringBuilder();
        emit(out);
        return out.toString();
    }

    static Json parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON 是空的");
        }
        Parser parser = new Parser(text);
        parser.skip();
        Json value = parser.value(0);
        parser.skip();
        if (parser.i != parser.s.length()) {
            throw new IllegalArgumentException("JSON 后面还有内容");
        }
        return value;
    }

    private void emit(StringBuilder out) {
        switch (kind) {
            case NUL:
                out.append("null");
                break;
            case BOOL:
                out.append(((Boolean) value).booleanValue() ? "true" : "false");
                break;
            case NUM:
                out.append((String) value);
                break;
            case STR:
                emitString(out, (String) value);
                break;
            case ARR:
                out.append('[');
                List<Json> list = array();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    list.get(i).emit(out);
                }
                out.append(']');
                break;
            case OBJ:
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Json> entry : map().entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    emitString(out, entry.getKey());
                    out.append(':');
                    entry.getValue().emit(out);
                }
                out.append('}');
                break;
            default:
                throw new IllegalStateException(kind.name());
        }
    }

    private static void emitString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            out.append('0');
                        }
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private Map<String, Json> map() {
        if (kind != Kind.OBJ) {
            throw new IllegalArgumentException("需要对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Json> map = (Map<String, Json>) value;
        return map;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        Json value(int depth) {
            if (depth > 40) {
                throw new IllegalArgumentException("JSON 嵌套太深");
            }
            skip();
            if (i >= s.length()) {
                throw new IllegalArgumentException("JSON 不完整");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object(depth);
            }
            if (c == '[') {
                return array(depth);
            }
            if (c == '"') {
                return Json.str(string());
            }
            if (c == 't') {
                literal("true");
                return Json.bool(true);
            }
            if (c == 'f') {
                literal("false");
                return Json.bool(false);
            }
            if (c == 'n') {
                literal("null");
                return Json.nul();
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return number();
            }
            throw new IllegalArgumentException("JSON 无法解析");
        }

        Json object(int depth) {
            expect('{');
            Json obj = Json.obj();
            skip();
            if (peek('}')) {
                i++;
                return obj;
            }
            while (true) {
                skip();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw new IllegalArgumentException("JSON 的键要是字符串");
                }
                String key = string();
                skip();
                expect(':');
                if (obj.has(key)) { throw new IllegalArgumentException("JSON 对象字段重复"); }
                obj.put(key, value(depth + 1));
                skip();
                if (peek('}')) {
                    i++;
                    return obj;
                }
                expect(',');
            }
        }

        Json array(int depth) {
            expect('[');
            Json arr = Json.arr();
            skip();
            if (peek(']')) {
                i++;
                return arr;
            }
            while (true) {
                arr.add(value(depth + 1));
                skip();
                if (peek(']')) {
                    i++;
                    return arr;
                }
                expect(',');
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        throw new IllegalArgumentException("JSON 字符串不完整");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':
                        case '\\':
                        case '/':
                            out.append(e);
                            break;
                        case 'b':
                            out.append('\b');
                            break;
                        case 'f':
                            out.append('\f');
                            break;
                        case 'n':
                            out.append('\n');
                            break;
                        case 'r':
                            out.append('\r');
                            break;
                        case 't':
                            out.append('\t');
                            break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("JSON 转义不完整");
                            }
                            int code = 0;
                            for (int k = 0; k < 4; k++) {
                                int d = hex(s.charAt(i++));
                                if (d < 0) {
                                    throw new IllegalArgumentException("JSON 转义不完整");
                                }
                                code = (code << 4) | d;
                            }
                            out.append((char) code);
                            break;
                        default:
                            throw new IllegalArgumentException("JSON 转义无法识别");
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException("JSON 字符串里有控制字符");
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("JSON 字符串不完整");
        }

        Json number() {
            int start = i;
            if (peek('-')) {
                i++;
            }
            if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') {
                throw new IllegalArgumentException("JSON 数字不完整");
            }
            if (s.charAt(i) == '0') {
                i++;
            } else {
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    i++;
                }
            }
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                int frac = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    i++;
                }
                if (i == frac) {
                    throw new IllegalArgumentException("JSON 数字不完整");
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                int exp = i;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    i++;
                }
                if (i == exp) {
                    throw new IllegalArgumentException("JSON 数字不完整");
                }
            }
            return new Json(Kind.NUM, s.substring(start, i));
        }

        void literal(String word) {
            if (!s.regionMatches(i, word, 0, word.length())) {
                throw new IllegalArgumentException("JSON 无法解析");
            }
            i += word.length();
        }

        void skip() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                    return;
                }
                i++;
            }
        }

        boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        void expect(char c) {
            if (!peek(c)) {
                throw new IllegalArgumentException("JSON 无法解析");
            }
            i++;
        }

        static int hex(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            return -1;
        }
    }
}
