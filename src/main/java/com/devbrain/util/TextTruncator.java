package com.devbrain.util;

/**
 * Truncates text to fit within LLM context windows.
 * From Bits2Brain — prevents oversized prompts.
 */
public final class TextTruncator {

    private static final int DEFAULT_MAX_CHARS = 8000;

    private TextTruncator() {}

    public static String truncate(String text) {
        return truncate(text, DEFAULT_MAX_CHARS);
    }

    public static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n\n[...truncated]";
    }
}
