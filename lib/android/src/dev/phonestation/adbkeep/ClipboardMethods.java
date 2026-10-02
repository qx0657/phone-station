package dev.phonestation.adbkeep;

/** Known AOSP and OEM IClipboard signatures. Do not guess values for unknown arguments. */
final class ClipboardMethods {
    static Object[] arguments(Class<?>[] types, boolean write, Object clip, int user) {
        int offset = write ? 1 : 0;
        StringBuilder signature = new StringBuilder();
        for (int i = offset; i < types.length; i++) {
            if (types[i] == String.class) { signature.append('s'); }
            else if (types[i] == int.class) { signature.append('i'); }
            else if (types[i] == boolean.class) { signature.append('b'); }
            else { return null; }
        }
        String shape = signature.toString();
        boolean standard = shape.equals("si") || shape.equals("ssi") || shape.equals("ssii") || shape.equals("ssiib");
        boolean readVariant = !write && (shape.equals("sis") || shape.equals("ssssiib") || shape.equals("ssiis"));
        if (!standard && !readVariant) { return null; }
        Object[] values = new Object[types.length];
        if (write) { values[0] = clip; }
        int integers = 0;
        for (int i = offset; i < types.length; i++) {
            if (types[i] == String.class) { values[i] = i == offset ? "com.android.shell" : null; }
            else if (types[i] == int.class) { values[i] = integers++ == 0 ? user : 0; }
            else { values[i] = true; } // OEM userOperate argument
        }
        return values;
    }
}
