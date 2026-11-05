package com.mdedit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, dependency-free Markdown to HTML converter.
 *
 * <p>Supports the CommonMark / GitHub-flavoured features people use day to day:
 * ATX and Setext headings, paragraphs, emphasis, strong, strikethrough, inline code,
 * fenced and indented code blocks, block quotes, nested ordered / unordered / task lists,
 * tables with alignment, horizontal rules, inline and reference links, images,
 * autolinks, hard line breaks, backslash escapes and simple inline HTML.</p>
 *
 * <p>The produced HTML is deliberately conservative (HTML 3.2 + simple CSS) so that it
 * renders well in Swing's {@link javax.swing.JEditorPane} as well as in browsers.</p>
 *
 * <p>Instances are not thread-safe; use {@link #toHtml(String)} for one-off conversions.</p>
 */
public final class MarkdownParser {

    private static final Pattern ATX = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern HR = Pattern.compile("^ {0,3}(?:(?:-[ \\t]*){3,}|(?:\\*[ \\t]*){3,}|(?:_[ \\t]*){3,})$");
    private static final Pattern FENCE = Pattern.compile("^( {0,3})(`{3,}|~{3,})[ \\t]*([^`]*)$");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^ {0,3}> ?(.*)$");
    private static final Pattern BULLET = Pattern.compile("^( {0,3})([-*+])(?:([ \\t]+)(.*)|$)");
    private static final Pattern ORDERED = Pattern.compile("^( {0,3})(\\d{1,9})([.)])(?:([ \\t]+)(.*)|$)");
    private static final Pattern SETEXT_H1 = Pattern.compile("^ {0,3}=+[ \\t]*$");
    private static final Pattern SETEXT_H2 = Pattern.compile("^ {0,3}-+[ \\t]*$");
    private static final Pattern TABLE_SEP = Pattern.compile("^ {0,3}\\|?[ \\t]*:?-+:?[ \\t]*(\\|[ \\t]*:?-+:?[ \\t]*)*\\|?[ \\t]*$");
    private static final Pattern REF_DEF = Pattern.compile(
            "^ {0,3}\\[([^\\]]+)\\]:[ \\t]*<?([^\\s>]+)>?(?:[ \\t]+(?:\"([^\"]*)\"|'([^']*)'|\\(([^)]*)\\)))?[ \\t]*$");
    private static final Pattern TASK = Pattern.compile("^\\[([ xX])\\][ \\t]+(.*)$", Pattern.DOTALL);
    private static final Pattern HTML_BLOCK = Pattern.compile(
            "^ {0,3}(?:<!--|</?(?:address|article|aside|blockquote|center|details|div|dl|dd|dt|fieldset|figure|figcaption|footer|form|h[1-6]|header|hr|main|nav|ol|p|pre|section|summary|table|tbody|td|tfoot|th|thead|tr|ul|li)(?:[\\s/>]|$))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_TAG = Pattern.compile(
            "^</?(?:a|b|i|u|em|strong|sub|sup|br|s|strike|del|code|kbd|span|font|small|big|img|tt)(?:\\s[^<>]*)?/?>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_COMMENT = Pattern.compile("^<!--.*?-->", Pattern.DOTALL);
    private static final Pattern AUTOLINK = Pattern.compile("^<((?:https?|ftp)://[^\\s<>]+|mailto:[^\\s<>]+)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_AUTOLINK = Pattern.compile("^<([^\\s<>@]+@[^\\s<>@]+\\.[^\\s<>@]+)>");
    private static final Pattern ENTITY = Pattern.compile("^&(?:[a-zA-Z][a-zA-Z0-9]{1,31}|#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6});");
    private static final Pattern BARE_URL = Pattern.compile("^(?:https?://|www\\.)[^\\s<]+", Pattern.CASE_INSENSITIVE);

    private static final String PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private final Map<String, String[]> references = new HashMap<>();
    private final Map<String, Integer> usedAnchors = new HashMap<>();

    /** Maximum nesting of block containers / inline spans before content is treated literally. */
    private static final int MAX_DEPTH = 48;
    private int blockDepth;
    private int inlineDepth;
    private boolean inLink;
    /** Limits total link-destination scanning so pathological input stays fast. */
    private long scanBudget;

    /** Converts Markdown source to an HTML fragment (no {@code <html>}/{@code <body>} wrapper). */
    public static String toHtml(String markdown) {
        return new MarkdownParser().render(markdown);
    }

    /** Converts Markdown source to an HTML fragment (no {@code <html>}/{@code <body>} wrapper). */
    public String render(String markdown) {
        references.clear();
        usedAnchors.clear();
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(expandTabs(line));
        }
        scanBudget = 4_000_000L + 8L * text.length();
        try {
            lines = extractReferences(lines);
            StringBuilder out = new StringBuilder(text.length() * 2);
            parseBlocks(lines, out, false);
            return out.toString();
        } catch (RuntimeException | StackOverflowError e) {
            // Never let a pathological document break the editor: show it as plain text instead.
            return "<pre class=\"code\"><code>" + escapeHtml(text) + "</code></pre>\n";
        } finally {
            blockDepth = 0;
            inlineDepth = 0;
            inLink = false;
        }
    }

    // ------------------------------------------------------------------ block level

    private List<String> extractReferences(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        String fence = null;
        boolean previousBlank = true;
        for (String line : lines) {
            Matcher f = FENCE.matcher(line);
            if (fence == null && f.matches() && !(f.group(2).charAt(0) == '`' && f.group(3).contains("`"))) {
                fence = f.group(2);
            } else if (fence != null && isClosingFence(line, fence)) {
                fence = null;
            } else if (fence == null && previousBlank || fence == null && !result.isEmpty() && REF_DEF.matcher(result.get(result.size() - 1)).matches()) {
                Matcher m = REF_DEF.matcher(line);
                if (m.matches()) {
                    String title = firstNonNull(m.group(3), m.group(4), m.group(5));
                    references.put(normalizeLabel(m.group(1)), new String[]{m.group(2), title});
                    continue; // drop the definition line
                }
            }
            previousBlank = isBlank(line);
            result.add(line);
        }
        return result;
    }

    private void parseBlocks(List<String> lines, StringBuilder out, boolean tight) {
        if (blockDepth >= MAX_DEPTH) {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            out.append("<p>").append(escapeHtml(trimTrailing(sb.toString()))).append("</p>\n");
            return;
        }
        blockDepth++;
        try {
            parseBlocksImpl(lines, out, tight);
        } finally {
            blockDepth--;
        }
    }

    private void parseBlocksImpl(List<String> lines, StringBuilder out, boolean tight) {
        int n = lines.size();
        int i = 0;
        while (i < n) {
            String line = lines.get(i);
            if (isBlank(line)) {
                i++;
                continue;
            }
            Matcher m;

            // Fenced code block
            m = FENCE.matcher(line);
            if (m.matches() && !(m.group(2).charAt(0) == '`' && m.group(3).contains("`"))) {
                String fence = m.group(2);
                int indent = m.group(1).length();
                String info = m.group(3).trim();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < n) {
                    String l = lines.get(i);
                    if (isClosingFence(l, fence)) {
                        i++;
                        break;
                    }
                    code.append(stripIndent(l, indent)).append('\n');
                    i++;
                }
                emitCode(out, code.toString(), info);
                continue;
            }

            // ATX heading
            m = ATX.matcher(line);
            if (m.matches()) {
                String content = m.group(2) == null ? "" : m.group(2);
                emitHeading(out, m.group(1).length(), content);
                i++;
                continue;
            }

            // Horizontal rule
            if (HR.matcher(line).matches()) {
                out.append("<hr>\n");
                i++;
                continue;
            }

            // Block quote
            if (BLOCKQUOTE.matcher(line).matches()) {
                List<String> inner = new ArrayList<>();
                boolean lastWasText = false;
                while (i < n) {
                    String l = lines.get(i);
                    Matcher q = BLOCKQUOTE.matcher(l);
                    if (q.matches()) {
                        inner.add(q.group(1));
                        lastWasText = !isBlank(q.group(1));
                        i++;
                    } else if (lastWasText && !isBlank(l) && !startsBlock(l)) {
                        inner.add(l); // lazy continuation
                        i++;
                    } else {
                        break;
                    }
                }
                out.append("<blockquote>\n");
                parseBlocks(inner, out, false);
                out.append("</blockquote>\n");
                continue;
            }

            // Lists
            if (BULLET.matcher(line).matches() || ORDERED.matcher(line).matches()) {
                i = parseList(lines, i, out);
                continue;
            }

            // Indented code block
            if (leadingSpaces(line) >= 4) {
                List<String> code = new ArrayList<>();
                while (i < n && (isBlank(lines.get(i)) || leadingSpaces(lines.get(i)) >= 4)) {
                    code.add(stripIndent(lines.get(i), 4));
                    i++;
                }
                while (!code.isEmpty() && isBlank(code.get(code.size() - 1))) {
                    code.remove(code.size() - 1);
                }
                StringBuilder sb = new StringBuilder();
                for (String c : code) {
                    sb.append(c).append('\n');
                }
                emitCode(out, sb.toString(), "");
                continue;
            }

            // Table
            if (isTableStart(lines, i)) {
                i = parseTable(lines, i, out);
                continue;
            }

            // Raw HTML block
            if (HTML_BLOCK.matcher(line).find()) {
                boolean comment = line.trim().startsWith("<!--");
                StringBuilder html = new StringBuilder();
                while (i < n) {
                    String l = lines.get(i);
                    if (!comment && isBlank(l)) {
                        break;
                    }
                    html.append(l).append('\n');
                    i++;
                    if (comment && l.contains("-->")) {
                        break;
                    }
                }
                if (!comment) {
                    out.append(html);
                }
                continue;
            }

            // Paragraph (possibly a Setext heading)
            StringBuilder para = new StringBuilder();
            int headingLevel = 0;
            while (i < n) {
                String l = lines.get(i);
                if (isBlank(l)) {
                    break;
                }
                if (para.length() > 0) {
                    if (SETEXT_H1.matcher(l).matches()) {
                        headingLevel = 1;
                        i++;
                        break;
                    }
                    if (SETEXT_H2.matcher(l).matches()) {
                        headingLevel = 2;
                        i++;
                        break;
                    }
                    if (interruptsParagraph(l) || isTableStart(lines, i)) {
                        break;
                    }
                    para.append('\n');
                }
                para.append(stripLeading(l));
                i++;
            }
            String content = trimTrailing(para.toString());
            if (headingLevel > 0) {
                emitHeading(out, headingLevel, content);
            } else if (tight) {
                out.append(inline(content)).append('\n');
            } else {
                out.append("<p>").append(inline(content)).append("</p>\n");
            }
        }
    }

    private int parseList(List<String> lines, int start, StringBuilder out) {
        int n = lines.size();
        int i = start;
        String first = lines.get(i);
        Matcher fm = BULLET.matcher(first);
        boolean ordered = !fm.matches();
        if (ordered) {
            fm = ORDERED.matcher(first);
            fm.matches();
        }
        String marker = ordered ? fm.group(3) : fm.group(2);
        int startNumber = ordered ? parseIntSafe(fm.group(2)) : 1;

        List<List<String>> items = new ArrayList<>();
        boolean loose = false;

        while (i < n) {
            String line = lines.get(i);
            Matcher m = ordered ? ORDERED.matcher(line) : BULLET.matcher(line);
            if (!m.matches()) {
                break;
            }
            String thisMarker = ordered ? m.group(3) : m.group(2);
            if (!thisMarker.equals(marker) || HR.matcher(line).matches()) {
                break;
            }
            int markerIndent = m.group(1).length();
            int markerWidth = ordered ? m.group(2).length() + 1 : 1;
            String spaces = ordered ? m.group(4) : m.group(3);
            String rest = ordered ? m.group(5) : m.group(4);
            int contentIndent;
            if (rest == null || isBlank(rest)) {
                contentIndent = markerIndent + markerWidth + 1;
                rest = "";
            } else if (spaces.length() > 4) {
                contentIndent = markerIndent + markerWidth + 1;
                rest = spaces.substring(1) + rest;
            } else {
                contentIndent = markerIndent + markerWidth + spaces.length();
            }

            List<String> item = new ArrayList<>();
            item.add(rest);
            i++;
            boolean previousBlank = false;
            boolean previousText = !rest.isEmpty();
            while (i < n) {
                String l = lines.get(i);
                if (isBlank(l)) {
                    item.add("");
                    previousBlank = true;
                    previousText = false;
                    i++;
                    continue;
                }
                int indent = leadingSpaces(l);
                if (indent >= contentIndent) {
                    String stripped = l.substring(contentIndent);
                    if (previousBlank && leadingSpaces(stripped) == 0 && !BULLET.matcher(stripped).matches()
                            && !ORDERED.matcher(stripped).matches() && hasContentBeforeBlank(item)) {
                        loose = true; // two block elements separated by a blank line inside one item
                    }
                    item.add(stripped);
                    previousText = true;
                    previousBlank = false;
                    i++;
                    continue;
                }
                if (!previousBlank && previousText && !startsBlock(l)) {
                    item.add(l.trim()); // lazy continuation line
                    i++;
                    continue;
                }
                break;
            }
            int trailingBlanks = 0;
            while (!item.isEmpty() && isBlank(item.get(item.size() - 1))) {
                item.remove(item.size() - 1);
                trailingBlanks++;
            }
            if (item.isEmpty()) {
                item.add(""); // empty list item
            }
            items.add(item);
            if (trailingBlanks > 0 && i < n) {
                Matcher next = ordered ? ORDERED.matcher(lines.get(i)) : BULLET.matcher(lines.get(i));
                if (next.matches() && (ordered ? next.group(3) : next.group(2)).equals(marker)) {
                    loose = true;
                } else {
                    break;
                }
            }
        }

        boolean allTasks = !items.isEmpty();
        for (List<String> item : items) {
            if (!TASK.matcher(item.get(0)).matches()) {
                allTasks = false;
                break;
            }
        }

        if (ordered) {
            out.append(startNumber != 1 ? "<ol start=\"" + startNumber + "\">\n" : "<ol>\n");
        } else {
            out.append(allTasks ? "<ul class=\"task-list\">\n" : "<ul>\n");
        }
        for (List<String> item : items) {
            Matcher task = TASK.matcher(item.get(0));
            if (task.matches()) {
                boolean checked = !task.group(1).equals(" ");
                item.set(0, task.group(2));
                out.append("<li class=\"task\"><span class=\"checkbox\">")
                        .append(checked ? "&#9745;" : "&#9744;")
                        .append("</span>&nbsp;");
            } else {
                out.append("<li>");
            }
            StringBuilder inner = new StringBuilder();
            parseBlocks(item, inner, !loose);
            String html = inner.toString();
            if (html.endsWith("\n")) {
                html = html.substring(0, html.length() - 1);
            }
            out.append(html).append("</li>\n");
        }
        out.append(ordered ? "</ol>\n" : "</ul>\n");
        return i;
    }

    private static boolean hasContentBeforeBlank(List<String> item) {
        for (String s : item) {
            if (!isBlank(s)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTableStart(List<String> lines, int i) {
        if (i + 1 >= lines.size()) {
            return false;
        }
        String header = lines.get(i);
        String sep = lines.get(i + 1);
        if (!header.contains("|") || !TABLE_SEP.matcher(sep).matches()) {
            return false;
        }
        if (!sep.contains("|") && splitRow(header).size() > 1) {
            return false;
        }
        return splitRow(header).size() == splitRow(sep).size();
    }

    private int parseTable(List<String> lines, int start, StringBuilder out) {
        List<String> header = splitRow(lines.get(start));
        List<String> sep = splitRow(lines.get(start + 1));
        String[] align = new String[sep.size()];
        for (int c = 0; c < sep.size(); c++) {
            String s = sep.get(c).trim();
            boolean left = s.startsWith(":");
            boolean right = s.endsWith(":");
            align[c] = left && right ? "center" : right ? "right" : left ? "left" : null;
        }
        out.append("<table class=\"md-table\" cellspacing=\"1\" cellpadding=\"6\" border=\"0\">\n<tr>");
        for (int c = 0; c < header.size(); c++) {
            out.append("<th").append(alignAttr(align[c])).append('>')
                    .append(inline(header.get(c).trim())).append("</th>");
        }
        out.append("</tr>\n");
        int i = start + 2;
        int row = 0;
        while (i < lines.size()) {
            String l = lines.get(i);
            if (isBlank(l) || !l.contains("|") && interruptsParagraph(l)) {
                break;
            }
            List<String> cells = splitRow(l);
            out.append(row % 2 == 1 ? "<tr class=\"alt\">" : "<tr>");
            for (int c = 0; c < header.size(); c++) {
                String cell = c < cells.size() ? cells.get(c).trim() : "";
                out.append("<td").append(alignAttr(align[c])).append('>')
                        .append(cell.isEmpty() ? "&nbsp;" : inline(cell)).append("</td>");
            }
            out.append("</tr>\n");
            row++;
            i++;
        }
        out.append("</table>\n");
        return i;
    }

    private static String alignAttr(String align) {
        return align == null ? "" : " align=\"" + align + "\"";
    }

    /** Splits a table row on unescaped pipes that are not inside code spans. */
    private static List<String> splitRow(String row) {
        String s = row.trim();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|") && !s.endsWith("\\|")) {
            s = s.substring(0, s.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        int codeEnd = -1; // index just past the closing backticks of the current code span
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '`' && i >= codeEnd) {
                int run = runLength(s, i, '`');
                int close = findCodeClose(s, i + run, run, newCodeFail());
                if (close >= 0) {
                    codeEnd = close + run;
                }
                cell.append(s, i, i + run);
                i += run - 1;
            } else if (c == '|' && i >= codeEnd) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private void emitHeading(StringBuilder out, int level, String content) {
        String anchor = uniqueAnchor(slugify(content));
        out.append("<h").append(level).append("><a name=\"").append(anchor).append("\"></a>")
                .append(inline(content.trim())).append("</h").append(level).append(">\n");
    }

    private static void emitCode(StringBuilder out, String code, String info) {
        String lang = info.isEmpty() ? "" : info.split("\\s+")[0];
        out.append("<pre class=\"code\"");
        if (!lang.isEmpty()) {
            out.append(" title=\"").append(escapeAttr(lang)).append('"');
        }
        out.append("><code>");
        if (code.endsWith("\n")) {
            code = code.substring(0, code.length() - 1);
        }
        out.append(escapeHtml(code)).append("</code></pre>\n");
    }

    private boolean interruptsParagraph(String l) {
        if (ATX.matcher(l).matches() || HR.matcher(l).matches() || BLOCKQUOTE.matcher(l).matches()) {
            return true;
        }
        Matcher f = FENCE.matcher(l);
        if (f.matches()) {
            return true;
        }
        Matcher b = BULLET.matcher(l);
        if (b.matches() && b.group(4) != null && !isBlank(b.group(4))) {
            return true;
        }
        Matcher o = ORDERED.matcher(l);
        if (o.matches() && o.group(2).equals("1") && o.group(5) != null && !isBlank(o.group(5))) {
            return true;
        }
        return HTML_BLOCK.matcher(l).find();
    }

    private boolean startsBlock(String l) {
        return interruptsParagraph(l) || BULLET.matcher(l).matches() || ORDERED.matcher(l).matches();
    }

    private static boolean isClosingFence(String line, String fence) {
        if (leadingSpaces(line) > 3) {
            return false;
        }
        String t = line.trim();
        if (t.length() < fence.length()) {
            return false;
        }
        char fc = fence.charAt(0);
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) != fc) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ inline level

    /** A piece of inline output: either literal HTML or a run of emphasis delimiters. */
    private static final class Node {
        final String html;          // literal HTML (text nodes only)
        final char delim;           // '*', '_' or '~' for delimiter runs, 0 for text
        final int origCount;
        int count;                  // delimiters not (yet) consumed by a match
        final boolean canOpen;
        final boolean canClose;
        boolean active = true;
        final StringBuilder openTags = new StringBuilder();
        final StringBuilder closeTags = new StringBuilder();

        Node(String html) {
            this.html = html;
            this.delim = 0;
            this.origCount = 0;
            this.canOpen = false;
            this.canClose = false;
        }

        Node(char delim, int count, boolean canOpen, boolean canClose) {
            this.html = null;
            this.delim = delim;
            this.origCount = count;
            this.count = count;
            this.canOpen = canOpen;
            this.canClose = canClose;
        }
    }

    /** Converts inline Markdown (emphasis, links, code spans ...) to HTML. */
    String inline(String s) {
        if (inlineDepth >= MAX_DEPTH) {
            return escapeHtml(s);
        }
        inlineDepth++;
        try {
            return inlineImpl(s);
        } finally {
            inlineDepth--;
        }
    }

    private String inlineImpl(String s) {
        List<Node> nodes = new ArrayList<>();
        List<Node> delims = new ArrayList<>();
        int[] codeFail = newCodeFail();
        int[] brackets = null;
        StringBuilder o = new StringBuilder(s.length() + 16);
        int len = s.length();
        int i = 0;
        while (i < len) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': {
                    if (i + 1 < len && PUNCTUATION.indexOf(s.charAt(i + 1)) >= 0) {
                        o.append(escapeHtml(String.valueOf(s.charAt(i + 1))));
                        i += 2;
                    } else if (i + 1 < len && s.charAt(i + 1) == '\n') {
                        o.append("<br>\n");
                        i += 2;
                    } else {
                        o.append('\\');
                        i++;
                    }
                    continue;
                }
                case '`': {
                    int run = runLength(s, i, '`');
                    int close = findCodeClose(s, i + run, run, codeFail);
                    if (close >= 0) {
                        String code = s.substring(i + run, close).replace('\n', ' ');
                        if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ") && !code.trim().isEmpty()) {
                            code = code.substring(1, code.length() - 1);
                        }
                        o.append("<code>").append(escapeHtml(code)).append("</code>");
                        i = close + run;
                    } else {
                        o.append(s, i, i + run);
                        i += run;
                    }
                    continue;
                }
                case '!': {
                    if (i + 1 < len && s.charAt(i + 1) == '[') {
                        if (brackets == null) {
                            brackets = matchBrackets(s, codeFail);
                        }
                        int end = tryLink(s, i + 1, true, o, brackets);
                        if (end > 0) {
                            i = end;
                            continue;
                        }
                    }
                    o.append('!');
                    i++;
                    continue;
                }
                case '[': {
                    if (brackets == null) {
                        brackets = matchBrackets(s, codeFail);
                    }
                    int end = inLink ? -1 : tryLink(s, i, false, o, brackets);
                    if (end > 0) {
                        i = end;
                    } else {
                        o.append('[');
                        i++;
                    }
                    continue;
                }
                case '<': {
                    Matcher m;
                    if (!inLink && (m = AUTOLINK.matcher(s).region(i, len)).lookingAt()) {
                        String url = m.group(1);
                        o.append("<a href=\"").append(escapeAttr(url)).append("\">").append(escapeHtml(url)).append("</a>");
                        i = m.end();
                    } else if (!inLink && (m = EMAIL_AUTOLINK.matcher(s).region(i, len)).lookingAt()) {
                        String mail = m.group(1);
                        o.append("<a href=\"mailto:").append(escapeAttr(mail)).append("\">").append(escapeHtml(mail)).append("</a>");
                        i = m.end();
                    } else if ((m = HTML_COMMENT.matcher(s).region(i, len)).lookingAt()) {
                        i = m.end();
                    } else if ((m = INLINE_TAG.matcher(s).region(i, len)).lookingAt()) {
                        o.append(m.group());
                        i = m.end();
                    } else {
                        o.append("&lt;");
                        i++;
                    }
                    continue;
                }
                case '&': {
                    Matcher m = ENTITY.matcher(s).region(i, Math.min(len, i + 40));
                    if (m.lookingAt()) {
                        o.append(m.group());
                        i = m.end();
                    } else {
                        o.append("&amp;");
                        i++;
                    }
                    continue;
                }
                case '*':
                case '_':
                case '~': {
                    int run = runLength(s, i, c);
                    if (c == '~' && run != 2) {
                        o.append(s, i, i + run);
                        i += run;
                        continue;
                    }
                    char before = i == 0 ? ' ' : s.charAt(i - 1);
                    char after = i + run >= len ? ' ' : s.charAt(i + run);
                    boolean left = !Character.isWhitespace(after)
                            && (!isPunctuation(after) || Character.isWhitespace(before) || isPunctuation(before));
                    boolean right = !Character.isWhitespace(before)
                            && (!isPunctuation(before) || Character.isWhitespace(after) || isPunctuation(after));
                    boolean canOpen = left;
                    boolean canClose = right;
                    if (c == '_') {
                        canOpen = left && (!right || isPunctuation(before));
                        canClose = right && (!left || isPunctuation(after));
                    }
                    if (o.length() > 0) {
                        nodes.add(new Node(o.toString()));
                        o.setLength(0);
                    }
                    Node d = new Node(c, run, canOpen, canClose);
                    nodes.add(d);
                    if (canOpen || canClose) {
                        delims.add(d);
                    }
                    i += run;
                    continue;
                }
                case ' ': {
                    int run = runLength(s, i, ' ');
                    if (i + run < len && s.charAt(i + run) == '\n') {
                        o.append(run >= 2 ? "<br>\n" : "\n");
                        i += run + 1;
                    } else if (i + run >= len) {
                        i += run; // trailing spaces
                    } else {
                        o.append(s, i, i + run);
                        i += run;
                    }
                    continue;
                }
                case '>':
                    o.append("&gt;");
                    i++;
                    continue;
                case '"':
                    o.append("&quot;");
                    i++;
                    continue;
                default:
                    break;
            }
            if (!inLink && (c == 'h' || c == 'H' || c == 'w' || c == 'W') && (i == 0 || isUrlBoundary(s.charAt(i - 1)))) {
                Matcher m = BARE_URL.matcher(s).region(i, len);
                if (m.lookingAt()) {
                    String url = m.group();
                    while (!url.isEmpty() && ".,;:!?)'\"*_~".indexOf(url.charAt(url.length() - 1)) >= 0) {
                        if (url.endsWith(")") && count(url, '(') >= count(url, ')')) {
                            break;
                        }
                        url = url.substring(0, url.length() - 1);
                    }
                    if (url.length() > 7 && url.indexOf('.') > 0) {
                        String href = url.regionMatches(true, 0, "www.", 0, 4) ? "http://" + url : url;
                        o.append("<a href=\"").append(escapeAttr(href)).append("\">").append(escapeHtml(url)).append("</a>");
                        i += url.length();
                        continue;
                    }
                }
            }
            o.append(c);
            i++;
        }
        if (delims.isEmpty() && nodes.isEmpty()) {
            return o.toString();
        }
        if (o.length() > 0) {
            nodes.add(new Node(o.toString()));
        }
        processEmphasis(delims);
        StringBuilder out = new StringBuilder(len + 32);
        for (Node node : nodes) {
            if (node.delim == 0) {
                out.append(node.html);
            } else {
                out.append(node.closeTags);
                for (int k = 0; k < node.count; k++) {
                    out.append(node.delim);
                }
                out.append(node.openTags);
            }
        }
        return out.toString();
    }

    /** CommonMark "process emphasis": matches delimiter runs into em / strong / s pairs. */
    private static void processEmphasis(List<Node> delims) {
        int n = delims.size();
        // doubly linked list over the delimiter stack so removals are O(1)
        int[] prev = new int[n];
        int[] next = new int[n];
        for (int k = 0; k < n; k++) {
            prev[k] = k - 1;
            next[k] = k + 1;
        }
        // openers_bottom, keyed by delimiter char, closer length mod 3 and whether the closer can open
        int[] bottom = new int[3 * 3 * 2];
        java.util.Arrays.fill(bottom, -1);
        for (int ci = 0; ci < n; ci++) {
            Node closer = delims.get(ci);
            while (closer.active && closer.canClose && closer.count > 0) {
                int key = (closer.delim == '*' ? 0 : closer.delim == '_' ? 1 : 2) * 6
                        + (closer.origCount % 3) * 2 + (closer.canOpen ? 1 : 0);
                int found = -1;
                for (int oi = prev[ci]; oi > bottom[key]; oi = prev[oi]) {
                    Node op = delims.get(oi);
                    if (op.delim != closer.delim || !op.canOpen || op.count == 0) {
                        continue;
                    }
                    if (closer.delim != '~' && (op.canClose || closer.canOpen)
                            && (op.origCount + closer.origCount) % 3 == 0
                            && !(op.origCount % 3 == 0 && closer.origCount % 3 == 0)) {
                        continue; // "rule of 3"
                    }
                    found = oi;
                    break;
                }
                if (found < 0) {
                    bottom[key] = ci - 1;
                    if (!closer.canOpen) {
                        unlink(ci, prev, next, n);
                        closer.active = false;
                    }
                    break;
                }
                Node op = delims.get(found);
                int use = closer.delim == '~' ? 2 : op.count >= 2 && closer.count >= 2 ? 2 : 1;
                String tag = closer.delim == '~' ? "s" : use == 2 ? "strong" : "em";
                op.count -= use;
                closer.count -= use;
                op.openTags.insert(0, "<" + tag + ">");
                closer.closeTags.append("</").append(tag).append('>');
                // delimiters between opener and closer can no longer match
                for (int k = prev[ci]; k > found; k = prev[k]) {
                    delims.get(k).active = false;
                    unlink(k, prev, next, n);
                }
                if (op.count == 0) {
                    op.active = false;
                    unlink(found, prev, next, n);
                }
            }
        }
    }

    private static void unlink(int k, int[] prev, int[] next, int n) {
        if (prev[k] >= 0) {
            next[prev[k]] = next[k];
        }
        if (next[k] < n) {
            prev[next[k]] = prev[k];
        }
    }

    private static boolean isPunctuation(char c) {
        if (c < 128) {
            return PUNCTUATION.indexOf(c) >= 0;
        }
        switch (Character.getType(c)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.MATH_SYMBOL:
            case Character.CURRENCY_SYMBOL:
            case Character.MODIFIER_SYMBOL:
            case Character.OTHER_SYMBOL:
                return true;
            default:
                return false;
        }
    }

    private static boolean isUrlBoundary(char c) {
        return Character.isWhitespace(c) || c == '(' || c == '*' || c == '_' || c == '~';
    }

    /**
     * Finds the closing backtick run of exactly {@code run} backticks at or after {@code from}.
     * {@code codeFail[r]} remembers the smallest start from which a search for length r failed, so
     * repeated hopeless searches (e.g. many unmatched backticks) stay linear.
     */
    private static int findCodeClose(String s, int from, int run, int[] codeFail) {
        if (run < codeFail.length && from >= codeFail[run]) {
            return -1;
        }
        int i = from;
        while (i < s.length()) {
            int idx = s.indexOf('`', i);
            if (idx < 0) {
                break;
            }
            int r = runLength(s, idx, '`');
            if (r == run) {
                return idx;
            }
            i = idx + r;
        }
        if (run < codeFail.length) {
            codeFail[run] = Math.min(codeFail[run], from);
        }
        return -1;
    }

    private static int[] newCodeFail() {
        int[] f = new int[33];
        java.util.Arrays.fill(f, Integer.MAX_VALUE);
        return f;
    }

    /** Parses a link or image starting at the '[' at {@code open}. Returns end index or -1. */
    private int tryLink(String s, int open, boolean image, StringBuilder o, int[] brackets) {
        int close = brackets[open];
        if (close < 0) {
            return -1;
        }
        String text = s.substring(open + 1, close);
        String url = null;
        String title = null;
        int end;
        int len = s.length();
        if (close + 1 < len && s.charAt(close + 1) == '(') {
            int p = close + 2;
            while (p < len && Character.isWhitespace(s.charAt(p))) {
                p++;
            }
            StringBuilder dest = new StringBuilder();
            if (p < len && s.charAt(p) == '<') {
                int gt = s.indexOf('>', p);
                if (gt < 0) {
                    return -1;
                }
                dest.append(s, p + 1, gt);
                p = gt + 1;
            } else {
                int depth = 0;
                while (p < len) {
                    if (--scanBudget < 0) {
                        return -1;
                    }
                    char ch = s.charAt(p);
                    if (Character.isWhitespace(ch)) {
                        break;
                    }
                    if (ch == '\\' && p + 1 < len && PUNCTUATION.indexOf(s.charAt(p + 1)) >= 0) {
                        dest.append(s.charAt(p + 1));
                        p += 2;
                        continue;
                    }
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        if (depth == 0) {
                            break;
                        }
                        depth--;
                    }
                    dest.append(ch);
                    p++;
                }
            }
            while (p < len && Character.isWhitespace(s.charAt(p))) {
                p++;
            }
            if (p < len && (s.charAt(p) == '"' || s.charAt(p) == '\'' || s.charAt(p) == '(')) {
                char q = s.charAt(p) == '(' ? ')' : s.charAt(p);
                int qe = s.indexOf(q, p + 1);
                if (qe < 0) {
                    return -1;
                }
                title = s.substring(p + 1, qe);
                p = qe + 1;
                while (p < len && Character.isWhitespace(s.charAt(p))) {
                    p++;
                }
            }
            if (p >= len || s.charAt(p) != ')') {
                return -1;
            }
            url = dest.toString();
            end = p + 1;
        } else {
            String label;
            end = close + 1;
            if (close + 1 < len && s.charAt(close + 1) == '[') {
                int lc = s.indexOf(']', close + 2);
                if (lc < 0) {
                    return -1;
                }
                label = s.substring(close + 2, lc);
                if (label.trim().isEmpty()) {
                    label = text;
                }
                end = lc + 1;
            } else {
                label = text;
            }
            String[] ref = references.get(normalizeLabel(label));
            if (ref == null) {
                return -1;
            }
            url = ref[0];
            title = ref[1];
        }
        if (image) {
            o.append("<img src=\"").append(escapeAttr(url)).append("\" alt=\"").append(escapeAttr(stripMarkup(text))).append('"');
            if (title != null) {
                o.append(" title=\"").append(escapeAttr(title)).append('"');
            }
            o.append(" border=\"0\">");
        } else {
            o.append("<a href=\"").append(escapeAttr(url)).append('"');
            if (title != null) {
                o.append(" title=\"").append(escapeAttr(title)).append('"');
            }
            boolean wasInLink = inLink;
            inLink = true;
            try {
                o.append('>').append(inline(text)).append("</a>");
            } finally {
                inLink = wasInLink;
            }
        }
        return end;
    }

    /**
     * For every '[' in {@code s} computes the index of its matching ']' (or -1), skipping escapes
     * and code spans. One linear pass instead of a scan per bracket.
     */
    private static int[] matchBrackets(String s, int[] codeFail) {
        int len = s.length();
        int[] match = new int[len];
        java.util.Arrays.fill(match, -1);
        int[] stack = new int[16];
        int sp = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '`') {
                int run = runLength(s, i, '`');
                int close = findCodeClose(s, i + run, run, codeFail);
                i = close >= 0 ? close + run - 1 : i + run - 1;
            } else if (c == '[') {
                if (sp == stack.length) {
                    stack = java.util.Arrays.copyOf(stack, sp * 2);
                }
                stack[sp++] = i;
            } else if (c == ']' && sp > 0) {
                match[stack[--sp]] = i;
            }
        }
        return match;
    }

    // ------------------------------------------------------------------ helpers

    private String uniqueAnchor(String slug) {
        Integer count = usedAnchors.get(slug);
        if (count == null) {
            usedAnchors.put(slug, 1);
            return slug;
        }
        usedAnchors.put(slug, count + 1);
        return slug + "-" + count;
    }

    /** GitHub-style heading slug: lower case, punctuation removed, spaces to hyphens. */
    public static String slugify(String heading) {
        String plain = stripMarkup(heading).toLowerCase(Locale.ROOT).trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('-');
            }
        }
        return sb.length() == 0 ? "section" : sb.toString();
    }

    private static String stripMarkup(String s) {
        return s.replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1").replaceAll("[*_`~\\\\]", "").replaceAll("<[^>]+>", "");
    }

    private static String normalizeLabel(String label) {
        return label.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    static String escapeAttr(String s) {
        return escapeHtml(s);
    }

    private static String expandTabs(String line) {
        if (line.indexOf('\t') < 0) {
            return line;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                int spaces = 4 - (sb.length() % 4);
                for (int k = 0; k < spaces; k++) {
                    sb.append(' ');
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s.trim().isEmpty();
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String stripIndent(String s, int max) {
        int n = Math.min(max, leadingSpaces(s));
        return s.substring(n);
    }

    private static String stripLeading(String s) {
        return s.substring(leadingSpaces(s));
    }

    private static String trimTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static int runLength(String s, int i, char c) {
        int n = 0;
        while (i + n < s.length() && s.charAt(i + n) == c) {
            n++;
        }
        return n;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
