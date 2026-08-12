package com.rk22.routinedebugger.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Core-owned configuration for persistent application logging. */
public final class PersistentLogging {
    private static final Logger LOG = Logger.getLogger(PersistentLogging.class.getName());
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final int MAX_LOG_BYTES = 2 * 1024 * 1024;
    private static final int LOG_FILE_COUNT = 5;
    private static volatile Path logDirectory;

    private PersistentLogging() {}

    /** Installs five rotating 2 MiB log files on the root logger. */
    public static Path initialize(String component) {
        if (!INITIALIZED.compareAndSet(false, true)) return logDirectory;
        try {
            logDirectory = resolveLogDirectory();
            Files.createDirectories(logDirectory);
            String safeComponent = component == null || component.isBlank()
                ? "application" : component.replaceAll("[^A-Za-z0-9._-]", "-");
            Path pattern = logDirectory.resolve(safeComponent + "-%g.log");
            FileHandler handler = new FileHandler(pattern.toString(), MAX_LOG_BYTES,
                                                  LOG_FILE_COUNT, true);
            handler.setEncoding("UTF-8");
            handler.setLevel(Level.ALL);
            handler.setFormatter(new SimpleFormatter());
            Logger root = Logger.getLogger("");
            root.addHandler(handler);
            // Keep framework FINER/FINEST chatter (notably JavaFX layout traces)
            // out of the persistent files while retaining application diagnostics.
            root.setLevel(Level.INFO);
            installUncaughtExceptionHandler();
            LOG.info(() -> "Persistent logging initialized in " + logDirectory);
        } catch (IOException | SecurityException ex) {
            System.err.println("Could not initialize persistent logging: " + ex.getMessage());
        }
        return logDirectory;
    }

    public static Path logDirectory() {
        return logDirectory;
    }

    private static Path resolveLogDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank())
                return Path.of(localAppData, "MySQL Routine Debugger", "logs");
        } else if (os.startsWith("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Logs",
                           "MySQL Routine Debugger");
        } else {
            String stateHome = System.getenv("XDG_STATE_HOME");
            if (stateHome != null && !stateHome.isBlank())
                return Path.of(stateHome, "mysql-routine-debugger");
            return Path.of(System.getProperty("user.home"), ".local", "state",
                           "mysql-routine-debugger");
        }
        return Path.of(System.getProperty("user.home"), ".mysql-routine-debugger", "logs");
    }

    private static void installUncaughtExceptionHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Logger.getLogger("uncaught").log(Level.SEVERE,
                "Uncaught exception on thread " + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
