package com.rk22.routinedebugger.core;

/** Database metadata for one stored-routine parameter. */
public class RoutineParameter {
    public final String name;
    public final String type;
    public final String mode;

    public RoutineParameter(String name, String type, String mode) {
        this.name = name;
        this.type = type;
        this.mode = mode == null ? "IN" : mode;
    }
}
