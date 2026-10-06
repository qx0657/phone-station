package dev.phonestation.adbkeep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Translates app-owned display copy. Protocol values and user content stay unchanged. */
final class StationText {
    private static volatile StationText catalog;
    private static volatile BooleanSupplier english = () -> false;
    private final Map<String, String> entries;
    private final List<String> phrases;
    private final List<Template> templates = new ArrayList<>();

    StationText(Map<String, String> entries) {
        this.entries = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(entries));
        phrases = new ArrayList<>(entries.keySet());
        phrases.sort(Comparator.comparingInt(String::length).reversed());
        for (String key : phrases) {
            if (key.matches("(?s).*\\{[0-9]+\\}.*")) { templates.add(new Template(key, entries.get(key))); }
        }
    }

    static void install(StationText value, BooleanSupplier language) { catalog = value; english = language; }

    static String translate(CharSequence source) {
        if (source == null) { return null; }
        String value = source.toString();
        StationText current = catalog;
        return current != null && english.getAsBoolean() ? current.render(value) : value;
    }

    static String[] translate(String[] sources) {
        String[] result = sources.clone();
        for (int i = 0; i < result.length; i++) { result[i] = translate(result[i]); }
        return result;
    }

    String render(String source) {
        String exact = entries.get(source);
        if (exact != null) { return exact; }
        for (Template template : templates) {
            Matcher match = template.pattern.matcher(source);
            if (!match.matches()) { continue; }
            Matcher slots = Pattern.compile("\\{([0-9]+)\\}").matcher(template.translation);
            StringBuilder result = new StringBuilder();
            int offset = 0;
            while (slots.find()) {
                result.append(template.translation, offset, slots.start());
                result.append(match.group(Integer.parseInt(slots.group(1)) + 1));
                offset = slots.end();
            }
            return result.append(template.translation.substring(offset)).toString();
        }
        // Existing copy models compose known phrases. Require complete Chinese coverage;
        // never partially replace an unknown message or arbitrary user text.
        String[] suffix = new String[source.length() + 1];
        suffix[source.length()] = "";
        for (int offset = source.length() - 1; offset >= 0; offset--) {
            for (String phrase : phrases) {
                if (!phrase.contains("{") && !phrase.isEmpty() && source.startsWith(phrase, offset)
                        && suffix[offset + phrase.length()] != null) {
                    suffix[offset] = join(entries.get(phrase), suffix[offset + phrase.length()]);
                    break;
                }
            }
            if (suffix[offset] == null) {
                int character = source.codePointAt(offset);
                if (Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN) { continue; }
                String rest = suffix[offset + Character.charCount(character)];
                if (rest == null) { continue; }
                String rendered;
                switch (character) {
                    case '。': rendered = "."; break;
                    case '，': rendered = ", "; break;
                    case '；': rendered = "; "; break;
                    case '：': rendered = ": "; break;
                    case '「': case '」': rendered = "\""; break;
                    default: rendered = new String(Character.toChars(character));
                }
                suffix[offset] = rendered + rest;
            }
        }
        return suffix[0] == null ? source : suffix[0];
    }

    private static String join(String first, String second) {
        if (!first.isEmpty() && !second.isEmpty()) {
            char end = first.charAt(first.length() - 1), start = second.charAt(0);
            if ((Character.isLetterOrDigit(end) || end == '.') && Character.isLetter(start)) { return first + " " + second; }
        }
        return first + second;
    }

    private static final class Template {
        final Pattern pattern;
        final String translation;
        Template(String source, String translation) {
            this.translation = translation;
            Matcher slots = Pattern.compile("\\{[0-9]+\\}").matcher(source);
            StringBuilder expression = new StringBuilder("^");
            int offset = 0;
            while (slots.find()) {
                expression.append(Pattern.quote(source.substring(offset, slots.start()))).append("(.*?)");
                offset = slots.end();
            }
            expression.append(Pattern.quote(source.substring(offset))).append('$');
            pattern = Pattern.compile(expression.toString(), Pattern.DOTALL);
        }
    }
}
