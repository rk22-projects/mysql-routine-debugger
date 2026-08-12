package com.rk22.dbgplugin;

import java.util.List;

/** One routine participating in a debug deployment. */
public class DeployedRoutine {
    public final String name;
    public final String type;
    public final String sessionId;
    public final String ddl;
    public final List<String> breakpoints;

    public DeployedRoutine(String name, String type, String sessionId,
                           String ddl, List<String> breakpoints) {
        this.name = name;
        this.type = type;
        this.sessionId = sessionId;
        this.ddl = ddl;
        this.breakpoints = List.copyOf(breakpoints);
    }
}
