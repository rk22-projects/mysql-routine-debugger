package com.rk22.routinedebugger.standalone;

import com.rk22.routinedebugger.core.PersistentLogging;

/** Plain launcher avoids the JDK's special JavaFX main-class handling for fat JARs. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        PersistentLogging.initialize("standalone");
        App.main(args);
    }
}
