package com.pinpoint.ui;

import com.pinpoint.model.PinManager;
import com.pinpoint.model.PinManager.MatchType;
import com.pinpoint.model.TrafficEntry;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Breakpoints and Live Grep panels. Rows keep the order they were added in and update in place
 * (traffic only refreshes the match counts), so nothing jumps or cancels an edit. Every cell can be
 * edited directly and is validated; Add opens a small form with a live preview of what it matches.
 */
final class RulesSidebar {

    static final String HOLDS_BOTH = "Req + Res";
    static final String HOLDS_REQUEST = "Req only";
    static final String HOLDS_RESPONSE = "Res only";

    private final PinManager pinManager;
    private final Supplier<TrafficEntry> selectedNode;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final BreakpointModel breakpointModel = new BreakpointModel();
    private final GrepModel grepModel = new GrepModel();
    private final HintTable breakpointTable;
    private final HintTable grepTable;
    private final JLabel breakpointCount = new JLabel();
    private final JLabel grepCount = new JLabel();

    RulesSidebar(PinManager pinManager, Supplier<TrafficEntry> selectedNode) {
        this.pinManager = pinManager;
        this.selectedNode = selectedNode;

        breakpointTable = new HintTable(breakpointModel,
                "No breakpoints yet.",
                "Click the ○ dot on a node in the graph, or use + Add.");
        configureBreakpointTable();
        grepTable = new HintTable(grepModel,
                "No grep rules yet.",
                "Add text or a regex to flag responses that contain it (headers or body).");
        configureGrepTable();

        JPanel breakpoints = section("Breakpoints", breakpointCount, breakpointTable,
                this::addBreakpoint, () -> removeSelected(breakpointTable, true),
                "Add a breakpoint: hold matching requests/responses so you can edit them");
        JPanel grep = section("Live Grep", grepCount, grepTable,
                this::addGrepRule, () -> removeSelected(grepTable, false),
                "Add a grep rule: flag responses that contain some text or match a regex");

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, breakpoints, grep);
        split.setResizeWeight(0.6);
        split.setBorder(null);
        panel.add(split, BorderLayout.CENTER);
        rulesChanged();
    }

    JComponent getComponent() {
        return panel;
    }

    /** Toolbar summary: active breakpoints · active grep rules. */
    String summary() {
        long pins = pinManager.getPins().stream().filter(PinManager.PinRule::isEnabled).count();
        long grep = pinManager.getKeywordRules().stream().filter(PinManager.KeywordRule::isEnabled).count();
        return "Breakpoints & Grep (" + pins + " · " + grep + ")";
    }

    /** A rule was added, removed or changed: reload rows, keeping the selection. Call on the EDT. */
    void rulesChanged() {
        breakpointModel.reload();
        grepModel.reload();
        breakpointCount.setText(countLabel(breakpointModel.rows.size()));
        grepCount.setText(countLabel(grepModel.rows.size()));
    }

    /** Traffic changed: only the match-count column changes, so edits in progress aren't interrupted. */
    void refreshCounts() {
        breakpointModel.refreshCounts();
        grepModel.refreshCounts();
    }

    private static String countLabel(int n) {
        return n == 0 ? "" : String.valueOf(n);
    }

    // ---- Layout -------------------------------------------------------------------------------

    private JPanel section(String title, JLabel count, JTable table, Runnable add, Runnable remove, String addTip) {
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(Theme.font(Font.BOLD, 0));
        count.setFont(Theme.font(Font.PLAIN, -2));
        count.setForeground(Theme.muted());

        JButton addButton = new JButton("+ Add");
        addButton.setToolTipText(addTip);
        addButton.addActionListener(e -> add.run());
        JButton removeButton = new JButton("Remove");
        removeButton.setToolTipText("Remove the selected rows (Delete)");
        removeButton.setEnabled(false);
        removeButton.addActionListener(e -> remove.run());
        table.getSelectionModel().addListSelectionListener(e -> removeButton.setEnabled(table.getSelectedRowCount() > 0));

        // Delete / Backspace removes the selected rows when not editing a cell
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "pinpoint.remove");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "pinpoint.remove");
        table.getActionMap().put("pinpoint.remove", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { remove.run(); }
        });

        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(Theme.bar(false, true));
        bar.add(titleLabel);
        bar.add(Theme.gap(Theme.SPACE_S));
        bar.add(count);
        bar.add(Box.createHorizontalGlue());
        bar.add(addButton);
        bar.add(Theme.gap(Theme.SPACE_S));
        bar.add(removeButton);

        JPanel section = new JPanel(new BorderLayout());
        section.add(bar, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setColumnHeaderView(table.getTableHeader());
        scroll.setBorder(BorderFactory.createEmptyBorder());
        section.add(scroll, BorderLayout.CENTER);
        return section;
    }

    private void configureBreakpointTable() {
        sizeColumn(breakpointTable.getColumnModel().getColumn(0), 34);
        sizeColumn(breakpointTable.getColumnModel().getColumn(1), 68);
        sizeColumn(breakpointTable.getColumnModel().getColumn(3), 74);
        sizeColumn(breakpointTable.getColumnModel().getColumn(4), 46);
        breakpointTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(MatchType.values())));
        breakpointTable.getColumnModel().getColumn(2).setCellRenderer(new PatternRenderer());
        breakpointTable.getColumnModel().getColumn(2).setCellEditor(monoEditor());
        breakpointTable.getColumnModel().getColumn(3).setCellEditor(
                new DefaultCellEditor(new JComboBox<>(new String[]{HOLDS_BOTH, HOLDS_REQUEST, HOLDS_RESPONSE})));
        breakpointTable.getColumnModel().getColumn(4).setCellRenderer(new CountRenderer("Nodes this breakpoint matches in the graph"));
    }

    private void configureGrepTable() {
        sizeColumn(grepTable.getColumnModel().getColumn(0), 34);
        sizeColumn(grepTable.getColumnModel().getColumn(1), 48);
        sizeColumn(grepTable.getColumnModel().getColumn(3), 46);
        grepTable.getColumnModel().getColumn(2).setCellRenderer(new PatternRenderer());
        grepTable.getColumnModel().getColumn(2).setCellEditor(monoEditor());
        grepTable.getColumnModel().getColumn(3).setCellRenderer(new CountRenderer("Nodes whose latest response this rule flags"));
    }

    private static void sizeColumn(TableColumn column, int designWidth) {
        column.setPreferredWidth(Theme.px(designWidth));
        column.setMaxWidth(Theme.px(designWidth + 30));
    }

    private static DefaultCellEditor monoEditor() {
        JTextField field = new JTextField();
        field.setFont(Theme.mono(Font.PLAIN, -1));
        DefaultCellEditor editor = new DefaultCellEditor(field);
        editor.setClickCountToStart(2);
        return editor;
    }

    private void removeSelected(JTable table, boolean breakpoints) {
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        int[] rows = table.getSelectedRows();
        // Highest index first, so earlier removals don't shift the later ones
        for (int i = rows.length - 1; i >= 0; i--) {
            if (breakpoints) pinManager.removePin(rows[i]);
            else pinManager.removeKeyword(rows[i]);
        }
    }

    private void showError(String title, String message) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(panel, message, title, JOptionPane.WARNING_MESSAGE));
    }

    static String holdsLabel(boolean request, boolean response) {
        if (request && !response) return HOLDS_REQUEST;
        if (response && !request) return HOLDS_RESPONSE;
        return HOLDS_BOTH;
    }

    // ---- Add dialogs ----------------------------------------------------------------------------

    private void addBreakpoint() {
        TrafficEntry node = selectedNode.get();
        JTextField pattern = new JTextField(node != null ? node.getHost() + node.getPath() : "", 30);
        pattern.setFont(Theme.mono(Font.PLAIN, 0));
        JComboBox<MatchType> match = new JComboBox<>(MatchType.values());
        match.setSelectedItem(node != null ? MatchType.EXACT : MatchType.CONTAINS);
        JComboBox<String> holds = new JComboBox<>(new String[]{HOLDS_BOTH, HOLDS_REQUEST, HOLDS_RESPONSE});
        JTextArea preview = FormDialog.previewText();

        Runnable updatePreview = () -> {
            String p = pattern.getText().trim();
            MatchType type = (MatchType) match.getSelectedItem();
            String regexError = type == MatchType.REGEX && !p.isEmpty() ? PinManager.regexError(p) : null;
            if (p.isEmpty()) FormDialog.setPreview(preview, "Enter a pattern.", false);
            else if (regexError != null) FormDialog.setPreview(preview, "Invalid regex: " + regexError, true);
            else {
                int n = pinManager.countPinMatches(p, type);
                FormDialog.setPreview(preview, n == 0 ? "Matches nothing captured yet (it will apply to new traffic)."
                        : "Matches " + n + (n == 1 ? " node" : " nodes") + " in the graph.", false);
            }
        };
        onChange(pattern, updatePreview);
        match.addActionListener(e -> updatePreview.run());
        updatePreview.run();

        JPanel form = FormDialog.grid(
                new Object[]{"Pattern", pattern},
                new Object[]{"Match", match},
                new Object[]{"Hold", holds},
                new Object[]{null, preview},
                new Object[]{null, FormDialog.wrappingText("Contains: anywhere in the URL.  Exact: host + path "
                        + "(app.example.com/api/login) or the full URL.  Regex: a Java regex, e.g. /api/users/\\d+/orders", true)});

        FormDialog.show(panel, "Add breakpoint", form, pattern, "Add", () -> {
            String held = (String) holds.getSelectedItem();
            return pinManager.addPin(pattern.getText().trim(), (MatchType) match.getSelectedItem(),
                    !HOLDS_RESPONSE.equals(held), !HOLDS_REQUEST.equals(held));
        });
    }

    private void addGrepRule() {
        JTextField keyword = new JTextField(30);
        keyword.setFont(Theme.mono(Font.PLAIN, 0));
        JCheckBox regex = new JCheckBox("Regular expression");
        JTextArea preview = FormDialog.previewText();

        // Scanning captured responses can take a moment, so the preview waits for typing to pause
        Timer debounce = new Timer(250, null);
        debounce.setRepeats(false);
        debounce.addActionListener(e -> {
            String k = keyword.getText();
            String regexError = regex.isSelected() && !k.isEmpty() ? PinManager.regexError(k) : null;
            if (k.isEmpty()) FormDialog.setPreview(preview, "Enter text to look for.", false);
            else if (regexError != null) FormDialog.setPreview(preview, "Invalid regex: " + regexError, true);
            else {
                int n = pinManager.countKeywordPreview(k, regex.isSelected());
                FormDialog.setPreview(preview, n == 0 ? "No captured responses contain it yet."
                        : "Flags " + n + (n == 1 ? " captured response." : " captured responses."), false);
            }
        });
        onChange(keyword, debounce::restart);
        regex.addActionListener(e -> debounce.restart());
        debounce.restart();

        JPanel form = FormDialog.grid(
                new Object[]{"Look for", keyword},
                new Object[]{null, regex},
                new Object[]{null, preview},
                new Object[]{null, FormDialog.wrappingText("Searches each response's headers and body. Matching nodes get "
                        + "a red badge; type the keyword in the graph's filter to show only them.", true)});

        FormDialog.show(panel, "Add grep rule", form, keyword, "Add",
                () -> pinManager.addKeyword(keyword.getText(), regex.isSelected()));
        debounce.stop();
    }

    private static void onChange(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { action.run(); }
            @Override public void removeUpdate(DocumentEvent e) { action.run(); }
            @Override public void changedUpdate(DocumentEvent e) { action.run(); }
        });
    }

    // ---- Table models -------------------------------------------------------------------------

    private abstract class RuleModel<T> extends AbstractTableModel {
        final List<T> rows = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();

        abstract List<T> source();

        abstract int count(T rule);

        abstract HintTable table();

        void reload() {
            HintTable table = table();
            if (table != null && table.isEditing() && source().size() == rows.size()) {
                // Same rules, user mid-edit: just refresh values
                rows.clear();
                rows.addAll(source());
                refreshCounts();
                return;
            }
            int[] selected = table != null ? table.getSelectedRows() : new int[0];
            rows.clear();
            rows.addAll(source());
            counts.clear();
            for (T rule : rows) counts.add(count(rule));
            fireTableDataChanged();
            if (table != null) {
                for (int r : selected) {
                    if (r < rows.size()) table.addRowSelectionInterval(r, r);
                }
            }
        }

        void refreshCounts() {
            if (rows.isEmpty()) return;
            for (int i = 0; i < rows.size(); i++) {
                int n = count(rows.get(i));
                if (i < counts.size()) counts.set(i, n);
                else counts.add(n);
            }
            fireTableChanged(new javax.swing.event.TableModelEvent(this, 0, rows.size() - 1, getColumnCount() - 1));
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }
    }

    private final class BreakpointModel extends RuleModel<PinManager.PinRule> {
        private final String[] columns = {"On", "Match", "Pattern", "Hold", "Nodes"};

        @Override List<PinManager.PinRule> source() { return pinManager.getPins(); }
        @Override int count(PinManager.PinRule rule) { return pinManager.countPinMatches(rule); }
        @Override HintTable table() { return breakpointTable; }

        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Class<?> getColumnClass(int c) {
            return switch (c) {
                case 0 -> Boolean.class;
                case 1 -> MatchType.class;
                case 4 -> Integer.class;
                default -> String.class;
            };
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c != 4;
        }

        @Override
        public Object getValueAt(int r, int c) {
            PinManager.PinRule pin = rows.get(r);
            return switch (c) {
                case 0 -> pin.isEnabled();
                case 1 -> pin.getMatchType();
                case 2 -> pin.getPattern();
                case 3 -> holdsLabel(pin.isInterceptRequest(), pin.isInterceptResponse());
                default -> r < counts.size() ? counts.get(r) : 0;
            };
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            if (r >= rows.size()) return;
            PinManager.PinRule pin = rows.get(r);
            String error = null;
            switch (c) {
                case 0 -> pinManager.setPinEnabled(r, (Boolean) value);
                case 1 -> {
                    if (value != pin.getMatchType()) error = pinManager.updatePin(r, pin.getPattern(), (MatchType) value);
                }
                case 2 -> {
                    String p = ((String) value).trim();
                    if (!p.equals(pin.getPattern())) error = pinManager.updatePin(r, p, pin.getMatchType());
                }
                case 3 -> pinManager.setPinIntercept(r, !HOLDS_RESPONSE.equals(value), !HOLDS_REQUEST.equals(value));
                default -> { }
            }
            if (error != null) showError("Breakpoint not changed", error);
        }
    }

    private final class GrepModel extends RuleModel<PinManager.KeywordRule> {
        private final String[] columns = {"On", "Regex", "Look for", "Hits"};

        @Override List<PinManager.KeywordRule> source() { return pinManager.getKeywordRules(); }
        @Override int count(PinManager.KeywordRule rule) { return pinManager.countKeywordHits(rule.getKeyword()); }
        @Override HintTable table() { return grepTable; }

        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Class<?> getColumnClass(int c) {
            return switch (c) {
                case 0, 1 -> Boolean.class;
                case 3 -> Integer.class;
                default -> String.class;
            };
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c != 3;
        }

        @Override
        public Object getValueAt(int r, int c) {
            PinManager.KeywordRule rule = rows.get(r);
            return switch (c) {
                case 0 -> rule.isEnabled();
                case 1 -> rule.isRegex();
                case 2 -> rule.getKeyword();
                default -> r < counts.size() ? counts.get(r) : 0;
            };
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            if (r >= rows.size()) return;
            PinManager.KeywordRule rule = rows.get(r);
            String error = null;
            switch (c) {
                case 0 -> pinManager.setKeywordEnabled(r, (Boolean) value);
                case 1 -> error = pinManager.setKeywordRegex(r, (Boolean) value);
                case 2 -> {
                    String k = (String) value;
                    if (!k.equals(rule.getKeyword())) error = pinManager.updateKeyword(r, k);
                }
                default -> { }
            }
            if (error != null) showError("Grep rule not changed", error);
        }
    }

    // ---- Rendering ------------------------------------------------------------------------------

    /** A table that explains what to do when it's empty, and sizes rows to Burp's font. */
    private static final class HintTable extends JTable {
        private final String hintTitle;
        private final String hintText;

        HintTable(AbstractTableModel model, String hintTitle, String hintText) {
            super(model);
            this.hintTitle = hintTitle;
            this.hintText = hintText;
            setFillsViewportHeight(true);
            setShowVerticalLines(false);
            putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            getTableHeader().setReorderingAllowed(false);
            updateRowHeight();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            updateRowHeight();
        }

        private void updateRowHeight() {
            setRowHeight(getFontMetrics(Theme.font(Font.PLAIN, 0)).getHeight() + Theme.px(8));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getRowCount() > 0 || hintTitle == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int x = Theme.px(Theme.SPACE_L);
            int y = Theme.px(Theme.SPACE_XL);
            g2.setFont(Theme.font(Font.BOLD, -1));
            g2.setColor(Theme.muted());
            g2.drawString(hintTitle, x, y + g2.getFontMetrics().getAscent());
            y += g2.getFontMetrics().getHeight() + Theme.px(Theme.SPACE_XS);
            g2.setFont(Theme.font(Font.PLAIN, -2));
            for (String line : wrap(g2.getFontMetrics(), hintText, getWidth() - 2 * x)) {
                g2.drawString(line, x, y + g2.getFontMetrics().getAscent());
                y += g2.getFontMetrics().getHeight();
            }
            g2.dispose();
        }

        private static List<String> wrap(FontMetrics fm, String text, int width) {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (fm.stringWidth(candidate) > width && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (line.length() > 0) lines.add(line.toString());
            return lines;
        }
    }

    /** Patterns in monospace, with the full text as a tooltip. */
    private static final class PatternRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, col);
            setFont(Theme.mono(Font.PLAIN, -1));
            setToolTipText(value + "  (double-click to edit)");
            return this;
        }
    }

    /** Match counts: right-aligned, muted when zero. */
    private static final class CountRenderer extends DefaultTableCellRenderer {
        private final String tooltip;

        CountRenderer(String tooltip) {
            this.tooltip = tooltip;
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, col);
            if (!selected && Integer.valueOf(0).equals(value)) setForeground(Theme.muted());
            else if (!selected) setForeground(table.getForeground());
            setToolTipText(tooltip);
            return this;
        }
    }
}
