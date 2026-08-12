package com.rk22.routinedebugger.netbeans;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;

/** Opens (or focuses) the debugger TopComponent from the Window menu. */
public class OpenDebuggerAction extends AbstractAction {

    public OpenDebuggerAction() {
        super("MySQL Routine Debugger");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DebuggerTopComponent tc = DebuggerTopComponent.findInstance();
        tc.open();
        tc.requestActive();
    }
}
