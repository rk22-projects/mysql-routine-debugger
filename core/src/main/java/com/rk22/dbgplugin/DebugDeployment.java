package com.rk22.dbgplugin;

import java.util.List;

/** The root routine and automatically instrumented callees deployed together. */
public class DebugDeployment {
    public final DeployedRoutine root;
    public final List<DeployedRoutine> callees;

    public DebugDeployment(DeployedRoutine root, List<DeployedRoutine> callees) {
        this.root = root;
        this.callees = List.copyOf(callees);
    }
}
