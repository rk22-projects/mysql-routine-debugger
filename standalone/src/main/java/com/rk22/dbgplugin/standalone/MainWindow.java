package com.rk22.dbgplugin.standalone;

import com.rk22.dbgplugin.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Main application window — equivalent to DebuggerTopComponent in the NB plugin.
 * Owns all DB state, orchestrates deploy/debug/stop, implements DebugEventListener.
 */
public class MainWindow implements DebugEventListener {

    private static final Logger LOG = Logger.getLogger(MainWindow.class.getName());

    // ── UI ────────────────────────────────────────────────────────────────────
    private final BorderPane root      = new BorderPane();
    private final ComboBox<RoutineInfo> routineCombo = new ComboBox<>();
    private final Button btnLoad    = btn("Load",          "#E0E0E0", "#333333");
    private final Button btnDeploy  = btn("▶ Debug",       "#276749", "white");
    private final Button btnStop    = btn("■ Stop",        "#C0392B", "white");
    private final Button btnCont    = btn("▶ Continue F5", "#0E639C", "white");
    private final Button btnStep    = btn("↓ Step  F8",   "#6B5CE7", "white");
    private final Label  statusBar  = new Label("Ready");
    private final Label  bannerLbl  = new Label();
    private final HBox   banner     = new HBox(bannerLbl);

    private final SourceView sourceView = new SourceView();
    private final WatchView  watchView  = new WatchView();
    private final LogView    logView    = new LogView();

    // ── DB state ──────────────────────────────────────────────────────────────
    private Connection    conn;
    private DbgConnection db;
    private String        schema;
    private DebugSession  session;
    private String        currentRoutine;
    private String        currentRoutineType;

    private final Map<String, String> watchPrev    = new HashMap<>();
    private final Set<String>         watchChanged = new HashSet<>();

    private final ExecutorService bgExec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dbg-bg");
        t.setDaemon(true);
        return t;
    });

    // ── Constructor ───────────────────────────────────────────────────────────

    public MainWindow() {
        buildLayout();
        wireActions();
    }

    public BorderPane getRoot() { return root; }

    /** Call after the scene is attached to the stage so accelerators resolve. */
    public void initScene(javafx.scene.Scene scene) {
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.F5),
            () -> { if (session != null && session.isPaused()) doContinue(); }
        );
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.F8),
            () -> { if (session != null && session.isPaused()) doStep(); }
        );
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private void buildLayout() {
        // Toolbar
        routineCombo.setPrefWidth(220);
        routineCombo.setStyle("-fx-font-size: 13;");

        ToolBar toolbar = new ToolBar(
            routineCombo, sep(),
            btnLoad, btnDeploy, btnStop, sep(),
            btnCont, btnStep,
            new Pane() {{ HBox.setHgrow(this, Priority.ALWAYS); }},
            overflowMenu()
        );
        toolbar.setStyle("-fx-background-color: #F3F3F3; -fx-border-color: #DDDDDD; -fx-border-width: 0 0 1 0;");

        btnDeploy.setDisable(true);
        btnStop  .setDisable(true);
        btnCont  .setDisable(true);
        btnStep  .setDisable(true);

        // Banner
        bannerLbl.setFont(Font.font(null, 12));
        bannerLbl.setStyle("-fx-text-fill: #1A7F37;");
        banner.setPadding(new Insets(4, 10, 4, 10));
        banner.setStyle("-fx-background-color: #EAFAF1; -fx-border-color: #27AE60; -fx-border-width: 0 0 2 0;");
        banner.setVisible(false);
        banner.setManaged(false);

        VBox top = new VBox(toolbar, banner);
        root.setTop(top);

        // Source view (left) + right panel
        VBox rightPanel = new VBox(watchView, logView);
        rightPanel.setPrefWidth(360);
        VBox.setVgrow(watchView, Priority.ALWAYS);

        SplitPane split = new SplitPane(sourceView, rightPanel);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.70);
        root.setCenter(split);

        // Status bar
        statusBar.setPadding(new Insets(3, 10, 3, 10));
        statusBar.setMaxWidth(Double.MAX_VALUE);
        statusBar.setFont(Font.font(null, 12));
        statusBar.setStyle("-fx-background-color: #007ACC; -fx-text-fill: white;");
        root.setBottom(statusBar);
    }

    private MenuButton overflowMenu() {
        MenuItem resetAll = new MenuItem("⚠  Reset all debug changes…");
        resetAll.setOnAction(e -> resetAll());
        MenuButton btn = new MenuButton("⋮", null, resetAll);
        btn.setStyle("-fx-font-size: 15; -fx-font-weight: bold; -fx-background-color: #E8E8E8;");
        return btn;
    }

    // ── Wire actions ──────────────────────────────────────────────────────────

    private void wireActions() {
        btnLoad  .setOnAction(e -> loadRoutine());
        btnDeploy.setOnAction(e -> deployDebug());
        btnStop  .setOnAction(e -> stopDebugging());
        btnCont  .setOnAction(e -> doContinue());
        btnStep  .setOnAction(e -> doStep());

        logView.setOnClear(this::clearLog);

        watchView.setOnAdd(name -> watchPrev.putIfAbsent(name, null));
        watchView.setOnRemove(name -> { watchPrev.remove(name); watchChanged.remove(name); });
        watchView.setOnToggleAll(() -> {
            if (!watchView.isWatchAll()) return;
            watchPrev.forEach((name, val) -> {
                watchView.addVariable(name);
                if (val != null) watchView.updateValue(name, val, watchChanged.contains(name));
            });
        });

        sourceView.setOnBreakpointToggle(label -> {
            if (currentRoutine == null || db == null) return;
            try { db.saveBreakpoints(currentRoutine, sourceView.getBreakpoints()); }
            catch (DbgException ex) { LOG.log(Level.WARNING, "bp save failed", ex); }
        });
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    private void promptConnect() {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Connect to MySQL or MariaDB");
        dlg.setHeaderText("Enter connection details");
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<DatabaseEngine> engineF = new ComboBox<>();
        engineF.getItems().setAll(DatabaseEngine.values());
        engineF.setValue(DatabaseEngine.MYSQL);
        TextField hostF = field("localhost");
        TextField portF = field("3306");
        TextField userF = field("");
        PasswordField passF = new PasswordField();
        TextField dbF = field("");

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(16, 16, 16, 16));
        grid.addRow(0, lbl("Database engine:"), engineF);
        grid.addRow(1, lbl("Host:"), hostF);
        grid.addRow(2, lbl("Port:"), portF);
        grid.addRow(3, lbl("User:"), userF);
        grid.addRow(4, lbl("Password:"), passF);
        grid.addRow(5, lbl("Database:"), dbF);
        dlg.getDialogPane().setContent(grid);

        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        try {
            DatabaseEngine engine = engineF.getValue();
            int port = Integer.parseInt(portF.getText().trim());
            conn   = engine.connect(hostF.getText().trim(), port, dbF.getText().trim(),
                                    userF.getText().trim(), passF.getText());
            conn.setAutoCommit(true);
            schema = dbF.getText().trim();
            db     = new DbgConnection(conn);
        } catch (SQLException ex) {
            showError("Connection failed: " + ex.getMessage());
            return;
        }

        setStatus("Connecting…");
        bgExec.submit(() -> {
            try {
                db.setupInfrastructure();
                List<RoutineInfo> routines = db.fetchRoutines(schema);
                Platform.runLater(() -> {
                    routineCombo.getItems().setAll(routines);
                    setDebugActive(false);
                    setStatus("Connected to " + schema);
                });
            } catch (DbgException ex) {
                Platform.runLater(() -> showError("Setup failed: " + ex.getMessage()));
            }
        });
    }

    // ── Load routine ──────────────────────────────────────────────────────────

    private void loadRoutine() {
        if (db == null) { promptConnect(); return; }
        RoutineInfo ri = routineCombo.getValue();
        if (ri == null) return;
        currentRoutine     = ri.name;
        currentRoutineType = ri.type;
        stopSession();
        setStatus("Loading " + ri.name + "…");

        final String rName = ri.name;
        final String rType = ri.type;
        bgExec.submit(() -> {
            try {
                String ddl      = db.loadOriginalDdl(rName);
                boolean deployed = ddl != null;
                if (!deployed) ddl = db.fetchRoutineDdl(rName, rType);
                final String finalDdl = ddl;
                List<String> bps = db.loadBreakpoints(rName);
                String sid = deployed ? db.loadSessionId(rName) : null;
                Platform.runLater(() -> {
                    sourceView.setSource(finalDdl);
                    sourceView.setBreakpoints(bps);
                    if (deployed) {
                        startSession(sid != null ? sid : newSessionId());
                        showBanner(rName);
                        setDebugActive(true);
                    } else {
                        hideBanner();
                        setDebugActive(false);
                    }
                    setStatus("Loaded: " + rName);
                });
            } catch (DbgException ex) {
                Platform.runLater(() -> showError("Load failed: " + ex.getMessage()));
            }
        });
    }

    // ── Deploy / Stop ─────────────────────────────────────────────────────────

    private void deployDebug() {
        if (db == null || currentRoutine == null) return;
        stopSession();
        logView.clear();
        watchView.clearValues();
        watchPrev.clear();
        watchChanged.clear();
        String sid = newSessionId();
        setDebugActive(false);
        btnDeploy.setDisable(true);
        setStatus("Deploying…");

        final String routine     = currentRoutine;
        final String routineType = currentRoutineType;
        bgExec.submit(() -> {
            try {
                String originalDdl  = db.fetchRoutineDdl(routine, routineType);
                String origCopy     = InstrumentEngine.buildOrigCopy(routine, originalDdl);
                String instrumented = InstrumentEngine.instrumentAuto(routine, originalDdl, sid, conn, schema);

                List<String> pNames = new ArrayList<>(), pTypes = new ArrayList<>(), pModes = new ArrayList<>();
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
                String returnType    = fetchReturnType(routine, routineType);
                boolean deterministic = fetchDeterministic(routine);
                String proxy = InstrumentEngine.buildProxy(
                    routine, routineType, pNames, pTypes, pModes, returnType, deterministic, sid);

                db.deployDebug(routine, routineType, originalDdl, origCopy, instrumented, proxy, sid);
                db.initSessionState(sid, routine);

                Platform.runLater(() -> {
                    startSession(sid);
                    showBanner(routine);
                    setDebugActive(true);
                    setStatus("Debug active — call " + routine + "(…) in your SQL client");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setDebugActive(false);
                    showError("Deploy failed: " + ex.getMessage());
                });
            }
        });
    }

    private void stopDebugging() {
        if (db == null || currentRoutine == null) return;
        stopSession();
        btnStop.setDisable(true);
        setStatus("Stopping…");

        final String routine     = currentRoutine;
        final String routineType = currentRoutineType;
        bgExec.submit(() -> {
            try {
                String sid = db.loadSessionId(routine);
                if (sid != null) {
                    try { db.updateState(sid, "continue"); } catch (DbgException ignored) {}
                }
                String origDdl = db.loadOriginalDdl(routine);
                if (origDdl == null) {
                    Platform.runLater(() -> {
                        setDebugActive(true);
                        showError("No saved original found.");
                    });
                    return;
                }
                db.restoreOriginal(routine, routineType, origDdl);
                String freshDdl = db.fetchRoutineDdl(routine, routineType);
                Platform.runLater(() -> {
                    hideBanner();
                    sourceView.clearCurrentLine();
                    sourceView.setSource(freshDdl);
                    sourceView.setBreakpoints(List.of());
                    setDebugActive(false);
                    setStatus("Stopped debugging: " + routine);
                });
            } catch (DbgException ex) {
                Platform.runLater(() -> {
                    setDebugActive(true);
                    showError("Stop failed: " + ex.getMessage());
                });
            }
        });
    }

    private void resetAll() {
        if (db == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "This will restore ALL deployed routines to their original DDL,\n" +
            "drop all _dbg_* and _orig_* routines, and remove debug tables.\n\nContinue?",
            ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Reset All Debug Changes");
        confirm.setHeaderText("Reset All Debug Changes");
        if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;

        stopSession();
        logView.clear();
        watchView.clearValues();
        watchPrev.clear();
        watchChanged.clear();
        hideBanner();
        sourceView.clearCurrentLine();
        setDebugActive(false);
        btnDeploy.setDisable(true);
        setStatus("Resetting all debug changes…");

        final String currentSchema = schema;
        bgExec.submit(() -> {
            try {
                db.restoreAll(currentSchema);
                db.setupInfrastructure();
                List<RoutineInfo> routines = db.fetchRoutines(currentSchema);
                Platform.runLater(() -> {
                    routineCombo.getItems().setAll(routines);
                    currentRoutine     = null;
                    currentRoutineType = null;
                    sourceView.setSource(null);
                    setDebugActive(false);
                    setStatus("All debug changes reverted.");
                });
            } catch (DbgException ex) {
                Platform.runLater(() -> {
                    setDebugActive(false);
                    showError("Reset failed: " + ex.getMessage());
                });
            }
        });
    }

    // ── Session ───────────────────────────────────────────────────────────────

    private void startSession(String sid) {
        stopSession();
        session = new DebugSession(sid, currentRoutine, db);
        session.start(this, Platform::runLater);
    }

    private void stopSession() {
        if (session != null) { session.stop(); session = null; }
        setPaused(false);
    }

    // ── Execution control ─────────────────────────────────────────────────────

    private void doContinue() {
        if (session == null || !session.isPaused()) return;
        sourceView.clearCurrentLine();
        watchChanged.clear();
        watchView.clearChanged();
        session.doContinue();
        setPaused(false);
        setStatus("Resumed…");
    }

    private void doStep() {
        if (session == null || !session.isPaused()) return;
        sourceView.clearCurrentLine();
        watchChanged.clear();
        watchView.clearChanged();
        session.doStep();
        setPaused(false);
        setStatus("Stepping…");
    }

    private void clearLog() {
        if (session != null) session.clearLog();
        logView.clear();
        watchView.clearValues();
        watchPrev.clear();
        watchChanged.clear();
        setPaused(false);
        setStatus("Log cleared");
    }

    // ── DebugEventListener ────────────────────────────────────────────────────

    @Override
    public void onLogEntries(List<LogEntry> entries) {
        for (LogEntry e : entries) {
            logView.append(e);
            if (!e.isBreakpoint()) {
                String name    = e.varName;
                if (watchView.isWatchAll()) watchView.addVariable(name);
                String prev    = watchPrev.get(name);
                boolean changed = prev != null && !Objects.equals(prev, e.varValue);
                watchPrev.put(name, e.varValue);
                if (changed) watchChanged.add(name);
                watchView.updateValue(name, e.varValue, watchChanged.contains(name));
            }
        }
    }

    @Override
    public void onPaused(String label, int lineNumber) {
        setPaused(true);
        if (lineNumber > 0) sourceView.setCurrentLine(lineNumber);
        setStatus("⏸  Paused at line " + (lineNumber > 0 ? lineNumber : label), true);
    }

    @Override
    public void onResumed() {
        setPaused(false);
        setStatus("Running…");
    }

    @Override
    public void onError(String message) {
        LOG.warning("[dbg] " + message);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void onClose() {
        stopSession();
        bgExec.shutdownNow();
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void setDebugActive(boolean active) {
        btnDeploy.setDisable(active || db == null);
        btnStop  .setDisable(!active);
    }

    private void setPaused(boolean on) {
        btnCont.setDisable(!on);
        btnStep.setDisable(!on);
        statusBar.setStyle("-fx-background-color: " + (on ? "#C0392B" : "#007ACC") + "; -fx-text-fill: white;");
    }

    private void setStatus(String msg) {
        statusBar.setText(msg);
    }

    private void setStatus(String msg, boolean paused) {
        statusBar.setText(msg);
        statusBar.setStyle("-fx-background-color: " + (paused ? "#C0392B" : "#007ACC") + "; -fx-text-fill: white;");
    }

    private void showBanner(String name) {
        bannerLbl.setText("▶ Debug active — call " + name + "(…) normally in your SQL client");
        banner.setVisible(true);
        banner.setManaged(true);
    }

    private void hideBanner() {
        banner.setVisible(false);
        banner.setManaged(false);
    }

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
        setStatus(msg);
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private String fetchReturnType(String routine, String type) throws SQLException {
        if (!"FUNCTION".equalsIgnoreCase(type)) return null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DTD_IDENTIFIER FROM information_schema.ROUTINES " +
                "WHERE ROUTINE_SCHEMA=? AND ROUTINE_NAME=?")) {
            ps.setString(1, schema); ps.setString(2, routine);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : "VARCHAR(255)";
        }
    }

    private boolean fetchDeterministic(String routine) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT IS_DETERMINISTIC FROM information_schema.ROUTINES " +
                "WHERE ROUTINE_SCHEMA=? AND ROUTINE_NAME=?")) {
            ps.setString(1, schema); ps.setString(2, routine);
            ResultSet rs = ps.executeQuery();
            return rs.next() && "YES".equals(rs.getString(1));
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    private static Button btn(String text, String bg, String fg) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: " + fg +
                   "; -fx-font-size: 13; -fx-cursor: hand; -fx-border-width: 0;");
        return b;
    }

    private static Separator sep() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.setPadding(new Insets(0, 2, 0, 2));
        return s;
    }

    private static Label lbl(String text) {
        Label l = new Label(text);
        l.setMinWidth(70);
        return l;
    }

    private static TextField field(String def) {
        TextField f = new TextField(def);
        f.setPrefWidth(180);
        return f;
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }
}
