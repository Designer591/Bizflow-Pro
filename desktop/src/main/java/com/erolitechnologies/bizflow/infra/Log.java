package com.erolitechnologies.bizflow.infra;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Thin logging facade over java.util.logging. Never pass passwords, tokens or customer data to these methods. */
public final class Log {
    private static final Logger ROOT = Logger.getLogger("bizflow");
    private static boolean fileConfigured;

    private Log() {}

    public static synchronized void configureFile(Path logDir) {
        if (fileConfigured) return;
        try {
            FileHandler fh = new FileHandler(logDir.resolve("bizflow-%g.log").toString(), 2_000_000, 5, true);
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s %3$s - %5$s%6$s%n");
            fh.setFormatter(new SimpleFormatter());
            ROOT.addHandler(fh);
            ROOT.setLevel(Level.INFO);
            fileConfigured = true;
        } catch (IOException | SecurityException e) {
            ROOT.log(Level.WARNING, "File logging unavailable: " + e.getMessage());
        }
    }

    public static void info(String msg) { ROOT.info(msg); }
    public static void warn(String msg) { ROOT.warning(msg); }
    public static void error(String msg, Throwable t) { ROOT.log(Level.SEVERE, msg, t); }
}
