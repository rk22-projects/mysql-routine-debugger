package com.rk22.routinedebugger.netbeans;

import com.rk22.routinedebugger.core.*;
import com.rk22.routinedebugger.core.database.DatabaseEngine;
import com.rk22.routinedebugger.core.database.ConnectionProfile;
import com.rk22.routinedebugger.core.database.ConnectionService;
import com.rk22.routinedebugger.core.session.DebugSession;

import org.netbeans.api.db.explorer.DatabaseConnection;
import org.openide.util.RequestProcessor;
import org.openide.windows.Mode;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.sql.Connection;
import java.sql.SQLException;
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

    // ── Mode ──────────────────────────────────────────────────────────────────
    private final boolean isChild;
    private DebuggerTopComponent parentDebugger;

    // ── DB state ──────────────────────────────────────────────────────────────
    private Connection          conn;
    private final ConnectionService connectionService = new ConnectionService();
    private DebuggerService     debugger;
    private String              schema;
    private DebugSession        session;
    private DebugDeployment     deployment;
    private String              currentRoutine;
    private String              currentRoutineType;
    private boolean             debugActive = false;
    private boolean             ownsConnection = false;

    // ── UI ────────────────────────────────────────────────────────────────────
    private final JComboBox<RoutineInfo> routineCombo = new JComboBox<>();
    private final JButton btnConnect    = new JButton("Connect");
    private final JButton btnDisconnect = new JButton("Disconnect");
    private final JButton btnDeploy  = new JButton("▶ Debug");
    private final JButton btnStop    = new JButton("■ Stop Debugging");
    private final JButton btnCont     = new JButton("▶ Continue  F5");
    private final JButton btnStep     = new JButton("↓ Step Over  F8");
    private final JButton btnStepInto = new JButton("↘ Step Into  F7");
    private final JButton btnStepOut  = new JButton("↑ Step Out  Ctrl+F7");
    private final JLabel  statusBar   = new JLabel("Ready");
    private final JLabel  banner     = new JLabel();

    private final SourcePanel sourcePanel = new SourcePanel();
    private final WatchPanel  watchPanel  = new WatchPanel();
    private final LogPanel    logPanel    = new LogPanel();

    // ── Watch state ───────────────────────────────────────────────────────────
    private final Map<String, String> watchPrev    = new HashMap<>();
    private final Set<String>         watchChanged = new HashSet<>();
    private final List<RoutineInfo>    availableRoutines = new ArrayList<>();
    private boolean                   filteringRoutines;

    // ── Singleton (root only) ─────────────────────────────────────────────────

    public static synchronized DebuggerTopComponent findInstance() {
        if (INSTANCE == null) INSTANCE = new DebuggerTopComponent();
        return INSTANCE;
    }

    // ── Constructors / factory ────────────────────────────────────────────────

    public DebuggerTopComponent() {
        this(false);
    }

    private DebuggerTopComponent(boolean isChild) {
        this.isChild = isChild;
        setName("MySQL Routine Debugger");
        setDisplayName("MySQL Routine Debugger");
        setLayout(new BorderLayout(0, 0));
        buildUI();
        wireActions();
    }

    /**
     * Creates a child debug window for a step-in callee.
     * The caller is responsible for calling open(), startSession(), and setDebugActive(true).
     */
    static DebuggerTopComponent createChildInstance(String routineName,
                                                     DebuggerService debugger,
                                                     Connection conn,
                                                     String schema,
                                                     DebuggerTopComponent parent) {
        DebuggerTopComponent tc = new DebuggerTopComponent(true);
        tc.parentDebugger = parent;
        tc.currentRoutine = routineName;
        tc.conn   = conn;
        tc.debugger = debugger;
        tc.schema = schema;
        tc.setName("↵ " + routineName);
        tc.setDisplayName("↵ " + routineName);
        return tc;
    }

    @Override
    public void open() {
        Mode m = WindowManager.getDefault().findMode("editor");
        if (m != null) m.dockInto(this);
        super.open();
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        // Banner (shared by root and child)
        banner.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 2, 0, new Color(0x27, 0xAE, 0x60)),
            BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        banner.setBackground(new Color(0xEA, 0xFA, 0xF1));
        banner.setOpaque(true);
        banner.setFont(banner.getFont().deriveFont(Font.PLAIN, 12f));
        banner.setForeground(new Color(0x1A, 0x7F, 0x37));
        banner.setVisible(false);

        JPanel topArea = new JPanel(new BorderLayout());
        if (isChild) {
            buildChildToolbar(topArea);
        } else {
            buildRootToolbar(topArea);
        }
        topArea.add(banner, BorderLayout.SOUTH);
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

    private void buildRootToolbar(JPanel topArea) {
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(new Color(0xF3, 0xF3, 0xF3));
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xDD, 0xDD, 0xDD)));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        btnPanel.setOpaque(false);

        routineCombo.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        routineCombo.setPreferredSize(new Dimension(240, 26));
        routineCombo.setEditable(true);

        style(btnConnect,    new Color(0xE0, 0xE0, 0xE0), Color.DARK_GRAY);
        style(btnDisconnect, new Color(0xE0, 0xE0, 0xE0), Color.DARK_GRAY);
        style(btnDeploy,   new Color(0x27, 0x67, 0x49), Color.WHITE);
        style(btnStop,     new Color(0xC0, 0x39, 0x2B), Color.WHITE);
        style(btnCont,     new Color(0x0E, 0x63, 0x9C), Color.WHITE);
        style(btnStep,     new Color(0x6B, 0x5C, 0xE7), Color.WHITE);
        style(btnStepInto, new Color(0x8A, 0x6B, 0xF5), Color.WHITE);

        btnDeploy.setEnabled(false);
        btnStop.setEnabled(false);
        btnCont.setEnabled(false);
        btnStep.setEnabled(false);
        btnStepInto.setEnabled(false);
        btnDisconnect.setVisible(false);
        routineCombo.setEnabled(false);

        btnPanel.add(btnConnect);
        btnPanel.add(btnDisconnect);
        btnPanel.add(routineCombo);
        btnPanel.add(btnDeploy);
        btnPanel.add(btnStop);
        btnPanel.add(Box.createHorizontalStrut(12));
        btnPanel.add(btnCont);
        btnPanel.add(btnStep);
        btnPanel.add(btnStepInto);
        toolbar.add(btnPanel, BorderLayout.CENTER);

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

        topArea.add(toolbar, BorderLayout.NORTH);
    }

    private void buildChildToolbar(JPanel topArea) {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.setBackground(new Color(0x24, 0x41, 0x5F));
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x1A, 0x2E, 0x44)));

        JLabel lbl = new JLabel("  ↵  Step-in");
        lbl.setForeground(new Color(0xAD, 0xC8, 0xFF));
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 13f));

        style(btnCont,    new Color(0x0E, 0x63, 0x9C), Color.WHITE);
        style(btnStep,    new Color(0x6B, 0x5C, 0xE7), Color.WHITE);
        style(btnStepOut, new Color(0x4A, 0x6B, 0x8A), Color.WHITE);
        btnCont.setEnabled(false);
        btnStep.setEnabled(false);
        btnStepOut.setEnabled(false);

        toolbar.add(lbl);
        toolbar.add(Box.createHorizontalStrut(8));
        toolbar.add(btnCont);
        toolbar.add(btnStep);
        toolbar.add(btnStepOut);
        topArea.add(toolbar, BorderLayout.NORTH);
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
        btnCont.addActionListener(e -> doContinue());

        logPanel.setOnClear(this::clearLog);

        watchPanel.setOnAdd(name -> {
            watchPrev.putIfAbsent(name, null);
            if (watchPrev.get(name) != null) watchPanel.updateValue(name, watchPrev.get(name), false);
        });
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
            try { debugger.saveBreakpoints(currentRoutine, sourcePanel.getBreakpoints()); }
            catch (DbgException ex) { LOG.log(Level.WARNING, "bp save failed", ex); }
        });
        sourcePanel.setOnAddWatch(name -> {
            watchPanel.addVariable(name);
            watchPrev.putIfAbsent(name, null);
            if (watchPrev.get(name) != null) watchPanel.updateValue(name, watchPrev.get(name), false);
        });

        if (!isChild) {
            btnConnect.addActionListener(e   -> promptConnect());
            btnDisconnect.addActionListener(e -> disconnect());
            installRoutineSearch();
            btnDeploy.addActionListener(e    -> deployDebug());
            btnStop.addActionListener(e      -> stopDebugging());
            btnStep.addActionListener(e      -> doStepOver());
            btnStepInto.addActionListener(e  -> doStepInto());
        } else {
            btnStep.addActionListener(e      -> doStep());
            btnStepOut.addActionListener(e   -> doStepOut());
        }

        // F5 / F7 / F8 / Ctrl+F7 via KeyboardFocusManager so NetBeans doesn't swallow them.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(evt -> {
            if (evt.getID() != KeyEvent.KEY_PRESSED) return false;
            if (!isShowing()) return false;
            if (WindowManager.getDefault().getRegistry().getActivated() != this) return false;
            if (evt.getKeyCode() == KeyEvent.VK_F9) {
                sourcePanel.toggleBreakpointAtCaret();
                return true;
            }
            if (session == null || !session.isPaused()) return false;
            if (evt.getKeyCode() == KeyEvent.VK_F5) { doContinue(); return true; }
            if (!isChild) {
                if (evt.getKeyCode() == KeyEvent.VK_F8) { doStepOver(); return true; }
                if (evt.getKeyCode() == KeyEvent.VK_F7 && !evt.isControlDown()) { doStepInto(); return true; }
            } else {
                if (evt.getKeyCode() == KeyEvent.VK_F8) { doStep();    return true; }
                if (evt.getKeyCode() == KeyEvent.VK_F7 && evt.isControlDown()) { doStepOut(); return true; }
            }
            return false;
        });
    }

    // ── Connection management ─────────────────────────────────────────────────

    @Override
    protected void componentOpened() {
        if (!isChild && debugger == null) SwingUtilities.invokeLater(() -> {
            if (debugger == null && isShowing()) promptConnect();
        });
    }

    private void installRoutineSearch() {
        JTextField editor = (JTextField) routineCombo.getEditor().getEditorComponent();
        editor.setToolTipText("Type to filter procedures and functions");
        editor.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if (!filteringRoutines) SwingUtilities.invokeLater(() -> filterRoutineChoices(editor.getText()));
            }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });
        routineCombo.addActionListener(e -> {
            if (filteringRoutines || debugActive) return;
            Object selected = routineCombo.getSelectedItem();
            if (selected instanceof RoutineInfo ri &&
                (!ri.name.equals(currentRoutine) || sourcePanel.getSourceText().isEmpty())) loadRoutine();
        });
    }

    private void setAvailableRoutines(List<RoutineInfo> routines) {
        availableRoutines.clear();
        availableRoutines.addAll(routines);
        currentRoutine = null;
        currentRoutineType = null;
        filteringRoutines = true;
        routineCombo.removeAllItems();
        routines.forEach(routineCombo::addItem);
        routineCombo.setSelectedItem(null);
        routineCombo.getEditor().setItem("");
        filteringRoutines = false;
    }

    private void filterRoutineChoices(String text) {
        if (filteringRoutines || !routineCombo.isEnabled()) return;
        String query = text == null ? "" : text;
        String needle = query.toLowerCase(Locale.ROOT);
        filteringRoutines = true;
        routineCombo.removeAllItems();
        availableRoutines.stream()
            .filter(r -> needle.isBlank() || r.name.toLowerCase(Locale.ROOT).contains(needle) ||
                         r.type.toLowerCase(Locale.ROOT).contains(needle))
            .forEach(routineCombo::addItem);
        routineCombo.getEditor().setItem(query);
        filteringRoutines = false;
        if (routineCombo.isFocusOwner() || routineCombo.getEditor().getEditorComponent().isFocusOwner()) {
            routineCombo.setPopupVisible(routineCombo.getItemCount() > 0);
        }
    }

    /** Called by DeployAction with a DatabaseConnection from the DB Browser. */
    public void initFromDbConnection(DatabaseConnection dbConn, String routineName) {
        if (debugActive) { showError("Stop the active debug session before changing the connection."); return; }
        Connection previousConn = conn;
        boolean previousOwned = ownsConnection;
        Connection controlConn = null;
        try {
            String newSchema = dbConn.getSchema();
            if (newSchema == null || newSchema.isBlank())
                newSchema = dbConn.getDatabaseURL().replaceAll(".*/","").replaceAll("\\?.*","");

            controlConn = openDedicatedControlConnection(dbConn);
            controlConn.setAutoCommit(true);
            if (newSchema != null && !newSchema.isBlank()) controlConn.setCatalog(newSchema);

            conn = controlConn;
            schema = newSchema;
            debugger = new DebuggerService(controlConn, schema);
            ownsConnection = true;
            if (previousOwned && previousConn != null && previousConn != controlConn) {
                try { previousConn.close(); } catch (SQLException ignored) {}
            }
        } catch (Exception ex) {
            if (controlConn != null) {
                try { controlConn.close(); } catch (SQLException ignored) {}
            }
            showError("Connection failed: " + ex.getMessage());
            return;
        }
        setStatus("Connecting…", false);
        RequestProcessor.getDefault().post(() -> {
            try {
                List<RoutineInfo> routines = debugger.initialize();
                SwingUtilities.invokeLater(() -> {
                    setAvailableRoutines(routines);
                    setStatus("Connected to " + schema, false);
                    setDebugActive(false);
                    if (routineName != null) {
                        for (int i = 0; i < routineCombo.getItemCount(); i++) {
                            if (routineCombo.getItemAt(i).name.equals(routineName)) {
                                routineCombo.setSelectedIndex(i);
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
        ConnectionProfile saved = connectionService.loadProfile();
        JComboBox<DatabaseEngine> engineF = new JComboBox<>(DatabaseEngine.values());
        engineF.setSelectedItem(saved.engine());
        JTextField hostF = new JTextField(saved.host(), 12);
        JTextField portF = new JTextField(Integer.toString(saved.port()), 5);
        JTextField userF = new JTextField(saved.user(), 8);
        JPasswordField passF = new JPasswordField(8);
        passF.setText(saved.password());
        JTextField dbF   = new JTextField(saved.database(), 10);
        JButton discoverDb = new JButton("…");
        discoverDb.setToolTipText("Discover databases on this server");
        JPanel databaseBox = new JPanel(new BorderLayout(4, 0));
        databaseBox.add(dbF, BorderLayout.CENTER);
        databaseBox.add(discoverDb, BorderLayout.EAST);
        discoverDb.addActionListener(e -> {
            final ConnectionProfile profile;
            try {
                profile = connectionProfile(engineF, hostF, portF, userF, passF, dbF);
            } catch (RuntimeException ex) {
                showError("Invalid connection details: " + ex.getMessage());
                return;
            }
            discoverDb.setEnabled(false);
            RequestProcessor.getDefault().post(() -> {
                try {
                    List<String> databases = connectionService.discoverDatabases(profile);
                    SwingUtilities.invokeLater(() -> {
                        discoverDb.setEnabled(true);
                        Object selected = JOptionPane.showInputDialog(this,
                            "Databases available on " + profile.host() + ":", "Select database",
                            JOptionPane.PLAIN_MESSAGE, null, databases.toArray(), dbF.getText());
                        if (selected != null) dbF.setText(selected.toString());
                    });
                } catch (SQLException ex) {
                    SwingUtilities.invokeLater(() -> {
                        discoverDb.setEnabled(true);
                        showError("Database discovery failed: " + ex.getMessage());
                    });
                }
            });
        });
        Object[] msg = {"Database engine:", engineF, "Host:", hostF, "Port:", portF,
                        "User:", userF, "Password:", passF, "Database:", databaseBox};
        int r = JOptionPane.showConfirmDialog(this, msg, "Connect to MySQL or MariaDB", JOptionPane.OK_CANCEL_OPTION);
        if (r != JOptionPane.OK_OPTION) return;
        try {
            ConnectionProfile profile = connectionProfile(engineF, hostF, portF, userF, passF, dbF);
            conn = connectionService.connect(profile);
            schema = dbF.getText().trim();
            debugger = new DebuggerService(conn, schema);
            ownsConnection = true;
        } catch (Exception ex) {
            showError("Connection failed: " + ex.getMessage());
            return;
        }
        setStatus("Connecting…", false);
        RequestProcessor.getDefault().post(() -> {
            try {
                List<RoutineInfo> routines = debugger.initialize();
                SwingUtilities.invokeLater(() -> {
                    setAvailableRoutines(routines);
                    setDebugActive(false);
                    setStatus("Connected to " + schema, false);
                });
            } catch (DbgException ex) {
                SwingUtilities.invokeLater(() -> showError("Connection failed: " + ex.getMessage()));
            }
        });
    }

    private static ConnectionProfile connectionProfile(JComboBox<DatabaseEngine> engine,
            JTextField host, JTextField port, JTextField user, JPasswordField password,
            JTextField database) {
        return new ConnectionProfile((DatabaseEngine) engine.getSelectedItem(), host.getText().trim(),
            Integer.parseInt(port.getText().trim()), user.getText().trim(),
            new String(password.getPassword()), database.getText().trim());
    }

    /**
     * Opens a private debugger/control session from a NetBeans DB Explorer
     * connection.  Reusing getJDBCConnection() would deadlock as soon as the SQL
     * editor executes a routine that pauses: that physical connection is then
     * occupied by CALL and cannot also poll or update _dbg_state.
     */
    private Connection openDedicatedControlConnection(DatabaseConnection dbConn)
            throws Exception {
        String url = dbConn.getDatabaseURL();
        Properties properties = dbConn.getConnectionProperties();
        return connectionService.connectUrl(url, dbConn.getDriverClass(), properties,
                                            dbConn.getUser(), dbConn.getPassword());
    }

    private void disconnect() {
        if (debugActive) {
            showError("Stop the active debug session before disconnecting.");
            return;
        }
        stopSession();
        try { if (ownsConnection && conn != null) conn.close(); } catch (SQLException ignored) {}
        conn = null; debugger = null; schema = null; deployment = null;
        ownsConnection = false;
        availableRoutines.clear();
        filteringRoutines = true;
        routineCombo.removeAllItems();
        routineCombo.getEditor().setItem("");
        filteringRoutines = false;
        currentRoutine = null; currentRoutineType = null;
        sourcePanel.setSource(null); logPanel.clear(); watchPanel.clearValues();
        watchPrev.clear(); watchChanged.clear(); hideBanner();
        setDebugActive(false);
        setStatus("Disconnected", false);
    }

    // ── Routine loading ───────────────────────────────────────────────────────

    private void loadRoutine() {
        if (debugger == null) { promptConnect(); return; }
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
                RoutineDetails loaded = debugger.loadRoutine(rName, rType);
                deployment = loaded.deployed ? debugger.loadDeployment(rName, rType) : null;
                SwingUtilities.invokeLater(() -> {
                    sourcePanel.setSource(loaded.ddl);
                    sourcePanel.setBreakpoints(loaded.breakpoints);
                    if (loaded.deployed) {
                        startSession(loaded.sessionId);
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
        if (debugger == null || currentRoutine == null) return;
        stopSession();
        logPanel.clear();
        watchPanel.clearValues();
        watchPrev.clear();
        watchChanged.clear();
        setDebugActive(false);
        btnDeploy.setEnabled(false);
        setStatus("Deploying…", false);

        final String routine     = currentRoutine;
        final String routineType = currentRoutineType;
        RequestProcessor.getDefault().post(() -> {
            try {
                DebugDeployment started = debugger.deploy(routine, routineType);
                deployment = started;

                SwingUtilities.invokeLater(() -> {
                    startSession(started.root.sessionId);
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

    private void stopDebugging() {
        if (debugger == null || currentRoutine == null) return;
        stopSession();
        btnStop.setEnabled(false);
        setStatus("Stopping…", false);

        final String routine          = currentRoutine;
        RequestProcessor.getDefault().post(() -> {
            try {
                String freshDdl = debugger.stop(deployment);
                deployment = null;
                SwingUtilities.invokeLater(() -> {
                    hideBanner();
                    sourcePanel.clearCurrentLine();
                    sourcePanel.setSource(freshDdl);
                    setDebugActive(false);
                    setStatus("Stopped debugging: " + routine, false);
                });
            } catch (DbgException ex) {
                SwingUtilities.invokeLater(() -> {
                    setDebugActive(true);
                    showError("Stop failed: " + ex.getMessage());
                });
            }
        });
    }

    private void resetAll() {
        if (debugger == null) return;
        int r = JOptionPane.showConfirmDialog(this,
            "<html>This will:<br>" +
            "• Restore <b>all</b> deployed routines to their original DDL<br>" +
            "• Drop all _dbg_* and _orig_* routines<br>" +
            "• Remove all debug infrastructure tables<br><br>" +
            "Continue?</html>",
            "Reset All Debug Changes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;

        stopSession();
        deployment = null;
        logPanel.clear();
        watchPanel.clearValues();
        watchPrev.clear();
        watchChanged.clear();
        hideBanner();
        sourcePanel.clearCurrentLine();
        setDebugActive(false);
        btnDeploy.setEnabled(false);
        setStatus("Resetting all debug changes…", false);

        RequestProcessor.getDefault().post(() -> {
            try {
                List<RoutineInfo> routines = debugger.reset();
                SwingUtilities.invokeLater(() -> {
                    setAvailableRoutines(routines);
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

    // ── TopComponent lifecycle ────────────────────────────────────────────────

    @Override
    public void componentClosed() {
        // When the window is closed (X button or programmatically), send 'continue'
        // to any blocked _dbg_checkpoint wait loops so they don't spin indefinitely.
        if (session != null && debugger != null) {
            stopSession();
            RequestProcessor.getDefault().post(() -> debugger.unblock(deployment));
        } else {
            stopSession();
        }
    }

    // ── Session management ────────────────────────────────────────────────────

    private void startSession(String sid) {
        stopSession();
        session = debugger.openSession(currentRoutine, sid,
            isChild ? null : deployment, this, SwingUtilities::invokeLater);
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
        try { debugger.continueExecution(session, deployment); }
        catch (DbgException ex) { showError("Continue failed: " + ex.getMessage()); return; }
        setPaused(false);
        setStatus("Resumed…", false);
    }

    /** Step Over — advance to next statement; callees run without pausing. */
    private void doStepOver() {
        if (session == null || !session.isPaused()) return;
        sourcePanel.clearCurrentLine();
        watchChanged.clear();
        watchPanel.clearChanged();
        try { debugger.stepOver(session, deployment); }
        catch (DbgException ex) { showError("Step Over failed: " + ex.getMessage()); return; }
        setPaused(false);
        setStatus("Stepping over…", false);
    }

    /** Step Into — advance to next statement; if a callee is entered it will pause. */
    private void doStepInto() {
        if (session == null || !session.isPaused()) return;
        sourcePanel.clearCurrentLine();
        watchChanged.clear();
        watchPanel.clearChanged();
        try { debugger.stepInto(session, deployment); }
        catch (DbgException ex) { showError("Step Into failed: " + ex.getMessage()); return; }
        setPaused(false);
        setStatus("Stepping into…", false);
    }

    /** Step Out — run callee to completion; the parent resumes at its next checkpoint. */
    private void doStepOut() {
        if (session == null || !session.isPaused()) return;
        sourcePanel.clearCurrentLine();
        watchChanged.clear();
        watchPanel.clearChanged();
        debugger.stepOut(session);
        setPaused(false);
        setStatus("Stepping out…", false);
    }

    /** Basic step — used by child window to advance within the callee. */
    private void doStep() {
        if (session == null || !session.isPaused()) return;
        sourcePanel.clearCurrentLine();
        watchChanged.clear();
        watchPanel.clearChanged();
        debugger.step(session);
        setPaused(false);
        setStatus("Stepping…", false);
    }

    private void clearLog() {
        if (session != null) session.clearLog();
        logPanel.clear();
    }

    private void setDebugActive(boolean active) {
        debugActive = active;
        if (!isChild) {
            btnDeploy.setEnabled(!active && debugger != null);
            btnStop.setEnabled(active);
            btnConnect.setVisible(debugger == null);
            btnDisconnect.setVisible(debugger != null);
            btnDisconnect.setEnabled(!active && debugger != null);
            routineCombo.setEnabled(!active && debugger != null);
        }
    }

    private void setPaused(boolean on) {
        btnCont.setEnabled(on);
        btnStep.setEnabled(on);
        if (!isChild) btnStepInto.setEnabled(on);
        else           btnStepOut.setEnabled(on);
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
    public void onCompleted() {
        setPaused(false);
        sourcePanel.clearCurrentLine();
        setStatus("Routine completed", false);
        if (isChild) {
            close();
            if (parentDebugger != null) parentDebugger.requestActive();
        }
    }

    @Override
    public void onError(String message) {
        LOG.warning("[dbg] " + message);
    }

    @Override
    public void onCalleeStarted(String routineName, String sessionId) {
        // Already on the EDT (dispatched by SwingUtilities::invokeLater in DebugSession.poll)
        DebuggerTopComponent child = createChildInstance(routineName, debugger, conn, schema, this);

        Mode m = WindowManager.getDefault().findMode("editor");
        if (m != null) m.dockInto(child);
        child.open();
        child.requestActive();
        child.setStatus("Loading " + routineName + "…", false);

        // Load source first, then start the session — this guarantees the doc is
        // populated before the first onPaused fires, so setCurrentLine finds content.
        RequestProcessor.getDefault().post(() -> {
            try {
                DeployedRoutine callee = debugger.loadDeployedRoutine(routineName);
                if (callee == null) throw new DbgException("No deployed callee found for " + routineName);
                SwingUtilities.invokeLater(() -> {
                    child.currentRoutineType = callee.type;
                    child.sourcePanel.setSource(callee.ddl);   // synchronous — doc ready now
                    child.sourcePanel.setBreakpoints(callee.breakpoints);
                    child.showBanner(routineName);
                    child.setDebugActive(true);
                    child.startSession(sessionId);           // first onPaused will find doc ready
                });
            } catch (DbgException ex) {
                SwingUtilities.invokeLater(() ->
                    child.showError("Failed to load callee source: " + ex.getMessage()));
            }
        });
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

}
