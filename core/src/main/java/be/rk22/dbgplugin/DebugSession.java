package be.rk22.dbgplugin;

import java.util.concurrent.*;

/**
 * Manages one active debug session: drives the 600ms poll loop and
 * signals continue/step to the DB.
 */
public class DebugSession {

    public final  String        sessionId;
    public final  String        routineName;
    private final DbgConnection db;

    private volatile long    lastId   = 0;
    private volatile boolean paused   = false;

    private ScheduledExecutorService poller;
    private DebugEventListener       listener;
    private Executor                 uiExecutor;

    public DebugSession(String sessionId, String routineName, DbgConnection db) {
        this.sessionId   = sessionId;
        this.routineName = routineName;
        this.db          = db;
    }

    /**
     * Start the poll loop.
     * @param uiExecutor dispatches listener callbacks onto the UI thread
     *                   (e.g. SwingUtilities::invokeLater or Platform::runLater)
     */
    public void start(DebugEventListener listener, Executor uiExecutor) {
        this.listener   = listener;
        this.uiExecutor = uiExecutor;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dbg-poll-" + routineName);
            t.setDaemon(true);
            return t;
        });
        poller.scheduleAtFixedRate(this::poll, 0, 600, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
    }

    public boolean isPaused() { return paused; }

    // ── Execution control ─────────────────────────────────────────────────────

    public void doContinue() {
        paused = false;
        try { db.updateState(sessionId, "continue"); }
        catch (DbgException e) { fireError(e.getMessage()); }
    }

    public void doStep() {
        paused = false;
        try { db.updateState(sessionId, "step"); }
        catch (DbgException e) { fireError(e.getMessage()); }
    }

    public void clearLog() {
        try { db.clearLog(sessionId); lastId = 0; paused = false; }
        catch (DbgException e) { fireError(e.getMessage()); }
    }

    // ── Poll ──────────────────────────────────────────────────────────────────

    private void poll() {
        try {
            PollResult result = db.pollLog(sessionId, lastId);

            if (!result.entries.isEmpty()) {
                long maxId = result.entries.stream().mapToLong(e -> e.id).max().orElse(lastId);
                lastId = maxId;
                final var entries = result.entries;
                uiExecutor.execute(() -> listener.onLogEntries(entries));
            }

            if (result.paused && !paused) {
                paused = true;
                final String lbl  = result.pausedAt;
                final int    line = result.pausedLine();
                uiExecutor.execute(() -> listener.onPaused(lbl, line));
            } else if (!result.paused && paused) {
                paused = false;
                uiExecutor.execute(listener::onResumed);
            }
        } catch (DbgException e) {
            // Don't spam errors on every tick — only fire once per exception run
            uiExecutor.execute(() -> listener.onError(e.getMessage()));
        }
    }

    private void fireError(String msg) {
        uiExecutor.execute(() -> listener.onError(msg));
    }
}
