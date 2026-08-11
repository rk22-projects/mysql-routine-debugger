package be.rk22.dbgplugin;

import java.util.List;

public interface DebugEventListener {
    void onLogEntries(List<LogEntry> entries);
    void onPaused(String label, int lineNumber);   // lineNumber is -1 if not parseable
    void onResumed();
    void onError(String message);
}
