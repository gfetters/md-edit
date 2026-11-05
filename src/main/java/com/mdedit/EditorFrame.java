package com.mdedit;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * The main (single-document) application window: a {@link MarkdownEditorPanel} with menus,
 * file handling, recent files, preferences, drag &amp; drop and a status bar.
 */
public class EditorFrame extends JFrame {

    public static final String APP_NAME = "MD Edit";
    public static final String APP_VERSION = Version.get();

    static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final int MAX_RECENT = 10;
    private static final String LF = "\n";
    private static final String CRLF = "\r\n";

    private final Preferences prefs = Preferences.userRoot().node("com/mdedit");
    private final MarkdownEditorPanel panel = new MarkdownEditorPanel();
    private final JTextArea editor = panel.getEditor();
    private final PrintSupport printSupport = new PrintSupport(this);
    private FindReplaceDialog findDialog;
    private JFrame guideFrame;

    private File file;
    private String savedText = "";
    private String eol = defaultEol();
    private boolean dirty;
    private boolean dirtyCheckPending;

    private final List<File> recent = new ArrayList<>();
    private final JMenu recentMenu = new JMenu("Open Recent");
    private JCheckBoxMenuItem wrapItem;
    private JCheckBoxMenuItem syncItem;
    private JCheckBoxMenuItem previewItem;

    private final JLabel posLabel = statusLabel("Ln 1, Col 1");
    private final JLabel wordsLabel = statusLabel("0 words");
    private final JLabel charsLabel = statusLabel("0 chars");
    private final JLabel encLabel = statusLabel("UTF-8");
    private final JLabel eolLabel = statusLabel("LF");
    private final Timer countTimer;

    private Rectangle normalBounds;

    public EditorFrame() {
        super(APP_NAME);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setIconImages(createIcons());

        add(panel, BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);

        loadRecent();
        setJMenuBar(createMenuBar());
        applyPreferences();

        countTimer = new Timer(200, e -> updateCounts());
        countTimer.setRepeats(false);
        panel.addChangeListener(this::scheduleDirtyCheck);
        installDocumentWatch();
        editor.addCaretListener(e -> updateCaretPosition());
        panel.setOpenFileHandler(f -> SwingUtilities.invokeLater(() -> openWithPrompt(f)));

        installFileDrop();
        installDesktopHandlers();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApplication();
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                rememberNormalBounds();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                rememberNormalBounds();
            }
        });

        newDocumentState(null, "", defaultEol());
    }

    public MarkdownEditorPanel getEditorPanel() {
        return panel;
    }

    public File getFile() {
        return file;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Shows the window, restoring the maximized state and divider position afterwards. */
    public void showWindow() {
        setVisible(true);
        if (prefs.getBoolean("maximized", false)
                && Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.MAXIMIZED_BOTH)) {
            setExtendedState(getExtendedState() | Frame.MAXIMIZED_BOTH);
        }
        SwingUtilities.invokeLater(() -> {
            double div = prefs.getDouble("divider", 0.5);
            JSplitPane sp = panel.getSplitPane();
            if (panel.isPreviewVisible() && div > 0.05 && div < 0.95) {
                sp.setDividerLocation(div);
            }
            editor.requestFocusInWindow();
        });
    }

    // =================================================================== menus

    private static int menuMask() {
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException e) {
            return InputEvent.CTRL_DOWN_MASK;
        }
    }

    private static KeyStroke ks(int key, int mods) {
        return KeyStroke.getKeyStroke(key, mods);
    }

    private static KeyStroke cmd(int key) {
        return ks(key, menuMask());
    }

    private static KeyStroke cmdShift(int key) {
        return ks(key, menuMask() | InputEvent.SHIFT_DOWN_MASK);
    }

    private final List<KeyStroke> accelerators = new ArrayList<>();

    private JMenuItem item(String name, int mnemonic, KeyStroke accel, Runnable r) {
        JMenuItem mi = new JMenuItem(name);
        if (mnemonic != 0 && !IS_MAC) {
            mi.setMnemonic(mnemonic);
        }
        if (accel != null) {
            mi.setAccelerator(accel);
            accelerators.add(accel);
        }
        mi.addActionListener(e -> r.run());
        return mi;
    }

    private JCheckBoxMenuItem check(String name, int mnemonic, KeyStroke accel, boolean initial,
                                    java.util.function.Consumer<Boolean> c) {
        JCheckBoxMenuItem mi = new JCheckBoxMenuItem(name, initial);
        if (mnemonic != 0 && !IS_MAC) {
            mi.setMnemonic(mnemonic);
        }
        if (accel != null) {
            mi.setAccelerator(accel);
            accelerators.add(accel);
        }
        mi.addActionListener(e -> c.accept(mi.isSelected()));
        return mi;
    }

    private static JMenu menu(String name, int mnemonic) {
        JMenu m = new JMenu(name);
        if (!IS_MAC) {
            m.setMnemonic(mnemonic);
        }
        return m;
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();

        // ---- File
        JMenu fileMenu = menu("File", KeyEvent.VK_F);
        fileMenu.add(item("New", KeyEvent.VK_N, cmd(KeyEvent.VK_N), this::newDocument));
        fileMenu.add(item("Open...", KeyEvent.VK_O, cmd(KeyEvent.VK_O), this::openDocument));
        if (!IS_MAC) {
            recentMenu.setMnemonic(KeyEvent.VK_R);
        }
        fileMenu.add(recentMenu);
        rebuildRecentMenu();
        fileMenu.add(item("Close", KeyEvent.VK_C, cmd(KeyEvent.VK_W), this::closeDocument));
        fileMenu.addSeparator();
        fileMenu.add(item("Save", KeyEvent.VK_S, cmd(KeyEvent.VK_S), this::save));
        fileMenu.add(item("Save As...", KeyEvent.VK_A, cmdShift(KeyEvent.VK_S), this::saveAs));
        fileMenu.add(item("Export as HTML...", KeyEvent.VK_E, cmdShift(KeyEvent.VK_E), this::exportHtml));
        fileMenu.addSeparator();
        fileMenu.add(item("Page Setup...", KeyEvent.VK_U, cmdShift(KeyEvent.VK_P), printSupport::pageSetup));
        fileMenu.add(item("Print Preview...", KeyEvent.VK_V, null, () ->
                printSupport.printPreview(panel.getMarkdown(), panel.getBaseUrl(), documentName())));
        fileMenu.add(item("Print...", KeyEvent.VK_P, cmd(KeyEvent.VK_P), () ->
                printSupport.print(panel.getMarkdown(), panel.getBaseUrl(), documentName())));
        if (!IS_MAC) {
            fileMenu.addSeparator();
            fileMenu.add(item("Exit", KeyEvent.VK_X, cmd(KeyEvent.VK_Q), this::exitApplication));
        }
        bar.add(fileMenu);

        // ---- Edit
        JMenu edit = menu("Edit", KeyEvent.VK_E);
        Action undo = panel.getUndoAction();
        Action redo = panel.getRedoAction();
        JMenuItem undoItem = new JMenuItem(undo);
        undoItem.setText("Undo");
        undoItem.setAccelerator(cmd(KeyEvent.VK_Z));
        accelerators.add(cmd(KeyEvent.VK_Z));
        JMenuItem redoItem = new JMenuItem(redo);
        redoItem.setText("Redo");
        KeyStroke redoKs = IS_MAC ? cmdShift(KeyEvent.VK_Z) : cmd(KeyEvent.VK_Y);
        redoItem.setAccelerator(redoKs);
        accelerators.add(redoKs);
        if (!IS_MAC) {
            undoItem.setMnemonic(KeyEvent.VK_U);
            redoItem.setMnemonic(KeyEvent.VK_R);
            // also accept Ctrl+Shift+Z for redo
            bindKey(cmdShift(KeyEvent.VK_Z), "mdedit-redo", redo);
        }
        edit.add(undoItem);
        edit.add(redoItem);
        edit.addSeparator();
        edit.add(item("Cut", KeyEvent.VK_T, cmd(KeyEvent.VK_X), () -> focusedText().cut()));
        edit.add(item("Copy", KeyEvent.VK_C, cmd(KeyEvent.VK_C), () -> focusedText().copy()));
        edit.add(item("Paste", KeyEvent.VK_P, cmd(KeyEvent.VK_V), () -> focusedText().paste()));
        edit.add(item("Select All", KeyEvent.VK_A, cmd(KeyEvent.VK_A), () -> {
            JTextComponent t = focusedText();
            t.requestFocusInWindow();
            t.selectAll();
        }));
        edit.addSeparator();
        edit.add(item("Find/Replace...", KeyEvent.VK_F, cmd(KeyEvent.VK_F), () -> findDialog().showDialog()));
        edit.add(item("Find Next", KeyEvent.VK_N, ks(KeyEvent.VK_F3, 0), this::findNext));
        edit.add(item("Find Previous", KeyEvent.VK_V, ks(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK),
                this::findPrevious));
        if (IS_MAC) {
            bindKey(cmd(KeyEvent.VK_G), "mdedit-findnext", action(this::findNext));
            bindKey(cmdShift(KeyEvent.VK_G), "mdedit-findprev", action(this::findPrevious));
        }
        bar.add(edit);

        // ---- Format
        JMenu fmt = menu("Format", KeyEvent.VK_O);
        fmt.add(item("Bold", KeyEvent.VK_B, cmd(KeyEvent.VK_B), () -> format(MarkdownFormatter::bold)));
        fmt.add(item("Italic", KeyEvent.VK_I, cmd(KeyEvent.VK_I), () -> format(MarkdownFormatter::italic)));
        fmt.add(item("Strikethrough", KeyEvent.VK_S, cmdShift(KeyEvent.VK_X),
                () -> format(MarkdownFormatter::strikethrough)));
        fmt.add(item("Inline Code", KeyEvent.VK_C, cmdShift(KeyEvent.VK_C),
                () -> format(MarkdownFormatter::inlineCode)));
        fmt.addSeparator();
        fmt.add(item("Link", KeyEvent.VK_L, cmd(KeyEvent.VK_K), () -> format(MarkdownFormatter::link)));
        fmt.add(item("Image", KeyEvent.VK_M, cmdShift(KeyEvent.VK_I), () -> format(MarkdownFormatter::image)));
        fmt.addSeparator();
        fmt.add(item("Heading 1", KeyEvent.VK_1, cmd(KeyEvent.VK_1), () -> format(t -> MarkdownFormatter.heading(t, 1))));
        fmt.add(item("Heading 2", KeyEvent.VK_2, cmd(KeyEvent.VK_2), () -> format(t -> MarkdownFormatter.heading(t, 2))));
        fmt.add(item("Heading 3", KeyEvent.VK_3, cmd(KeyEvent.VK_3), () -> format(t -> MarkdownFormatter.heading(t, 3))));
        fmt.addSeparator();
        fmt.add(item("Bullet List", KeyEvent.VK_U, cmdShift(KeyEvent.VK_8),
                () -> format(MarkdownFormatter::bulletList)));
        fmt.add(item("Numbered List", KeyEvent.VK_N, cmdShift(KeyEvent.VK_7),
                () -> format(MarkdownFormatter::numberedList)));
        fmt.add(item("Task List", KeyEvent.VK_T, cmdShift(KeyEvent.VK_T),
                () -> format(MarkdownFormatter::taskList)));
        fmt.add(item("Blockquote", KeyEvent.VK_Q, cmdShift(KeyEvent.VK_B),
                () -> format(MarkdownFormatter::blockquote)));
        fmt.add(item("Code Block", KeyEvent.VK_K, cmdShift(KeyEvent.VK_K),
                () -> format(MarkdownFormatter::codeBlock)));
        fmt.add(item("Table", KeyEvent.VK_A, null, () -> format(MarkdownFormatter::table)));
        fmt.add(item("Horizontal Rule", KeyEvent.VK_R, null, () -> format(MarkdownFormatter::horizontalRule)));
        bar.add(fmt);

        // ---- View
        JMenu view = menu("View", KeyEvent.VK_V);
        wrapItem = check("Word Wrap", KeyEvent.VK_W, null, panel.isWordWrap(), panel::setWordWrap);
        syncItem = check("Sync Scrolling", KeyEvent.VK_S, null, panel.isSyncScroll(), panel::setSyncScroll);
        previewItem = check("Show Preview", KeyEvent.VK_P, cmdShift(KeyEvent.VK_R), panel.isPreviewVisible(),
                b -> {
                    if (!b) {
                        storeDivider();
                    }
                    panel.setPreviewVisible(b);
                });
        view.add(wrapItem);
        view.add(syncItem);
        view.add(previewItem);
        view.addSeparator();
        view.add(item("Zoom In", KeyEvent.VK_I, cmd(KeyEvent.VK_EQUALS), panel::zoomIn));
        view.add(item("Zoom Out", KeyEvent.VK_O, cmd(KeyEvent.VK_MINUS), panel::zoomOut));
        view.add(item("Reset Zoom", KeyEvent.VK_R, cmd(KeyEvent.VK_0), panel::resetZoom));
        bindKey(cmdShift(KeyEvent.VK_EQUALS), "mdedit-zoomin2", action(panel::zoomIn));
        bindKey(cmd(KeyEvent.VK_PLUS), "mdedit-zoomin3", action(panel::zoomIn));
        bindKey(cmd(KeyEvent.VK_ADD), "mdedit-zoomin4", action(panel::zoomIn));
        bindKey(cmd(KeyEvent.VK_SUBTRACT), "mdedit-zoomout2", action(panel::zoomOut));
        bindKey(cmd(KeyEvent.VK_NUMPAD0), "mdedit-zoomreset2", action(panel::resetZoom));
        bar.add(view);

        // ---- Help
        JMenu help = menu("Help", KeyEvent.VK_H);
        help.add(item("Markdown Guide", KeyEvent.VK_G, IS_MAC ? null : ks(KeyEvent.VK_F1, 0), this::showGuide));
        if (!IS_MAC || !Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
            help.add(item("About " + APP_NAME, KeyEvent.VK_A, null, this::showAbout));
        }
        bar.add(help);

        unbindEditorKeys();
        return bar;
    }

    /**
     * Makes sure the editor's own key bindings (e.g. Ctrl+H = backspace or emacs-style
     * bindings in some look and feels) do not swallow menu accelerators.
     */
    private void unbindEditorKeys() {
        KeyStroke[] keep = {cmd(KeyEvent.VK_X), cmd(KeyEvent.VK_C), cmd(KeyEvent.VK_V), cmd(KeyEvent.VK_A)};
        for (KeyStroke k : accelerators) {
            boolean skip = false;
            for (KeyStroke kk : keep) {
                if (kk.equals(k)) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                editor.getInputMap(JComponent.WHEN_FOCUSED).put(k, "none");
                panel.getPreview().getInputMap(JComponent.WHEN_FOCUSED).put(k, "none");
            }
        }
    }

    private void bindKey(KeyStroke k, String name, Action a) {
        JComponent root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(k, name);
        root.getActionMap().put(name, a);
        editor.getInputMap(JComponent.WHEN_FOCUSED).put(k, "none");
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    private void format(java.util.function.Consumer<JTextArea> op) {
        if (!editor.isEditable()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        op.accept(editor);
        editor.requestFocusInWindow();
    }

    private JTextComponent focusedText() {
        Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        if (c instanceof JTextComponent && SwingUtilities.getWindowAncestor(c) == this) {
            return (JTextComponent) c;
        }
        return editor;
    }

    private FindReplaceDialog findDialog() {
        if (findDialog == null) {
            findDialog = new FindReplaceDialog(this, editor);
        }
        return findDialog;
    }

    private void findNext() {
        FindReplaceDialog d = findDialog();
        if (!d.hasSearchText()) {
            d.showDialog();
        } else {
            d.findNext();
        }
    }

    private void findPrevious() {
        FindReplaceDialog d = findDialog();
        if (!d.hasSearchText()) {
            d.showDialog();
        } else {
            d.findPrevious();
        }
    }

    // =================================================================== status bar

    private static JLabel statusLabel(String text) {
        JLabel l = new JLabel(text);
        l.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        return l;
    }

    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new java.awt.Color(0, 0, 0, 40)),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(wordsLabel);
        right.add(sep());
        right.add(charsLabel);
        right.add(sep());
        right.add(encLabel);
        right.add(sep());
        right.add(eolLabel);
        bar.add(posLabel, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        eolLabel.setToolTipText("Line endings (preserved on save)");
        encLabel.setToolTipText("File encoding");
        return bar;
    }

    private static Component sep() {
        JSeparator s = new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(1, 14));
        return s;
    }

    private void updateCaretPosition() {
        int pos = editor.getCaretPosition();
        Element root = editor.getDocument().getDefaultRootElement();
        int line = root.getElementIndex(pos);
        int col = pos - root.getElement(line).getStartOffset();
        String sel = "";
        int selLen = editor.getSelectionEnd() - editor.getSelectionStart();
        if (selLen > 0) {
            sel = "  (" + selLen + " selected)";
        }
        posLabel.setText("Ln " + (line + 1) + ", Col " + (col + 1) + sel);
    }

    private void updateCounts() {
        String t = editor.getText();
        int words = 0;
        boolean in = false;
        int chars = t.codePointCount(0, t.length());
        for (int i = 0; i < t.length(); i++) {
            boolean ws = Character.isWhitespace(t.charAt(i));
            if (!ws && !in) {
                words++;
            }
            in = !ws;
        }
        wordsLabel.setText(words + (words == 1 ? " word" : " words"));
        charsLabel.setText(chars + (chars == 1 ? " char" : " chars"));
    }

    private void updateEolLabel() {
        eolLabel.setText(CRLF.equals(eol) ? "CRLF" : "LF");
    }

    // =================================================================== dirty state & title

    private final DocumentListener docWatch = new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent e) {
            changed();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            changed();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
        }

        private void changed() {
            scheduleDirtyCheck();
            countTimer.restart();
        }
    };

    private void installDocumentWatch() {
        editor.getDocument().addDocumentListener(docWatch);
        editor.addPropertyChangeListener("document", e -> {
            if (e.getOldValue() instanceof Document) {
                ((Document) e.getOldValue()).removeDocumentListener(docWatch);
            }
            if (e.getNewValue() instanceof Document) {
                ((Document) e.getNewValue()).addDocumentListener(docWatch);
            }
            scheduleDirtyCheck();
            countTimer.restart();
        });
    }

    private void scheduleDirtyCheck() {
        if (dirtyCheckPending) {
            return;
        }
        dirtyCheckPending = true;
        SwingUtilities.invokeLater(() -> {
            dirtyCheckPending = false;
            setDirty(!panel.getMarkdown().equals(savedText));
        });
    }

    private void setDirty(boolean d) {
        if (d != dirty) {
            dirty = d;
            updateTitle();
        }
    }

    private String documentName() {
        return file == null ? "Untitled" : file.getName();
    }

    private void updateTitle() {
        setTitle(documentName() + (dirty ? "*" : "") + " — " + APP_NAME + " " + APP_VERSION);
        if (IS_MAC) {
            getRootPane().putClientProperty("Window.documentModified", dirty);
            getRootPane().putClientProperty("Window.documentFile", file);
        }
    }

    // =================================================================== document lifecycle

    private void newDocumentState(File f, String text, String lineEnding) {
        file = f;
        eol = lineEnding;
        savedText = text;
        panel.setBaseDirectory(f == null ? null : f.getAbsoluteFile().getParentFile());
        panel.setMarkdown(text);
        editor.setCaretPosition(0);
        dirty = false;
        updateTitle();
        updateEolLabel();
        updateCounts();
        updateCaretPosition();
    }

    /** Asks to save unsaved changes. Returns true if the caller may discard the document. */
    public boolean confirmDiscard() {
        if (!dirty && panel.getMarkdown().equals(savedText)) {
            return true;
        }
        if (isVisible()) {
            toFront();
        }
        Object[] options = {"Save", "Don't Save", "Cancel"};
        int r = JOptionPane.showOptionDialog(this,
                "Do you want to save the changes you made to \"" + documentName() + "\"?\n"
                        + "Your changes will be lost if you don't save them.",
                APP_NAME, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (r == 0) {
            return save();
        }
        return r == 1;
    }

    public void newDocument() {
        if (confirmDiscard()) {
            newDocumentState(null, "", defaultEol());
        }
    }

    public void closeDocument() {
        newDocument();
    }

    public void openDocument() {
        if (!confirmDiscard()) {
            return;
        }
        JFileChooser fc = createChooser();
        fc.setDialogTitle("Open");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            rememberDir(fc.getCurrentDirectory());
            loadFile(fc.getSelectedFile());
        }
    }

    /** Opens a file after asking about unsaved changes. */
    public void openWithPrompt(File f) {
        if (f == null) {
            return;
        }
        if (file != null && sameFile(f, file)) {
            toFront();
            return;
        }
        if (confirmDiscard()) {
            loadFile(f);
        }
    }

    /**
     * Loads a file into the editor without prompting. A non-existent file becomes a new,
     * empty document that will be saved at that path.
     */
    public boolean loadFile(File f) {
        f = f.getAbsoluteFile();
        if (!f.exists()) {
            if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                newDocumentState(f, "", defaultEol());
                return true;
            }
            error("The file \"" + f + "\" does not exist.");
            removeRecent(f);
            return false;
        }
        if (f.isDirectory()) {
            error("\"" + f + "\" is a folder, not a file.");
            return false;
        }
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            String detected = detectEol(text);
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            newDocumentState(f, text, detected);
            addRecent(f);
            return true;
        } catch (IOException | OutOfMemoryError ex) {
            error("Could not open \"" + f.getName() + "\":\n" + describe(ex));
            return false;
        }
    }

    public boolean save() {
        if (file == null) {
            return saveAs();
        }
        return writeTo(file);
    }

    public boolean saveAs() {
        JFileChooser fc = createChooser();
        fc.setDialogTitle("Save As");
        fc.setSelectedFile(new File(fc.getCurrentDirectory(),
                file == null ? "Untitled.md" : file.getName()));
        while (true) {
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return false;
            }
            rememberDir(fc.getCurrentDirectory());
            File target = withDefaultExtension(fc.getSelectedFile(), "md");
            if (target.exists() && (file == null || !sameFile(target, file)) && !confirmOverwrite(target)) {
                continue;
            }
            return writeTo(target);
        }
    }

    private boolean writeTo(File target) {
        String text = panel.getMarkdown();
        String out = CRLF.equals(eol) ? text.replace("\n", CRLF) : text;
        try {
            File parent = target.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                Files.createDirectories(parent.toPath());
            }
            Files.write(target.toPath(), out.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            error("Could not save \"" + target.getName() + "\":\n" + describe(ex));
            return false;
        }
        boolean changedFile = file == null || !sameFile(file, target);
        file = target.getAbsoluteFile();
        savedText = text;
        if (changedFile) {
            panel.setBaseDirectory(file.getParentFile());
            panel.refreshPreviewNow();
        }
        dirty = false;
        updateTitle();
        addRecent(file);
        return true;
    }

    private void exportHtml() {
        JFileChooser fc = createChooser();
        fc.resetChoosableFileFilters();
        FileNameExtensionFilter html = new FileNameExtensionFilter("HTML files (*.html, *.htm)", "html", "htm");
        fc.addChoosableFileFilter(html);
        fc.setFileFilter(html);
        fc.setDialogTitle("Export as HTML");
        String base = file == null ? "Untitled" : stripExtension(file.getName());
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), base + ".html"));
        while (true) {
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            rememberDir(fc.getCurrentDirectory());
            File target = withDefaultExtension(fc.getSelectedFile(), "html");
            if (target.exists() && !confirmOverwrite(target)) {
                continue;
            }
            try {
                PrintSupport.exportHtml(target, panel.getMarkdown(), stripExtension(documentName()));
            } catch (IOException | RuntimeException ex) {
                error("Could not export \"" + target.getName() + "\":\n" + describe(ex));
            }
            return;
        }
    }

    /** Called on window close / Exit / macOS Quit. Returns false if the user cancelled. */
    public boolean exitApplication() {
        if (!confirmDiscard()) {
            return false;
        }
        savePreferences();
        if (findDialog != null) {
            findDialog.dispose();
        }
        if (guideFrame != null) {
            guideFrame.dispose();
        }
        dispose();
        System.exit(0);
        return true;
    }

    // =================================================================== file helpers

    private static String defaultEol() {
        return CRLF.equals(System.lineSeparator()) ? CRLF : LF;
    }

    static String detectEol(String text) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                if (i > 0 && text.charAt(i - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            }
        }
        if (crlf == 0 && lf == 0) {
            return defaultEol();
        }
        return crlf > lf ? CRLF : LF;
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    private static File withDefaultExtension(File f, String ext) {
        String n = f.getName();
        if (n.lastIndexOf('.') <= 0) {
            return new File(f.getParentFile(), n + "." + ext);
        }
        return f;
    }

    private static String stripExtension(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private boolean confirmOverwrite(File f) {
        int r = JOptionPane.showConfirmDialog(this,
                "\"" + f.getName() + "\" already exists.\nDo you want to replace it?",
                "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return r == JOptionPane.YES_OPTION;
    }

    private JFileChooser createChooser() {
        File dir = null;
        String last = prefs.get("lastDir", null);
        if (file != null) {
            dir = file.getParentFile();
        } else if (last != null && new File(last).isDirectory()) {
            dir = new File(last);
        }
        JFileChooser fc = new JFileChooser(dir);
        FileNameExtensionFilter md = new FileNameExtensionFilter(
                "Markdown files (*.md, *.markdown, *.mdown, *.txt)", "md", "markdown", "mdown", "txt");
        fc.addChoosableFileFilter(md);
        fc.setFileFilter(md);
        return fc;
    }

    private void rememberDir(File dir) {
        if (dir != null) {
            prefs.put("lastDir", dir.getAbsolutePath());
        }
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(isShowing() ? this : null, msg, APP_NAME, JOptionPane.ERROR_MESSAGE);
    }

    private static String describe(Throwable ex) {
        if (ex instanceof java.nio.file.NoSuchFileException) {
            return "No such file: " + ex.getMessage();
        }
        if (ex instanceof java.nio.file.AccessDeniedException) {
            return "Access denied: " + ex.getMessage();
        }
        if (ex instanceof OutOfMemoryError) {
            return "The file is too large.";
        }
        String m = ex.getMessage();
        return m == null ? ex.getClass().getSimpleName() : m;
    }

    // =================================================================== recent files

    private void loadRecent() {
        recent.clear();
        for (int i = 0; i < MAX_RECENT; i++) {
            String p = prefs.get("recent." + i, null);
            if (p != null && !p.isEmpty()) {
                recent.add(new File(p));
            }
        }
    }

    private void storeRecent() {
        for (int i = 0; i < MAX_RECENT; i++) {
            if (i < recent.size()) {
                prefs.put("recent." + i, recent.get(i).getAbsolutePath());
            } else {
                prefs.remove("recent." + i);
            }
        }
        flushPrefs();
    }

    private void addRecent(File f) {
        recent.removeIf(r -> sameFile(r, f));
        recent.add(0, f.getAbsoluteFile());
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.size() - 1);
        }
        storeRecent();
        rebuildRecentMenu();
    }

    private void removeRecent(File f) {
        if (recent.removeIf(r -> sameFile(r, f))) {
            storeRecent();
            rebuildRecentMenu();
        }
    }

    private void rebuildRecentMenu() {
        recentMenu.removeAll();
        int i = 1;
        for (File f : recent) {
            String label = (IS_MAC || i > 9 ? "" : i + "  ") + f.getName();
            JMenuItem mi = new JMenuItem(label);
            if (!IS_MAC && i <= 9) {
                mi.setMnemonic(KeyEvent.VK_0 + i);
            }
            mi.setToolTipText(f.getAbsolutePath());
            mi.addActionListener(e -> openRecent(f));
            recentMenu.add(mi);
            i++;
        }
        if (recent.isEmpty()) {
            JMenuItem none = new JMenuItem("(No recent files)");
            none.setEnabled(false);
            recentMenu.add(none);
        }
        recentMenu.addSeparator();
        JMenuItem clear = new JMenuItem("Clear List");
        clear.setEnabled(!recent.isEmpty());
        clear.addActionListener(e -> {
            recent.clear();
            storeRecent();
            rebuildRecentMenu();
        });
        recentMenu.add(clear);
    }

    private void openRecent(File f) {
        if (!f.isFile()) {
            int r = JOptionPane.showConfirmDialog(this,
                    "The file \"" + f.getAbsolutePath() + "\" could not be found.\n"
                            + "Remove it from the recent files list?",
                    APP_NAME, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r == JOptionPane.YES_OPTION) {
                removeRecent(f);
            }
            return;
        }
        openWithPrompt(f);
    }

    // =================================================================== preferences

    private void applyPreferences() {
        panel.setWordWrap(prefs.getBoolean("wordWrap", true));
        panel.setSyncScroll(prefs.getBoolean("syncScroll", true));
        int fs = prefs.getInt("fontSize", -1);
        if (fs >= 6 && fs <= 72) {
            panel.setFontSize(fs);
        }
        boolean preview = prefs.getBoolean("previewVisible", true);
        panel.setPreviewVisible(preview);
        if (wrapItem != null) {
            wrapItem.setSelected(panel.isWordWrap());
            syncItem.setSelected(panel.isSyncScroll());
            previewItem.setSelected(panel.isPreviewVisible());
        }

        Rectangle b = new Rectangle(
                prefs.getInt("x", Integer.MIN_VALUE), prefs.getInt("y", Integer.MIN_VALUE),
                prefs.getInt("w", -1), prefs.getInt("h", -1));
        if (b.x != Integer.MIN_VALUE && b.y != Integer.MIN_VALUE && b.width >= 300 && b.height >= 200
                && isOnScreen(b)) {
            setBounds(b);
        } else {
            Rectangle screen = defaultScreenBounds();
            int w = Math.min(1200, (int) (screen.width * 0.85));
            int h = Math.min(800, (int) (screen.height * 0.85));
            setSize(w, h);
            setLocation(screen.x + (screen.width - w) / 2, screen.y + (screen.height - h) / 2);
        }
        setMinimumSize(new Dimension(400, 300));
        normalBounds = getBounds();
    }

    private static Rectangle defaultScreenBounds() {
        if (GraphicsEnvironment.isHeadless()) {
            return new Rectangle(0, 0, 1280, 800);
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    }

    /** True if a reasonable part of the rectangle (title bar area) is visible on some screen. */
    private static boolean isOnScreen(Rectangle r) {
        if (GraphicsEnvironment.isHeadless()) {
            return true;
        }
        Rectangle titleBar = new Rectangle(r.x, r.y, r.width, 40);
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle sb = gd.getDefaultConfiguration().getBounds();
            Rectangle inter = sb.intersection(titleBar);
            if (!inter.isEmpty() && inter.width >= Math.min(120, r.width) && inter.height >= 20) {
                return true;
            }
        }
        return false;
    }

    private void rememberNormalBounds() {
        if (isShowing() && (getExtendedState() & Frame.MAXIMIZED_BOTH) == 0
                && (getExtendedState() & Frame.ICONIFIED) == 0) {
            normalBounds = getBounds();
        }
    }

    private void storeDivider() {
        JSplitPane sp = panel.getSplitPane();
        if (!panel.isPreviewVisible() || !sp.isShowing()) {
            return;
        }
        int total = sp.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? sp.getWidth() : sp.getHeight();
        total -= sp.getDividerSize();
        if (total > 0) {
            double d = sp.getDividerLocation() / (double) total;
            if (d > 0.05 && d < 0.95) {
                prefs.putDouble("divider", d);
            }
        }
    }

    public void savePreferences() {
        rememberNormalBounds();
        boolean max = (getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        prefs.putBoolean("maximized", max);
        if (normalBounds != null) {
            prefs.putInt("x", normalBounds.x);
            prefs.putInt("y", normalBounds.y);
            prefs.putInt("w", normalBounds.width);
            prefs.putInt("h", normalBounds.height);
        }
        storeDivider();
        prefs.putInt("fontSize", panel.getFontSize());
        prefs.putBoolean("wordWrap", panel.isWordWrap());
        prefs.putBoolean("syncScroll", panel.isSyncScroll());
        prefs.putBoolean("previewVisible", panel.isPreviewVisible());
        storeRecent();
    }

    private void flushPrefs() {
        try {
            prefs.flush();
        } catch (BackingStoreException | SecurityException ignored) {
            // preferences are best effort
        }
    }

    // =================================================================== drag & drop

    private void installFileDrop() {
        wrapTransferHandler(editor);
        wrapTransferHandler(panel.getPreview());
        setTransferHandler(new FileDropHandler(null));
    }

    private void wrapTransferHandler(JComponent c) {
        TransferHandler original = c.getTransferHandler();
        if (!(original instanceof FileDropHandler)) {
            c.setTransferHandler(new FileDropHandler(original));
        }
    }

    private static final DataFlavor URI_LIST_FLAVOR = createUriListFlavor();

    private static DataFlavor createUriListFlavor() {
        try {
            return new DataFlavor("text/uri-list;class=java.lang.String");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static boolean hasFiles(TransferHandler.TransferSupport s) {
        return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                || (URI_LIST_FLAVOR != null && s.isDataFlavorSupported(URI_LIST_FLAVOR)
                && !s.isDataFlavorSupported(DataFlavor.stringFlavor));
    }

    @SuppressWarnings("unchecked")
    private static List<File> droppedFiles(Transferable t) {
        List<File> out = new ArrayList<>();
        try {
            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                out.addAll((List<File>) t.getTransferData(DataFlavor.javaFileListFlavor));
            } else if (URI_LIST_FLAVOR != null && t.isDataFlavorSupported(URI_LIST_FLAVOR)) {
                String data = (String) t.getTransferData(URI_LIST_FLAVOR);
                for (String line : data.split("\r?\n")) {
                    line = line.trim();
                    if (line.startsWith("file:")) {
                        out.add(new File(new URI(line)));
                    }
                }
            }
        } catch (Exception ignored) {
            // unsupported drop
        }
        return out;
    }

    /**
     * Accepts dropped files (opening the first one) and delegates everything else to the
     * component's original handler, so normal text cut/copy/paste and text DnD keep working.
     */
    private final class FileDropHandler extends TransferHandler {
        private final TransferHandler delegate;
        private Position dragStart;
        private Position dragEnd;

        FileDropHandler(TransferHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (hasFiles(support)) {
                if (support.isDrop() && (support.getSourceDropActions() & COPY) != 0) {
                    support.setDropAction(COPY);
                }
                return true;
            }
            return delegate != null && delegate.canImport(support);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (hasFiles(support)) {
                List<File> files = droppedFiles(support.getTransferable());
                if (files.isEmpty()) {
                    return false;
                }
                File f = files.get(0);
                SwingUtilities.invokeLater(() -> {
                    toFront();
                    openWithPrompt(f);
                });
                return true;
            }
            return delegate != null && delegate.importData(support);
        }

        @Override
        public int getSourceActions(JComponent c) {
            return delegate == null ? NONE : delegate.getSourceActions(c);
        }

        @Override
        public void exportToClipboard(JComponent comp, Clipboard clip, int action) {
            if (delegate != null) {
                delegate.exportToClipboard(comp, clip, action);
            }
        }

        @Override
        public Image getDragImage() {
            return delegate == null ? null : delegate.getDragImage();
        }

        @Override
        public Icon getVisualRepresentation(Transferable t) {
            return delegate == null ? null : delegate.getVisualRepresentation(t);
        }

        // Only used when text dragging is enabled on the component (off by default):
        // the original handler's protected methods cannot be delegated, so do it here.
        @Override
        protected Transferable createTransferable(JComponent c) {
            if (c instanceof JTextComponent) {
                JTextComponent tc = (JTextComponent) c;
                int s = tc.getSelectionStart();
                int e = tc.getSelectionEnd();
                if (s == e) {
                    return null;
                }
                try {
                    dragStart = tc.getDocument().createPosition(s);
                    dragEnd = tc.getDocument().createPosition(e);
                } catch (BadLocationException ex) {
                    dragStart = null;
                    dragEnd = null;
                }
                return new StringSelection(tc.getSelectedText());
            }
            return null;
        }

        @Override
        protected void exportDone(JComponent c, Transferable data, int action) {
            if (action == MOVE && c instanceof JTextComponent && dragStart != null && dragEnd != null
                    && ((JTextComponent) c).isEditable()) {
                int s = dragStart.getOffset();
                int e = dragEnd.getOffset();
                if (e > s) {
                    try {
                        ((JTextComponent) c).getDocument().remove(s, e - s);
                    } catch (BadLocationException ignored) {
                        // ignore
                    }
                }
            }
            dragStart = null;
            dragEnd = null;
        }
    }

    // =================================================================== desktop integration

    private void installDesktopHandlers() {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        Desktop d = Desktop.getDesktop();
        try {
            if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                d.setQuitHandler((e, response) -> {
                    if (confirmDiscard()) {
                        savePreferences();
                        response.performQuit();
                    } else {
                        response.cancelQuit();
                    }
                });
            }
            if (d.isSupported(Desktop.Action.APP_ABOUT)) {
                d.setAboutHandler(e -> showAbout());
            }
            if (d.isSupported(Desktop.Action.APP_OPEN_FILE)) {
                d.setOpenFileHandler(e -> {
                    List<File> files = e.getFiles();
                    if (!files.isEmpty()) {
                        SwingUtilities.invokeLater(() -> openWithPrompt(files.get(0)));
                    }
                });
            }
        } catch (RuntimeException ignored) {
            // not supported on this platform
        }
    }

    // =================================================================== help

    private void showGuide() {
        if (guideFrame == null) {
            guideFrame = new JFrame("Markdown Guide — " + APP_NAME);
            guideFrame.setIconImages(getIconImages());
            guideFrame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            MarkdownEditorPanel gp = new MarkdownEditorPanel();
            gp.setMarkdown(loadGuide());
            gp.setWordWrap(true);
            gp.setOpenFileHandler(f -> SwingUtilities.invokeLater(() -> openWithPrompt(f)));
            JLabel hint = new JLabel("Edit the text on the left to experiment — changes are not saved.");
            hint.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            guideFrame.add(hint, BorderLayout.NORTH);
            guideFrame.add(gp, BorderLayout.CENTER);
            Rectangle b = getBounds();
            guideFrame.setSize(Math.max(700, (int) (b.width * 0.9)), Math.max(500, (int) (b.height * 0.9)));
            guideFrame.setLocation(b.x + 40, b.y + 40);
            JComponent root = guideFrame.getRootPane();
            KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(esc, "close");
            root.getActionMap().put("close", action(() -> guideFrame.setVisible(false)));
            KeyStroke closeKs = cmd(KeyEvent.VK_W);
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(closeKs, "close");
            gp.getEditor().getInputMap(JComponent.WHEN_FOCUSED).put(closeKs, "none");
            guideFrame.setVisible(true);
            SwingUtilities.invokeLater(() -> {
                gp.getSplitPane().setDividerLocation(0.45);
                gp.getEditor().setCaretPosition(0);
            });
        } else {
            guideFrame.setVisible(true);
            guideFrame.setExtendedState(guideFrame.getExtendedState() & ~Frame.ICONIFIED);
            guideFrame.toFront();
        }
    }

    static String loadGuide() {
        try (InputStream in = EditorFrame.class.getResourceAsStream("/com/mdedit/guide.md")) {
            if (in != null) {
                String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (s.startsWith("﻿")) {
                    s = s.substring(1);
                }
                return s.replace("\r\n", "\n");
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "# Markdown Guide\n\nThe guide resource could not be loaded.\n";
    }

    private void showAbout() {
        String msg = "<html><b style='font-size:120%'>" + APP_NAME + " " + APP_VERSION + "</b><br>"
                + "A lightweight Markdown editor with live preview.<br><br>"
                + "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")<br>"
                + System.getProperty("os.name") + " " + System.getProperty("os.version") + "</html>";
        JOptionPane.showMessageDialog(isShowing() ? this : null, msg, "About " + APP_NAME,
                JOptionPane.INFORMATION_MESSAGE);
    }

    private static List<Image> createIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : new int[]{16, 32, 64, 128}) {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(0x2F6FEB));
            int arc = Math.max(4, size / 5);
            g.fillRoundRect(0, 0, size, size, arc, arc);
            g.setColor(java.awt.Color.WHITE);
            float s = size / 16f;
            java.awt.geom.Path2D p = new java.awt.geom.Path2D.Float();
            // "M"
            p.moveTo(2.5 * s, 11.5 * s);
            p.lineTo(2.5 * s, 4.5 * s);
            p.lineTo(5 * s, 7.5 * s);
            p.lineTo(7.5 * s, 4.5 * s);
            p.lineTo(7.5 * s, 11.5 * s);
            // down arrow
            p.moveTo(11.5 * s, 4.5 * s);
            p.lineTo(11.5 * s, 11 * s);
            p.moveTo(9.5 * s, 9 * s);
            p.lineTo(11.5 * s, 11.2 * s);
            p.lineTo(13.5 * s, 9 * s);
            g.setStroke(new java.awt.BasicStroke(Math.max(1.2f, 1.5f * s),
                    java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            g.draw(p);
            g.dispose();
            icons.add(img);
        }
        return icons;
    }
}
