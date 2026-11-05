package com.mdedit;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Non-modal Find / Replace dialog bound to one text component. Remembers the last search so
 * {@link #findNext()} / {@link #findPrevious()} can be used for F3 / Shift+F3 while hidden.
 */
public class FindReplaceDialog extends JDialog {

    private final JTextComponent target;
    private final JTextField findField = new JTextField(28);
    private final JTextField replaceField = new JTextField(28);
    private final JCheckBox matchCase = new JCheckBox("Match case");
    private final JCheckBox wholeWord = new JCheckBox("Whole word");
    private final JCheckBox wrapAround = new JCheckBox("Wrap around", true);
    private final JLabel status = new JLabel(" ");
    private final Color normalStatus;

    public FindReplaceDialog(Frame owner, JTextComponent target) {
        super(owner, "Find / Replace", false);
        this.target = target;
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 3, 3, 3);
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0;
        gc.gridy = 0;
        JLabel fl = new JLabel("Find:");
        fl.setDisplayedMnemonic('n');
        fl.setLabelFor(findField);
        fields.add(fl, gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        fields.add(findField, gc);
        gc.gridx = 0;
        gc.gridy = 1;
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        JLabel rl = new JLabel("Replace with:");
        rl.setDisplayedMnemonic('p');
        rl.setLabelFor(replaceField);
        fields.add(rl, gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        fields.add(replaceField, gc);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        matchCase.setMnemonic('c');
        wholeWord.setMnemonic('w');
        wrapAround.setMnemonic('a');
        options.add(matchCase);
        options.add(wholeWord);
        options.add(wrapAround);
        gc.gridx = 1;
        gc.gridy = 2;
        fields.add(options, gc);
        gc.gridy = 3;
        normalStatus = status.getForeground();
        fields.add(status, gc);

        JButton next = new JButton("Find Next");
        next.setMnemonic('f');
        next.addActionListener(e -> findNext());
        JButton prev = new JButton("Find Previous");
        prev.setMnemonic('v');
        prev.addActionListener(e -> findPrevious());
        JButton repl = new JButton("Replace");
        repl.setMnemonic('r');
        repl.addActionListener(e -> replace());
        JButton all = new JButton("Replace All");
        all.setMnemonic('l');
        all.addActionListener(e -> replaceAll());
        JButton close = new JButton("Close");
        close.addActionListener(e -> setVisible(false));
        JPanel buttons = new JPanel(new GridLayout(0, 1, 4, 4));
        buttons.add(next);
        buttons.add(prev);
        buttons.add(repl);
        buttons.add(all);
        buttons.add(close);
        JPanel east = new JPanel(new BorderLayout());
        east.add(buttons, BorderLayout.NORTH);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(fields, BorderLayout.CENTER);
        content.add(east, BorderLayout.EAST);
        setContentPane(content);

        findField.addActionListener(e -> findNext());
        replaceField.addActionListener(e -> replace());
        getRootPane().setDefaultButton(next);

        JComponent root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind");
        root.getActionMap().put("closeFind", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setVisible(false);
            }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "findNext");
        root.getActionMap().put("findNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext();
            }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, KeyEvent.SHIFT_DOWN_MASK), "findPrev");
        root.getActionMap().put("findPrev", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findPrevious();
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                findField.requestFocusInWindow();
            }
        });
        pack();
        setResizable(true);
        setMinimumSize(getSize());
    }

    /** Shows the dialog, seeding the search with the current single-line selection. */
    public void showDialog() {
        String sel = target.getSelectedText();
        if (sel != null && !sel.isEmpty() && sel.indexOf('\n') < 0) {
            findField.setText(sel);
        }
        setStatus(" ", false);
        if (!isVisible()) {
            setLocationRelativeTo(getOwner());
            Rectangle ob = getOwner().getBounds();
            setLocation(ob.x + ob.width - getWidth() - 40, ob.y + 80);
        }
        setVisible(true);
        toFront();
        findField.requestFocusInWindow();
        findField.selectAll();
    }

    public boolean hasSearchText() {
        return !findField.getText().isEmpty();
    }

    public String getSearchText() {
        return findField.getText();
    }

    // ------------------------------------------------------------------ search

    private Pattern pattern() {
        String s = findField.getText();
        if (s.isEmpty()) {
            return null;
        }
        String re = Pattern.quote(s);
        if (wholeWord.isSelected()) {
            re = "(?<![\\p{L}\\p{N}_])" + re + "(?![\\p{L}\\p{N}_])";
        }
        int flags = matchCase.isSelected() ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        try {
            return Pattern.compile(re, flags);
        } catch (PatternSyntaxException ex) {
            return null;
        }
    }

    private String text() {
        Document d = target.getDocument();
        try {
            return d.getText(0, d.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    /** Finds the next occurrence after the selection. Returns true if found. */
    public boolean findNext() {
        Pattern p = pattern();
        if (p == null) {
            setStatus("Enter text to find.", true);
            return false;
        }
        String t = text();
        Matcher m = p.matcher(t);
        int from = target.getSelectionEnd();
        if (m.find(Math.min(from, t.length()))) {
            return select(m.start(), m.end(), false);
        }
        if (wrapAround.isSelected() && m.find(0)) {
            return select(m.start(), m.end(), true);
        }
        notFound();
        return false;
    }

    /** Finds the previous occurrence before the selection. Returns true if found. */
    public boolean findPrevious() {
        Pattern p = pattern();
        if (p == null) {
            setStatus("Enter text to find.", true);
            return false;
        }
        String t = text();
        int limit = target.getSelectionStart();
        int[] last = lastMatchBefore(p, t, limit);
        if (last != null) {
            return select(last[0], last[1], false);
        }
        if (wrapAround.isSelected()) {
            last = lastMatchBefore(p, t, t.length() + 1);
            if (last != null) {
                return select(last[0], last[1], true);
            }
        }
        notFound();
        return false;
    }

    private static int[] lastMatchBefore(Pattern p, String t, int limit) {
        Matcher m = p.matcher(t);
        int[] last = null;
        int from = 0;
        while (from <= t.length() && m.find(from)) {
            if (m.end() > limit || (m.start() >= limit)) {
                break;
            }
            last = new int[]{m.start(), m.end()};
            from = m.end() > m.start() ? m.end() : m.start() + 1;
        }
        return last;
    }

    private boolean select(int start, int end, boolean wrapped) {
        target.setCaretPosition(start);
        target.moveCaretPosition(end);
        scrollIntoView(start, end);
        int line = target.getDocument().getDefaultRootElement().getElementIndex(start) + 1;
        setStatus((wrapped ? "Wrapped around. " : "") + "Found at line " + line + ".", false);
        target.getCaret().setSelectionVisible(true);
        return true;
    }

    private void scrollIntoView(int start, int end) {
        try {
            java.awt.geom.Rectangle2D r1 = target.modelToView2D(start);
            java.awt.geom.Rectangle2D r2 = target.modelToView2D(end);
            if (r1 != null) {
                Rectangle r = r1.getBounds();
                if (r2 != null) {
                    r = r.union(r2.getBounds());
                }
                r.grow(0, r.height * 3);
                target.scrollRectToVisible(r);
            }
        } catch (BadLocationException ignored) {
            // ignore
        }
    }

    private void notFound() {
        setStatus("\"" + findField.getText() + "\" not found.", true);
        target.getToolkit().beep();
    }

    // ------------------------------------------------------------------ replace

    /** Replaces the current selection if it is a match, then finds the next one. */
    public void replace() {
        Pattern p = pattern();
        if (p == null) {
            setStatus("Enter text to find.", true);
            return;
        }
        if (!target.isEditable()) {
            target.getToolkit().beep();
            return;
        }
        int s = target.getSelectionStart();
        int e = target.getSelectionEnd();
        String t = text();
        Matcher m = p.matcher(t);
        if (e > s && m.find(s) && m.start() == s && m.end() == e) {
            String rep = replaceField.getText();
            MarkdownFormatter.runAsSingleEdit(target, () -> {
                try {
                    Document d = target.getDocument();
                    if (d instanceof AbstractDocument) {
                        ((AbstractDocument) d).replace(s, e - s, rep, null);
                    } else {
                        d.remove(s, e - s);
                        d.insertString(s, rep, null);
                    }
                } catch (BadLocationException ex) {
                    target.getToolkit().beep();
                }
            });
            target.setCaretPosition(s + rep.length());
            if (!findNext()) {
                setStatus("Replaced. No more occurrences.", false);
            }
        } else {
            findNext();
        }
    }

    /** Replaces every occurrence in the document as one undoable edit. */
    public int replaceAll() {
        Pattern p = pattern();
        if (p == null) {
            setStatus("Enter text to find.", true);
            return 0;
        }
        if (!target.isEditable()) {
            target.getToolkit().beep();
            return 0;
        }
        String t = text();
        Matcher m = p.matcher(t);
        List<int[]> hits = new ArrayList<>();
        while (m.find()) {
            if (m.end() == m.start()) {
                continue;
            }
            hits.add(new int[]{m.start(), m.end()});
        }
        if (hits.isEmpty()) {
            notFound();
            return 0;
        }
        String rep = replaceField.getText();
        int caret = target.getCaretPosition();
        MarkdownFormatter.runAsSingleEdit(target, () -> {
            Document d = target.getDocument();
            try {
                for (int i = hits.size() - 1; i >= 0; i--) {
                    int[] h = hits.get(i);
                    if (d instanceof AbstractDocument) {
                        ((AbstractDocument) d).replace(h[0], h[1] - h[0], rep, null);
                    } else {
                        d.remove(h[0], h[1] - h[0]);
                        d.insertString(h[0], rep, null);
                    }
                }
            } catch (BadLocationException ex) {
                target.getToolkit().beep();
            }
        });
        target.setCaretPosition(Math.min(caret, target.getDocument().getLength()));
        int n = hits.size();
        setStatus("Replaced " + n + (n == 1 ? " occurrence." : " occurrences."), false);
        return n;
    }

    private void setStatus(String msg, boolean error) {
        Runnable r = () -> {
            status.setText(msg);
            status.setForeground(error ? new Color(0xB00020) : normalStatus);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }
}
