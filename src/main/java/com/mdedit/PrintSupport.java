package com.mdedit;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.Line2D;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterAbortException;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.prefs.Preferences;

import javax.print.PrintService;
import javax.print.attribute.Attribute;
import javax.print.attribute.EnumSyntax;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.DialogTypeSelection;
import javax.print.attribute.standard.JobName;
import javax.print.attribute.standard.Media;
import javax.print.attribute.standard.MediaPrintableArea;
import javax.print.attribute.standard.MediaSize;
import javax.print.attribute.standard.MediaSizeName;
import javax.print.attribute.standard.OrientationRequested;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * Printing support for the rendered (styled) Markdown view: page setup, print, print preview
 * and HTML export. Page settings (media + orientation) are kept in a {@link PrintRequestAttributeSet}
 * and persisted in {@code java.util.prefs} (node {@code com/mdedit/print}).
 *
 * <p>Printing lays the rendered HTML out to the page's imageable width (via
 * {@link javax.swing.text.JTextComponent#getPrintable}), paginates on line boundaries and adds a
 * small header (job title) and footer ("Page n").
 */
public class PrintSupport {

    private static final String PREFS_NODE = "com/mdedit/print";
    private static final String KEY_MEDIA = "media";
    private static final String KEY_ORIENTATION = "orientation";
    private static final String KEY_MARGINS = "margins";

    /** Fallback margin (points) when no printer is available to tell us its imageable area. */
    private static final double DEFAULT_MARGIN = 54; // 0.75 inch

    private final Component parent;
    private final PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();

    public PrintSupport(Component parent) {
        this.parent = parent;
        initAttributes();
    }

    // ------------------------------------------------------------------ public API

    /** Shows the page setup dialog; changes are kept for later print/preview and persisted. */
    public void pageSetup() {
        doPageSetup();
    }

    /** Prints the rendered Markdown. Shows the print dialog, then prints off the EDT with progress. */
    public void print(String markdown, URL base, String jobTitle) {
        final String title = (jobTitle == null || jobTitle.isEmpty()) ? "Untitled" : jobTitle;
        if (!hasPrinters()) {
            showNoPrinters();
            return;
        }
        final PrinterJob job;
        final ProgressPrintable printable;
        try {
            job = PrinterJob.getPrinterJob();
            job.setJobName(title);
            printable = new ProgressPrintable(createPrintable(markdown, base, title));
            job.setPrintable(printable);
        } catch (RuntimeException ex) {
            showError("Could not prepare the print job", ex);
            return;
        }

        final PrintRequestAttributeSet jobAttrs = new HashPrintRequestAttributeSet(attrs);
        jobAttrs.add(new JobName(title, null));
        if (useNativeDialog()) {
            jobAttrs.add(DialogTypeSelection.NATIVE);
        }
        boolean ok;
        try {
            ok = job.printDialog(jobAttrs);
        } catch (RuntimeException ex) { // e.g. HeadlessException, odd driver problems
            showError("Could not open the print dialog", ex);
            return;
        }
        if (!ok) {
            return;
        }
        // remember paper/orientation chosen in the print dialog
        copyPageAttributes(jobAttrs, attrs);
        savePrefs();

        final ProgressDialog progress = new ProgressDialog(title, job);
        printable.setListener(page -> SwingUtilities.invokeLater(() -> progress.setPage(page + 1)));
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                job.print(jobAttrs);
                return null;
            }

            @Override
            protected void done() {
                progress.dispose();
                try {
                    get();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    if (!(cause instanceof PrinterAbortException)) {
                        showError("Printing failed", cause);
                    }
                }
            }
        };
        progress.showLater();
        worker.execute();
    }

    /** Opens the print preview window for the rendered Markdown. */
    public void printPreview(String markdown, URL base, String jobTitle) {
        final String title = (jobTitle == null || jobTitle.isEmpty()) ? "Untitled" : jobTitle;
        try {
            PrintPreviewDialog dlg = new PrintPreviewDialog(windowOf(parent), this, markdown, base, title);
            dlg.setVisible(true);
        } catch (RuntimeException ex) {
            showError("Could not create the print preview", ex);
        }
    }

    /** Writes a standalone HTML document (UTF-8). */
    public static void exportHtml(File target, String markdown, String title) throws IOException {
        String html = HtmlRenderer.buildExportHtml(markdown == null ? "" : markdown, title);
        Files.write(target.toPath(), html.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ package API (preview)

    /** The kept print request attributes (media, orientation, ...). Live object. */
    PrintRequestAttributeSet getAttributes() {
        return attrs;
    }

    /** Shows the page dialog. @return true if the user accepted (settings changed). */
    boolean doPageSetup() {
        if (!hasPrinters()) {
            showNoPrinters();
            return false;
        }
        try {
            PrinterJob job = PrinterJob.getPrinterJob();
            PrintRequestAttributeSet copy = new HashPrintRequestAttributeSet(attrs);
            PageFormat pf = job.pageDialog(copy);
            if (pf == null) {
                return false;
            }
            copyPageAttributes(copy, attrs);
            savePrefs();
            return true;
        } catch (RuntimeException ex) {
            showError("Could not open the page setup dialog", ex);
            return false;
        }
    }

    /** The page format for the current attributes (printer's imageable area if a printer exists). */
    PageFormat getPageFormat() {
        ensurePrintableArea(attrs);
        if (hasPrinters()) {
            try {
                PageFormat pf = PrinterJob.getPrinterJob().getPageFormat(attrs);
                if (pf != null && pf.getImageableWidth() > 72 && pf.getImageableHeight() > 72) {
                    return pf;
                }
            } catch (RuntimeException ignored) {
                // fall through to computed format
            }
        }
        return computePageFormat(attrs);
    }

    /**
     * Creates a Printable for the styled rendering of {@code markdown}: content is re-laid-out to
     * the imageable width of the PageFormat given at print time, pages break between lines, and a
     * small header/footer is drawn. Must be called on the EDT.
     */
    Printable createPrintable(String markdown, URL base, String title) {
        JEditorPane pane = HtmlRenderer.createPrintPane(markdown == null ? "" : markdown, base);
        Printable body = pane.getPrintable(null, null);
        return new HeaderFooterPrintable(body, title, new MessageFormat("Page {0}"));
    }

    // ------------------------------------------------------------------ attributes & prefs

    private void initAttributes() {
        MediaSizeName media = null;
        OrientationRequested orientation = OrientationRequested.PORTRAIT;
        try {
            Preferences p = Preferences.userRoot().node(PREFS_NODE);
            media = mediaByName(p.get(KEY_MEDIA, null));
            String o = p.get(KEY_ORIENTATION, null);
            if ("landscape".equals(o)) {
                orientation = OrientationRequested.LANDSCAPE;
            } else if ("reverse-landscape".equals(o)) {
                orientation = OrientationRequested.REVERSE_LANDSCAPE;
            }
        } catch (RuntimeException | Error ignored) {
            // prefs unavailable: use defaults
        }
        if (media == null) {
            media = defaultMedia();
        }
        attrs.add(media);
        attrs.add(orientation);
        try {
            MediaPrintableArea mpa = parseArea(Preferences.userRoot().node(PREFS_NODE).get(KEY_MARGINS, null));
            if (mpa != null) {
                attrs.add(mpa);
            }
        } catch (RuntimeException | Error ignored) {
            // defaults
        }
        ensurePrintableArea(attrs);
    }

    /**
     * Makes sure {@code set} has a MediaPrintableArea that fits its media. Without one the
     * printer's hardware margins (often only a few mm, sometimes bogus) would be used, so we
     * default to comfortable 0.75 inch margins. The page dialog lets the user change them.
     */
    static void ensurePrintableArea(PrintRequestAttributeSet set) {
        double[] size = mediaSizePt(set);
        MediaPrintableArea mpa = (MediaPrintableArea) set.get(MediaPrintableArea.class);
        if (mpa != null) {
            double x = mpa.getX(MediaPrintableArea.INCH) * 72, y = mpa.getY(MediaPrintableArea.INCH) * 72;
            double w = mpa.getWidth(MediaPrintableArea.INCH) * 72, h = mpa.getHeight(MediaPrintableArea.INCH) * 72;
            if (x >= 0 && y >= 0 && w > 144 && h > 144 && x + w <= size[0] + 1 && y + h <= size[1] + 1) {
                return;
            }
        }
        set.add(defaultArea(size[0], size[1]));
    }

    private static MediaPrintableArea defaultArea(double wPt, double hPt) {
        float m = (float) (DEFAULT_MARGIN / 72.0);
        return new MediaPrintableArea(m, m, (float) (wPt / 72.0) - 2 * m, (float) (hPt / 72.0) - 2 * m,
                MediaPrintableArea.INCH);
    }

    /** Portrait media size in points (Letter if unknown). */
    static double[] mediaSizePt(PrintRequestAttributeSet set) {
        Attribute m = set.get(Media.class);
        if (m instanceof MediaSizeName) {
            MediaSize ms = MediaSize.getMediaSizeForName((MediaSizeName) m);
            if (ms != null) {
                return new double[] {ms.getX(MediaSize.INCH) * 72.0, ms.getY(MediaSize.INCH) * 72.0};
            }
        }
        return new double[] {612, 792};
    }

    private static String formatArea(MediaPrintableArea a) {
        if (a == null) {
            return null;
        }
        float[] v = a.getPrintableArea(MediaPrintableArea.INCH);
        return v[0] + "," + v[1] + "," + v[2] + "," + v[3];
    }

    private static MediaPrintableArea parseArea(String s) {
        if (s == null) {
            return null;
        }
        try {
            String[] p = s.split(",");
            return new MediaPrintableArea(Float.parseFloat(p[0]), Float.parseFloat(p[1]),
                    Float.parseFloat(p[2]), Float.parseFloat(p[3]), MediaPrintableArea.INCH);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static MediaSizeName defaultMedia() {
        String c = Locale.getDefault().getCountry();
        return ("US".equals(c) || "CA".equals(c)) ? MediaSizeName.NA_LETTER : MediaSizeName.ISO_A4;
    }

    private void savePrefs() {
        try {
            Preferences p = Preferences.userRoot().node(PREFS_NODE);
            Attribute m = attrs.get(Media.class);
            if (m instanceof MediaSizeName) {
                p.put(KEY_MEDIA, m.toString());
            }
            Attribute o = attrs.get(OrientationRequested.class);
            p.put(KEY_ORIENTATION, o == null ? "portrait" : o.toString());
            String area = formatArea((MediaPrintableArea) attrs.get(MediaPrintableArea.class));
            if (area != null) {
                p.put(KEY_MARGINS, area);
            }
            p.flush();
        } catch (Exception ignored) {
            // not fatal
        }
    }

    /** Copies media, orientation and margins chosen in a dialog back into the kept set. */
    private static void copyPageAttributes(PrintRequestAttributeSet from, PrintRequestAttributeSet to) {
        Attribute oldMedia = to.get(Media.class);
        Attribute oldArea = to.get(MediaPrintableArea.class);
        Attribute m = from.get(Media.class);
        if (m instanceof MediaSizeName) {
            to.add(m);
        }
        Attribute o = from.get(OrientationRequested.class);
        if (o != null) {
            to.add(o);
        }
        Attribute area = from.get(MediaPrintableArea.class);
        boolean mediaChanged = m instanceof MediaSizeName && !m.equals(oldMedia);
        if (mediaChanged && (area == null || area.equals(oldArea))) {
            // paper changed but margins were not touched: recompute default margins for new paper
            to.remove(MediaPrintableArea.class);
        } else if (area != null) {
            to.add(area);
        }
        ensurePrintableArea(to);
    }

    /** Looks up a MediaSizeName by its string value (e.g. "iso-a4", "na-letter"). */
    static MediaSizeName mediaByName(String name) {
        if (name == null) {
            return null;
        }
        for (EnumSyntax e : MediaNames.all()) {
            if (e instanceof MediaSizeName && name.equals(e.toString())) {
                return (MediaSizeName) e;
            }
        }
        return null;
    }

    /** Gives access to the protected enumeration table of MediaSizeName. */
    private static final class MediaNames extends MediaSizeName {
        private static final long serialVersionUID = 1L;

        private MediaNames() {
            super(0);
        }

        static EnumSyntax[] all() {
            EnumSyntax[] t = new MediaNames().getEnumValueTable();
            return t == null ? new EnumSyntax[0] : t;
        }
    }

    /** Builds a PageFormat from media/orientation attributes without asking a printer. */
    static PageFormat computePageFormat(PrintRequestAttributeSet set) {
        double[] size = mediaSizePt(set);
        double w = size[0], h = size[1];
        Paper paper = new Paper();
        paper.setSize(w, h);
        MediaPrintableArea mpa = (MediaPrintableArea) set.get(MediaPrintableArea.class);
        if (mpa != null) {
            paper.setImageableArea(mpa.getX(MediaPrintableArea.INCH) * 72, mpa.getY(MediaPrintableArea.INCH) * 72,
                    mpa.getWidth(MediaPrintableArea.INCH) * 72, mpa.getHeight(MediaPrintableArea.INCH) * 72);
        } else {
            paper.setImageableArea(DEFAULT_MARGIN, DEFAULT_MARGIN, w - 2 * DEFAULT_MARGIN, h - 2 * DEFAULT_MARGIN);
        }
        PageFormat pf = new PageFormat();
        pf.setPaper(paper);
        Attribute o = set.get(OrientationRequested.class);
        if (OrientationRequested.LANDSCAPE.equals(o)) {
            pf.setOrientation(PageFormat.LANDSCAPE);
        } else if (OrientationRequested.REVERSE_LANDSCAPE.equals(o)) {
            pf.setOrientation(PageFormat.REVERSE_LANDSCAPE);
        } else {
            pf.setOrientation(PageFormat.PORTRAIT);
        }
        return pf;
    }

    // ------------------------------------------------------------------ helpers

    static boolean hasPrinters() {
        try {
            PrintService[] services = PrinterJob.lookupPrintServices();
            return services != null && services.length > 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean useNativeDialog() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") || os.contains("mac");
    }

    static Window windowOf(Component c) {
        if (c == null) {
            return null;
        }
        return c instanceof Window ? (Window) c : SwingUtilities.getWindowAncestor(c);
    }

    private void showNoPrinters() {
        JOptionPane.showMessageDialog(parent,
                "No printers are installed.\n\nInstall a printer (or a \"Print to PDF\" printer) "
                        + "in your operating system settings and try again.\n"
                        + "Tip: File > Export HTML lets you save the rendered document instead.",
                "No Printer", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showError(String what, Throwable ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = ex.getClass().getSimpleName();
        }
        JOptionPane.showMessageDialog(parent, what + ":\n" + msg, "Print", JOptionPane.ERROR_MESSAGE);
    }

    // ------------------------------------------------------------------ printables

    /**
     * Wraps a body Printable, reserving room at the top/bottom of the imageable area for a small
     * header (title) and footer ("Page n").
     */
    static final class HeaderFooterPrintable implements Printable {
        private static final Font HEADER_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 8);
        private static final double BAND = 20; // points reserved for header and for footer
        private static final Color TEXT = new Color(0x55, 0x55, 0x55);
        private static final Color RULE = new Color(0xBB, 0xBB, 0xBB);

        private final Printable body;
        private final String title;
        private final MessageFormat footer;

        HeaderFooterPrintable(Printable body, String title, MessageFormat footer) {
            this.body = body;
            this.title = title == null ? "" : title;
            this.footer = footer;
        }

        @Override
        public int print(Graphics graphics, PageFormat pf, int pageIndex) throws PrinterException {
            double ix = pf.getImageableX();
            double iy = pf.getImageableY();
            double iw = pf.getImageableWidth();
            double ih = pf.getImageableHeight();

            // inner format: same page, imageable area shrunk by header/footer bands
            Paper paper = new Paper();
            paper.setSize(pf.getWidth(), pf.getHeight());
            paper.setImageableArea(ix, iy + BAND, iw, Math.max(36, ih - 2 * BAND));
            PageFormat inner = new PageFormat();
            inner.setOrientation(PageFormat.PORTRAIT);
            inner.setPaper(paper);

            Graphics2D bodyG = (Graphics2D) graphics.create();
            // Clip to the body band. On continuation pages skip the first point: anti-aliased
            // descenders of the previous page's last line can bleed a pixel row across the break.
            double top = iy + BAND + (pageIndex > 0 ? 1 : 0);
            bodyG.clip(new java.awt.geom.Rectangle2D.Double(ix, top, iw, iy + ih - BAND - top));
            int result;
            try {
                result = body.print(bodyG, inner, pageIndex);
            } finally {
                bodyG.dispose();
            }
            if (result != PAGE_EXISTS) {
                return result;
            }

            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                g.setFont(HEADER_FONT);
                FontMetrics fm = g.getFontMetrics();
                g.setColor(TEXT);

                // header: title, left aligned, with a hairline below
                String head = elide(title, fm, iw);
                float headBase = (float) (iy + BAND - 7 - fm.getDescent());
                g.drawString(head, (float) ix, headBase);
                g.setColor(RULE);
                g.setStroke(new BasicStroke(0.5f));
                g.draw(new Line2D.Double(ix, iy + BAND - 5, ix + iw, iy + BAND - 5));

                // footer: "Page n", centered, with a hairline above
                double footTop = iy + ih - BAND;
                g.draw(new Line2D.Double(ix, footTop + 5, ix + iw, footTop + 5));
                g.setColor(TEXT);
                String foot = footer == null ? "" : footer.format(new Object[] {pageIndex + 1});
                float fx = (float) (ix + (iw - fm.stringWidth(foot)) / 2.0);
                g.drawString(foot, fx, (float) (footTop + 7 + fm.getAscent()));
            } finally {
                g.dispose();
            }
            return PAGE_EXISTS;
        }

        private static String elide(String s, FontMetrics fm, double max) {
            if (fm.stringWidth(s) <= max) {
                return s;
            }
            String dots = "\u2026";
            int n = s.length();
            while (n > 0 && fm.stringWidth(s.substring(0, n) + dots) > max) {
                n--;
            }
            return s.substring(0, n) + dots;
        }
    }

    /** Reports which page is being printed (called from the print thread). */
    private static final class ProgressPrintable implements Printable {
        private final Printable delegate;
        private volatile IntConsumer listener;
        private int lastReported = -1;

        ProgressPrintable(Printable delegate) {
            this.delegate = delegate;
        }

        void setListener(IntConsumer l) {
            this.listener = l;
        }

        @Override
        public int print(Graphics g, PageFormat pf, int pageIndex) throws PrinterException {
            int r = delegate.print(g, pf, pageIndex);
            IntConsumer l = listener;
            if (r == PAGE_EXISTS && l != null && pageIndex != lastReported) {
                lastReported = pageIndex;
                l.accept(pageIndex);
            }
            return r;
        }
    }

    /** Small modeless "Printing..." window with a Cancel button. */
    private final class ProgressDialog {
        private final JDialog dialog;
        private final JLabel label;
        private volatile boolean disposed;

        ProgressDialog(String title, PrinterJob job) {
            dialog = new JDialog(windowOf(parent), "Printing", java.awt.Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            label = new JLabel("Printing \u201C" + title + "\u201D\u2026");
            JProgressBar bar = new JProgressBar();
            bar.setIndeterminate(true);
            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(e -> {
                job.cancel();
                label.setText("Cancelling\u2026");
                cancel.setEnabled(false);
            });
            JPanel p = new JPanel(new java.awt.BorderLayout(8, 8));
            p.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
            p.add(label, java.awt.BorderLayout.NORTH);
            p.add(bar, java.awt.BorderLayout.CENTER);
            JPanel south = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
            south.add(cancel);
            p.add(south, java.awt.BorderLayout.SOUTH);
            dialog.setContentPane(p);
            dialog.pack();
            dialog.setSize(Math.max(320, dialog.getWidth()), dialog.getHeight());
            dialog.setLocationRelativeTo(parent);
        }

        void showLater() {
            // only show if printing takes a moment, avoids flicker for tiny jobs
            javax.swing.Timer t = new javax.swing.Timer(300, e -> {
                if (!disposed) {
                    dialog.setVisible(true);
                }
            });
            t.setRepeats(false);
            t.start();
        }

        void setPage(int page) {
            if (!disposed) {
                label.setText("Printing page " + page + "\u2026");
            }
        }

        void dispose() {
            disposed = true;
            dialog.dispose();
        }
    }
}
