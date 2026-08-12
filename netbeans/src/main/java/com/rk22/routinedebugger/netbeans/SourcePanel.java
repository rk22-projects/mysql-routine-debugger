package com.rk22.routinedebugger.netbeans;

import com.rk22.routinedebugger.core.SourceLineClassifier;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Displays the stored routine source with:
 *  - Syntax highlighting
 *  - Clickable gutter for breakpoint toggle
 *  - Current-line amber highlight
 *  - Right-click "Add to Watch" context menu
 */
public class SourcePanel extends JPanel {

    private static final Color COLOR_CUR_LINE = new Color(0xCC, 0xED, 0xCC);

    private final JTextPane              textPane;
    private final DefaultStyledDocument  doc;
    private final SqlHighlighter         highlighter;
    private final JScrollPane            scroll;
    private final GutterPanel            gutter;
    private final Set<String>            breakpoints = new LinkedHashSet<>();

    private String[]                 lines          = new String[0];
    private int                      currentLine    = -1;   // 1-based
    private Object                   curLineTag     = null; // Highlighter tag
    private Consumer<String>         onBpToggle;
    private Consumer<String>         onAddWatch;
    private Function<String, String> varValueLookup;

    private final Highlighter.HighlightPainter curLinePainter = new FullLinePainter(COLOR_CUR_LINE);

    public SourcePanel() {
        super(new BorderLayout());
        setBackground(Color.WHITE);

        doc         = new DefaultStyledDocument();
        textPane = new JTextPane(doc) {
            @Override public boolean getScrollableTracksViewportWidth() { return false; }
            @Override
            public String getToolTipText(MouseEvent e) {
                if (varValueLookup == null) return null;
                String word = wordAtPoint(e.getPoint());
                if (word == null) return null;
                String val = varValueLookup.apply(word);
                return val != null ? "<html><b>" + word + "</b> = " + escHtml(val) + "</html>" : null;
            }
        };
        ToolTipManager.sharedInstance().registerComponent(textPane);
        highlighter = new SqlHighlighter();

        textPane.setEditable(false);
        textPane.setFont(new Font("Consolas", Font.PLAIN, 13));
        textPane.setBackground(Color.WHITE);
        textPane.setForeground(new Color(0x1E, 0x1E, 0x1E));

        // Right-click context menu
        textPane.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger() || onAddWatch == null) return;
                String word = wordAtPoint(e.getPoint());
                if (word == null) return;
                JPopupMenu menu = new JPopupMenu();
                JMenuItem item = new JMenuItem("Add to Watch: " + word);
                item.addActionListener(ev -> onAddWatch.accept(word));
                menu.add(item);
                menu.show(textPane, e.getX(), e.getY());
            }
        });

        scroll = new JScrollPane(textPane);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        gutter = new GutterPanel(
            textPane,
            breakpoints,
            line -> isExecutable(line - 1),
            this::gutterClicked
        );
        gutter.setViewport(scroll.getViewport());

        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.add(gutter, BorderLayout.WEST);
        contentPanel.add(scroll, BorderLayout.CENTER);
        add(contentPanel, BorderLayout.CENTER);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setOnBreakpointToggle(Consumer<String> handler) { this.onBpToggle       = handler; }
    public void setOnAddWatch(Consumer<String> handler)         { this.onAddWatch        = handler; }
    public void setVarValueLookup(Function<String, String> fn)  { this.varValueLookup   = fn; }

    public void setSource(String ddl) {
        String text = ddl == null ? "" : ddl;
        lines = text.isEmpty() ? new String[0] : text.split("\n", -1);
        try {
            doc.remove(0, doc.getLength());
            doc.insertString(0, text, null);
            highlighter.highlight(doc, text);
            clearCurrentLine();
            gutter.refresh();
        } catch (BadLocationException ignored) {}
    }

    public void setBreakpoints(Collection<String> labels) {
        breakpoints.clear();
        breakpoints.addAll(labels);
        gutter.refresh();
    }

    public Set<String> getBreakpoints() { return Collections.unmodifiableSet(breakpoints); }
    public String getSourceText() {
        try { return doc.getText(0, doc.getLength()); }
        catch (BadLocationException ex) { return ""; }
    }

    /** Toggle the executable source line containing the caret (F9). */
    public void toggleBreakpointAtCaret() {
        int offset = Math.max(0, textPane.getCaretPosition());
        Element root = doc.getDefaultRootElement();
        int line = root.getElementIndex(offset) + 1;
        if (isExecutable(line - 1)) gutterClicked(line);
    }

    public void setCurrentLine(int lineNumber) {
        currentLine = lineNumber;
        gutter.setCurrentLine(lineNumber);
        applyCurrentLineHighlight(lineNumber);
        scrollToLine(lineNumber);
    }

    public void clearCurrentLine() {
        removeCurLineHighlight();
        currentLine = -1;
        gutter.clearCurrentLine();
    }

    // ── Breakpoint gutter click ───────────────────────────────────────────────

    private void gutterClicked(int lineNumber) {
        String label = "L" + lineNumber;
        if (breakpoints.contains(label)) breakpoints.remove(label);
        else breakpoints.add(label);
        gutter.refresh();
        if (onBpToggle != null) onBpToggle.accept(label);
    }

    // ── Line highlighting ─────────────────────────────────────────────────────

    private void applyCurrentLineHighlight(int lineNumber) {
        removeCurLineHighlight();
        if (lineNumber < 1 || lineNumber > lines.length) return;
        int[] range = lineCharRange(lineNumber - 1);
        if (range == null) return;
        try {
            curLineTag = textPane.getHighlighter().addHighlight(
                range[0], Math.max(range[0] + 1, range[1]), curLinePainter);
        } catch (BadLocationException ignored) {}
    }

    private void removeCurLineHighlight() {
        if (curLineTag != null) {
            textPane.getHighlighter().removeHighlight(curLineTag);
            curLineTag = null;
        }
    }

    private int[] lineCharRange(int lineIndex) {
        int start = 0;
        for (int i = 0; i < lineIndex && i < lines.length; i++) {
            start += lines[i].length() + 1; // +1 for \n
        }
        if (lineIndex >= lines.length) return null;
        int end = start + lines[lineIndex].length();
        return new int[]{start, end};
    }

    private void scrollToLine(int lineNumber) {
        SwingUtilities.invokeLater(() -> {
            try {
                int[] range = lineCharRange(lineNumber - 1);
                if (range == null) return;
                Rectangle r = textPane.modelToView(range[0]);
                if (r != null) {
                    r.height = scroll.getViewport().getHeight() / 2;
                    textPane.scrollRectToVisible(r);
                }
            } catch (BadLocationException ignored) {}
        });
    }

    // ── Word at point (for right-click context menu) ──────────────────────────

    private String wordAtPoint(Point p) {
        int pos = textPane.viewToModel(p);
        try {
            String text = doc.getText(0, doc.getLength());
            if (pos < 0 || pos >= text.length()) return null;
            int s = pos, e = pos;
            while (s > 0 && isWordChar(text.charAt(s - 1))) s--;
            while (e < text.length() && isWordChar(text.charAt(e))) e++;
            if (s == e) return null;
            String word = text.substring(s, e);
            return word.matches("[a-zA-Z_]\\w*") ? word : null;
        } catch (BadLocationException ex) { return null; }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ── Executable line check (mirrors Python is_executable_line) ─────────────

    public boolean isExecutable(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= lines.length) return false;
        return SourceLineClassifier.isExecutable(lines[lineIndex].strip());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getText() {
        try { return doc.getText(0, doc.getLength()); }
        catch (BadLocationException e) { return ""; }
    }

    private static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── Full-width line highlighter ───────────────────────────────────────────

    // Non-layered painter: DefaultHighlighter calls paint() before view rendering,
    // while the clip is still the full viewport — so fillRect reaches edge-to-edge.
    // Layered painters (DefaultHighlightPainter subclasses) are called from inside
    // the view pipeline where the clip is already clamped to the text's allocated width.
    private static final class FullLinePainter implements Highlighter.HighlightPainter {
        private final Color color;
        FullLinePainter(Color c) { this.color = c; }

        @Override
        public void paint(Graphics g, int offs0, int offs1, Shape bounds, JTextComponent c) {
            try {
                Rectangle r = c.modelToView(offs0);
                if (r == null) return;
                g.setColor(color);
                g.fillRect(0, r.y, c.getWidth(), r.height);
            } catch (BadLocationException ignored) {}
        }
    }

}
