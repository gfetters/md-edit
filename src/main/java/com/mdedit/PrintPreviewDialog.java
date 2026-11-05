package com.mdedit;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.net.URL;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * Print preview: shows the pages exactly as {@link PrintSupport} would print them (styled
 * rendering, header/footer, page breaks) one page at a time on a gray background, with zoom,
 * page navigation, Page Setup and Print.
 */
public class PrintPreviewDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    /** Screen pixels per printer point at 100% zoom (96 dpi screen / 72 pt per inch). */
    private static final double PX_PER_PT = 96.0 / 72.0;
    private static final String FIT_WIDTH = "Fit Width";
    private static final String FIT_PAGE = "Fit Page";
    private static final String[] ZOOMS = {FIT_WIDTH, FIT_PAGE, "50%", "75%", "100%", "125%", "150%", "200%"};
    private static final int GAP = 24;
    private static final Color PAGE_BG = new Color(0x80, 0x84, 0x88);

    private final PrintSupport support;
    private final String markdown;
    private final URL base;
    private final String title;

    private Printable printable;
    private PageFormat pageFormat;
    private int pageCount;
    private int page;
    private double zoom = 1.0;

    private final PagePanel pagePanel = new PagePanel();
    private final JScrollPane scroll;
    private final JLabel pageLabel = new JLabel("", SwingConstants.CENTER);
    private final JComboBox<String> zoomBox = new JComboBox<>(ZOOMS);
    private final Action firstAction;
    private final Action prevAction;
    private final Action nextAction;
    private final Action lastAction;

    public PrintPreviewDialog(Window owner, PrintSupport support, String markdown, URL base, String title) {
        super(owner, "Print Preview \u2014 " + title, ModalityType.MODELESS);
        this.support = support;
        this.markdown = markdown == null ? "" : markdown;
        this.base = base;
        this.title = title;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        Action printAction = action("Print\u2026", KeyStroke.getKeyStroke(KeyEvent.VK_P, menuMask), e -> {
            support.print(this.markdown, this.base, this.title);
        });
        Action setupAction = action("Page Setup\u2026", null, e -> {
            if (support.doPageSetup()) {
                rebuild();
            }
        });
        firstAction = action("|\u25C0", KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), e -> showPage(0));
        prevAction = action("\u25C0", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), e -> showPage(page - 1));
        nextAction = action("\u25B6", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), e -> showPage(page + 1));
        lastAction = action("\u25B6|", KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), e -> showPage(pageCount - 1));
        Action closeAction = action("Close", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), e -> dispose());
        Action zoomIn = action("+", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menuMask), e -> stepZoom(1));
        Action zoomOut = action("-", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menuMask), e -> stepZoom(-1));
        firstAction.putValue(Action.SHORT_DESCRIPTION, "First page (Home)");
        prevAction.putValue(Action.SHORT_DESCRIPTION, "Previous page (Page Up)");
        nextAction.putValue(Action.SHORT_DESCRIPTION, "Next page (Page Down)");
        lastAction.putValue(Action.SHORT_DESCRIPTION, "Last page (End)");

        // also allow arrow keys for paging when the scroll bar doesn't need them
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK), prevAction);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK), nextAction);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, menuMask), zoomIn);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, menuMask), zoomOut);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, menuMask), zoomIn);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menuMask | InputEvent.SHIFT_DOWN_MASK), zoomIn);

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(new JButton(printAction));
        bar.add(new JButton(setupAction));
        bar.addSeparator();
        bar.add(new JButton(firstAction));
        bar.add(new JButton(prevAction));
        pageLabel.setText("Page 8888 of 8888");
        Dimension labelSize = pageLabel.getPreferredSize();
        pageLabel.setPreferredSize(new Dimension(labelSize.width + 8, labelSize.height));
        bar.add(pageLabel);
        bar.add(new JButton(nextAction));
        bar.add(new JButton(lastAction));
        bar.addSeparator();
        bar.add(new JLabel("Zoom:"));
        JButton out = new JButton(zoomOut);
        out.setToolTipText("Zoom out");
        bar.add(out);
        zoomBox.setEditable(false);
        zoomBox.setSelectedItem(FIT_WIDTH);
        zoomBox.addActionListener(e -> applyZoom());
        bar.add(zoomBox);
        JButton in = new JButton(zoomIn);
        in.setToolTipText("Zoom in");
        bar.add(in);
        bar.addSeparator();
        bar.add(new JButton(closeAction));

        scroll = new JScrollPane(pagePanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(PAGE_BG);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                String z = (String) zoomBox.getSelectedItem();
                if (FIT_WIDTH.equals(z) || FIT_PAGE.equals(z)) {
                    applyZoom();
                }
            }
        });
        // mouse wheel past the end of a page flips to the next/previous page
        scroll.addMouseWheelListener(e -> {
            if (e.isControlDown() || e.isMetaDown()) {
                stepZoom(e.getWheelRotation() < 0 ? 1 : -1);
                return;
            }
            javax.swing.JScrollBar vb = scroll.getVerticalScrollBar();
            boolean atBottom = vb.getValue() + vb.getVisibleAmount() >= vb.getMaximum();
            boolean atTop = vb.getValue() <= vb.getMinimum();
            if (e.getWheelRotation() > 0 && atBottom && page < pageCount - 1) {
                showPage(page + 1);
            } else if (e.getWheelRotation() < 0 && atTop && page > 0) {
                showPage(page - 1);
                SwingUtilities.invokeLater(() -> vb.setValue(vb.getMaximum()));
            }
        });

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(bar, BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);

        rebuild();

        Rectangle screen = owner != null && owner.getGraphicsConfiguration() != null
                ? owner.getGraphicsConfiguration().getBounds()
                : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        int w = Math.min(screen.width - 80, Math.max(760, (int) (pageFormat.getWidth() * PX_PER_PT) + 2 * GAP + 40));
        int h = Math.min(screen.height - 80, 900);
        setSize(Math.max(500, w), Math.max(400, h));
        setLocationRelativeTo(owner);
    }

    // ------------------------------------------------------------------ model

    /** (Re)creates the printable for the current page settings and counts the pages. */
    private void rebuild() {
        Cursor old = getCursor();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            pageFormat = support.getPageFormat();
            printable = support.createPrintable(markdown, base, title);
            pageCount = countPages(printable, pageFormat);
            page = Math.max(0, Math.min(page, pageCount - 1));
            pagePanel.invalidateImage();
            applyZoom();
            updateNav();
        } finally {
            setCursor(old);
        }
    }

    static int countPages(Printable p, PageFormat pf) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        int n = 0;
        try {
            while (n < 10000) {
                Graphics2D g = scratch.createGraphics();
                try {
                    if (p.print(g, pf, n) != Printable.PAGE_EXISTS) {
                        break;
                    }
                } finally {
                    g.dispose();
                }
                n++;
            }
        } catch (PrinterException ex) {
            // show what we have
        }
        return Math.max(1, n);
    }

    /**
     * Renders page {@code index} to an image at {@code scale} device pixels per point, white
     * background. Exposed for tests.
     */
    static BufferedImage renderPage(Printable p, PageFormat pf, int index, double scale) {
        int w = (int) Math.ceil(pf.getWidth() * scale);
        int h = (int) Math.ceil(pf.getHeight() * scale);
        BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.scale(scale, scale);
            g.setClip(0, 0, (int) Math.ceil(pf.getWidth()), (int) Math.ceil(pf.getHeight()));
            p.print(g, pf, index);
        } catch (PrinterException ex) {
            g.setColor(Color.RED);
            g.drawString("Error: " + ex.getMessage(), 20, 40);
        } finally {
            g.dispose();
        }
        return img;
    }

    // ------------------------------------------------------------------ navigation & zoom

    private void showPage(int index) {
        int p = Math.max(0, Math.min(index, pageCount - 1));
        if (p != page) {
            page = p;
            pagePanel.invalidateImage();
            pagePanel.repaint();
            scroll.getVerticalScrollBar().setValue(0);
        }
        updateNav();
    }

    private void updateNav() {
        pageLabel.setText("Page " + (page + 1) + " of " + pageCount);
        firstAction.setEnabled(page > 0);
        prevAction.setEnabled(page > 0);
        nextAction.setEnabled(page < pageCount - 1);
        lastAction.setEnabled(page < pageCount - 1);
    }

    private void applyZoom() {
        if (pageFormat == null) {
            return;
        }
        String z = (String) zoomBox.getSelectedItem();
        Dimension vp = scroll.getViewport().getExtentSize();
        double pw = pageFormat.getWidth();
        double ph = pageFormat.getHeight();
        double nz;
        if (FIT_WIDTH.equals(z) || FIT_PAGE.equals(z)) {
            int vw = vp.width > 0 ? vp.width : 800;
            int vh = vp.height > 0 ? vp.height : 800;
            int sb = scroll.getVerticalScrollBar().getPreferredSize().width;
            double fw = (vw - 2 * GAP - sb) / (pw * PX_PER_PT);
            nz = fw;
            if (FIT_PAGE.equals(z)) {
                nz = Math.min(fw, (vh - 2 * GAP) / (ph * PX_PER_PT));
            }
            nz = Math.max(0.1, Math.min(4.0, nz));
        } else {
            try {
                nz = Integer.parseInt(z.replace("%", "").trim()) / 100.0;
            } catch (RuntimeException ex) {
                nz = 1.0;
            }
        }
        if (Math.abs(nz - zoom) > 1e-4) {
            zoom = nz;
            pagePanel.invalidateImage();
        }
        pagePanel.revalidate();
        pagePanel.repaint();
    }

    private void stepZoom(int dir) {
        int[] steps = {50, 75, 100, 125, 150, 200};
        int cur = (int) Math.round(zoom * 100);
        int target = dir > 0 ? steps[steps.length - 1] : steps[0];
        if (dir > 0) {
            for (int s : steps) {
                if (s > cur) {
                    target = s;
                    break;
                }
            }
        } else {
            for (int i = steps.length - 1; i >= 0; i--) {
                if (steps[i] < cur) {
                    target = steps[i];
                    break;
                }
            }
        }
        zoomBox.setSelectedItem(target + "%");
    }

    // ------------------------------------------------------------------ helpers

    private interface Handler {
        void run(ActionEvent e);
    }

    private Action action(String name, KeyStroke key, Handler h) {
        Action a = new AbstractAction(name) {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                h.run(e);
            }
        };
        if (key != null) {
            bindKey(key, a);
        }
        return a;
    }

    private void bindKey(KeyStroke key, Action a) {
        JComponent root = getRootPane();
        String id = "pp-" + key.toString();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, id);
        root.getActionMap().put(id, a);
    }

    // ------------------------------------------------------------------ page view

    /** Displays the current page, centered with a drop shadow, rendered at screen resolution. */
    private final class PagePanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        private BufferedImage image;
        private int imagePage = -1;
        private double imageScale;

        PagePanel() {
            setBackground(PAGE_BG);
            setOpaque(true);
        }

        void invalidateImage() {
            image = null;
        }

        private int pageW() {
            return (int) Math.round(pageFormat.getWidth() * PX_PER_PT * zoom);
        }

        private int pageH() {
            return (int) Math.round(pageFormat.getHeight() * PX_PER_PT * zoom);
        }

        @Override
        public Dimension getPreferredSize() {
            if (pageFormat == null) {
                return new Dimension(400, 400);
            }
            return new Dimension(pageW() + 2 * GAP, pageH() + 2 * GAP);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (pageFormat == null || printable == null) {
                return;
            }
            Graphics2D g = (Graphics2D) g0.create();
            try {
                int w = pageW();
                int h = pageH();
                int x = Math.max(GAP, (getWidth() - w) / 2);
                int y = Math.max(GAP, (getHeight() - h) / 2);
                // shadow
                g.setColor(new Color(0, 0, 0, 40));
                g.fillRect(x + 2, y + 2, w + 4, h + 4);
                g.setColor(new Color(0, 0, 0, 70));
                g.fillRect(x + 3, y + 3, w + 1, h + 1);
                // page image at device resolution (crisp on HiDPI screens)
                double device = deviceScale(g);
                double scale = PX_PER_PT * zoom * device;
                if (image == null || imagePage != page || Math.abs(imageScale - scale) > 1e-6) {
                    image = renderPage(printable, pageFormat, page, scale);
                    imagePage = page;
                    imageScale = scale;
                }
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image, x, y, w, h, null);
                g.setColor(new Color(0x60, 0x60, 0x60));
                g.drawRect(x - 1, y - 1, w + 1, h + 1);
            } finally {
                g.dispose();
            }
        }

        private double deviceScale(Graphics2D g) {
            GraphicsConfiguration gc = g.getDeviceConfiguration();
            double s = gc != null ? gc.getDefaultTransform().getScaleX() : 1.0;
            return s > 0 ? s : 1.0;
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height - 24 : visibleRect.width - 24;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            // stretch to viewport when the page is narrower, so it stays centered
            return getParent() != null && getParent().getWidth() > getPreferredSize().width;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() > getPreferredSize().height;
        }
    }
}
