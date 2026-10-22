package com.devbrain.util;

/**
 * Cleans up LLM replies before JSON parsing: models often wrap JSON in markdown fences
 * despite being told not to.
 */
public final class JsonText {

    private JsonText() {}

    public static String stripFences(String s) {
        String t = s == null ? "" : s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl >= 0 ? t.substring(nl + 1) : t.substring(3);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.strip();
    }
}
