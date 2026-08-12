package com.rk22.routinedebugger.standalone;

import com.rk22.routinedebugger.core.*;
import com.rk22.routinedebugger.core.database.DatabaseEngine;
import com.rk22.routinedebugger.core.session.DebugSession;
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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Main application window — equivalent to DebuggerTopComponent in the NB plugin.
 * Thin JavaFX adapter around the shared debugger core.
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
    private DebuggerService debugger;
    private String        schema;
    private DebugSession  session;
    private DebugDeployment deployment;
    private String        currentRoutine;
    private String        currentRoutineType;
    private boolean       debugActive;
    private final boolean isChild;
    private MainWindow    parentDebugger;
    private Stage         stage;
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
    public void promptInitialConnect() { if (!isChild && debugger == null) promptConnect(); }

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
            if (currentRoutine == null || debugger == null) return;
            try { debugger.saveBreakpoints(currentRoutine, sourceView.getBreakpoints()); }
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
            debugger = new DebuggerService(conn, schema);
        } catch (SQLException ex) {
            showError("Connection failed: " + ex.getMessage());
            return;
        }

        setStatus("Connecting…");
        bgExec.submit(() -> {
            try {
                List<RoutineInfo> routines = debugger.initialize();
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
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        conn = null; debugger = null; schema = null; deployment = null;
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
        if (debugger == null) { promptConnect(); return; }
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
                RoutineDetails loaded = debugger.loadRoutine(rName, rType);
                if (loaded.deployed && loaded.sessionId != null) {
                    deployment = new DebugDeployment(new DeployedRoutine(
                        rName, rType, loaded.sessionId, loaded.ddl, loaded.breakpoints), List.of());
                } else {
                    deployment = null;
                }
                Platform.runLater(() -> {
                    sourceView.setSource(loaded.ddl);
                    sourceView.setBreakpoints(loaded.breakpoints);
                    if (loaded.deployed) {
                        startSession(loaded.sessionId);
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
        if (debugger == null || currentRoutine == null) return;
        stopSession();
        logView.clear();
        watchView.clearValues();
        watchPrev.clear();
        watchChanged.clear();
        setDebugActive(false);
        btnDeploy.setDisable(true);
        setStatus("Deploying…");

        final String routine     = currentRoutine;
        final String routineType = currentRoutineType;
        bgExec.submit(() -> {
            try {
                DebugDeployment started = debugger.deploy(routine, routineType);
                deployment = started;

                Platform.runLater(() -> {
                    startSession(started.root.sessionId);
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
        if (debugger == null || currentRoutine == null) return;
        stopSession();
        btnStop.setDisable(true);
        setStatus("Stopping…");

        final String routine     = currentRoutine;
        bgExec.submit(() -> {
            try {
                String freshDdl = debugger.stop(deployment);
                deployment = null;
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
        if (debugger == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "This will restore ALL deployed routines to their original DDL,\n" +
            "drop all _dbg_* and _orig_* routines, and remove debug tables.\n\nContinue?",
            ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Reset All Debug Changes");
        confirm.setHeaderText("Reset All Debug Changes");
        if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;

        stopSession();
        deployment = null;
        logView.clear();
        watchView.clearValues();
        watchPrev.clear();
        watchChanged.clear();
        hideBanner();
        sourceView.clearCurrentLine();
        setDebugActive(false);
        btnDeploy.setDisable(true);
        setStatus("Resetting all debug changes…");

        bgExec.submit(() -> {
            try {
                List<RoutineInfo> routines = debugger.reset();
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
        session = debugger.openSession(currentRoutine, sid, this, Platform::runLater);
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
        if (isChild) debugger.stepOut(session);
        else debugger.continueExecution(session, deployment);
        setPaused(false);
        setStatus("Resumed…");
    }

    private void doStep() {
        if (session == null || !session.isPaused()) return;
        sourceView.clearCurrentLine();
        watchChanged.clear();
        watchView.clearChanged();
        debugger.step(session);
        setPaused(false);
        setStatus("Stepping…");
    }

    private void doStepOver() {
        if (session == null || !session.isPaused()) return;
        sourceView.clearCurrentLine(); watchChanged.clear(); watchView.clearChanged();
        debugger.stepOver(session, deployment); setPaused(false); setStatus("Stepping over…");
    }

    private void doStepInto() {
        if (session == null || !session.isPaused()) return;
        sourceView.clearCurrentLine(); watchChanged.clear(); watchView.clearChanged();
        debugger.stepInto(session, deployment); setPaused(false); setStatus("Stepping into…");
    }

    private void doStepOut() {
        if (session == null || !session.isPaused()) return;
        sourceView.clearCurrentLine(); watchChanged.clear(); watchView.clearChanged();
        debugger.stepOut(session); setPaused(false); setStatus("Stepping out…");
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
        child.conn = conn; child.debugger = debugger; child.schema = schema;
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
                DeployedRoutine callee = deployment == null ? null : deployment.callees.stream()
                    .filter(item -> item.name.equals(routineName)).findFirst().orElse(null);
                if (callee == null) throw new DbgException("No deployed callee found for " + routineName);
                Platform.runLater(() -> {
                    child.currentRoutineType = callee.type;
                    child.sourceView.setSource(callee.ddl);
                    child.sourceView.setBreakpoints(callee.breakpoints);
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
        if (session != null && debugger != null)
            try { debugger.updateSessionState(session.sessionId, "continue"); } catch (DbgException ignored) {}
        if (!isChild && debugger != null) debugger.unblock(deployment);
        sourceView.refreshValues();
        stopSession();
        bgExec.shutdownNow();
        if (!isChild) try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void setDebugActive(boolean active) {
        debugActive = active;
        btnDeploy.setDisable(active || debugger == null);
        btnStop  .setDisable(!active);
        if (!isChild) {
            btnConnect.setVisible(debugger == null); btnConnect.setManaged(debugger == null);
            btnDisconnect.setVisible(debugger != null); btnDisconnect.setManaged(debugger != null);
            btnDisconnect.setDisable(active || debugger == null);
            routineCombo.setDisable(active || debugger == null);
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

}
