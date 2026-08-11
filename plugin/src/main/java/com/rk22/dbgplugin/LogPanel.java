package com.rk22.dbgplugin;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Variable log panel — displays LogEntry rows in a JTable.
 * Collapsible (hidden by default). Breakpoint rows are highlighted red,
 * ENTRY rows green, normal rows white.
 */
public class LogPanel extends JPanel {

    private static final Color COLOR_BP_BG    = new Color(0xFF, 0xEB, 0xEB);
    private static final Color COLOR_BP_FG    = new Color(0xC0, 0x39, 0x2B);
    private static final Color COLOR_ENTRY_BG = new Color(0xEF, 0xFB, 0xF4);
    private static final Color COLOR_NAME     = new Color(0x00, 0x70, 0xC1);
    private static final Color COLOR_VALUE    = new Color(0xA3, 0x15, 0x15);
    private static final Color COLOR_DIM      = new Color(0xAA, 0xAA, 0xAA);

    private static final String[] COLS = {"Time", "Label", "Variable", "Value"};

    private final List<LogEntry>  rows      = new ArrayList<>();
    private final LogTableModel   model     = new LogTableModel();
    private final JTable          table     = new JTable(model);
    private final JScrollPane     scroll;
    private boolean               expanded  = false;
    private Runnable              onClear;

    public LogPanel() {
        super(new BorderLayout());

        // ── Title bar ──────────────────────────────────────────────────────
        JPanel title = new JPanel(new BorderLayout());
        title.setBackground(new Color(0xF3, 0xF3, 0xF3));
        title.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xDD, 0xDD, 0xDD)),
            BorderFactory.createEmptyBorder(3, 6, 3, 4)));

        JLabel chevron = new JLabel("▶");
        chevron.setFont(chevron.getFont().deriveFont(9f));
        chevron.setForeground(new Color(0x88, 0x88, 0x88));

        JLabel lbl = new JLabel(" Variable log");
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 11f));
        lbl.setForeground(new Color(0x88, 0x88, 0x88));

        JButton clearBtn = new JButton("Clear");
        clearBtn.setFont(clearBtn.getFont().deriveFont(11f));
        clearBtn.setMargin(new Insets(1, 5, 1, 5));
        clearBtn.addActionListener(e -> { if (onClear != null) onClear.run(); });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.setOpaque(false);
        left.add(chevron);
        left.add(lbl);

        title.add(left, BorderLayout.WEST);
        title.add(clearBtn, BorderLayout.EAST);

        MouseAdapter toggle = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                expanded = !expanded;
                chevron.setText(expanded ? "▼" : "▶");
                scroll.setVisible(expanded);
                revalidate(); repaint();
            }
        };
        title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        title.addMouseListener(toggle);
        left.addMouseListener(toggle);

        add(title, BorderLayout.NORTH);

        // ── Table ──────────────────────────────────────────────────────────
        table.setFont(new Font("Consolas", Font.PLAIN, 12));
        table.setRowHeight(20);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        TableColumnModel cm = table.getColumnModel();
        cm.getColumn(0).setPreferredWidth(62);   // Time
        cm.getColumn(0).setMaxWidth(70);
        cm.getColumn(1).setPreferredWidth(52);   // Label
        cm.getColumn(1).setMaxWidth(70);
        cm.getColumn(2).setPreferredWidth(110);  // Variable
        cm.getColumn(2).setMaxWidth(180);
        // column 3 (Value) fills the rest

        LogCellRenderer renderer = new LogCellRenderer();
        for (int i = 0; i < 4; i++) cm.getColumn(i).setCellRenderer(renderer);

        // Header
        JTableHeader header = table.getTableHeader();
        header.setFont(header.getFont().deriveFont(Font.PLAIN, 10f));
        header.setReorderingAllowed(false);

        scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setVisible(false);
        scroll.setPreferredSize(new Dimension(0, 200));
        add(scroll, BorderLayout.CENTER);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setOnClear(Runnable r) { onClear = r; }

    public void append(LogEntry entry) {
        rows.add(entry);
        int row = rows.size() - 1;
        model.fireTableRowsInserted(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
    }

    public void clear() {
        rows.clear();
        model.fireTableDataChanged();
    }

    // ── Table model ───────────────────────────────────────────────────────────

    private class LogTableModel extends AbstractTableModel {
        @Override public int    getRowCount()    { return rows.size(); }
        @Override public int    getColumnCount() { return 4; }
        @Override public String getColumnName(int col) { return COLS[col]; }
        @Override public Object getValueAt(int row, int col) {
            LogEntry e = rows.get(row);
            switch (col) {
                case 0: return e.ts != null && e.ts.length() >= 19 ? e.ts.substring(11, 19) : "";
                case 1: return e.isBreakpoint() ? "⏸ BP" : e.label;
                case 2: return e.isBreakpoint() ? e.varValue : e.varName;
                case 3: return e.isBreakpoint() ? "" : (e.varValue == null ? "NULL" : e.varValue);
                default: return "";
            }
        }
    }

    // ── Cell renderer ─────────────────────────────────────────────────────────

    private class LogCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object val,
                boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, val, sel, foc, row, col);
            LogEntry e = rows.get(row);
            setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));

            if (e.isBreakpoint()) {
                setBackground(COLOR_BP_BG);
                setForeground(col == 1 ? COLOR_BP_FG : COLOR_BP_FG);
                setFont(table.getFont().deriveFont(Font.BOLD));
            } else if (e.isEntry()) {
                setBackground(COLOR_ENTRY_BG);
                setForeground(col == 0 || col == 1 ? COLOR_DIM
                            : col == 2              ? COLOR_NAME
                                                    : COLOR_VALUE);
                setFont(table.getFont().deriveFont(Font.PLAIN));
            } else {
                setBackground(Color.WHITE);
                setForeground(col == 0 || col == 1 ? COLOR_DIM
                            : col == 2              ? COLOR_NAME
                                                    : COLOR_VALUE);
                setFont(table.getFont().deriveFont(Font.PLAIN));
            }
            return this;
        }
    }
}
