package be.rk22.dbgplugin;

import java.util.List;

public class PollResult {
    public final List<LogEntry> entries;
    public final boolean        paused;
    public final String         pausedAt;   // label like "L12", or null

    public PollResult(List<LogEntry> entries, boolean paused, String pausedAt) {
        this.entries  = entries;
        this.paused   = paused;
        this.pausedAt = pausedAt;
    }

    /** Extract 1-based line number from a label like "L12", or -1 if not parseable. */
    public int pausedLine() {
        if (pausedAt != null && pausedAt.matches("L\\d+")) {
            try { return Integer.parseInt(pausedAt.substring(1)); }
            catch (NumberFormatException ignored) {}
        }
        return -1;
    }
}
