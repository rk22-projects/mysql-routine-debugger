package be.rk22.dbgplugin;

import org.netbeans.api.db.explorer.DatabaseConnection;
import org.openide.util.RequestProcessor;
import org.openide.windows.Mode;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.logging.*;

@TopComponent.Description(
    preferredID = "DebuggerTopComponent",
    persistenceType = TopComponent.PERSISTENCE_NEVER
)
@TopComponent.Registration(mode = "editor", openAtStartup = false)
public class DebuggerTopComponent extends TopComponent implements DebugEventListener {

    private static final Logger LOG = Logger.getLogger(DebuggerTopComponent.class.getName());
    private static DebuggerTopComponent INSTANCE;

    // ── DB state ──────────────────────────────────────────────────────────────
    private Connection          conn;
    private DbgConnection       db;
    private String              schema;
    private DebugSession        session;
    private String              currentRoutine;
    private String              currentRoutineType;
    private boolean             debugActive = false;

    // ── UI ────────────────────────────────────────────────────────────────────
    private final JComboBox<RoutineInfo> routineCombo = new JComboBox<>();
    private final JButton btnLoad    = new JButton("Load");
    private final JButton btnDeploy  = new JButton("▶ Debug");
    private final JButton btnStop    = new JButton("■ Stop Debugging");
    private final JButton btnCont    = new JButton("▶ Continue  F5");
    private final JButton btnStep    = new JButton("↓ Step  F8");
    private final JLabel  statusBar  = new JLabel("Ready");
    private final JLabel  banner     = new JLabel();

    private final SourcePanel sourcePanel = new SourcePanel();
    private final WatchPanel  watchPanel  = new WatchPanel();
    private final LogPanel    logPanel    = new LogPanel();

    // ── Watch state ───────────────────────────────────────────────────────────
    private final Map<String, String> watchPrev    = new HashMap<>();
    private final Set<String>         watchChanged = new HashSet<>();

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static synchronized DebuggerTopComponent findInstance() {
        if (INSTANCE == null) INSTANCE = new DebuggerTopComponent();
        return INSTANCE;
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public DebuggerTopComponent() {
        setName("MariaDB Procedure Debugger");
        setDisplayName("MariaDB Procedure Debugger");
        setLayout(new BorderLayout(0, 0));
        buildUI();
        wireActions();
    }

    @Override
    public void open() {
        Mode m = WindowManager.getDefault().findMode("editor");
        if (m != null) m.dockInto(this);
        super.open();
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        // Outer toolbar: BorderLayout so we can push ⋮ to the right
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(new Color(0xF3, 0xF3, 0xF3));
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xDD, 0xDD, 0xDD)));

        // Left buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        btnPanel.setOpaque(false);

        routineCombo.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        routineCombo.setPreferredSize(new Dimension(200, 26));

        style(btnLoad,   new Color(0xE0, 0xE0, 0xE0), Color.DARK_GRAY);
        style(btnDeploy, new Color(0x27, 0x67, 0x49), Color.WHITE);
        style(btnStop,   new Color(0xC0, 0x39, 0x2B), Color.WHITE);
        style(btnCont,   new Color(0x0E, 0x63, 0x9C), Color.WHITE);
        style(btnStep,   new Color(0x6B, 0x5C, 0xE7), Color.WHITE);

        btnDeploy.setEnabled(false);
        btnStop.setEnabled(false);
        btnCont.setEnabled(false);
        btnStep.setEnabled(false);

        btnPanel.add(routineCombo);
        btnPanel.add(btnLoad);
        btnPanel.add(btnDeploy);
        btnPanel.add(btnStop);
        btnPanel.add(Box.createHorizontalStrut(12));
        btnPanel.add(btnCont);
        btnPanel.add(btnStep);
        toolbar.add(btnPanel, BorderLayout.CENTER);

        // Right side: ⋮ overflow menu (Reset All lives here)
        JPopupMenu overflowMenu = new JPopupMenu();
        JMenuItem  resetAllItem = new JMenuItem("⚠  Reset all debug changes…");
        resetAllItem.addActionListener(e -> resetAll());
        overflowMenu.add(resetAllItem);

        JButton btnMore = new JButton("⋮");
        style(btnMore, new Color(0xE8, 0xE8, 0xE8), new Color(0x44, 0x44, 0x44));
        btnMore.setFont(btnMore.getFont().deriveFont(Font.BOLD, 15f));
        btnMore.setToolTipText("More actions");
        btnMore.addActionListener(e -> overflowMenu.show(btnMore, 0, btnMore.getHeight()));

        JPanel morePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        morePanel.setOpaque(false);
        morePanel.add(btnMore);
        toolbar.add(morePanel, BorderLayout.EAST);

        // Banner
        banner.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 2, 0, new Color(0x27, 0xAE, 0x60)),
            BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        banner.setBackground(new Color(0xEA, 0xFA, 0xF1));
        banner.setOpaque(true);
        banner.setFont(banner.getFont().deriveFont(Font.PLAIN, 12f));
        banner.setForeground(new Color(0x1A, 0x7F, 0x37));
        banner.setVisible(false);

        JPanel topArea = new JPanel(new BorderLayout());
        topArea.add(toolbar, BorderLayout.NORTH);
        topArea.add(banner,  BorderLayout.SOUTH);
        add(topArea, BorderLayout.NORTH);

        // Source panel (left) + right panel in a split pane
        sourcePanel.setBorder(BorderFactory.createEmptyBorder());
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setPreferredSize(new Dimension(360, 0));
        rightPanel.add(watchPanel, BorderLayout.CENTER);
        rightPanel.add(logPanel,   BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourcePanel, rightPanel);
        split.setResizeWeight(0.75);
        split.setBorder(null);
        split.setDividerSize(4);
        add(split, BorderLayout.CENTER);

        // Status bar
        statusBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xDD, 0xDD, 0xDD)),
            BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        statusBar.setBackground(new Color(0x00, 0x7A, 0xCC));
        statusBar.setForeground(Color.WHITE);
        statusBar.setOpaque(true);
        statusBar.setFont(statusBar.getFont().deriveFont(Font.PLAIN, 12f));
        add(statusBar, BorderLayout.SOUTH);
    }

    private static void style(JButton btn, Color bg, Color fg) {
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 13f));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    // ── Wire actions ──────────────────────────────────────────────────────────

    private void wireActions() {
        btnLoad.addActionListener(e  -> loadRoutine());
        btnDeploy.addActionListener(e -> deployDebug());
        btnStop.addActionListener(e  -> stopDebugging());
        btnCont.addActionListener(e  -> doContinue());
        btnStep.addActionListener(e  -> doStep());

        logPanel.setOnClear(this::clearLog);

        watchPanel.setOnAdd(name -> watchPrev.putIfAbsent(name, null));
        watchPanel.setOnRemove(name -> { watchPrev.remove(name); watchChanged.remove(name); });
        watchPanel.setOnToggleAll(() -> {
            if (!watchPanel.isWatchAll()) return;
            watchPrev.forEach((name, val) -> {
                watchPanel.addVariable(name);
                if (val != null) watchPanel.updateValue(name, val, watchChanged.contains(name));
            });
        });

        sourcePanel.setVarValueLookup(watchPrev::get);
        sourcePanel.setOnBreakpointToggle(label -> {
            if (currentRoutine == null) return;
            try { db.saveBreakpoints(currentRoutine, sourcePanel.getBreakpoints()); }
            catch (DbgException ex) { LOG.log(Level.WARNING, "bp save failed", ex); }
        });
        sourcePanel.setOnAddWatch(watchPanel::addVariable);

        // F5 / F8 via KeyboardFocusManager so NetBeans doesn't swallow them first.
        // Only intercept when our TC is the active component and a session is paused.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(evt -> {
            if (evt.getID() != KeyEvent.KEY_PRESSED) return false;
            if (!isShowing()) return false;
            if (WindowManager.getDefault().getRegistry().getActivated() != this) return false;
            if (session == null || !session.isPaused()) return false;
            if (evt.getKeyCode() == KeyEvent.VK_F5) { doContinue(); return true; }
            if (evt.getKeyCode() == KeyEvent.VK_F8) { doStep();     return true; }
            return false;
        });
    }

    // ── Connection management ─────────────────────────────────────────────────

    /** Called by DeployAction with a DatabaseConnection from the DB Browser. */
    public void initFromDbConnection(DatabaseConnection dbConn, String routineName) {
        try {
            conn = dbConn.getJDBCConnection();
            conn.setAutoCommit(true);
            schema = dbConn.getSchema();
            if (schema == null || schema.isBlank())
                schema = dbConn.getDatabaseURL().replaceAll(".*/","").replaceAll("\\?.*","");
            db = new DbgConnection(conn);
        } catch (Exception ex) {
            showError("Connection failed: " + ex.getMessage());
            return;
        }
        setStatus("Connecting…", false);
        RequestProcessor.getDefault().post(() -> {
            try {
                db.setupInfrastructure();
                List<RoutineInfo> routines = db.fetchRoutines(schema);
                SwingUtilities.invokeLater(() -> {
                    routineCombo.removeAllItems();
                    routines.forEach(routineCombo::addItem);
                    setStatus("Connected to " + schema, false);
                    if (routineName != null) {
                        for (int i = 0; i < routineCombo.getItemCount(); i++) {
                            if (routineCombo.getItemAt(i).name.equals(routineName)) {
                                routineCombo.setSelectedIndex(i);
                                loadRoutine();
                                break;
                            }
                        }
                    } else {
                        setDebugActive(false);
                    }
                });
            } catch (DbgException ex) {
                SwingUtilities.invokeLater(() -> showError("Connection failed: " + ex.getMessage()));
            }
        });
    }

    /** Show a simple connection dialog and connect manually. */
    private void promptConnect() {
        JTextField hostF = new JTextField("localhost", 12);
        JTextField portF = new JTextField("3306", 5);
        JTextField userF = new JTextField(8);
        JPasswordField passF = new JPasswordField(8);
        JTextField dbF   = new JTextField(10);
        Object[] msg = {"Host:", hostF, "Port:", portF, "User:", userF, "Password:", passF, "Database:", dbF};
        int r = JOptionPane.showConfirmDialog(this, msg, "Connect to MariaDB", JOptionPane.OK_CANCEL_OPTION);
        if (r != JOptionPane.OK_OPTION) return;
        try {
            String url = "jdbc:mariadb://" + hostF.getText().trim() + ":" + portF.getText().trim()
                       + "/" + dbF.getText().trim();
            conn   = DriverManager.getConnection(url, userF.getText().trim(), new String(passF.getPassword()));
            schema = dbF.getText().trim();
            db     = new DbgConnection(conn);
        } catch (Exception ex) {
            showError("Connection failed: " + ex.getMessage());
            return;
        }
        setStatus("Connecting…", false);
        RequestProcessor.getDefault().post(() -> {
            try {
                db.setupInfrastructure();
                List<RoutineInfo> routines = db.fetchRoutines(schema);
                SwingUtilities.invokeLater(() -> {
                    routineCombo.removeAllItems();
                    routines.forEach(routineCombo::addItem);
                    setDebugActive(false);
                    setStatus("Connected to " + schema, false);
                });
            } catch (DbgException ex) {
                SwingUtilities.invokeLater(() -> showError("Connection failed: " + ex.getMessage()));
            }
        });
    }

    // ── Routine loading ───────────────────────────────────────────────────────

    private void loadRoutine() {
        if (db == null) { promptConnect(); return; }
        RoutineInfo ri = (RoutineInfo) routineCombo.getSelectedItem();
        if (ri == null) return;
        currentRoutine     = ri.name;
        currentRoutineType = ri.type;
        stopSession();
        setStatus("Loading " + ri.name + "…", false);

        final String rName = ri.name;
        final String rType = ri.type;
        RequestProcessor.getDefault().post(() -> {
            try {
                String ddl = db.loadOriginalDdl(rName);
                boolean deployed = ddl != null;
                if (!deployed) ddl = db.fetchRoutineDdl(rName, rType);
                final String finalDdl = ddl;
                List<String> bps = db.loadBreakpoints(rName);
                String sid = deployed ? db.loadSessionId(rName) : null;
                final String finalSid = sid;
                SwingUtilities.invokeLater(() -> {
                    sourcePanel.setSource(finalDdl);
                    sourcePanel.setBreakpoints(bps);
                    if (deployed) {
                        startSession(finalSid != null ? finalSid : newSessionId());
                        showBanner(rName);
                        setDebugActive(true);
                    } else {
                        hideBanner();
                        setDebugActive(false);
                    }
                    setStatus("Loaded: " + rName, false);
                });
            } catch (DbgException ex) {
                SwingUtilities.invokeLater(() -> showError("Load failed: " + ex.getMessage()));
            }
        });
    }

    // ── Deploy / Stop ─────────────────────────────────────────────────────────

    private void deployDebug() {
        if (db == null || currentRoutine == null) return;
        stopSession();
        logPanel.clear();
        watchPanel.clearValues();
        watchPrev.clear();
        watchChanged.clear();
        String sid = newSessionId();
        setDebugActive(false);  // disable both buttons while working
        btnDeploy.setEnabled(false);
        setStatus("Deploying…", false);

        final String routine     = currentRoutine;
        final String routineType = currentRoutineType;
        RequestProcessor.getDefault().post(() -> {
            try {
                String originalDdl  = db.fetchRoutineDdl(routine, routineType);
                String origCopy     = InstrumentEngine.buildOrigCopy(routine, originalDdl);
                String instrumented = InstrumentEngine.instrumentAuto(routine, originalDdl, sid, conn, schema);

                List<String> pNames = new ArrayList<>();
                List<String> pTypes = new ArrayList<>();
                List<String> pModes = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT PARAMETER_NAME, DTD_IDENTIFIER, PARAMETER_MODE " +
                        "FROM information_schema.PARAMETERS " +
                        "WHERE SPECIFIC_SCHEMA=? AND SPECIFIC_NAME=? AND ORDINAL_POSITION>0 " +
                        "ORDER BY ORDINAL_POSITION")) {
                    ps.setString(1, schema); ps.setString(2, routine);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        pNames.add(rs.getString(1));
                        pTypes.add(rs.getString(2));
                        pModes.add(rs.getString(3) != null ? rs.getString(3) : "IN");
                    }
                }
                String returnType = "FUNCTION".equalsIgnoreCase(routineType) ?
                    fetchReturnType(routine) : null;
                boolean deterministic = false;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT IS_DETERMINISTIC FROM information_schema.ROUTINES " +
                        "WHERE ROUTINE_SCHEMA=? AND ROUTINE_NAME=?")) {
                    ps.setString(1, schema); ps.setString(2, routine);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) deterministic = "YES".equals(rs.getString(1));
                }
                String proxy = InstrumentEngine.buildProxy(
                    routine, routineType, pNames, pTypes, pModes, returnType, deterministic, sid);

                db.deployDebug(routine, routineType, originalDdl, origCopy, instrumented, proxy, sid);
                db.initSessionState(sid, routine);

                SwingUtilities.invokeLater(() -> {
                    startSession(sid);
                    showBanner(routine);
                    setDebugActive(true);
                    setStatus("Debug active — call " + routine + "(…) in your SQL client", false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setDebugActive(false);
                    showError("Deploy failed: " + ex.getMessage());
                });
            }
        });
    }

    private String fetchReturnType(String routineName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DTD_IDENTIFIER FROM information_schema.ROUTINES " +
                "WHERE ROUTINE_SCHEMA=? AND ROUTINE_NAME=?")) {
            ps.setString(1, schema); ps.setString(2, routineName);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : "VARCHAR(255)";
        }
    }

    private void stopDebugging() {
        if (db == null || currentRoutine == null) return;
        stopSession();
        btnStop.setEnabled(false);
        setStatus("Stopping…", false);

        final String routine     = currentRoutine;
        final String routineType = currentRoutineType;
        RequestProcessor.getDefault().post(() -> {
            try {
                // Unblock any paused DB session so the caller's SQL doesn't hang
                String sid = db.loadSessionId(routine);
                if (sid != null) {
                    try { db.updateState(sid, "continue"); } catch (DbgException ignored) {}
                }
                String origDdl = db.loadOriginalDdl(routine);
                if (origDdl == null) {
                    SwingUtilities.invokeLater(() -> {
                        setDebugActive(true); // was still active
                        showError("No saved original found.");
                    });
                    return;
                }
                db.restoreOriginal(routine, routineType, origDdl);
                String freshDdl = db.fetchRoutineDdl(routine, routineType);
                SwingUtilities.invokeLater(() -> {
                    hideBanner();
                    sourcePanel.clearCurrentLine();
                    sourcePanel.setSource(freshDdl);
                    setDebugActive(false);
                    setStatus("Stopped debugging: " + routine, false);
                });
            } catch (DbgException ex) {
                SwingUtilities.invokeLater(() -> {
                    setDebugActive(true); // still active — let user retry
                    showError("Stop failed: " + ex.getMessage());
                });
            }
        });
    }

    private void resetAll() {
        if (db == null) return;
        int r = JOptionPane.showConfirmDialog(this,
            "<html>This will:<br>" +
            "• Restore <b>all</b> deployed routines to their original DDL<br>" +
            "• Drop all _dbg_* and _orig_* routines<br>" +
            "• Remove all debug infrastructure tables<br><br>" +
            "Continue?</html>",
            "Reset All Debug Changes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;

        stopSession();
        logPanel.clear();
        watchPanel.clearValues();
        watchPrev.clear();
        watchChanged.clear();
        hideBanner();
        sourcePanel.clearCurrentLine();
        setDebugActive(false);
        btnDeploy.setEnabled(false);
        setStatus("Resetting all debug changes…", false);

        final String currentSchema = schema;
        RequestProcessor.getDefault().post(() -> {
            try {
                db.restoreAll(currentSchema);
                db.setupInfrastructure();   // recreate tables so user can deploy again immediately
                List<RoutineInfo> routines = db.fetchRoutines(currentSchema);
                SwingUtilities.invokeLater(() -> {
                    routineCombo.removeAllItems();
                    routines.forEach(routineCombo::addItem);
                    currentRoutine = null;
                    currentRoutineType = null;
                    sourcePanel.setSource(null);
                    setDebugActive(false);
                    setStatus("All debug changes reverted.", false);
                });
            } catch (DbgException ex) {
                SwingUtilities.invokeLater(() -> {
                    setDebugActive(false);
                    showError("Reset failed: " + ex.getMessage());
                });
            }
        });
    }

    // ── Session management ────────────────────────────────────────────────────

    private void startSession(String sid) {
        stopSession();
        session = new DebugSession(sid, currentRoutine, db);
        session.start(this, SwingUtilities::invokeLater);
    }

    private void stopSession() {
        if (session != null) { session.stop(); session = null; }
        setPaused(false);
    }

    // ── Execution control ─────────────────────────────────────────────────────

    private void doContinue() {
        if (session == null || !session.isPaused()) return;
        sourcePanel.clearCurrentLine();
        watchChanged.clear();
        watchPanel.clearChanged();
        session.doContinue();
        setPaused(false);
        setStatus("Resumed…", false);
    }

    private void doStep() {
        if (session == null || !session.isPaused()) return;
        sourcePanel.clearCurrentLine();
        watchChanged.clear();
        watchPanel.clearChanged();
        session.doStep();
        setPaused(false);
        setStatus("Stepping…", false);
    }

    private void clearLog() {
        if (session != null) session.clearLog();
        logPanel.clear();
        watchPanel.clearValues();
        watchPrev.clear();
        watchChanged.clear();
        setPaused(false);
        setStatus("Log cleared", false);
    }

    private void setDebugActive(boolean active) {
        debugActive = active;
        btnDeploy.setEnabled(!active && db != null);
        btnStop.setEnabled(active);
    }

    private void setPaused(boolean on) {
        btnCont.setEnabled(on);
        btnStep.setEnabled(on);
        statusBar.setBackground(on ? new Color(0xC0, 0x39, 0x2B) : new Color(0x00, 0x7A, 0xCC));
    }

    // ── DebugEventListener ────────────────────────────────────────────────────

    @Override
    public void onLogEntries(List<LogEntry> entries) {
        for (LogEntry e : entries) {
            logPanel.append(e);
            if (!e.isBreakpoint()) {
                String name    = e.varName;
                if (watchPanel.isWatchAll()) watchPanel.addVariable(name);
                String prev    = watchPrev.get(name);
                String cur     = e.varValue;
                boolean changed = prev != null && !Objects.equals(prev, cur);
                watchPrev.put(name, cur);
                if (changed) watchChanged.add(name);
                watchPanel.updateValue(name, cur, watchChanged.contains(name));
            }
        }
    }

    @Override
    public void onPaused(String label, int lineNumber) {
        setPaused(true);
        if (lineNumber > 0) sourcePanel.setCurrentLine(lineNumber);
        setStatus("⏸  Paused at line " + (lineNumber > 0 ? lineNumber : label), true);
    }

    @Override
    public void onResumed() {
        setPaused(false);
        setStatus("Running…", false);
    }

    @Override
    public void onError(String message) {
        LOG.warning("[dbg] " + message);
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void showBanner(String name) {
        banner.setText("▶ Debug active — call " + name + "(…) normally in your SQL client");
        banner.setVisible(true);
    }
    private void hideBanner() { banner.setVisible(false); }

    private void setStatus(String msg, boolean paused) {
        statusBar.setText(msg);
        statusBar.setBackground(paused ? new Color(0xC0, 0x39, 0x2B) : new Color(0x00, 0x7A, 0xCC));
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
        setStatus(msg, false);
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }
}
