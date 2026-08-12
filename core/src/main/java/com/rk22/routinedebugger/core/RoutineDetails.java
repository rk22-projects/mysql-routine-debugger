package com.rk22.routinedebugger.core;

import java.util.List;

/** Complete routine state needed by a frontend editor. */
public class RoutineDetails {
    public final RoutineInfo routine;
    public final String ddl;
    public final List<RoutineParameter> parameters;
    public final String returnType;
    public final boolean deterministic;
    public final boolean deployed;
    public final String sessionId;
    public final List<String> breakpoints;

    public RoutineDetails(RoutineInfo routine, String ddl,
                          List<RoutineParameter> parameters,
                          String returnType, boolean deterministic,
                          boolean deployed, String sessionId,
                          List<String> breakpoints) {
        this.routine = routine;
        this.ddl = ddl;
        this.parameters = List.copyOf(parameters);
        this.returnType = returnType;
        this.deterministic = deterministic;
        this.deployed = deployed;
        this.sessionId = sessionId;
        this.breakpoints = List.copyOf(breakpoints);
    }
}
