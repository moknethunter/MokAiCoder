package org.mokai.coder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GitHubEdit {
    static final String EXAMPLE = "/تعديل\nملف: README.md\nابحث:\n```\nالنص القديم\n```\nاستبدل:\n```\nالنص الجديد\n```";
    private static final int MAX_OPERATIONS = 12;

    static boolean isCommand(String message) {
        String firstLine = message.split("\\R", 2)[0].trim();
        return firstLine.matches("(?iu)/تعديل(?:\\s.*)?")
                || firstLine.matches("(?iu)(?:عد[ّ]?ل|أدخل|ادخل|طب[ّ]?ق)\\s.*(?:ملف|github).*");
    }

    static Map<String, List<Replacement>> parse(String message) throws IOException {
        if (message.length() > 24_000) throw new IOException("قسّم الطلب إلى رسائل أقصر من 24 ألف حرف.");
        String[] lines = message.replace("\r\n", "\n").split("\n", -1);
        Map<String, List<Replacement>> files = new LinkedHashMap<>();
        int index = 1;
        int operations = 0;
        while (index < lines.length) {
            String header = lines[index++].trim();
            if (header.isEmpty()) continue;
            if (!header.startsWith("ملف:")) throw syntax();
            String path = header.substring(4).trim();
            checkPath(path);
            Block old = block(lines, index, "ابحث:");
            Block replacement = block(lines, old.next, "استبدل:");
            if (old.text.isEmpty() || old.text.equals(replacement.text)) {
                throw new IOException("حدّد نصًا قديمًا ونصًا مختلفًا له في «" + path + "».");
            }
            if (++operations > MAX_OPERATIONS) throw new IOException("أرسل 12 استبدالًا كحد أقصى في الطلب الواحد.");
            files.computeIfAbsent(path, key -> new ArrayList<>()).add(new Replacement(old.text, replacement.text));
            index = replacement.next;
        }
        if (files.isEmpty()) throw syntax();
        return files;
    }

    private static void checkPath(String path) throws IOException {
        if (path.length() > 300 || !GitHubClient.shareable(path)
                || path.matches(".*[\\\\\\p{Cntrl}\\p{Cf}].*")) throw invalidPath(path);
        String[] parts = path.split("/", -1);
        if (parts.length > 12) throw invalidPath(path);
        for (String part : parts) {
            if (part.isEmpty() || part.startsWith(".") || !part.equals(part.trim())) throw invalidPath(path);
        }
    }

    private static IOException invalidPath(String path) {
        return new IOException("اختر مسار ملف نصي مدعوم داخل المستودع. المسار «" + path + "».");
    }

    private static IOException syntax() {
        return new IOException("اكتب أمر /تعديل وحدّد «ملف:» و«ابحث:» و«استبدل:» لكل تعديل.");
    }

    private static Block block(String[] lines, int index, String label) throws IOException {
        if (index + 2 >= lines.length || !label.equals(lines[index++].trim())) throw syntax();
        String opener = lines[index++];
        if (!opener.matches("`{3,}[\\w+-]*")) throw syntax();
        String fence = opener.replaceFirst("[^`].*$", "");
        StringBuilder text = new StringBuilder();
        boolean first = true;
        while (index < lines.length && !fence.equals(lines[index])) {
            if (!first) text.append('\n');
            text.append(lines[index++]);
            first = false;
        }
        if (index == lines.length) throw syntax();
        return new Block(text.toString(), index + 1);
    }

    static String apply(String path, String content, List<Replacement> replacements) throws IOException {
        String revised = content;
        boolean crlf = content.contains("\r\n") && !content.replace("\r\n", "").contains("\n");
        for (Replacement operation : replacements) {
            String oldText = crlf ? operation.before.replace("\n", "\r\n") : operation.before;
            String newText = crlf ? operation.after.replace("\n", "\r\n") : operation.after;
            int start = revised.indexOf(oldText);
            if (start < 0) throw new IOException("تعذّر العثور على النص القديم في «" + path + "».");
            if (revised.indexOf(oldText, start + 1) >= 0) {
                throw new IOException("النص القديم يتكرر في «" + path + "». أضف سطورًا حول الموضع لتحديده.");
            }
            revised = revised.substring(0, start) + newText + revised.substring(start + oldText.length());
        }
        if (revised.equals(content)) throw new IOException("لم ينتج عن الطلب تغيير في «" + path + "».");
        if (revised.indexOf('\0') >= 0 || revised.getBytes(StandardCharsets.UTF_8).length > GitHubClient.MAX_FILE) {
            throw new IOException("اختر نصًا صالحًا بحجم 24 كيلوبايت أو أقل للملف «" + path + "».");
        }
        return revised;
    }

    static final class Replacement {
        final String before;
        final String after;

        Replacement(String before, String after) {
            this.before = before;
            this.after = after;
        }
    }

    private static final class Block {
        final String text;
        final int next;

        Block(String text, int next) {
            this.text = text;
            this.next = next;
        }
    }
}
