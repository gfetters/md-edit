package com.mdedit;

import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Hashtable;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.ImageIcon;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.event.DocumentEvent;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.Position;
import javax.swing.text.StyleConstants;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.ImageView;

/**
 * Turns Markdown into HTML for the Swing preview, for printing and for export.
 *
 * <p>Swing's HTML engine understands HTML 3.2 and a small subset of CSS 1, so the Swing
 * flavour ({@link #buildSwingHtml}) uses only constructs that were verified to render
 * correctly in {@link JEditorPane}; table colours are additionally applied as
 * {@code bgcolor} attributes because Swing ignores descendant/class selectors on rows.
 * The export flavour ({@link #buildExportHtml}) targets modern browsers.</p>
 */
public final class HtmlRenderer {

    /** Document property holding the image cache that {@link ImageView} consults. */
    private static final String IMAGE_CACHE_PROPERTY = "imageCache";

    /** Padding used by the body in the Swing style sheet (px); images are limited to the remaining width. */
    private static final int BODY_MARGIN = 14;

    private static final String TABLE_BG = "#d0d7de";
    private static final String TH_BG = "#eef1f4";
    private static final String TD_BG = "#ffffff";
    private static final String ALT_BG = "#f6f8fa";

    private HtmlRenderer() {
    }

    // ------------------------------------------------------------------ Swing HTML

    /**
     * Builds a complete HTML document (with an embedded style sheet tuned for {@link JEditorPane}).
     *
     * @param markdown   Markdown source (null is treated as empty)
     * @param baseFontPx body font size in pixels (Java font points)
     * @param forPrint   true for print styling (white background, slightly tighter spacing)
     */
    public static String buildSwingHtml(String markdown, int baseFontPx, boolean forPrint) {
        String body = MarkdownParser.toHtml(markdown == null ? "" : markdown);
        body = decorateTables(body);
        StringBuilder sb = new StringBuilder(body.length() + 3000);
        sb.append("<html><head><style type=\"text/css\">\n");
        sb.append(swingCss(baseFontPx, forPrint));
        sb.append("</style></head><body>\n");
        sb.append(body);
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String swingCss(int base, boolean forPrint) {
        int b = Math.max(6, base);
        int small = Math.max(6, Math.round(b * 0.9f));
        int margin = forPrint ? 0 : BODY_MARGIN;
        String text = "#1f2328";
        String muted = "#59636e";
        StringBuilder c = new StringBuilder(2000);
        c.append("body { font-family: SansSerif; font-size: ").append(b).append("px; color: ").append(text)
                .append("; background-color: #ffffff; margin: ").append(margin).append("px; }\n");
        c.append("p { margin-top: 0px; margin-bottom: ").append(px(b, 0.75)).append("px; }\n");
        heading(c, "h1", b * 2.0, b, true);
        heading(c, "h2", b * 1.5, b, true);
        heading(c, "h3", b * 1.25, b, false);
        heading(c, "h4", b * 1.0, b, false);
        heading(c, "h5", b * 0.875, b, false);
        c.append("h6 { color: ").append(muted).append("; }\n");
        c.append("a { color: #0969da; text-decoration: none; }\n");
        c.append("code { font-family: Monospaced; font-size: ").append(small)
                .append("px; background-color: #eff1f3; }\n");
        c.append("pre { font-family: Monospaced; font-size: ").append(small).append("px; background-color: #f6f8fa; ")
                .append("margin-top: 0px; margin-bottom: ").append(px(b, 0.9)).append("px; ")
                .append("padding: ").append(px(b, 0.8)).append("px; ")
                .append("border: 1px solid #e1e4e8; }\n");
        c.append("pre code { background-color: #f6f8fa; }\n");
        c.append("blockquote { color: ").append(muted).append("; margin-top: 0px; margin-bottom: ").append(px(b, 0.75))
                .append("px; margin-left: 0px; margin-right: 0px; padding-left: ").append(px(b, 0.9))
                .append("px; padding-top: 2px; padding-bottom: 2px; ")
                .append("border-left: 4px solid #d0d7de; }\n");
        c.append("ul { margin-top: 0px; margin-bottom: ").append(px(b, 0.6)).append("px; margin-left: ")
                .append(px(b, 1.8)).append("px; }\n");
        c.append("ol { margin-top: 0px; margin-bottom: ").append(px(b, 0.6)).append("px; margin-left: ")
                .append(px(b, 2.0)).append("px; }\n");
        c.append("li { margin-top: 1px; margin-bottom: 1px; }\n");
        c.append("li p { margin-bottom: 2px; }\n");
        c.append("ul.task-list { list-style-type: none; margin-left: ").append(px(b, 0.6)).append("px; }\n");
        c.append("span.checkbox { font-family: Dialog; }\n");
        c.append("table { margin-bottom: ").append(px(b, 0.9)).append("px; }\n");
        c.append("th { font-weight: bold; text-align: left; }\n");
        c.append("td, th { font-size: ").append(b).append("px; }\n");
        c.append("hr { margin-top: ").append(px(b, 0.6)).append("px; margin-bottom: ").append(px(b, 0.6)).append("px; }\n");
        c.append("s, strike, del { color: ").append(muted).append("; }\n");
        return c.toString();
    }

    private static void heading(StringBuilder c, String tag, double size, int base, boolean rule) {
        c.append(tag).append(" { font-size: ").append(Math.round(size)).append("px; font-weight: bold; ")
                .append("margin-top: ").append(px(base, 1.2)).append("px; margin-bottom: ").append(px(base, 0.6))
                .append("px; ");
        if (rule) {
            c.append("padding-bottom: ").append(px(base, 0.25)).append("px; border-bottom: 1px solid #d8dee4; ");
        }
        c.append("}\n");
    }

    private static int px(int base, double factor) {
        return (int) Math.round(base * factor);
    }

    private static final Pattern TABLE_TAG = Pattern.compile("<table class=\"md-table\"");
    private static final Pattern ROW = Pattern.compile("<tr( class=\"alt\")?>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL_OPEN = Pattern.compile("<(td|th)(?=[ >])");

    /** Adds bgcolor attributes so the grid / header / zebra colours render in Swing. */
    private static String decorateTables(String html) {
        if (!html.contains("<table class=\"md-table\"")) {
            return html;
        }
        html = TABLE_TAG.matcher(html).replaceAll("<table class=\"md-table\" bgcolor=\"" + TABLE_BG
                + "\" style=\"border-right: 1px solid " + TABLE_BG + "; border-bottom: 1px solid " + TABLE_BG + "\"");
        Matcher m = ROW.matcher(html);
        StringBuffer sb = new StringBuffer(html.length() + 512);
        while (m.find()) {
            boolean alt = m.group(1) != null;
            Matcher cm = CELL_OPEN.matcher(m.group(2));
            StringBuffer row = new StringBuffer();
            while (cm.find()) {
                String color = "th".equals(cm.group(1)) ? TH_BG : alt ? ALT_BG : TD_BG;
                cm.appendReplacement(row, "<" + cm.group(1) + " bgcolor=\"" + color + "\"");
            }
            cm.appendTail(row);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    "<tr" + (alt ? " class=\"alt\"" : "") + ">" + row + "</tr>"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ export HTML

    /** Builds a standalone UTF-8 HTML page with GitHub-like CSS for modern browsers. */
    public static String buildExportHtml(String markdown, String title) {
        String body = MarkdownParser.toHtml(markdown == null ? "" : markdown);
        String t = title == null || title.trim().isEmpty() ? "Document" : title;
        StringBuilder sb = new StringBuilder(body.length() + 4000);
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta name=\"generator\" content=\"MD Edit\">\n")
                .append("<title>").append(escape(t)).append("</title>\n<style>\n")
                .append(EXPORT_CSS)
                .append("</style>\n</head>\n<body>\n<article class=\"markdown-body\">\n")
                .append(body)
                .append("</article>\n</body>\n</html>\n");
        return sb.toString();
    }

    private static final String EXPORT_CSS = String.join("\n",
            ":root { color-scheme: light; }",
            "* { box-sizing: border-box; }",
            "body { margin: 0; background: #ffffff; color: #1f2328;",
            "  font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", \"Noto Sans\", Helvetica, Arial, sans-serif,",
            "  \"Apple Color Emoji\", \"Segoe UI Emoji\"; font-size: 16px; line-height: 1.6; }",
            ".markdown-body { max-width: 900px; margin: 0 auto; padding: 32px 40px 64px; word-wrap: break-word; }",
            ".markdown-body > :first-child { margin-top: 0 !important; }",
            "h1, h2, h3, h4, h5, h6 { margin: 24px 0 16px; font-weight: 600; line-height: 1.25; }",
            "h1 { font-size: 2em; padding-bottom: .3em; border-bottom: 1px solid #d8dee4; }",
            "h2 { font-size: 1.5em; padding-bottom: .3em; border-bottom: 1px solid #d8dee4; }",
            "h3 { font-size: 1.25em; } h4 { font-size: 1em; } h5 { font-size: .875em; }",
            "h6 { font-size: .85em; color: #59636e; }",
            "p, blockquote, ul, ol, table, pre { margin: 0 0 16px; }",
            "a { color: #0969da; text-decoration: none; } a:hover { text-decoration: underline; }",
            "strong { font-weight: 600; }",
            "s, del { color: #59636e; }",
            "code { font-family: ui-monospace, SFMono-Regular, \"SF Mono\", Menlo, Consolas, \"Liberation Mono\", monospace;",
            "  font-size: 85%; background: rgba(175,184,193,.2); padding: .2em .4em; border-radius: 6px; }",
            "pre { background: #f6f8fa; padding: 16px; overflow: auto; border-radius: 6px; line-height: 1.45; }",
            "pre code { background: transparent; padding: 0; font-size: 85%; border-radius: 0; white-space: pre; }",
            "blockquote { padding: 0 1em; color: #59636e; border-left: .25em solid #d0d7de; margin-left: 0; }",
            "blockquote > :last-child { margin-bottom: 0; }",
            "ul, ol { padding-left: 2em; } li + li { margin-top: .25em; }",
            "ul.task-list { list-style: none; padding-left: 1em; }",
            ".checkbox { font-family: \"Segoe UI Symbol\", \"DejaVu Sans\", sans-serif; }",
            "table { border-collapse: collapse; border-spacing: 0; display: block; width: max-content; max-width: 100%; overflow: auto; }",
            "th, td { padding: 6px 13px; border: 1px solid #d0d7de; }",
            "th { font-weight: 600; background: #f6f8fa; }",
            "tr { background: #ffffff; border-top: 1px solid #d8dee4; } tr.alt { background: #f6f8fa; }",
            "hr { height: .25em; padding: 0; margin: 24px 0; background: #d0d7de; border: 0; }",
            "img { max-width: 100%; box-sizing: content-box; }",
            "@media print { .markdown-body { max-width: none; padding: 0; } pre { white-space: pre-wrap; } }",
            "");

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ kit & document

    /**
     * Creates an HTML editor kit for the preview / print panes.
     *
     * @param synchronousImages true to load images synchronously (needed for printing so that
     *                          images are present when the page is rendered)
     */
    public static HTMLEditorKit createKit(boolean synchronousImages) {
        return new MdKit(synchronousImages);
    }

    /**
     * Parses {@code markdown} into a fresh {@link HTMLDocument}. The base URL (may be null) is used to
     * resolve relative image and link URLs. Must be called on the EDT if the document will be shown
     * immediately; it does not touch any component itself.
     */
    public static HTMLDocument createDocument(String markdown, URL base, int baseFontPx, boolean forPrint,
                                              HTMLEditorKit kit) {
        HTMLEditorKit k = kit != null ? kit : createKit(forPrint);
        HTMLDocument doc = (HTMLDocument) k.createDefaultDocument();
        doc.putProperty("IgnoreCharsetDirective", Boolean.TRUE);
        doc.setAsynchronousLoadPriority(-1); // parse synchronously
        if (base != null) {
            doc.setBase(base);
        }
        if (k instanceof MdKit) {
            doc.putProperty(IMAGE_CACHE_PROPERTY, ((MdKit) k).imageCache);
        }
        try {
            k.read(new StringReader(buildSwingHtml(markdown, baseFontPx, forPrint)), doc, 0);
        } catch (IOException | BadLocationException e) {
            throw new IllegalStateException("Cannot build preview document", e);
        }
        return doc;
    }

    /**
     * Creates an offscreen, non-editable pane holding the print styled document, already sized and laid
     * out so that {@link JEditorPane#getPrintable} works without the pane ever being shown. Images are
     * loaded synchronously. Safe to call from any thread (work is done on the EDT).
     */
    public static JEditorPane createPrintPane(String markdown, URL base) {
        if (SwingUtilities.isEventDispatchThread()) {
            return buildPrintPane(markdown, base);
        }
        final JEditorPane[] result = new JEditorPane[1];
        try {
            SwingUtilities.invokeAndWait(() -> result[0] = buildPrintPane(markdown, base));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while preparing print document", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        }
        return result[0];
    }

    /** Base font size used for printing (Java points == 1/72 inch on paper). */
    public static final int PRINT_FONT_PX = 11;

    private static JEditorPane buildPrintPane(String markdown, URL base) {
        HTMLEditorKit kit = createKit(true);
        JEditorPane pane = new JEditorPane();
        pane.setEditable(false);
        pane.setEditorKit(kit);
        pane.setBackground(Color.WHITE);
        pane.setMargin(new Insets(0, 0, 0, 0));
        pane.setBorder(null);
        ((DefaultCaret) pane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        pane.setDocument(createDocument(markdown, base, PRINT_FONT_PX, true, kit));
        // Lay out at roughly the width of an A4/Letter imageable area at 72 dpi; the printable
        // re-lays out to the real page width anyway.
        int width = 468;
        pane.setSize(width, Short.MAX_VALUE);
        Dimension pref = pane.getPreferredSize();
        pane.setSize(width, Math.max(1, pref.height));
        pane.doLayout();
        return pane;
    }

    // ------------------------------------------------------------------ kit implementation

    /** HTMLEditorKit whose images optionally load synchronously and are scaled down to fit. */
    private static final class MdKit extends HTMLEditorKit {
        private static final long serialVersionUID = 1L;
        private final boolean synchronousImages;
        private final transient ImageCache imageCache;
        private final transient ViewFactory factory = new MdFactory();

        MdKit(boolean synchronousImages) {
            this.synchronousImages = synchronousImages;
            this.imageCache = new ImageCache(synchronousImages);
        }

        @Override
        public ViewFactory getViewFactory() {
            return factory;
        }

        @Override
        public Object clone() {
            return new MdKit(synchronousImages);
        }

        private final class MdFactory extends HTMLFactory {
            @Override
            public View create(Element elem) {
                AttributeSet attrs = elem.getAttributes();
                Object name = attrs.getAttribute(StyleConstants.NameAttribute);
                if (name == HTML.Tag.IMG) {
                    FitImageView v = new FitImageView(elem, synchronousImages ? 0 : BODY_MARGIN);
                    v.setLoadsSynchronously(synchronousImages);
                    return v;
                }
                if (name == HTML.Tag.HR) {
                    return new FlatRuleView(elem);
                }
                return super.create(elem);
            }
        }
    }

    /**
     * Image cache shared by all documents created with one kit, so that the preview does not
     * flicker / reload images on every refresh. {@link ImageView} looks images up by URL.
     */
    private static final class ImageCache extends Hashtable<Object, Object> {
        private static final long serialVersionUID = 1L;
        private static final int MAX = 64;
        private final boolean waitForLoad;

        ImageCache(boolean waitForLoad) {
            this.waitForLoad = waitForLoad;
        }

        @Override
        public synchronized Object get(Object key) {
            Object v = super.get(key);
            if (v == null && key instanceof URL) {
                if (size() >= MAX) {
                    clear();
                }
                Image img = Toolkit.getDefaultToolkit().createImage((URL) key);
                if (img == null) {
                    return null;
                }
                if (waitForLoad) {
                    // ImageView skips its own synchronous loading when a cache is present,
                    // so force the pixels in here (ImageIcon waits on a MediaTracker).
                    int status = new ImageIcon(img).getImageLoadStatus();
                    if (status == java.awt.MediaTracker.ERRORED || status == java.awt.MediaTracker.ABORTED) {
                        return null; // ImageView then shows its "broken image" icon + alt text
                    }
                }
                v = img;
                put(key, v);
            }
            return v;
        }
    }

    /** Horizontal rule painted as a flat light-gray bar instead of Swing's dark bevel. */
    private static final class FlatRuleView extends View {
        private static final int BAR = 2;
        private float above = 8;
        private float below = 8;

        FlatRuleView(Element elem) {
            super(elem);
            setPropertiesFromAttributes();
        }

        private void setPropertiesFromAttributes() {
            Document d = getDocument();
            if (d instanceof HTMLDocument) {
                AttributeSet va = ((HTMLDocument) d).getStyleSheet().getViewAttributes(this);
                above = Math.max(2f, StyleConstants.getSpaceAbove(va));
                below = Math.max(2f, StyleConstants.getSpaceBelow(va));
            }
        }

        @Override
        public void changedUpdate(DocumentEvent e, Shape a, ViewFactory f) {
            super.changedUpdate(e, a, f);
            setPropertiesFromAttributes();
            preferenceChanged(null, true, true);
        }

        @Override
        public float getPreferredSpan(int axis) {
            return axis == View.X_AXIS ? 1f : above + BAR + below;
        }

        @Override
        public float getMinimumSpan(int axis) {
            return getPreferredSpan(axis);
        }

        @Override
        public float getMaximumSpan(int axis) {
            return axis == View.X_AXIS ? Integer.MAX_VALUE : getPreferredSpan(axis);
        }

        @Override
        public int getResizeWeight(int axis) {
            return axis == View.X_AXIS ? 1 : 0;
        }

        @Override
        public int getBreakWeight(int axis, float pos, float len) {
            return axis == View.X_AXIS ? ForcedBreakWeight : BadBreakWeight;
        }

        @Override
        public View breakView(int axis, int offset, float pos, float len) {
            return null;
        }

        @Override
        public void paint(Graphics g, Shape a) {
            Rectangle r = a instanceof Rectangle ? (Rectangle) a : a.getBounds();
            Color old = g.getColor();
            g.setColor(RULE_COLOR);
            g.fillRect(r.x, r.y + Math.round(above), r.width, BAR);
            g.setColor(old);
        }

        @Override
        public Shape modelToView(int pos, Shape a, Position.Bias b) throws BadLocationException {
            int p0 = getStartOffset();
            int p1 = getEndOffset();
            if (pos >= p0 && pos <= p1) {
                Rectangle r = a.getBounds();
                if (pos == p1) {
                    r.x += r.width;
                }
                r.width = 0;
                return r;
            }
            throw new BadLocationException("Invalid position", pos);
        }

        @Override
        public int viewToModel(float x, float y, Shape a, Position.Bias[] bias) {
            Rectangle r = a.getBounds();
            if (x < r.x + r.width / 2f) {
                bias[0] = Position.Bias.Forward;
                return getStartOffset();
            }
            bias[0] = Position.Bias.Backward;
            return getEndOffset();
        }
    }

    private static final Color RULE_COLOR = new Color(0xd8, 0xde, 0xe4);

    /** ImageView that scales images wider than the available width down, preserving aspect ratio. */
    private static final class FitImageView extends ImageView {
        private final int bodyMargin;

        FitImageView(Element elem, int bodyMargin) {
            super(elem);
            this.bodyMargin = bodyMargin;
        }

        private float scale() {
            float w = super.getPreferredSpan(View.X_AXIS);
            Container c = getContainer();
            if (c == null || w <= 0 || getImage() == null) {
                return 1f;
            }
            int avail = c.getWidth();
            if (avail <= 0) {
                return 1f;
            }
            Insets in = c.getInsets();
            avail -= in.left + in.right + 2 * bodyMargin + 12;
            if (avail < 32) {
                return 1f;
            }
            return w > avail ? avail / w : 1f;
        }

        @Override
        public float getPreferredSpan(int axis) {
            float s = super.getPreferredSpan(axis);
            float f = scale();
            return f < 1f ? s * f : s;
        }

        @Override
        public float getMinimumSpan(int axis) {
            return getPreferredSpan(axis);
        }

        @Override
        public float getMaximumSpan(int axis) {
            return getPreferredSpan(axis);
        }

        @Override
        public void paint(Graphics g, Shape a) {
            float f = scale();
            Image img = getImage();
            if (f >= 1f || img == null) {
                super.paint(g, a);
                return;
            }
            Rectangle r = a instanceof Rectangle ? (Rectangle) a : a.getBounds();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.drawImage(img, r.x, r.y, r.width, r.height, getContainer());
            } finally {
                g2.dispose();
            }
        }
    }

    /** Lower-case file extension of a URL path or file name, or "". */
    static String extension(String path) {
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int h = path.indexOf('#');
        if (h >= 0) {
            path = path.substring(0, h);
        }
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
