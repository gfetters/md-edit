package com.mdedit;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.UndoableEditEvent;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;

/**
 * Reusable Markdown editor component: a plain-text editor on the left and a live, styled
 * preview on the right, in a {@link JSplitPane}. Embeddable in any Swing application.
 *
 * <p>All methods must be called on the Event Dispatch Thread.</p>
 */
public class MarkdownEditorPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Default font size (px) for editor and preview. */
    public static final int DEFAULT_FONT_SIZE = 14;
    public static final int MIN_FONT_SIZE = 8;
    public static final int MAX_FONT_SIZE = 48;
    private static final int REFRESH_DELAY_MS = 250;
    /** Typing pauses longer than this start a new undo step. */
    private static final long COALESCE_MS = 1500;

    private final JTextArea editor = new JTextArea();
    private final JEditorPane preview = new JEditorPane();
    private final JScrollPane editorScroll;
    private final JScrollPane previewScroll;
    private final JSplitPane splitPane;
    private final HTMLEditorKit kit = HtmlRenderer.createKit(false);
    private final GroupingUndoManager undoManager = new GroupingUndoManager();
    private final transient Action undoAction;
    private final transient Action redoAction;
    private final transient List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final Timer refreshTimer;

    private URL baseUrl;
    private File baseDirectory;
    private transient Consumer<File> openFileHandler;
    private int fontSize = DEFAULT_FONT_SIZE;
    private boolean syncScroll = true;
    private boolean previewVisible = true;
    private boolean suppressEvents;
    private boolean syncingScroll;
    private int lastDividerLocation = -1;
    private String renderedText;

    public MarkdownEditorPanel() {
        super(new BorderLayout());

        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
        editor.setTabSize(4);
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setMargin(new Insets(8, 10, 8, 10));
        editorScroll = new JScrollPane(editor);
        editorScroll.setBorder(BorderFactory.createEmptyBorder());

        preview.setEditable(false);
        preview.setEditorKit(kit);
        preview.setBackground(Color.WHITE);
        preview.setMargin(new Insets(0, 0, 0, 0));
        ((DefaultCaret) preview.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        preview.addHyperlinkListener(this::onHyperlink);
        previewScroll = new JScrollPane(preview);
        previewScroll.setBorder(BorderFactory.createEmptyBorder());
        previewScroll.getVerticalScrollBar().setUnitIncrement(16);
        editorScroll.getVerticalScrollBar().setUnitIncrement(16);

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorScroll, previewScroll);
        splitPane.setResizeWeight(0.5);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        add(splitPane, BorderLayout.CENTER);

        refreshTimer = new Timer(REFRESH_DELAY_MS, e -> refreshPreviewNow());
        refreshTimer.setRepeats(false);

        undoAction = new AbstractAction("Undo") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canUndo()) {
                    undoManager.undo();
                }
                updateUndoActions();
            }
        };
        redoAction = new AbstractAction("Redo") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canRedo()) {
                    undoManager.redo();
                }
                updateUndoActions();
            }
        };
        int menu = menuShortcutMask();
        undoAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu));
        redoAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                menu | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
        InputMap im = editor.getInputMap(JComponent.WHEN_FOCUSED);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu), "md-undo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menu), "md-redo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "md-redo");
        editor.getActionMap().put("md-undo", undoAction);
        editor.getActionMap().put("md-redo", redoAction);

        editor.getDocument().addUndoableEditListener(this::onUndoableEdit);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onTextChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onTextChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // attribute changes are irrelevant for plain text
            }
        });
        editorScroll.getVerticalScrollBar().getModel().addChangeListener(e -> syncPreviewScroll());

        updateUndoActions();
        refreshPreviewNow();
    }

    /** Cmd on macOS, Ctrl elsewhere; falls back to Ctrl in headless environments. */
    private static int menuShortcutMask() {
        try {
            return java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException e) {
            return java.awt.event.InputEvent.CTRL_DOWN_MASK;
        }
    }

    // ------------------------------------------------------------------ accessors

    public JTextArea getEditor() {
        return editor;
    }

    public JEditorPane getPreview() {
        return preview;
    }

    public JSplitPane getSplitPane() {
        return splitPane;
    }

    public String getMarkdown() {
        return editor.getText();
    }

    /** Replaces the text, clears the undo history and refreshes the preview. Does not fire change listeners. */
    public void setMarkdown(String text) {
        suppressEvents = true;
        try {
            editor.setText(text == null ? "" : text);
        } finally {
            suppressEvents = false;
        }
        undoManager.discardAllEdits();
        updateUndoActions();
        editor.setCaretPosition(0);
        refreshTimer.stop();
        refreshPreviewNow();
        SwingUtilities.invokeLater(() -> {
            editorScroll.getViewport().setViewPosition(new Point(0, 0));
            previewScroll.getViewport().setViewPosition(new Point(0, 0));
        });
    }

    /** Sets the directory used to resolve relative image paths and links ({@code null} = none). */
    public void setBaseDirectory(File dir) {
        baseDirectory = dir;
        URL url = null;
        if (dir != null) {
            try {
                url = dir.getAbsoluteFile().toURI().toURL();
            } catch (MalformedURLException e) {
                url = null;
            }
        }
        baseUrl = url;
        refreshPreviewNow();
    }

    public File getBaseDirectory() {
        return baseDirectory;
    }

    public URL getBaseUrl() {
        return baseUrl;
    }

    public UndoManager getUndoManager() {
        return undoManager;
    }

    public Action getUndoAction() {
        return undoAction;
    }

    public Action getRedoAction() {
        return redoAction;
    }

    /** Registers a listener fired (on the EDT) after each user edit; not fired by {@link #setMarkdown}. */
    public void addChangeListener(Runnable r) {
        if (r != null) {
            changeListeners.add(r);
        }
    }

    public void removeChangeListener(Runnable r) {
        changeListeners.remove(r);
    }

    public void setWordWrap(boolean b) {
        editor.setLineWrap(b);
    }

    public boolean isWordWrap() {
        return editor.getLineWrap();
    }

    public void setSyncScroll(boolean b) {
        syncScroll = b;
        if (b) {
            syncPreviewScroll();
        }
    }

    public boolean isSyncScroll() {
        return syncScroll;
    }

    public void setPreviewVisible(boolean b) {
        if (b == previewVisible) {
            return;
        }
        previewVisible = b;
        if (b) {
            previewScroll.setVisible(true);
            splitPane.setDividerSize(dividerSize());
            if (lastDividerLocation > 0) {
                splitPane.setDividerLocation(lastDividerLocation);
            } else {
                splitPane.setDividerLocation(0.5);
            }
            refreshPreviewNow();
        } else {
            lastDividerLocation = splitPane.getDividerLocation();
            previewScroll.setVisible(false);
            splitPane.setDividerSize(0);
        }
        splitPane.revalidate();
        splitPane.repaint();
    }

    private int dividerSize() {
        Object v = javax.swing.UIManager.get("SplitPane.dividerSize");
        return v instanceof Integer ? (Integer) v : 8;
    }

    public boolean isPreviewVisible() {
        return previewVisible;
    }

    /** Sets the font size (px) for both editor and preview; clamped to [8, 48]. */
    public void setFontSize(int px) {
        int size = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, px));
        if (size == fontSize) {
            return;
        }
        fontSize = size;
        editor.setFont(editor.getFont().deriveFont((float) size));
        refreshPreviewNow();
    }

    public int getFontSize() {
        return fontSize;
    }

    public void zoomIn() {
        setFontSize(fontSize + (fontSize >= 20 ? 2 : 1));
    }

    public void zoomOut() {
        setFontSize(fontSize - (fontSize > 20 ? 2 : 1));
    }

    public void resetZoom() {
        setFontSize(DEFAULT_FONT_SIZE);
    }

    /** Handler invoked when the user clicks a link to a local Markdown file in the preview. */
    public void setOpenFileHandler(Consumer<File> h) {
        openFileHandler = h;
    }

    // ------------------------------------------------------------------ preview

    /** Re-renders the preview immediately, keeping its scroll position. */
    public void refreshPreviewNow() {
        refreshTimer.stop();
        if (!previewVisible) {
            renderedText = null;
            return;
        }
        String text = editor.getText();
        JViewport vp = previewScroll.getViewport();
        final Point oldPos = vp.getViewPosition();
        HTMLDocument doc = HtmlRenderer.createDocument(text, baseUrl, fontSize, false, kit);
        preview.setDocument(doc);
        renderedText = text;
        SwingUtilities.invokeLater(() -> {
            if (syncScroll) {
                syncPreviewScroll();
            } else {
                restorePosition(oldPos);
            }
        });
        // Restore once more after asynchronously loaded images may have changed the height.
        restoreLater(oldPos);
    }

    private void restorePosition(Point p) {
        JViewport vp = previewScroll.getViewport();
        previewScroll.validate();
        int maxY = Math.max(0, preview.getHeight() - vp.getExtentSize().height);
        vp.setViewPosition(new Point(0, Math.min(p.y, maxY)));
    }

    private void restoreLater(Point p) {
        if (syncScroll) {
            return;
        }
        Timer t = new Timer(60, e -> {
            if (!syncScroll) {
                Point cur = previewScroll.getViewport().getViewPosition();
                if (cur.y < p.y) {
                    restorePosition(p);
                }
            }
        });
        t.setRepeats(false);
        t.start();
    }

    private void syncPreviewScroll() {
        if (!syncScroll || syncingScroll || !previewVisible) {
            return;
        }
        syncingScroll = true;
        try {
            JScrollBar src = editorScroll.getVerticalScrollBar();
            JScrollBar dst = previewScroll.getVerticalScrollBar();
            int srcRange = src.getMaximum() - src.getVisibleAmount() - src.getMinimum();
            int dstRange = dst.getMaximum() - dst.getVisibleAmount() - dst.getMinimum();
            if (dstRange <= 0) {
                return;
            }
            double f = srcRange <= 0 ? 0 : (src.getValue() - src.getMinimum()) / (double) srcRange;
            dst.setValue(dst.getMinimum() + (int) Math.round(f * dstRange));
        } finally {
            syncingScroll = false;
        }
    }

    // ------------------------------------------------------------------ editing

    private void onTextChanged() {
        if (previewVisible) {
            refreshTimer.restart();
        }
        if (!suppressEvents) {
            for (Runnable r : changeListeners) {
                r.run();
            }
        }
    }

    private void onUndoableEdit(UndoableEditEvent e) {
        if (suppressEvents) {
            return;
        }
        undoManager.addTypingEdit(e.getEdit());
        updateUndoActions();
    }

    private void updateUndoActions() {
        undoAction.setEnabled(undoManager.canUndo());
        redoAction.setEnabled(undoManager.canRedo());
        undoAction.putValue(Action.SHORT_DESCRIPTION, undoManager.getUndoPresentationName());
        redoAction.putValue(Action.SHORT_DESCRIPTION, undoManager.getRedoPresentationName());
    }

    /**
     * UndoManager that groups consecutive typing (and multi-part edits made in the same event,
     * such as replacing a selection) into single undo steps.
     */
    private static final class GroupingUndoManager extends UndoManager {
        private static final long serialVersionUID = 1L;
        private CompoundEdit group;
        private long lastTime;
        private int lastOffset = -1;
        private transient DocumentEvent.EventType lastType;
        private boolean lastWasSpace;
        private boolean sameEvent;

        synchronized void addTypingEdit(UndoableEdit edit) {
            long now = System.currentTimeMillis();
            boolean coalesce = false;
            int offset = -1;
            int length = 0;
            DocumentEvent.EventType type = null;
            boolean space = false;
            if (edit instanceof DocumentEvent) {
                DocumentEvent de = (DocumentEvent) edit;
                offset = de.getOffset();
                length = de.getLength();
                type = de.getType();
                if (type == DocumentEvent.EventType.INSERT && length == 1) {
                    try {
                        char c = de.getDocument().getText(offset, 1).charAt(0);
                        space = Character.isWhitespace(c);
                        if (c == '\n') {
                            space = true;
                        }
                    } catch (javax.swing.text.BadLocationException ignored) {
                        // keep defaults
                    }
                }
            }
            if (group != null && group.isInProgress()) {
                if (sameEvent) {
                    coalesce = true;
                } else if (length == 1 && type == lastType && now - lastTime < COALESCE_MS) {
                    if (type == DocumentEvent.EventType.INSERT) {
                        // continue a word; start a new step at the first whitespace after a word
                        coalesce = offset == lastOffset + 1 && (space == lastWasSpace || !space);
                    } else if (type == DocumentEvent.EventType.REMOVE) {
                        coalesce = offset == lastOffset - 1 || offset == lastOffset; // backspace / delete
                    }
                }
            }
            if (coalesce) {
                group.addEdit(edit);
            } else {
                endGroup();
                group = new CompoundEdit();
                group.addEdit(edit);
                super.addEdit(group);
            }
            lastTime = now;
            lastOffset = offset;
            lastType = type;
            lastWasSpace = space;
            if (!sameEvent) {
                sameEvent = true;
                SwingUtilities.invokeLater(() -> {
                    synchronized (GroupingUndoManager.this) {
                        sameEvent = false;
                    }
                });
            }
        }

        private void endGroup() {
            if (group != null && group.isInProgress()) {
                group.end();
            }
            group = null;
        }

        @Override
        public synchronized boolean addEdit(UndoableEdit anEdit) {
            endGroup();
            return super.addEdit(anEdit);
        }

        @Override
        public synchronized boolean canUndo() {
            if (group != null && group.isInProgress()) {
                return true;
            }
            return super.canUndo();
        }

        @Override
        public synchronized boolean canRedo() {
            if (group != null && group.isInProgress()) {
                return false;
            }
            return super.canRedo();
        }

        @Override
        public synchronized void undo() {
            endGroup();
            super.undo();
        }

        @Override
        public synchronized void redo() {
            endGroup();
            super.redo();
        }

        @Override
        public synchronized void undoOrRedo() {
            endGroup();
            super.undoOrRedo();
        }

        @Override
        public synchronized boolean canUndoOrRedo() {
            return group != null && group.isInProgress() || super.canUndoOrRedo();
        }

        @Override
        public synchronized String getUndoPresentationName() {
            return "Undo";
        }

        @Override
        public synchronized String getRedoPresentationName() {
            return "Redo";
        }

        @Override
        public synchronized void discardAllEdits() {
            group = null;
            super.discardAllEdits();
        }
    }

    // ------------------------------------------------------------------ links

    private void onHyperlink(HyperlinkEvent e) {
        String desc = e.getDescription();
        if (e.getEventType() == HyperlinkEvent.EventType.ENTERED) {
            preview.setToolTipText(desc);
            return;
        }
        if (e.getEventType() == HyperlinkEvent.EventType.EXITED) {
            preview.setToolTipText(null);
            return;
        }
        if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED || desc == null) {
            return;
        }
        desc = desc.trim();
        if (desc.startsWith("#")) {
            scrollToAnchor(desc.substring(1));
            return;
        }
        String lower = desc.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("mailto:")) {
            openExternal(desc, lower.startsWith("mailto:"));
            return;
        }
        URL url = e.getURL();
        if (url == null) {
            try {
                url = baseUrl != null ? new URL(baseUrl, desc) : new File(desc).getAbsoluteFile().toURI().toURL();
            } catch (MalformedURLException ex) {
                return;
            }
        }
        if ("file".equalsIgnoreCase(url.getProtocol())) {
            File f = toFile(url);
            if (f == null) {
                return;
            }
            String ext = HtmlRenderer.extension(f.getName());
            if (openFileHandler != null && (ext.equals("md") || ext.equals("markdown") || ext.equals("mdown")
                    || ext.equals("mkd") || ext.equals("txt"))) {
                openFileHandler.accept(f);
            } else if (f.exists()) {
                openLocal(f);
            }
        } else {
            openExternal(url.toString(), false);
        }
    }

    private void scrollToAnchor(String ref) {
        String name = ref;
        try {
            name = URLDecoder.decode(ref, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException ignored) {
            name = ref;
        }
        if (!hasAnchor(name)) {
            String slug = MarkdownParser.slugify(name);
            if (hasAnchor(slug)) {
                name = slug;
            }
        }
        preview.scrollToReference(name);
    }

    private boolean hasAnchor(String name) {
        if (!(preview.getDocument() instanceof HTMLDocument)) {
            return false;
        }
        HTMLDocument.Iterator it = ((HTMLDocument) preview.getDocument()).getIterator(HTML.Tag.A);
        for (; it.isValid(); it.next()) {
            Object n = it.getAttributes().getAttribute(HTML.Attribute.NAME);
            if (n != null && name.equals(n.toString())) {
                return true;
            }
        }
        return false;
    }

    private static File toFile(URL url) {
        try {
            URL clean = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getPath());
            return new File(clean.toURI());
        } catch (MalformedURLException | URISyntaxException | IllegalArgumentException e) {
            String p = url.getPath();
            try {
                p = URLDecoder.decode(p, StandardCharsets.UTF_8.name());
            } catch (java.io.UnsupportedEncodingException ignored) {
                // keep raw path
            }
            return p.isEmpty() ? null : new File(p);
        }
    }

    private void openExternal(String link, boolean mail) {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Desktop d = Desktop.getDesktop();
                URI uri = new URI(link.replace(" ", "%20"));
                if (mail && d.isSupported(Desktop.Action.MAIL)) {
                    d.mail(uri);
                } else if (d.isSupported(Desktop.Action.BROWSE)) {
                    d.browse(uri);
                }
            } catch (Exception ex) {
                java.awt.Toolkit.getDefaultToolkit().beep();
            }
        }, "md-open-link");
        t.setDaemon(true);
        t.start();
    }

    private void openLocal(File f) {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Desktop d = Desktop.getDesktop();
                if (d.isSupported(Desktop.Action.OPEN)) {
                    d.open(f);
                }
            } catch (Exception ex) {
                java.awt.Toolkit.getDefaultToolkit().beep();
            }
        }, "md-open-file");
        t.setDaemon(true);
        t.start();
    }

    /** Text the preview currently shows (for tests / callers wanting to know if it is up to date). */
    String getRenderedText() {
        return renderedText;
    }
}
