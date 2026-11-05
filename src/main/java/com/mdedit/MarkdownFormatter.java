package com.mdedit;

import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CompoundEdit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static Markdown formatting helpers that operate on a text component (usually the
 * {@link MarkdownEditorPanel} editor). Inline operations wrap the selection (or insert and
 * select a placeholder); line operations apply to every line touched by the selection and
 * toggle when all lines already carry the prefix. Every operation is a single undoable edit.
 */
public final class MarkdownFormatter {

    private MarkdownFormatter() {
    }

    // ------------------------------------------------------------------ undo grouping

    /**
     * Runs {@code r} so that all document modifications it performs reach the document's
     * undo listeners as one compound edit (one Undo step).
     */
    public static void runAsSingleEdit(JTextComponent c, Runnable r) {
        Document doc = c.getDocument();
        if (!(doc instanceof AbstractDocument)) {
            r.run();
            return;
        }
        AbstractDocument ad = (AbstractDocument) doc;
        UndoableEditListener[] listeners = ad.getUndoableEditListeners();
        CompoundEdit compound = new CompoundEdit();
        UndoableEditListener collector = e -> compound.addEdit(e.getEdit());
        for (UndoableEditListener l : listeners) {
            ad.removeUndoableEditListener(l);
        }
        ad.addUndoableEditListener(collector);
        try {
            r.run();
        } finally {
            ad.removeUndoableEditListener(collector);
            for (UndoableEditListener l : listeners) {
                ad.addUndoableEditListener(l);
            }
            compound.end();
            if (compound.canUndo()) {
                UndoableEditEvent ev = new UndoableEditEvent(ad, compound);
                for (UndoableEditListener l : listeners) {
                    l.undoableEditHappened(ev);
                }
            }
        }
    }

    // ------------------------------------------------------------------ inline formatting

    public static void bold(JTextComponent c) {
        wrap(c, "**", "**", "bold text");
    }

    public static void italic(JTextComponent c) {
        wrap(c, "*", "*", "italic text");
    }

    public static void strikethrough(JTextComponent c) {
        wrap(c, "~~", "~~", "strikethrough text");
    }

    public static void inlineCode(JTextComponent c) {
        wrap(c, "`", "`", "code");
    }

    /**
     * Wraps the selection in {@code prefix}/{@code suffix}. If the selection is already wrapped
     * (markers directly outside or inside the selection) the markers are removed instead.
     * With an empty selection the placeholder is inserted wrapped and selected.
     */
    public static void wrap(JTextComponent c, String prefix, String suffix, String placeholder) {
        runAsSingleEdit(c, () -> {
            try {
                Document doc = c.getDocument();
                int s = c.getSelectionStart();
                int e = c.getSelectionEnd();
                String sel = doc.getText(s, e - s);
                int pl = prefix.length();
                int sl = suffix.length();
                if (sel.isEmpty()) {
                    doc.insertString(s, prefix + placeholder + suffix, null);
                    select(c, s + pl, s + pl + placeholder.length());
                    return;
                }
                // markers just outside the selection -> unwrap
                if (s >= pl && e + sl <= doc.getLength()
                        && doc.getText(s - pl, pl).equals(prefix)
                        && doc.getText(e, sl).equals(suffix)
                        && !isSingleStarAmbiguity(doc, prefix, s - pl, e + sl)) {
                    doc.remove(e, sl);
                    doc.remove(s - pl, pl);
                    select(c, s - pl, e - pl);
                    return;
                }
                // markers inside the selection -> unwrap
                if (sel.length() >= pl + sl && sel.startsWith(prefix) && sel.endsWith(suffix)
                        && !(prefix.equals("*") && sel.startsWith("**") && !sel.startsWith("***"))) {
                    String inner = sel.substring(pl, sel.length() - sl);
                    replace(doc, s, e - s, inner);
                    select(c, s, s + inner.length());
                    return;
                }
                // keep surrounding whitespace outside the markers ("**word** " not "**word **")
                int lead = 0;
                while (lead < sel.length() && Character.isWhitespace(sel.charAt(lead))) {
                    lead++;
                }
                int trail = 0;
                while (trail < sel.length() - lead
                        && Character.isWhitespace(sel.charAt(sel.length() - 1 - trail))) {
                    trail++;
                }
                if (lead == sel.length()) {
                    doc.insertString(e, prefix + placeholder + suffix, null);
                    select(c, e + pl, e + pl + placeholder.length());
                    return;
                }
                String core = sel.substring(lead, sel.length() - trail);
                int cs = s + lead;
                replace(doc, cs, core.length(), prefix + core + suffix);
                select(c, cs + pl, cs + pl + core.length());
            } catch (BadLocationException ex) {
                c.getToolkit().beep();
            }
        });
        c.requestFocusInWindow();
    }

    /** "*x*" inside "**x**": the outer markers are part of bold, not italic. */
    private static boolean isSingleStarAmbiguity(Document doc, String prefix, int start, int end)
            throws BadLocationException {
        if (!prefix.equals("*")) {
            return false;
        }
        boolean starBefore = start > 0 && doc.getText(start - 1, 1).equals("*");
        boolean starAfter = end < doc.getLength() && doc.getText(end, 1).equals("*");
        return starBefore != starAfter;
    }

    public static void link(JTextComponent c) {
        runAsSingleEdit(c, () -> {
            try {
                Document doc = c.getDocument();
                int s = c.getSelectionStart();
                int e = c.getSelectionEnd();
                String sel = doc.getText(s, e - s);
                if (looksLikeUrl(sel)) {
                    String text = "link text";
                    replace(doc, s, e - s, "[" + text + "](" + sel.trim() + ")");
                    select(c, s + 1, s + 1 + text.length());
                } else {
                    String text = sel.isEmpty() ? "link text" : sel;
                    String url = "https://";
                    replace(doc, s, e - s, "[" + text + "](" + url + ")");
                    int us = s + text.length() + 3;
                    if (sel.isEmpty()) {
                        select(c, s + 1, s + 1 + text.length());
                    } else {
                        select(c, us, us + url.length());
                    }
                }
            } catch (BadLocationException ex) {
                c.getToolkit().beep();
            }
        });
        c.requestFocusInWindow();
    }

    public static void image(JTextComponent c) {
        runAsSingleEdit(c, () -> {
            try {
                Document doc = c.getDocument();
                int s = c.getSelectionStart();
                int e = c.getSelectionEnd();
                String sel = doc.getText(s, e - s).trim();
                if (!sel.isEmpty() && (looksLikeUrl(sel) || looksLikeImagePath(sel))) {
                    String alt = "alt text";
                    replace(doc, s, e - s, "![" + alt + "](" + sel + ")");
                    select(c, s + 2, s + 2 + alt.length());
                } else {
                    String alt = sel.isEmpty() ? "alt text" : sel;
                    String path = "image.png";
                    replace(doc, s, e - s, "![" + alt + "](" + path + ")");
                    int ps = s + alt.length() + 4;
                    select(c, ps, ps + path.length());
                }
            } catch (BadLocationException ex) {
                c.getToolkit().beep();
            }
        });
        c.requestFocusInWindow();
    }

    private static boolean looksLikeUrl(String s) {
        String t = s.trim();
        return !t.isEmpty() && !t.contains(" ") && !t.contains("\n")
                && (t.matches("(?i)^(https?|ftp|file|mailto):.*") || t.startsWith("www."));
    }

    private static boolean looksLikeImagePath(String s) {
        return !s.contains(" ") && !s.contains("\n")
                && s.matches("(?i).*\\.(png|jpe?g|gif|bmp|svg|webp)$");
    }

    // ------------------------------------------------------------------ line-prefix formatting

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})[ \\t]+");
    private static final Pattern BULLET = Pattern.compile("^([ \\t]*)[-*+][ \\t]+(?!\\[[ xX]\\][ \\t])");
    private static final Pattern NUMBERED = Pattern.compile("^([ \\t]*)\\d{1,9}[.)][ \\t]+");
    private static final Pattern TASK = Pattern.compile("^([ \\t]*)[-*+][ \\t]+\\[[ xX]\\][ \\t]+");
    private static final Pattern QUOTE = Pattern.compile("^>[ \\t]?");
    /** Any list marker, used to convert one list type into another. */
    private static final Pattern ANY_LIST = Pattern.compile(
            "^([ \\t]*)(?:[-*+][ \\t]+\\[[ xX]\\][ \\t]+|[-*+][ \\t]+|\\d{1,9}[.)][ \\t]+)");

    public static void heading(JTextComponent c, int level) {
        String marks = "#".repeat(Math.max(1, Math.min(6, level))) + " ";
        transformLines(c, lines -> {
            boolean all = true;
            for (String l : lines) {
                if (l.trim().isEmpty()) {
                    continue;
                }
                Matcher m = HEADING.matcher(l);
                if (!m.find() || m.group(1).length() != level) {
                    all = false;
                }
            }
            String[] out = new String[lines.length];
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                String body = HEADING.matcher(l).replaceFirst("");
                if (all || (l.trim().isEmpty() && lines.length > 1)) {
                    out[i] = body;
                } else {
                    out[i] = marks + body;
                }
            }
            return out;
        });
    }

    public static void bulletList(JTextComponent c) {
        listToggle(c, BULLET, i -> "- ");
    }

    public static void numberedList(JTextComponent c) {
        listToggle(c, NUMBERED, i -> (i + 1) + ". ");
    }

    public static void taskList(JTextComponent c) {
        listToggle(c, TASK, i -> "- [ ] ");
    }

    public static void blockquote(JTextComponent c) {
        transformLines(c, lines -> {
            boolean all = true;
            for (String l : lines) {
                if (!l.trim().isEmpty() && !QUOTE.matcher(l).find()) {
                    all = false;
                }
            }
            String[] out = new String[lines.length];
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (all) {
                    out[i] = QUOTE.matcher(l).replaceFirst("");
                } else {
                    out[i] = l.isEmpty() ? ">" : "> " + l;
                }
            }
            return out;
        });
    }

    private interface MarkerFn {
        String marker(int index);
    }

    private static void listToggle(JTextComponent c, Pattern own, MarkerFn fn) {
        transformLines(c, lines -> {
            boolean all = true;
            for (String l : lines) {
                if (!l.trim().isEmpty() && !own.matcher(l).find()) {
                    all = false;
                }
            }
            String[] out = new String[lines.length];
            int n = 0;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (l.trim().isEmpty() && lines.length > 1) {
                    out[i] = l;
                    continue;
                }
                if (all) {
                    Matcher m = own.matcher(l);
                    out[i] = m.find() ? m.group(1) + l.substring(m.end()) : l;
                } else {
                    Matcher m = ANY_LIST.matcher(l);
                    String indent;
                    String body;
                    if (m.find()) {
                        indent = m.group(1);
                        body = l.substring(m.end());
                    } else {
                        int k = 0;
                        while (k < l.length() && (l.charAt(k) == ' ' || l.charAt(k) == '\t')) {
                            k++;
                        }
                        indent = l.substring(0, k);
                        body = l.substring(k);
                    }
                    out[i] = indent + fn.marker(n++) + body;
                }
            }
            return out;
        });
    }

    private interface LineTransform {
        String[] apply(String[] lines);
    }

    /**
     * Replaces all lines touched by the selection with the transformed lines and selects
     * the result (or keeps a caret at the end of the line when there was no selection).
     */
    private static void transformLines(JTextComponent c, LineTransform t) {
        runAsSingleEdit(c, () -> {
            try {
                Document doc = c.getDocument();
                Element root = doc.getDefaultRootElement();
                int s = c.getSelectionStart();
                int e = c.getSelectionEnd();
                boolean hadSelection = e > s;
                int first = root.getElementIndex(s);
                int last = root.getElementIndex(e);
                // a selection ending at the very start of a line does not include that line
                if (last > first && root.getElement(last).getStartOffset() == e) {
                    last--;
                }
                int start = root.getElement(first).getStartOffset();
                int end = root.getElement(last).getEndOffset() - 1; // exclude newline
                end = Math.min(end, doc.getLength());
                String block = doc.getText(start, end - start);
                String[] lines = block.split("\n", -1);
                String[] out = t.apply(lines);
                String result = String.join("\n", out);
                if (!result.equals(block)) {
                    replace(doc, start, end - start, result);
                }
                if (hadSelection || lines.length > 1) {
                    select(c, start, start + result.length());
                } else {
                    c.setCaretPosition(start + result.length());
                }
            } catch (BadLocationException ex) {
                c.getToolkit().beep();
            }
        });
        c.requestFocusInWindow();
    }

    // ------------------------------------------------------------------ block inserts

    public static void codeBlock(JTextComponent c) {
        runAsSingleEdit(c, () -> {
            try {
                Document doc = c.getDocument();
                int s = c.getSelectionStart();
                int e = c.getSelectionEnd();
                String sel = doc.getText(s, e - s);
                if (sel.endsWith("\n")) {
                    sel = sel.substring(0, sel.length() - 1);
                    e--;
                }
                String inner = sel.isEmpty() ? "code" : sel;
                String before = needsNewlineBefore(doc, s) ? "\n" : "";
                String after = needsNewlineAfter(doc, e) ? "\n" : "";
                String text = before + "```\n" + inner + "\n```" + after;
                replace(doc, s, e - s, text);
                int is = s + before.length() + 4;
                select(c, is, is + inner.length());
            } catch (BadLocationException ex) {
                c.getToolkit().beep();
            }
        });
        c.requestFocusInWindow();
    }

    public static void table(JTextComponent c) {
        String header = "Column 1";
        String tbl = "| Column 1 | Column 2 | Column 3 |\n"
                + "| -------- | -------- | -------- |\n"
                + "| Cell     | Cell     | Cell     |\n"
                + "| Cell     | Cell     | Cell     |";
        insertBlock(c, tbl, 2, 2 + header.length());
    }

    public static void horizontalRule(JTextComponent c) {
        insertBlock(c, "---", -1, -1);
    }

    /**
     * Inserts a block on its own line(s) (separated by blank lines) at the selection, then
     * selects the given range relative to the block, or places the caret after it.
     */
    private static void insertBlock(JTextComponent c, String block, int selFrom, int selTo) {
        runAsSingleEdit(c, () -> {
            try {
                Document doc = c.getDocument();
                int s = c.getSelectionStart();
                int e = c.getSelectionEnd();
                String before = "";
                if (s > 0) {
                    String prev = doc.getText(Math.max(0, s - 2), Math.min(2, s));
                    if (!prev.endsWith("\n")) {
                        before = "\n\n";
                    } else if (!prev.equals("\n\n") && s >= 2) {
                        before = "\n";
                    }
                }
                String after = "\n";
                if (e < doc.getLength()) {
                    String next = doc.getText(e, Math.min(2, doc.getLength() - e));
                    after = next.startsWith("\n\n") ? "" : next.startsWith("\n") ? "\n" : "\n\n";
                }
                replace(doc, s, e - s, before + block + after);
                int bs = s + before.length();
                if (selFrom >= 0) {
                    select(c, bs + selFrom, bs + selTo);
                } else {
                    c.setCaretPosition(bs + block.length() + after.length());
                }
            } catch (BadLocationException ex) {
                c.getToolkit().beep();
            }
        });
        c.requestFocusInWindow();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean needsNewlineBefore(Document doc, int pos) throws BadLocationException {
        return pos > 0 && !doc.getText(pos - 1, 1).equals("\n");
    }

    private static boolean needsNewlineAfter(Document doc, int pos) throws BadLocationException {
        return pos < doc.getLength() && !doc.getText(pos, 1).equals("\n");
    }

    private static void replace(Document doc, int offset, int length, String text)
            throws BadLocationException {
        if (doc instanceof AbstractDocument) {
            ((AbstractDocument) doc).replace(offset, length, text, null);
        } else {
            if (length > 0) {
                doc.remove(offset, length);
            }
            doc.insertString(offset, text, null);
        }
    }

    private static void select(JTextComponent c, int start, int end) {
        c.setCaretPosition(start);
        c.moveCaretPosition(end);
    }
}
