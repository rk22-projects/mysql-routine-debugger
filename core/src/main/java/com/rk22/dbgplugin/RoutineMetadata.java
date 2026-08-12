package com.rk22.dbgplugin;

import java.util.List;

/** Internal database metadata used while building an instrumented proxy. */
final class RoutineMetadata {
    final RoutineInfo routine;
    final List<RoutineParameter> parameters;
    final String returnType;
    final boolean deterministic;

    RoutineMetadata(RoutineInfo routine, List<RoutineParameter> parameters,
                    String returnType, boolean deterministic) {
        this.routine = routine;
        this.parameters = List.copyOf(parameters);
        this.returnType = returnType;
        this.deterministic = deterministic;
    }
}
