package org.mokai.coder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class LocalExports {
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    static final class Block {
        final String text;
        final String style;

        Block(String text, String style) {
            this.text = text;
            this.style = style;
        }
    }

    static List<Block> format(String title, String input) {
        List<Block> blocks = new ArrayList<>();
        String trimmed = title.trim();
        if (!trimmed.isEmpty()) blocks.add(new Block(trimmed, "Title"));
        for (String line : input.split("\\R")) {
            String text = line.trim();
            if (text.isEmpty()) continue;
            if (text.startsWith("# ")) blocks.add(new Block(text.substring(2), blocks.isEmpty() ? "Title" : "Heading1"));
            else if (text.startsWith("## ")) blocks.add(new Block(text.substring(3), "Heading1"));
            else if (text.startsWith("- ") || text.startsWith("* ")) blocks.add(new Block("• " + text.substring(2), "BulletText"));
            else blocks.add(new Block(text, blocks.isEmpty() ? "Title" : "Normal"));
        }
        return blocks;
    }

    static void writeWord(OutputStream destination, List<Block> blocks, boolean rtl) throws IOException {
        StringBuilder file = new StringBuilder(XML)
                .append("<w:document xmlns:w=\"").append(WORD_NS).append("\"><w:body>");
        for (Block block : blocks) {
            file.append("<w:p><w:pPr><w:pStyle w:val=\"").append(block.style).append("\"/>");
            if (rtl) file.append("<w:bidi/><w:jc w:val=\"right\"/>");
            file.append("</w:pPr><w:r>");
            if (rtl) file.append("<w:rPr><w:rtl/></w:rPr>");
            file.append("<w:t xml:space=\"preserve\">").append(escape(block.text)).append("</w:t></w:r></w:p>");
        }
        file.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
                .append("<w:pgMar w:top=\"1030\" w:right=\"1030\" w:bottom=\"1030\" w:left=\"1030\" w:header=\"600\" w:footer=\"600\" w:gutter=\"0\"/>")
                .append("</w:sectPr></w:body></w:document>");
        String styles = XML + "<w:styles xmlns:w=\"" + WORD_NS + "\">"
                + style("Normal", "Normal", 24, "263644", false)
                + style("Title", "Title", 46, "153541", true)
                + style("Heading1", "Heading 1", 31, "1B6170", true)
                + style("BulletText", "Bullet Text", 24, "263644", false)
                + "</w:styles>";
        try (ZipOutputStream archive = new ZipOutputStream(destination)) {
            put(archive, "[Content_Types].xml", XML
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>");
            put(archive, "_rels/.rels", relationship("officeDocument", "word/document.xml"));
            put(archive, "word/_rels/document.xml.rels", relationship("styles", "styles.xml"));
            put(archive, "word/styles.xml", styles);
            put(archive, "word/document.xml", file.toString());
        }
    }

    private static String relationship(String type, String target) {
        return XML + "<Relationships xmlns=\"" + REL_NS + "\"><Relationship Id=\"rId1\" Type=\""
                + OFFICE_REL + type + "\" Target=\"" + target + "\"/></Relationships>";
    }

    private static String style(String id, String label, int size, String color, boolean bold) {
        return "<w:style w:type=\"paragraph\" w:styleId=\"" + id + "\""
                + ("Normal".equals(id) ? " w:default=\"1\"" : "") + "><w:name w:val=\"" + label + "\"/>"
                + "<w:pPr>" + (bold ? "<w:keepNext/>" : "")
                + "<w:spacing w:before=\"" + (bold ? 220 : 0)
                + "\" w:after=\"150\" w:line=\"330\" w:lineRule=\"auto\"/></w:pPr>"
                + "<w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>"
                + (bold ? "<w:b/><w:bCs/>" : "") + "<w:color w:val=\"" + color
                + "\"/><w:sz w:val=\"" + size + "\"/><w:szCs w:val=\"" + size + "\"/></w:rPr></w:style>";
    }

    private static void put(ZipOutputStream archive, String name, String content) throws IOException {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(content.getBytes(StandardCharsets.UTF_8));
        archive.closeEntry();
    }

    static String printHtml(List<Block> blocks, boolean rtl) {
        StringBuilder body = new StringBuilder();
        for (Block block : blocks) {
            String tag = "Title".equals(block.style) ? "h1" : "Heading1".equals(block.style) ? "h2" : "p";
            body.append('<').append(tag).append('>').append(escape(block.text))
                    .append("</").append(tag).append('>');
        }
        return page("MokAiCoder", rtl,
                "body{margin:2em;color:#23343f;font:16px/1.65 sans-serif}"
                        + "h1,h2,p{overflow-wrap:anywhere}h1{color:#153541;border-bottom:2px solid #1b6170}"
                        + "h2{color:#1b6170;break-after:avoid}p{white-space:pre-wrap}",
                body.toString());
    }

    static String interfaceHtml(String title, String description, String sections, boolean rtl, int theme) {
        String[] accents = {"#196b59", "#285a98", "#8b4958"};
        String accent = accents[Math.max(0, Math.min(accents.length - 1, theme))];
        StringBuilder cards = new StringBuilder();
        for (String item : sections.split("\\R")) {
            if (item.trim().isEmpty()) continue;
            String[] parts = item.split(":", 2);
            cards.append("<article><h2>").append(escape(parts[0].trim())).append("</h2>");
            if (parts.length > 1) cards.append("<p>").append(escape(parts[1].trim())).append("</p>");
            cards.append("</article>");
        }
        return page(title, rtl,
                "body{background:#f3f6f6;color:#172d35;font:1rem/1.65 system-ui,sans-serif;margin:0}"
                        + "main,header{width:min(60rem,calc(100% - 2rem));margin-inline:auto}"
                        + "header{font-size:1.125rem;font-weight:700;padding-block:1rem}"
                        + ".hero,article{background:#fff;border:1px solid #dce6e6;border-radius:1rem;padding:1.25rem}"
                        + ".hero{border-block-start:5px solid " + accent + ";padding:clamp(1.2rem,5vw,3rem)}"
                        + "h1,h2,p{margin-block:0 .65em;overflow-wrap:anywhere}h1{font-size:clamp(1.55rem,5vw,2.7rem)}"
                        + "h2{font-size:1.2rem}section{display:grid;gap:1rem;margin-block:1rem 2rem;"
                        + "grid-template-columns:repeat(auto-fit,minmax(min(100%,14rem),1fr))}",
                "<header>MokAiCoder</header><main><div class=\"hero\"><h1>" + escape(title)
                        + "</h1><p>" + escape(description) + "</p></div><section>" + cards + "</section></main>");
    }

    private static String page(String title, boolean rtl, String css, String body) {
        return "<!doctype html><html lang=\"" + (rtl ? "ar" : "en") + "\" dir=\"" + (rtl ? "rtl" : "ltr")
                + "\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">"
                + "<title>" + escape(title) + "</title><style>" + css + "</style><body>" + body + "</body></html>";
    }

    private static String escape(String value) {
        StringBuilder text = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            int code = value.codePointAt(index);
            index += Character.charCount(code);
            switch (code) {
                case '&': text.append("&amp;"); break;
                case '<': text.append("&lt;"); break;
                case '>': text.append("&gt;"); break;
                case '"': text.append("&quot;"); break;
                case 39: text.append("&#39;"); break;
                default:
                    if (code == 9 || code == 10 || code == 13 || code >= 32 && code <= 0xd7ff
                            || code >= 0xe000 && code <= 0xfffd || code >= 0x10000 && code <= 0x10ffff) {
                        text.appendCodePoint(code);
                    }
            }
        }
        return text.toString();
    }
}
