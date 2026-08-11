package com.rk22.dbgplugin.standalone;

import com.rk22.dbgplugin.InstrumentEngine;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.*;
import java.util.function.Consumer;

/**
 * SQL source viewer: line numbers, clickable breakpoint gutter, current-line highlight.
 */
public class SourceView extends BorderPane {

    private static final String FONT      = "Consolas";
    private static final double FONT_SZ   = 12.5;
    private static final String BG_CUR    = "#CCEDCC";
    private static final String BG_GUTTER_CUR = "#A8D5A8";

    private String[]          lines       = new String[0];
    private int               currentLine = -1;             // 1-based, -1 = none
    private final Set<String> breakpoints = new LinkedHashSet<>();
    private Consumer<String>  onBpToggle;

    private final ListView<Integer> list = new ListView<>();

    public SourceView() {
        list.setCellFactory(lv -> new SourceCell());
        list.setStyle("-fx-background-color: white; -fx-border-color: transparent;");
        list.setSelectionModel(new NoSelectionModel<>());
        setCenter(list);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setOnBreakpointToggle(Consumer<String> h) { this.onBpToggle = h; }

    public void setSource(String ddl) {
        lines = (ddl == null || ddl.isEmpty()) ? new String[0] : ddl.split("\n", -1);
        breakpoints.clear();
        currentLine = -1;
        rebuildItems();
    }

    public void setBreakpoints(Collection<String> labels) {
        breakpoints.clear();
        breakpoints.addAll(labels);
        list.refresh();
    }

    public Set<String> getBreakpoints() { return Collections.unmodifiableSet(breakpoints); }

    public void setCurrentLine(int lineNumber) {
        currentLine = lineNumber;
        list.refresh();
        if (lineNumber >= 1 && lineNumber <= lines.length)
            list.scrollTo(Math.max(0, lineNumber - 5));
    }

    public void clearCurrentLine() {
        currentLine = -1;
        list.refresh();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void rebuildItems() {
        List<Integer> items = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) items.add(i);
        list.getItems().setAll(items);
    }

    private void toggleBreakpoint(int lineNumber) {
        String label = "L" + lineNumber;
        if (breakpoints.contains(label)) breakpoints.remove(label);
        else                             breakpoints.add(label);
        list.refresh();
        if (onBpToggle != null) onBpToggle.accept(label);
    }

    // ── Cell ──────────────────────────────────────────────────────────────────

    private class SourceCell extends ListCell<Integer> {

        private final Label  lineNumLbl = new Label();
        private final Circle bpDot      = new Circle(5);
        private final HBox   gutter;
        private final Label  codeLbl    = new Label();
        private final HBox   row;

        SourceCell() {
            lineNumLbl.setPrefWidth(34);
            lineNumLbl.setAlignment(Pos.CENTER_RIGHT);
            lineNumLbl.setFont(Font.font(FONT, FONT_SZ));
            lineNumLbl.setStyle("-fx-text-fill: #888;");

            bpDot.setFill(Color.TRANSPARENT);
            bpDot.setStroke(Color.TRANSPARENT);

            gutter = new HBox(4, lineNumLbl, bpDot);
            gutter.setAlignment(Pos.CENTER_LEFT);
            gutter.setPadding(new Insets(0, 6, 0, 4));
            gutter.setPrefWidth(58);
            gutter.setMinWidth(58);
            gutter.setMaxWidth(58);
            gutter.setStyle("-fx-background-color: #F5F5F5; -fx-border-color: #E0E0E0; -fx-border-width: 0 1 0 0;");
            gutter.setOnMouseClicked(e -> {
                Integer idx = getItem();
                if (idx != null) toggleBreakpoint(idx + 1);
            });

            codeLbl.setFont(Font.font(FONT, FONT_SZ));
            codeLbl.setPadding(new Insets(0, 8, 0, 8));
            codeLbl.setMaxWidth(Double.MAX_VALUE);

            row = new HBox(gutter, codeLbl);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(codeLbl, Priority.ALWAYS);

            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setPadding(Insets.EMPTY);
            setBackground(Background.EMPTY);
        }

        @Override
        protected void updateItem(Integer lineIdx, boolean empty) {
            super.updateItem(lineIdx, empty);
            if (empty || lineIdx == null) { setGraphic(null); return; }

            int     lineNum = lineIdx + 1;
            boolean isCur   = lineNum == currentLine;
            boolean isBp    = breakpoints.contains("L" + lineNum);
            boolean canBp   = lineIdx < lines.length &&
                              InstrumentEngine.isExecutableLine(lines[lineIdx].strip());

            lineNumLbl.setText(String.valueOf(lineNum));
            codeLbl.setText(lineIdx < lines.length ? lines[lineIdx] : "");

            if (isBp) {
                bpDot.setFill(Color.web("#C0392B"));
                bpDot.setStroke(Color.web("#922B21"));
            } else if (canBp) {
                bpDot.setFill(Color.TRANSPARENT);
                bpDot.setStroke(Color.web("#CCCCCC"));
            } else {
                bpDot.setFill(Color.TRANSPARENT);
                bpDot.setStroke(Color.TRANSPARENT);
            }

            if (isCur) {
                row.setStyle("-fx-background-color: " + BG_CUR + ";");
                gutter.setStyle("-fx-background-color: " + BG_GUTTER_CUR +
                                "; -fx-border-color: #78C878; -fx-border-width: 0 1 0 0;");
                lineNumLbl.setStyle("-fx-text-fill: #1A5E1A; -fx-font-weight: bold;");
            } else {
                row.setStyle("-fx-background-color: white;");
                gutter.setStyle("-fx-background-color: #F5F5F5; -fx-border-color: #E0E0E0; -fx-border-width: 0 1 0 0;");
                lineNumLbl.setStyle("-fx-text-fill: #888;");
            }

            setGraphic(row);
        }
    }

    // Prevent cells from being highlighted/selected on click (purely visual)
    private static class NoSelectionModel<T> extends MultipleSelectionModel<T> {
        @Override public ObservableList<Integer> getSelectedIndices() { return javafx.collections.FXCollections.emptyObservableList(); }
        @Override public ObservableList<T>       getSelectedItems()   { return javafx.collections.FXCollections.emptyObservableList(); }
        @Override public void selectIndices(int i, int... r) {}
        @Override public void selectAll() {}
        @Override public void selectFirst() {}
        @Override public void selectLast() {}
        @Override public void clearAndSelect(int i) {}
        @Override public void select(int i) {}
        @Override public void select(T t) {}
        @Override public void clearSelection(int i) {}
        @Override public void clearSelection() {}
        @Override public boolean isSelected(int i) { return false; }
        @Override public boolean isEmpty() { return true; }
        @Override public void selectPrevious() {}
        @Override public void selectNext() {}
    }
}
