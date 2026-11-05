package com.mdedit;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.File;
import java.util.Locale;

/** Application entry point. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // must be set before any AWT class is initialised
        if (os.contains("mac")) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", EditorFrame.APP_NAME);
            System.setProperty("com.apple.mrj.application.apple.menu.about.name", EditorFrame.APP_NAME);
            System.setProperty("apple.awt.application.appearance", "system");
        } else if (os.contains("linux") || os.contains("bsd") || os.contains("sunos")) {
            if (System.getProperty("awt.useSystemAAFontSettings") == null) {
                System.setProperty("awt.useSystemAAFontSettings", "on");
            }
            if (System.getProperty("swing.aatext") == null) {
                System.setProperty("swing.aatext", "true");
            }
        }

        File initial = null;
        for (String a : args) {
            if (a != null && !a.isEmpty() && !a.startsWith("-psn_")) { // ignore old macOS process serial arg
                initial = new File(a);
                break;
            }
        }
        final File toOpen = initial;

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception | LinkageError e) {
                try {
                    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                } catch (Exception | LinkageError ignored) {
                    // keep whatever is installed
                }
            }
            EditorFrame frame = new EditorFrame();
            if (toOpen != null) {
                frame.loadFile(toOpen);
            }
            frame.showWindow();
        });
    }
}
