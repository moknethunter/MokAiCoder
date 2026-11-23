package org.mokai.coder;

import java.util.Locale;

final class LocalModelCatalog {
    static final long MIB = 1024L * 1024;
    static final long MAX_BYTES = 2800 * MIB;

    enum Model {
        SMALL("Qwen3-0.6B", "qwen3-0.6b", 490_000_000L,
                "second-state/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
                "dd6df9d5627007fa6e29cf2fff3464b1ab01a789fa8220c61ff5f3cf4989ce0e"),
        CHAT("Qwen3-1.7B", "qwen3-1.7b", 1_290_000_000L,
                "ggml-org/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"),
        CODE("Qwen2.5-Coder-1.5B", "qwen2.5-coder-1.5b", 1_130_000_000L,
                "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
                "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046");

        final String label, id, url, sha256;
        final long size;
        Model(String label, String id, long size, String path, String sha256) {
            this.label = label;
            this.id = id;
            this.size = size;
            this.url = "https://huggingface.co/" + path;
            this.sha256 = sha256;
        }
    }

    static boolean downloadCommand(String text) {
        String lower = text.trim().toLowerCase(Locale.ROOT);
        return lower.matches("(?s)^(?:/download|download|نز[ّ]?ل|حم[ّ]?ل|تنزيل|تحميل)\\s+(?:نموذج\\s+|model\\s+|qwen).*");
    }

    static Model requested(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Model model : Model.values()) if (lower.contains(model.id)) return model;
        return null;
    }

    static boolean coding(String text) {
        return text.toLowerCase(Locale.ROOT).matches("(?s).*(?:كود|برمج|سكربت|دالة|function|code|script|javascript|python|java|typescript).*");
    }

    static long requiredMemory(long size) {
        return size + 560 * MIB;
    }
}
