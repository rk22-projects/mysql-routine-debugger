package com.rk22.dbgplugin;

import org.netbeans.api.db.explorer.DatabaseConnection;
import org.openide.nodes.Node;
import org.openide.util.HelpCtx;
import org.openide.util.actions.NodeAction;
import org.openide.util.actions.SystemAction;
import org.openide.windows.WindowManager;

/**
 * Right-click action on stored procedure/function nodes in the Services window.
 * Registered via layer.xml at Databases/Explorer/Procedure/Actions
 * and Databases/Explorer/Function/Actions.
 */
public class DeployAction extends NodeAction {

    /** Called by layer.xml via methodvalue to obtain the singleton instance. */
    public static DeployAction get() {
        return SystemAction.get(DeployAction.class);
    }

    @Override
    protected void performAction(Node[] activatedNodes) {
        if (activatedNodes == null || activatedNodes.length == 0) return;
        Node node = activatedNodes[0];
        String routineName = node.getDisplayName();

        // DatabaseConnection lives on the connection node, not on procedure/function nodes.
        // Walk up the tree until we find it.
        DatabaseConnection dbConn = null;
        Node n = node;
        while (n != null && dbConn == null) {
            dbConn = n.getLookup().lookup(DatabaseConnection.class);
            n = n.getParentNode();
        }

        final DatabaseConnection finalDbConn = dbConn;
        WindowManager.getDefault().invokeWhenUIReady(() -> {
            DebuggerTopComponent tc = DebuggerTopComponent.findInstance();
            tc.open();
            tc.requestActive();
            if (finalDbConn != null) {
                tc.initFromDbConnection(finalDbConn, routineName);
            }
        });
    }

    @Override
    protected boolean enable(Node[] activatedNodes) {
        return activatedNodes != null && activatedNodes.length == 1;
    }

    @Override public String getName()     { return "Debug in MySQL Routine Debugger…"; }
    @Override public HelpCtx getHelpCtx(){ return HelpCtx.DEFAULT_HELP; }
    @Override protected boolean asynchronous() { return false; }
}
