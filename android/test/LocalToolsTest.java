package org.mokai.coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class LocalToolsTest {
    public static void main(String[] args) throws Exception {
        List<LocalExports.Block> content = LocalExports.format("تقرير", "## القسم\n- نقطة مهمة\n<قيمة> & 5");
        require(content.size() == 4 && content.get(1).style.equals("Heading1"), "Headings and paragraphs");
        ByteArrayOutputStream word = new ByteArrayOutputStream();
        LocalExports.writeWord(word, content, true);
        String document = zipMember(word.toByteArray(), "word/document.xml");
        require(document.contains("تقرير") && document.contains("<w:bidi/>"), "Arabic and RTL Word");
        require(document.contains("&lt;قيمة&gt; &amp; 5"), "Word XML escapes user text");
        require(zipMember(word.toByteArray(), "word/styles.xml").contains("Heading1"), "Word styles");
        String print = LocalExports.printHtml(content, true);
        require(print.contains("dir=\"rtl\"") && print.contains("<h2>القسم</h2>"), "Printable document");
        String design = LocalExports.interfaceHtml("<script>", "وصف", "طلب: <img>\nإعدادات", true, 1);
        require(design.contains("&lt;script&gt;") && design.contains("&lt;img&gt;")
                && design.contains("default-src 'none'"), "Local HTML preview escapes markup");

        File temporary = Files.createTempDirectory("mokai-test").toFile();
        try {
            File valid = OfflineModel.importZip(new ByteArrayInputStream(archive(
                    "model/am/final.mdl", "model/conf/model.conf", "model/graph/words.txt")), new File(temporary, "valid"));
            require(valid.getName().equals("model"), "Locate nested model");
            try {
                OfflineModel.importZip(new ByteArrayInputStream(archive("../outside")),
                        new File(temporary, "invalid"));
                throw new AssertionError("Accepted path outside model folder");
            } catch (IOException expected) {
                require(!new File(temporary, "outside").exists(), "Did not write outside model folder");
            }
        } finally {
            OfflineModel.removeImportedFiles(temporary);
        }
        System.out.println("Local bot export and model-import tests passed.");
    }

    private static byte[] archive(String... names) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(1);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String zipMember(byte[] archive, String path) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(path)) return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing " + path);
    }

    private static void require(boolean state, String message) {
        if (!state) throw new AssertionError(message);
    }
}
