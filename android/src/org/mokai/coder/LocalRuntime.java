package org.mokai.coder;

import java.io.File;
import java.nio.charset.StandardCharsets;

final class LocalRuntime {
    static final boolean AVAILABLE = load();

    private static boolean load() {
        try {
            System.loadLibrary("mokai_llm");
            return true;
        } catch (LinkageError unavailable) {
            return false;
        }
    }

    static String generate(File model, String prompt) {
        int threads = Math.min(4, Runtime.getRuntime().availableProcessors());
        byte[] output = runNative(model.getAbsolutePath().getBytes(StandardCharsets.UTF_8),
                prompt.getBytes(StandardCharsets.UTF_8), threads);
        return new String(output, StandardCharsets.UTF_8)
                .replaceFirst("(?s)^\\s*<think>.*?</think>\\s*", "").trim();
    }

    static boolean supports(File model) {
        return inspectNative(model.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
    }

    static void cancel() {
        if (AVAILABLE) cancelNative();
    }

    private static native byte[] runNative(byte[] path, byte[] prompt, int threads);
    private static native boolean inspectNative(byte[] path);
    private static native void cancelNative();
}
