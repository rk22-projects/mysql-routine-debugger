package com.rk22.routinedebugger.core;

import java.util.List;

public interface DebugEventListener {
    void onLogEntries(List<LogEntry> entries);
    void onPaused(String label, int lineNumber);   // lineNumber is -1 if not parseable
    void onResumed();
    void onError(String message);
    /** Fired once when the routine proxy reports that execution returned normally. */
    default void onCompleted() {}
    /** Fired when a deployed callee starts executing and hits its first checkpoint. */
    default void onCalleeStarted(String routineName, String sessionId) {}
}
