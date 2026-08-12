package com.rk22.routinedebugger.core.database;

import com.rk22.routinedebugger.core.RoutineInfo;
import com.rk22.routinedebugger.core.RoutineParameter;

import java.util.List;

/** Internal database metadata used while building an instrumented proxy. */
public final class RoutineMetadata {
    public final RoutineInfo routine;
    public final List<RoutineParameter> parameters;
    public final String returnType;
    public final boolean deterministic;

    public RoutineMetadata(RoutineInfo routine, List<RoutineParameter> parameters,
                           String returnType, boolean deterministic) {
        this.routine = routine;
        this.parameters = List.copyOf(parameters);
        this.returnType = returnType;
        this.deterministic = deterministic;
    }
}
