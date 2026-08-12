package com.rk22.routinedebugger.core;

import java.util.List;

/** Result of initialization, including routines recovered from an interrupted session. */
public record StartupResult(List<RoutineInfo> routines, List<RoutineInfo> recoveredRoutines) {
    public StartupResult {
        routines = List.copyOf(routines);
        recoveredRoutines = List.copyOf(recoveredRoutines);
    }

    public boolean recoveredAnything() {
        return !recoveredRoutines.isEmpty();
    }
}
