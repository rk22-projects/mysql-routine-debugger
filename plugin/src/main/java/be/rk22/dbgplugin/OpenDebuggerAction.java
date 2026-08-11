package be.rk22.dbgplugin;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;

/** Opens (or focuses) the debugger TopComponent from the Window menu. */
public class OpenDebuggerAction extends AbstractAction {

    public OpenDebuggerAction() {
        super("MariaDB Procedure Debugger");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DebuggerTopComponent tc = DebuggerTopComponent.findInstance();
        tc.open();
        tc.requestActive();
    }
}
