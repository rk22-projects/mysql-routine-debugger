package com.rk22.routinedebugger.core;

import com.rk22.routinedebugger.core.instrumentation.InstrumentEngine;

/** Public source-analysis operations needed by frontend editors. */
public final class SourceLineClassifier {
    private SourceLineClassifier() {}

    public static boolean isExecutable(String sqlLine) {
        return InstrumentEngine.isExecutableLine(sqlLine);
    }
}
