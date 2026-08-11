package com.rk22.dbgplugin;

public class LogEntry {
    public final long   id;
    public final String ts;
    public final String sessionId;
    public final String routineName;
    public final String label;
    public final String varName;
    public final String varValue;

    public LogEntry(long id, String ts, String sessionId,
                    String routineName, String label,
                    String varName, String varValue) {
        this.id          = id;
        this.ts          = ts;
        this.sessionId   = sessionId;
        this.routineName = routineName;
        this.label       = label;
        this.varName     = varName;
        this.varValue    = varValue;
    }

    public boolean isBreakpoint() {
        return "__BREAKPOINT__".equals(varName);
    }
    public boolean isEntry() {
        return "ENTRY".equals(label);
    }
}
