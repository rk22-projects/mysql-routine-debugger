package com.rk22.routinedebugger.standalone;

import com.rk22.routinedebugger.core.SourceLineClassifier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * SQL source viewer: line numbers, clickable breakpoint gutter, current-line highlight.
 */
public class SourceView extends BorderPane {

    private static final String FONT      = "Consolas";
    private static final double FONT_SZ   = 12.5;
    private static final String BG_CUR    = "#CCEDCC";
    private static final String BG_GUTTER_CUR = "#A8D5A8";

    private String[]          lines       = new String[0];
    private boolean[]         startsInBlockComment = new boolean[0];
    private int               currentLine = -1;             // 1-based, -1 = none
    private int               selectedLine = -1;
    private final Set<String> breakpoints = new LinkedHashSet<>();
    private Consumer<String>  onBpToggle;
    private Consumer<String>  onAddWatch;
    private Function<String, String> varValueLookup;

    private final ListView<Integer> list = new ListView<>();

    public SourceView() {
        list.setCellFactory(lv -> new SourceCell());
        list.setStyle("-fx-background-color: white; -fx-border-color: transparent;");
        list.setSelectionModel(new NoSelectionModel<>());
        setCenter(list);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setOnBreakpointToggle(Consumer<String> h) { this.onBpToggle = h; }
    public void setOnAddWatch(Consumer<String> h) { this.onAddWatch = h; }
    public void setVarValueLookup(Function<String, String> lookup) { this.varValueLookup = lookup; }
    public boolean isEmpty() { return lines.length == 0; }

    public void setSource(String ddl) {
        lines = (ddl == null || ddl.isEmpty()) ? new String[0] : ddl.split("\n", -1);
        startsInBlockComment = new boolean[lines.length];
        boolean block = false;
        for (int i = 0; i < lines.length; i++) {
            startsInBlockComment[i] = block;
            for (int p = 0; p < lines[i].length();) {
                if (!block && p + 1 < lines[i].length() && lines[i].startsWith("/*", p)) { block = true; p += 2; continue; }
                if (block && p + 1 < lines[i].length() && lines[i].startsWith("*/", p)) { block = false; p += 2; continue; }
                p++;
            }
        }
        breakpoints.clear();
        currentLine = -1;
        selectedLine = -1;
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
    public void refreshValues() { list.refresh(); }

    public void toggleSelectedBreakpoint() {
        int line = selectedLine > 0 ? selectedLine : currentLine;
        if (line > 0 && line <= lines.length && SourceLineClassifier.isExecutable(lines[line - 1].strip())) {
            toggleBreakpoint(line);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void rebuildItems() {
        List<Integer> items = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) items.add(i);
        list.getItems().setAll(items);
    }

    private void toggleBreakpoint(int lineNumber) {
        if (lineNumber < 1 || lineNumber > lines.length ||
            !SourceLineClassifier.isExecutable(lines[lineNumber - 1].strip())) return;
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
        private final TextFlow codeFlow = new TextFlow();
        private final HBox   row;

        SourceCell() {
            lineNumLbl.setPrefWidth(34);
            lineNumLbl.setAlignment(Pos.CENTER_RIGHT);
            lineNumLbl.setFont(Font.font(FONT, FONT_SZ));
            lineNumLbl.setStyle("-fx-text-fill: #888;");

            bpDot.setFill(Color.TRANSPARENT);
            bpDot.setStroke(Color.TRANSPARENT);

            gutter = new HBox(4, lineNumLbl, bpDot);
            // Align the marker and line number on the same text baseline.  Centering
            // their layout bounds puts the circle slightly below the source glyphs
            // because Label and TextFlow use different font ascent/descent metrics.
            gutter.setAlignment(Pos.BASELINE_LEFT);
            gutter.setPadding(new Insets(0, 6, 0, 4));
            gutter.setPrefWidth(58);
            gutter.setMinWidth(58);
            gutter.setMaxWidth(58);
            gutter.setStyle("-fx-background-color: #F5F5F5; -fx-border-color: #E0E0E0; -fx-border-width: 0 1 0 0;");
            gutter.setOnMouseClicked(e -> {
                Integer idx = getItem();
                if (idx != null) { selectedLine = idx + 1; toggleBreakpoint(idx + 1); }
            });

            codeFlow.setPadding(new Insets(0, 8, 0, 8));

            row = new HBox(gutter, codeFlow);
            // Baseline alignment keeps the line number, breakpoint marker and the
            // syntax-highlighted TextFlow visually centred on one source line.
            row.setAlignment(Pos.BASELINE_LEFT);
            HBox.setHgrow(codeFlow, Priority.ALWAYS);
            row.setOnMouseClicked(e -> {
                if (e.getTarget() == gutter || gutter.isHover()) return;
                Integer idx = getItem();
                if (idx != null) { selectedLine = idx + 1; list.refresh(); }
            });

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
                              SourceLineClassifier.isExecutable(lines[lineIdx].strip());

            lineNumLbl.setText(String.valueOf(lineNum));
            renderSql(codeFlow, lineIdx < lines.length ? lines[lineIdx] : "",
                lineIdx < startsInBlockComment.length && startsInBlockComment[lineIdx]);

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
                row.setStyle("-fx-background-color: " + (lineNum == selectedLine ? "#E8F1FB" : "white") + ";");
                gutter.setStyle("-fx-background-color: #F5F5F5; -fx-border-color: #E0E0E0; -fx-border-width: 0 1 0 0;");
                lineNumLbl.setStyle("-fx-text-fill: #888;");
            }

            setGraphic(row);
        }
    }

    private static final Set<String> KEYWORDS = Set.of(
        "SELECT","FROM","WHERE","INSERT","INTO","UPDATE","DELETE","SET","BEGIN","END","IF","THEN","ELSE",
        "ELSEIF","WHILE","DO","LOOP","REPEAT","UNTIL","LEAVE","ITERATE","RETURN","DECLARE","CALL","AND","OR",
        "NOT","NULL","TRUE","FALSE","CASE","WHEN","PROCEDURE","FUNCTION","RETURNS","HANDLER","CURSOR","FOR",
        "CREATE","DROP","ALTER","TABLE","JOIN","LEFT","RIGHT","INNER","OUTER","ON","AS","DISTINCT","UNION",
        "HAVING","GROUP","ORDER","BY","LIMIT","EXISTS","BETWEEN","LIKE","IS","SIGNAL","SQLSTATE","CONTINUE",
        "EXIT","VALUES","IN","OUT","INOUT","INT","VARCHAR","TEXT","DATETIME","DATE","TIME","BOOLEAN","CHAR",
        "DECIMAL","FLOAT","DOUBLE","BIGINT","TINYINT","SMALLINT","MEDIUMINT","JSON","BLOB","TRIGGER","VIEW");
    private static final Set<String> BUILTINS = Set.of(
        "NOW","COUNT","SUM","AVG","MAX","MIN","COALESCE","NULLIF","IFNULL","CAST","CONVERT","CONCAT","LENGTH",
        "SUBSTRING","UPPER","LOWER","TRIM","REPLACE","ROUND","FLOOR","CEIL","ABS","SLEEP","LAST_INSERT_ID",
        "ROW_COUNT","FOUND_ROWS","UUID","MD5","SHA1","SHA2","DATE_FORMAT","DATEDIFF","TIMESTAMPDIFF","CURDATE");

    private void renderSql(TextFlow flow, String line, boolean inBlockComment) {
        flow.getChildren().clear();
        for (int i = 0; i < line.length();) {
            if (inBlockComment) {
                int end = line.indexOf("*/", i);
                int stop = end < 0 ? line.length() : end + 2;
                addText(flow, line.substring(i, stop), "#008000", false, null);
                i = stop; inBlockComment = end < 0; continue;
            }
            if (line.startsWith("/*", i)) { inBlockComment = true; continue; }
            if ((line.startsWith("--", i) && (i + 2 == line.length() || Character.isWhitespace(line.charAt(i + 2)))) || line.charAt(i) == '#') {
                addText(flow, line.substring(i), "#008000", false, null); break;
            }
            char c = line.charAt(i);
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < line.length()) {
                    if (line.charAt(j) == '\\') { j += 2; continue; }
                    if (line.charAt(j++) == c) break;
                }
                addText(flow, line.substring(i, Math.min(j, line.length())), "#A31515", false, null); i = j; continue;
            }
            if (c == '`') {
                int j = i + 1; while (j < line.length() && line.charAt(j) != '`') j++; j = Math.min(line.length(), j + 1);
                addText(flow, line.substring(i, j), "#267F99", false, null); i = j; continue;
            }
            if (Character.isDigit(c)) {
                int j = i + 1; while (j < line.length() && (Character.isDigit(line.charAt(j)) || ".xXabcdefABCDEF".indexOf(line.charAt(j)) >= 0)) j++;
                addText(flow, line.substring(i, j), "#098658", false, null); i = j; continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int j = i + 1; while (j < line.length() && (Character.isLetterOrDigit(line.charAt(j)) || "_$".indexOf(line.charAt(j)) >= 0)) j++;
                String word = line.substring(i, j), upper = word.toUpperCase(Locale.ROOT);
                if (KEYWORDS.contains(upper)) addText(flow, word, "#0000FF", true, null);
                else if (BUILTINS.contains(upper) || line.substring(j).stripLeading().startsWith("(")) addText(flow, word, "#795E26", false, null);
                else addText(flow, word, "#1E1E1E", false, word);
                i = j; continue;
            }
            addText(flow, String.valueOf(c), "#1E1E1E", false, null); i++;
        }
    }

    private void addText(TextFlow flow, String value, String color, boolean bold, String identifier) {
        Text text = new Text(value);
        text.setFont(Font.font(FONT, bold ? javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL, FONT_SZ));
        text.setFill(Color.web(color));
        if (identifier != null) {
            text.setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.SECONDARY || onAddWatch == null) return;
                showAddWatchMenu(text, identifier, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            String valueText = varValueLookup == null ? null : varValueLookup.apply(identifier);
            if (valueText != null) Tooltip.install(text, new Tooltip(identifier + " = " + valueText));
        }
        flow.getChildren().add(text);
    }

    private void showAddWatchMenu(Text owner, String identifier, double screenX, double screenY) {
        MenuItem add = new MenuItem("Add to Watch: " + identifier);
        add.setOnAction(e -> onAddWatch.accept(identifier));
        new ContextMenu(add).show(owner, screenX, screenY);
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
