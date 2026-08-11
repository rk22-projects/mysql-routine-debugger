package com.rk22.dbgplugin.standalone;

import com.rk22.dbgplugin.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
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
    private final Button btnConnect    = btn("Connect",       "#E0E0E0", "#333333");
    private final Button btnDisconnect = btn("Disconnect",    "#E0E0E0", "#333333");
    private final Button btnDeploy  = btn("▶ Debug",       "#276749", "white");
    private final Button btnStop    = btn("■ Stop",        "#C0392B", "white");
    private final Button btnCont    = btn("▶ Continue F5", "#0E639C", "white");
    private final Button btnStep    = btn("↓ Step Over  F8", "#6B5CE7", "white");
    private final Button btnStepInto = btn("↘ Step Into  F7", "#8A6BF5", "white");
    private final Button btnStepOut  = btn("↑ Step Out  Ctrl+F7", "#4A6B8A", "white");
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
    private boolean       debugActive;
    private final boolean isChild;
    private MainWindow    parentDebugger;
    private Stage         stage;
    private final List<String[]> deployedCallees = new ArrayList<>();
    private final List<RoutineInfo> availableRoutines = new ArrayList<>();
    private boolean filteringRoutines;

    private final Map<String, String> watchPrev    = new HashMap<>();
    private final Set<String>         watchChanged = new HashSet<>();

    private final ExecutorService bgExec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dbg-bg");
        t.setDaemon(true);
        return t;
    });

    // ── Constructor ───────────────────────────────────────────────────────────

    public MainWindow() {
        this(false);
    }

    private MainWindow(boolean isChild) {
        this.isChild = isChild;
        buildLayout();
        wireActions();
    }

    public BorderPane getRoot() { return root; }
    public void setStage(Stage stage) { this.stage = stage; }
    public void promptInitialConnect() { if (!isChild && db == null) promptConnect(); }

    /** Call after the scene is attached to the stage so accelerators resolve. */
    public void initScene(javafx.scene.Scene scene) {
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.F5),
            () -> { if (session != null && session.isPaused()) doContinue(); }
        );
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.F8),
            () -> { if (session != null && session.isPaused()) { if (isChild) doStep(); else doStepOver(); } }
        );
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F7),
            () -> { if (!isChild && session != null && session.isPaused()) doStepInto(); });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F7, KeyCombination.CONTROL_DOWN),
            () -> { if (isChild && session != null && session.isPaused()) doStepOut(); });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F9), sourceView::toggleSelectedBreakpoint);
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private void buildLayout() {
        // Toolbar
        routineCombo.setPrefWidth(260);
        routineCombo.setEditable(true);
        routineCombo.setPromptText("Search or select a routine…");
        routineCombo.setStyle("-fx-font-size: 13;");

        ToolBar toolbar;
        if (isChild) {
            toolbar = new ToolBar(btnCont, btnStep, btnStepOut);
        } else {
            toolbar = new ToolBar(
                btnConnect, btnDisconnect, sep(), routineCombo,
                btnDeploy, btnStop, sep(), btnCont, btnStep, btnStepInto,
                new Pane() {{ HBox.setHgrow(this, Priority.ALWAYS); }}, overflowMenu());
        }
        toolbar.setStyle("-fx-background-color: #F3F3F3; -fx-border-color: #DDDDDD; -fx-border-width: 0 0 1 0;");

        btnDeploy.setDisable(true);
        btnStop  .setDisable(true);
        btnCont  .setDisable(true);
        btnStep  .setDisable(true);
        btnStepInto.setDisable(true);
        btnStepOut.setDisable(true);
        btnDisconnect.setVisible(false);
        btnDisconnect.setManaged(false);
        routineCombo.setDisable(true);

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
        btnConnect.setOnAction(e -> promptConnect());
        btnDisconnect.setOnAction(e -> disconnect());
        btnDeploy.setOnAction(e -> deployDebug());
        btnStop  .setOnAction(e -> stopDebugging());
        btnCont  .setOnAction(e -> doContinue());
        btnStep  .setOnAction(e -> { if (isChild) doStep(); else doStepOver(); });
        btnStepInto.setOnAction(e -> doStepInto());
        btnStepOut.setOnAction(e -> doStepOut());

        if (!isChild) installRoutineSearch();

        logView.setOnClear(this::clearLog);

        watchView.setOnAdd(name -> {
            watchPrev.putIfAbsent(name, null);
            if (watchPrev.get(name) != null) watchView.updateValue(name, watchPrev.get(name), false);
        });
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
        sourceView.setOnAddWatch(name -> {
            watchView.addVariable(name);
            watchPrev.putIfAbsent(name, null);
            if (watchPrev.get(name) != null) watchView.updateValue(name, watchPrev.get(name), false);
        });
        sourceView.setVarValueLookup(watchPrev::get);
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    private void installRoutineSearch() {
        routineCombo.getEditor().textProperty().addListener((obs, old, text) -> {
            if (!filteringRoutines) Platform.runLater(() -> filterRoutineChoices(text));
        });
        routineCombo.valueProperty().addListener((obs, old, selected) -> {
            if (!filteringRoutines && selected != null && !debugActive &&
                (!selected.name.equals(currentRoutine) || sourceView.isEmpty())) loadRoutine();
        });
    }

    private void setAvailableRoutines(List<RoutineInfo> routines) {
        availableRoutines.clear();
        availableRoutines.addAll(routines);
        filteringRoutines = true;
        routineCombo.getItems().setAll(routines);
        routineCombo.setValue(null);
        routineCombo.getEditor().clear();
        filteringRoutines = false;
        currentRoutine = null;
        currentRoutineType = null;
    }

    private void filterRoutineChoices(String text) {
        if (filteringRoutines || routineCombo.isDisabled()) return;
        String query = text == null ? "" : text;
        String needle = query.toLowerCase(Locale.ROOT);
        filteringRoutines = true;
        routineCombo.setItems(FXCollections.observableArrayList(availableRoutines.stream()
            .filter(r -> needle.isBlank() || r.name.toLowerCase(Locale.ROOT).contains(needle) ||
                         r.type.toLowerCase(Locale.ROOT).contains(needle))
            .toList()));
        routineCombo.getEditor().setText(query);
        routineCombo.getEditor().positionCaret(query.length());
        filteringRoutines = false;
        if (routineCombo.isFocused() && !routineCombo.getItems().isEmpty()) routineCombo.show();
    }

    private void promptConnect() {
        if (debugActive) { showError("Stop the active debug session before changing the connection."); return; }
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
                    setAvailableRoutines(routines);
                    setDebugActive(false);
                    setStatus("Connected to " + schema);
                });
            } catch (DbgException ex) {
                Platform.runLater(() -> showError("Setup failed: " + ex.getMessage()));
            }
        });
    }

    private void disconnect() {
        if (debugActive) { showError("Stop the active debug session before disconnecting."); return; }
        stopSession();
        deployedCallees.clear();
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        conn = null; db = null; schema = null;
        availableRoutines.clear();
        filteringRoutines = true;
        routineCombo.getItems().clear(); routineCombo.setValue(null); routineCombo.getEditor().clear();
        filteringRoutines = false;
        currentRoutine = null; currentRoutineType = null;
        sourceView.setSource(null); logView.clear(); watchView.clearValues();
        watchPrev.clear(); watchChanged.clear(); hideBanner();
        setDebugActive(false);
        setStatus("Disconnected");
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

                Set<String> calleeNames = InstrumentEngine.findCallees(originalDdl);
                List<String[]> newCallees = new ArrayList<>();
                for (String callee : calleeNames) {
                    if (db.isDeployed(callee)) continue;
                    String calleeType = findRoutineType(callee);
                    if (calleeType == null) continue;
                    String calleeSid = newSessionId();
                    deployRoutineToDb(callee, calleeType, db.fetchRoutineDdl(callee, calleeType), calleeSid, "running");
                    newCallees.add(new String[]{callee, calleeType, calleeSid});
                }

                Platform.runLater(() -> {
                    startSession(sid);
                    deployedCallees.addAll(newCallees);
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

    private void deployRoutineToDb(String routine, String routineType, String originalDdl,
                                   String sid, String initialStatus) throws Exception {
        String origCopy = InstrumentEngine.buildOrigCopy(routine, originalDdl);
        String instrumented = InstrumentEngine.instrumentAuto(routine, originalDdl, sid, conn, schema);
        List<String> names = new ArrayList<>(), types = new ArrayList<>(), modes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT PARAMETER_NAME, DTD_IDENTIFIER, PARAMETER_MODE FROM information_schema.PARAMETERS " +
                "WHERE SPECIFIC_SCHEMA=? AND SPECIFIC_NAME=? AND ORDINAL_POSITION>0 ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, schema); ps.setString(2, routine);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                names.add(rs.getString(1)); types.add(rs.getString(2));
                modes.add(rs.getString(3) == null ? "IN" : rs.getString(3));
            }
        }
        String proxy = InstrumentEngine.buildProxy(routine, routineType, names, types, modes,
            fetchReturnType(routine, routineType), fetchDeterministic(routine), sid);
        db.deployDebug(routine, routineType, originalDdl, origCopy, instrumented, proxy, sid);
        db.initSessionState(sid, routine, initialStatus);
    }

    private String findRoutineType(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ROUTINE_TYPE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=? AND ROUTINE_NAME=?")) {
            ps.setString(1, schema); ps.setString(2, name);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void stopDebugging() {
        if (db == null || currentRoutine == null) return;
        stopSession();
        btnStop.setDisable(true);
        setStatus("Stopping…");

        final String routine     = currentRoutine;
        final String routineType = currentRoutineType;
        final List<String[]> callees = new ArrayList<>(deployedCallees);
        deployedCallees.clear();
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
                for (String[] callee : callees) {
                    try {
                        db.updateState(callee[2], "continue");
                        String calleeDdl = db.loadOriginalDdl(callee[0]);
                        if (calleeDdl != null) db.restoreOriginal(callee[0], callee[1], calleeDdl);
                    } catch (DbgException ignored) {}
                }
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
        deployedCallees.clear();
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
                    setAvailableRoutines(routines);
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
        if (!isChild) setCalleesStatus("running");
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

    private void doStepOver() {
        if (session == null || !session.isPaused()) return;
        sourceView.clearCurrentLine(); watchChanged.clear(); watchView.clearChanged();
        setCalleesStatus("running");
        session.doStep(); setPaused(false); setStatus("Stepping over…");
    }

    private void doStepInto() {
        if (session == null || !session.isPaused()) return;
        sourceView.clearCurrentLine(); watchChanged.clear(); watchView.clearChanged();
        setCalleesStatus("step");
        for (String[] callee : deployedCallees) session.registerChildSession(callee[0], callee[2]);
        session.doStep(); setPaused(false); setStatus("Stepping into…");
    }

    private void doStepOut() {
        if (session == null || !session.isPaused()) return;
        sourceView.clearCurrentLine(); watchChanged.clear(); watchView.clearChanged();
        session.doContinue(); setPaused(false); setStatus("Stepping out…");
    }

    private void setCalleesStatus(String status) {
        for (String[] callee : deployedCallees) {
            try { db.initSessionState(callee[2], callee[0], status); }
            catch (DbgException ignored) {}
        }
    }

    private void clearLog() {
        if (session != null) session.clearLog();
        logView.clear();
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
    public void onCompleted() {
        setPaused(false);
        sourceView.clearCurrentLine();
        setStatus("Routine completed");
        if (isChild && stage != null) {
            onClose();
            stage.close();
            if (parentDebugger != null && parentDebugger.stage != null) parentDebugger.stage.requestFocus();
        }
    }

    @Override
    public void onCalleeStarted(String routineName, String sessionId) {
        MainWindow child = new MainWindow(true);
        child.parentDebugger = this;
        child.conn = conn; child.db = db; child.schema = schema;
        child.currentRoutine = routineName;
        Stage childStage = new Stage();
        childStage.setTitle("MySQL Routine Debugger — " + routineName);
        if (stage != null) childStage.initOwner(stage);
        javafx.scene.Scene scene = new javafx.scene.Scene(child.getRoot(), 1080, 700);
        childStage.setScene(scene); child.setStage(childStage); child.initScene(scene);
        childStage.setOnCloseRequest(e -> child.onClose());
        childStage.show();
        child.setStatus("Loading " + routineName + "…");
        bgExec.submit(() -> {
            try {
                String type = db.loadOriginalType(routineName);
                String ddl = db.loadOriginalDdl(routineName);
                if (ddl == null && type != null) ddl = db.fetchRoutineDdl(routineName, type);
                List<String> bps = db.loadBreakpoints(routineName);
                String finalDdl = ddl, finalType = type;
                Platform.runLater(() -> {
                    child.currentRoutineType = finalType;
                    child.sourceView.setSource(finalDdl);
                    child.sourceView.setBreakpoints(bps);
                    child.startSession(sessionId);
                    child.setDebugActive(true);
                });
            } catch (DbgException ex) {
                Platform.runLater(() -> child.showError("Failed to load callee: " + ex.getMessage()));
            }
        });
    }

    @Override
    public void onError(String message) {
        LOG.warning("[dbg] " + message);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void onClose() {
        if (session != null && db != null) {
            try { db.updateState(session.sessionId, "continue"); } catch (DbgException ignored) {}
        }
        if (!isChild && db != null) {
            for (String[] callee : deployedCallees) {
                try { db.updateState(callee[2], "continue"); } catch (DbgException ignored) {}
            }
        }
        sourceView.refreshValues();
        stopSession();
        bgExec.shutdownNow();
        if (!isChild) try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void setDebugActive(boolean active) {
        debugActive = active;
        btnDeploy.setDisable(active || db == null);
        btnStop  .setDisable(!active);
        if (!isChild) {
            btnConnect.setVisible(db == null); btnConnect.setManaged(db == null);
            btnDisconnect.setVisible(db != null); btnDisconnect.setManaged(db != null);
            btnDisconnect.setDisable(active || db == null);
            routineCombo.setDisable(active || db == null);
        }
    }

    private void setPaused(boolean on) {
        btnCont.setDisable(!on);
        btnStep.setDisable(!on);
        btnStepInto.setDisable(!on || isChild);
        btnStepOut.setDisable(!on || !isChild);
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
