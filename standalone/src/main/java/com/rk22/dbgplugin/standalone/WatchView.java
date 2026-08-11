package com.rk22.dbgplugin.standalone;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.*;
import java.util.function.Consumer;

/**
 * Watch panel: variable names with their latest value.
 * Changed values are highlighted in amber.
 */
public class WatchView extends BorderPane {

    private static final String FONT        = "Consolas";
    private static final double FONT_SZ     = 12.0;
    private static final String CLR_CHANGED = "#FFF8E7";
    private static final String CLR_VAL_CHG = "#B45309";
    private static final String CLR_NAME    = "#0070C1";
    private static final String CLR_VALUE   = "#A31515";
    private static final String CLR_NULL    = "#BBBBBB";

    static final class WatchEntry {
        final String       name;
        String             value;
        boolean            changed;
        WatchEntry(String name) { this.name = name; }
    }

    private final ObservableList<WatchEntry> entries  = FXCollections.observableArrayList();
    private final Set<String>               changedNames = new HashSet<>();

    private Runnable          onToggleAll;
    private Consumer<String>  onAdd;
    private Consumer<String>  onRemove;

    private final CheckBox chkAll = new CheckBox("All");

    public WatchView() {
        // ── Title bar ──────────────────────────────────────────────────────
        Label title = new Label("Watch");
        title.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");
        chkAll.setStyle("-fx-font-size: 10;");
        chkAll.setTooltip(new Tooltip("Auto-watch every variable that appears in the log"));
        chkAll.selectedProperty().addListener((obs, o, n) -> {
            if (onToggleAll != null) onToggleAll.run();
        });
        HBox header = new HBox(6, title, chkAll);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 6, 4, 8));
        header.setStyle("-fx-background-color: #F3F3F3; -fx-border-color: #DDDDDD; -fx-border-width: 0 0 1 0;");
        setTop(header);

        // ── Table ──────────────────────────────────────────────────────────
        TableView<WatchEntry> table = new TableView<>(entries);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setStyle("-fx-font-family: '" + FONT + "'; -fx-font-size: " + FONT_SZ + "px;");
        table.setPlaceholder(new Label(""));

        TableColumn<WatchEntry, String> colName  = new TableColumn<>("Name");
        TableColumn<WatchEntry, String> colValue = new TableColumn<>("Value");
        TableColumn<WatchEntry, Void>   colDel   = new TableColumn<>("");

        colName .setPrefWidth(110); colName .setMaxWidth(160);
        colValue.setPrefWidth(200);
        colDel  .setPrefWidth(28);  colDel  .setMaxWidth(28);  colDel.setResizable(false);

        colName.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().name));
        colName.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setStyle(""); return; }
                WatchEntry e = getTableView().getItems().get(getIndex());
                setText(v);
                setFont(Font.font(FONT, FONT_SZ));
                setStyle("-fx-text-fill: " + CLR_NAME + "; -fx-background-color: " +
                         (e.changed ? CLR_CHANGED : "white") + "; -fx-padding: 1 4 1 4;");
            }
        });

        colValue.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().value));
        colValue.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty) { setText(null); setStyle(""); return; }
                WatchEntry e = getTableView().getItems().get(getIndex());
                if (e.value == null) {
                    setText("not yet seen");
                    setFont(Font.font(FONT, FontWeight.NORMAL, FONT_SZ));
                    setStyle("-fx-text-fill: " + CLR_NULL + "; -fx-font-style: italic;" +
                             " -fx-background-color: white; -fx-padding: 1 4 1 4;");
                } else {
                    setText(e.value);
                    setFont(Font.font(FONT, e.changed ? FontWeight.BOLD : FontWeight.NORMAL, FONT_SZ));
                    setStyle("-fx-text-fill: " + (e.changed ? CLR_VAL_CHG : CLR_VALUE) +
                             "; -fx-background-color: " + (e.changed ? CLR_CHANGED : "white") +
                             "; -fx-padding: 1 4 1 4;");
                }
            }
        });

        colDel.setCellFactory(col -> new TableCell<>() {
            private final Button btn = new Button("✕");
            { btn.setStyle("-fx-font-size: 9; -fx-text-fill: #BBBBBB; -fx-padding: 0 3 0 3;");
              btn.setOnAction(e -> {
                  WatchEntry entry = getTableView().getItems().get(getIndex());
                  removeVariable(entry.name);
                  if (onRemove != null) onRemove.accept(entry.name);
              }); }
            @Override protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                setGraphic(empty ? null : btn);
            }
        });

        table.getColumns().addAll(colName, colValue, colDel);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        ScrollPane scroll = new ScrollPane(table);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setStyle("-fx-background-color: transparent;");
        setCenter(scroll);

        // ── Input bar ──────────────────────────────────────────────────────
        TextField input = new TextField();
        input.setFont(Font.font(FONT, FONT_SZ));
        input.setPromptText("variable name…");
        Button addBtn = new Button("+");
        addBtn.setFont(Font.font(null, FontWeight.BOLD, 14));
        addBtn.setStyle("-fx-padding: 2 8 2 8;");

        Runnable doAdd = () -> {
            String name = input.getText().trim();
            if (name.isEmpty()) return;
            input.clear();
            addVariable(name);
            if (onAdd != null) onAdd.accept(name);
        };
        input.setOnAction(e -> doAdd.run());
        addBtn.setOnAction(e -> doAdd.run());

        HBox inputBar = new HBox(4, input, addBtn);
        HBox.setHgrow(input, Priority.ALWAYS);
        inputBar.setPadding(new Insets(4, 4, 4, 4));
        inputBar.setStyle("-fx-background-color: #FAFAFA; -fx-border-color: #EEEEEE; -fx-border-width: 1 0 0 0;");
        setBottom(inputBar);
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public void setOnToggleAll(Runnable r)         { onToggleAll = r; }
    public void setOnAdd(Consumer<String> c)       { onAdd = c; }
    public void setOnRemove(Consumer<String> c)    { onRemove = c; }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean isWatchAll() { return chkAll.isSelected(); }

    public void addVariable(String name) {
        if (name == null || name.isBlank()) return;
        if (entries.stream().anyMatch(e -> e.name.equals(name))) return;
        entries.add(new WatchEntry(name));
    }

    public void removeVariable(String name) {
        entries.removeIf(e -> e.name.equals(name));
        changedNames.remove(name);
    }

    public void updateValue(String name, String value, boolean isChanged) {
        entries.stream().filter(e -> e.name.equals(name)).findFirst().ifPresent(e -> {
            e.value   = value;
            e.changed = isChanged;
            if (isChanged) changedNames.add(name); else changedNames.remove(name);
            // force cell refresh
            int idx = entries.indexOf(e);
            entries.set(idx, e);
        });
    }

    public void clearChanged() {
        changedNames.clear();
        entries.forEach(e -> e.changed = false);
        for (int i = 0; i < entries.size(); i++) entries.set(i, entries.get(i));
    }

    public void clearValues() {
        changedNames.clear();
        entries.forEach(e -> { e.value = null; e.changed = false; });
        for (int i = 0; i < entries.size(); i++) entries.set(i, entries.get(i));
    }
}
