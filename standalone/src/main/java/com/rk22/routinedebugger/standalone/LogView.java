package com.rk22.routinedebugger.standalone;

import com.rk22.routinedebugger.core.LogEntry;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

/**
 * Collapsible variable log panel. Breakpoint rows are red, ENTRY rows green.
 */
public class LogView extends TitledPane {

    private static final String FONT      = "Consolas";
    private static final double FONT_SZ   = 12.0;
    private static final String CLR_BP_BG = "#FFEBEB";
    private static final String CLR_BP_FG = "#C0392B";
    private static final String CLR_EN_BG = "#EFFBF4";
    private static final String CLR_NAME  = "#0070C1";
    private static final String CLR_VALUE = "#A31515";
    private static final String CLR_DIM   = "#AAAAAA";

    private final ObservableList<LogEntry> rows = FXCollections.observableArrayList();
    private Runnable onClear;

    public LogView() {
        setText("Variable log");
        setExpanded(false);
        setAnimated(true);

        TableView<LogEntry> table = new TableView<>(rows);
        table.setStyle("-fx-font-family: '" + FONT + "'; -fx-font-size: " + FONT_SZ + "px;");
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(200);
        table.setPlaceholder(new Label(""));

        TableColumn<LogEntry, String> colTime  = col("Time",     62, e -> fmtTime(e.ts));
        TableColumn<LogEntry, String> colLabel = col("Label",    60, e -> e.isBreakpoint() ? "⏸ BP" : e.label);
        TableColumn<LogEntry, String> colVar   = col("Variable", 120, e -> e.isBreakpoint() ? e.varValue : e.varName);
        TableColumn<LogEntry, String> colVal   = col("Value",    200, e -> e.isBreakpoint() ? "" : (e.varValue == null ? "NULL" : e.varValue));

        for (TableColumn<LogEntry, String> c : List.of(colTime, colLabel, colVar, colVal))
            c.setCellFactory(col -> new LogCell(table.getColumns().indexOf(col)));

        table.getColumns().addAll(colTime, colLabel, colVar, colVal);

        Button clearBtn = new Button("Clear");
        clearBtn.setStyle("-fx-font-size: 11; -fx-padding: 2 6 2 6;");
        clearBtn.setOnAction(e -> { if (onClear != null) onClear.run(); });

        HBox toolbar = new HBox(clearBtn);
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        toolbar.setPadding(new Insets(3, 4, 3, 4));
        toolbar.setStyle("-fx-background-color: #F3F3F3; -fx-border-color: #DDDDDD; -fx-border-width: 0 0 1 0;");

        VBox content = new VBox(toolbar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        setContent(content);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setOnClear(Runnable r) { onClear = r; }

    public void append(LogEntry entry) {
        rows.add(entry);
    }

    public void clear() {
        rows.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String fmtTime(String ts) {
        return (ts != null && ts.length() >= 19) ? ts.substring(11, 19) : "";
    }

    private static TableColumn<LogEntry, String> col(String title, double pref,
                                                      java.util.function.Function<LogEntry, String> getter) {
        TableColumn<LogEntry, String> c = new TableColumn<>(title);
        c.setPrefWidth(pref);
        c.setCellValueFactory(p -> new SimpleStringProperty(getter.apply(p.getValue())));
        return c;
    }

    private class LogCell extends TableCell<LogEntry, String> {
        private final int colIdx;
        LogCell(int colIdx) { this.colIdx = colIdx; }

        @Override protected void updateItem(String v, boolean empty) {
            super.updateItem(v, empty);
            if (empty || v == null) { setText(null); setStyle(""); return; }
            LogEntry e = getTableView().getItems().get(getIndex());
            setText(v);
            setFont(Font.font(FONT, FONT_SZ));
            setPadding(new Insets(1, 4, 1, 4));

            if (e.isBreakpoint()) {
                setStyle("-fx-background-color: " + CLR_BP_BG + "; -fx-text-fill: " + CLR_BP_FG +
                         "; -fx-font-weight: bold;");
            } else if (e.isEntry()) {
                String fg = (colIdx <= 1) ? CLR_DIM : (colIdx == 2 ? CLR_NAME : CLR_VALUE);
                setStyle("-fx-background-color: " + CLR_EN_BG + "; -fx-text-fill: " + fg + ";");
            } else {
                String fg = (colIdx <= 1) ? CLR_DIM : (colIdx == 2 ? CLR_NAME : CLR_VALUE);
                setStyle("-fx-background-color: white; -fx-text-fill: " + fg + ";");
            }
        }
    }
}
