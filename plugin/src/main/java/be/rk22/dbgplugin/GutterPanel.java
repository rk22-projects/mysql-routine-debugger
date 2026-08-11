package be.rk22.dbgplugin;

import javax.swing.*;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.*;
import java.util.Set;
import java.util.function.*;

/**
 * Left-side gutter: line numbers, breakpoint dots, current-line indicator.
 *
 * Placed as a plain sibling panel (not a JScrollPane row-header).
 * Drives itself from the text pane's JViewport ChangeListener so it repaints
 * whenever the user scrolls.  All positions are computed from font metrics +
 * viewport.getViewPosition() — no modelToView2D, no coordinate-space mismatch.
 */
public class GutterPanel extends JPanel {

    private static final Color COLOR_BG         = new Color(0xFA, 0xFA, 0xFA);
    private static final Color COLOR_NUM         = new Color(0xAA, 0xAA, 0xAA);
    private static final Color COLOR_BP          = new Color(0xE5, 0x3E, 0x3E);
    private static final Color COLOR_BP_HOVER    = new Color(0xF5, 0xC6, 0xC6);
    private static final Color COLOR_CUR         = new Color(0x27, 0xAE, 0x60);
    private static final Color COLOR_CUR_LINE_BG = new Color(0xCC, 0xED, 0xCC);

    private final JTextPane                  textPane;
    private final Set<String>                breakpoints;
    private final Function<Integer, Boolean> canBreak;
    private final Consumer<Integer>          onToggle;

    private JViewport viewport;   // set by SourcePanel after scroll pane creation
    private int       lineCount  = 0;
    private int       currentLine = -1;
    private int       hoverLine   = -1;

    public GutterPanel(JTextPane textPane,
                       Set<String> breakpoints,
                       Function<Integer, Boolean> canBreak,
                       Consumer<Integer> onToggle) {
        this.textPane   = textPane;
        this.breakpoints = breakpoints;
        this.canBreak   = canBreak;
        this.onToggle   = onToggle;

        setBackground(COLOR_BG);
        setPreferredSize(new Dimension(50, 1));
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0xE0, 0xE0, 0xE0)));

        textPane.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateLineCount(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateLineCount(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { repaint(); }
        });

        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int line = lineAtY(e.getY());
                if (line > 0 && canBreak.apply(line)) onToggle.accept(line);
            }
            @Override public void mouseExited(MouseEvent e) { hoverLine = -1; repaint(); }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int line = lineAtY(e.getY());
                hoverLine = (line > 0 && canBreak.apply(line)) ? line : -1;
                repaint();
            }
        });
    }

    /** Called by SourcePanel once the JScrollPane exists. */
    public void setViewport(JViewport vp) {
        this.viewport = vp;
        // Repaint whenever the user scrolls
        vp.addChangeListener(e -> repaint());
    }

    public void setCurrentLine(int line) { currentLine = line; repaint(); }
    public void clearCurrentLine()       { currentLine = -1;   repaint(); }
    public void refresh()                { repaint(); }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void updateLineCount() {
        try {
            String text = textPane.getDocument().getText(0, textPane.getDocument().getLength());
            lineCount = text.isEmpty() ? 0 : text.split("\n", -1).length;
        } catch (BadLocationException ex) {
            lineCount = 0;
        }
        repaint();
    }

    private int scrollY() {
        return (viewport != null) ? viewport.getViewPosition().y : 0;
    }

    private int lineAtY(int gutterY) {
        FontMetrics fm = textPane.getFontMetrics(textPane.getFont());
        int lineH = fm.getHeight();
        int top   = textPane.getInsets().top;
        int idx   = (gutterY + scrollY() - top) / lineH;
        return (idx < 0 || idx >= lineCount) ? -1 : idx + 1;
    }

    // ── Painting ──────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (lineCount == 0) return;

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        FontMetrics fm     = textPane.getFontMetrics(textPane.getFont());
        int         lineH  = fm.getHeight();
        int         ascent = fm.getAscent();
        int         top    = textPane.getInsets().top;

        Font        numFont = textPane.getFont().deriveFont(Font.PLAIN, 11f);
        FontMetrics numFm   = g2.getFontMetrics(numFont);

        int scrollY  = scrollY();
        int visibleH = getHeight();

        // Line indices (0-based) whose pixel rows overlap the visible area
        int firstIdx = Math.max(0,             (scrollY - top) / lineH);
        int lastIdx  = Math.min(lineCount - 1, (scrollY + visibleH - top) / lineH + 1);

        for (int i = firstIdx; i <= lastIdx; i++) {
            int lineNum = i + 1;
            // y in the gutter's own (0 = top of visible area) coordinates
            int lineY = top + i * lineH - scrollY;

            boolean hasBp = breakpoints.contains("L" + lineNum);
            boolean isCur = lineNum == currentLine;
            boolean isHov = lineNum == hoverLine;

            if (isCur) {
                g2.setColor(COLOR_CUR_LINE_BG);
                g2.fillRect(0, lineY, getWidth(), lineH);
                g2.setColor(COLOR_CUR);
                g2.fillRect(0, lineY, 3, lineH);
            }

            int dotX = 4, dotY = lineY + (lineH - 10) / 2;
            if (hasBp) {
                g2.setColor(COLOR_BP);
                g2.fillOval(dotX, dotY, 10, 10);
            } else if (isCur) {
                g2.setColor(COLOR_CUR);
                int[] xp = {dotX + 5, dotX + 10, dotX + 5, dotX};
                int[] yp = {dotY,     dotY + 5,  dotY + 10, dotY + 5};
                g2.fillPolygon(xp, yp, 4);
            } else if (isHov) {
                g2.setColor(COLOR_BP_HOVER);
                g2.fillOval(dotX, dotY, 10, 10);
            }

            String num  = String.valueOf(lineNum);
            int    numX = getWidth() - numFm.stringWidth(num) - 5;
            g2.setColor(isCur ? COLOR_CUR : COLOR_NUM);
            g2.setFont(numFont);
            g2.drawString(num, numX, lineY + ascent);
        }
    }
}
