package com.mdedit;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The application version. The build stamps it into {@code /com/mdedit/version.properties}
 * from the project's {@code VERSION} file; it falls back to the jar manifest, then to "dev".
 */
public final class Version {

    private static final String VERSION = load();

    private Version() {
    }

    /** The version string, e.g. {@code 1.0.3}, or {@code dev} when running unbuilt classes. */
    public static String get() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("/com/mdedit/version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version", "").trim();
                if (!v.isEmpty() && !v.startsWith("${")) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        Package pkg = Version.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        return v == null || v.trim().isEmpty() ? "dev" : v.trim();
    }
}
