package be.rk22.dbgplugin;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watch window: shows watched variables with their latest value.
 * Changed values (since last continue/step) are highlighted in amber.
 */
public class WatchPanel extends JPanel {

    private static final Color COLOR_CHANGED_BG  = new Color(0xFF, 0xFB, 0xEB);
    private static final Color COLOR_CHANGED_VAL = new Color(0xB4, 0x53, 0x09);
    private static final Color COLOR_NAME        = new Color(0x00, 0x70, 0xC1);
    private static final Color COLOR_VALUE       = new Color(0xA3, 0x15, 0x15);
    private static final Color COLOR_NULL        = new Color(0xBB, 0xBB, 0xBB);

    private final List<String>         names    = new ArrayList<>();
    private final Map<String, String>  values   = new LinkedHashMap<>();
    private final Set<String>          changed  = new HashSet<>();

    private final WatchTableModel      model    = new WatchTableModel();
    private final JTable               table    = new JTable(model);
    private final JTextField           input    = new JTextField();
    private final JToggleButton        btnAll   = new JToggleButton("All");
    private boolean                    watchAll = false;

    private Runnable onToggleAll;
    private java.util.function.Consumer<String> onAdd;
    private java.util.function.Consumer<String> onRemove;

    public WatchPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xDD, 0xDD, 0xDD)));

        // ── Title bar ──────────────────────────────────────────────────────
        JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        title.setBackground(new Color(0xF3, 0xF3, 0xF3));
        title.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xDD, 0xDD, 0xDD)));
        JLabel lbl = new JLabel("Watch");
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 11f));
        lbl.setForeground(new Color(0x88, 0x88, 0x88));
        btnAll.setFont(btnAll.getFont().deriveFont(Font.PLAIN, 10f));
        btnAll.setMargin(new Insets(1, 5, 1, 5));
        btnAll.setToolTipText("Auto-watch every variable that appears in the log");
        btnAll.addActionListener(e -> {
            watchAll = btnAll.isSelected();
            if (onToggleAll != null) onToggleAll.run();
        });
        title.add(lbl);
        title.add(btnAll);
        add(title, BorderLayout.NORTH);

        // ── Input bar ──────────────────────────────────────────────────────
        JPanel inputBar = new JPanel(new BorderLayout(3, 0));
        inputBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xEE, 0xEE, 0xEE)),
            BorderFactory.createEmptyBorder(3, 4, 3, 4)));
        inputBar.setBackground(new Color(0xFA, 0xFA, 0xFA));
        input.setFont(new Font("Consolas", Font.PLAIN, 12));
        input.setToolTipText("Variable name to watch (Enter or + to add)");
        input.addActionListener(e -> doAdd());
        JButton addBtn = new JButton("+");
        addBtn.setFont(addBtn.getFont().deriveFont(Font.BOLD, 14f));
        addBtn.setMargin(new Insets(0, 6, 0, 6));
        addBtn.setToolTipText("Add variable to watch");
        addBtn.addActionListener(e -> doAdd());
        inputBar.add(input,  BorderLayout.CENTER);
        inputBar.add(addBtn, BorderLayout.EAST);
        add(inputBar, BorderLayout.SOUTH);

        // ── Table ──────────────────────────────────────────────────────────
        table.setFont(new Font("Consolas", Font.PLAIN, 12));
        table.setRowHeight(20);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setDefaultRenderer(Object.class, new WatchCellRenderer());
        table.getTableHeader().setVisible(false);
        table.setTableHeader(null);

        // Columns: name (110px), value (rest), delete (22px)
        TableColumnModel cm = table.getColumnModel();
        cm.getColumn(0).setPreferredWidth(110);
        cm.getColumn(0).setMaxWidth(160);
        cm.getColumn(2).setPreferredWidth(22);
        cm.getColumn(2).setMaxWidth(22);
        cm.getColumn(2).setCellRenderer(new DeleteButtonRenderer());
        cm.getColumn(2).setCellEditor(new DeleteButtonEditor());

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public void setOnToggleAll(Runnable r)                                        { onToggleAll = r; }
    public void setOnAdd(java.util.function.Consumer<String> c)                   { onAdd = c; }
    public void setOnRemove(java.util.function.Consumer<String> c)                { onRemove = c; }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean isWatchAll() { return watchAll; }

    public void addVariable(String name) {
        if (name == null || name.isBlank() || names.contains(name)) return;
        names.add(name);
        values.put(name, null);
        model.fireTableDataChanged();
    }

    public void removeVariable(String name) {
        names.remove(name);
        values.remove(name);
        changed.remove(name);
        model.fireTableDataChanged();
    }

    public void updateValue(String name, String value, boolean isChanged) {
        if (!names.contains(name)) return;
        values.put(name, value);
        if (isChanged) changed.add(name);
        else           changed.remove(name);
        model.fireTableDataChanged();
    }

    public void clearChanged() {
        changed.clear();
        model.fireTableDataChanged();
    }

    public void clearValues() {
        values.replaceAll((k, v) -> null);
        changed.clear();
        model.fireTableDataChanged();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void doAdd() {
        String name = input.getText().trim();
        if (name.isEmpty()) return;
        input.setText("");
        addVariable(name);
        if (onAdd != null) onAdd.accept(name);
    }

    // ── Table model ───────────────────────────────────────────────────────────

    private class WatchTableModel extends AbstractTableModel {
        @Override public int getRowCount()    { return names.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public boolean isCellEditable(int row, int col) { return col == 2; }
        @Override public Object getValueAt(int row, int col) {
            String name = names.get(row);
            if (col == 0) return name;
            if (col == 1) return values.get(name);
            return name; // delete column carries the name
        }
    }

    // ── Cell renderers ────────────────────────────────────────────────────────

    private class WatchCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object val,
                boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, val, sel, foc, row, col);
            String name = names.get(row);
            boolean ch  = changed.contains(name);
            setBackground(ch ? COLOR_CHANGED_BG : Color.WHITE);
            setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
            if (col == 0) {
                setForeground(COLOR_NAME);
                setFont(getFont().deriveFont(Font.PLAIN));
            } else {
                String v = values.get(name);
                if (v == null) {
                    setText("not yet seen");
                    setForeground(COLOR_NULL);
                    setFont(getFont().deriveFont(Font.ITALIC));
                } else {
                    setText(v);
                    setForeground(ch ? COLOR_CHANGED_VAL : COLOR_VALUE);
                    setFont(getFont().deriveFont(Font.PLAIN));
                    if (ch) setFont(getFont().deriveFont(Font.BOLD));
                }
            }
            return this;
        }
    }

    private class DeleteButtonRenderer extends JButton implements TableCellRenderer {
        DeleteButtonRenderer() { setText("✕"); setMargin(new Insets(0,0,0,0)); setFont(getFont().deriveFont(9f)); setForeground(Color.LIGHT_GRAY); }
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) { return this; }
    }

    private class DeleteButtonEditor extends DefaultCellEditor {
        private String currentName;
        DeleteButtonEditor() { super(new JCheckBox()); }
        @Override public Component getTableCellEditorComponent(JTable t, Object v, boolean s, int r, int c) {
            currentName = (String) v;
            JButton btn = new JButton("✕");
            btn.setMargin(new Insets(0,0,0,0));
            btn.setFont(btn.getFont().deriveFont(9f));
            btn.setForeground(Color.RED);
            btn.addActionListener(e -> {
                stopCellEditing();
                removeVariable(currentName);
                if (onRemove != null) onRemove.accept(currentName);
            });
            return btn;
        }
        @Override public Object getCellEditorValue() { return currentName; }
    }
}
