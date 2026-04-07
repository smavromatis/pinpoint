package com.pinpoint.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.pinpoint.model.PinManager;
import com.pinpoint.model.TrafficEntry;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class PinsPanel {

    private final JPanel mainPanel;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final DefaultTableModel keywordModel;
    private final JTable kwTable;
    private final TrafficPlotPanel trafficPlotPanel;
    private TrafficEntry selectedTraffic = null;
    private final List<PinRuleId> sortedRowIdentities = new ArrayList<>();

    private static class PinRuleId {
        final String pattern;
        final boolean isExact;
        PinRuleId(String pattern, boolean isExact) {
            this.pattern = pattern;
            this.isExact = isExact;
        }
    }

    public PinsPanel(MontoyaApi api, PinManager pinManager) {
        this.mainPanel = new JPanel(new BorderLayout());

        JPanel pinsTopPanel = new JPanel(new BorderLayout());

        this.tableModel = new DefaultTableModel(new String[]{"Active", "Intercept", "Pattern"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                if (columnIndex == 1) return String.class;
                return String.class;
            }
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0 || column == 1;
            }
        };

        this.table = new JTable(tableModel);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(0).setMinWidth(50);
        table.getColumnModel().getColumn(1).setMaxWidth(120);
        table.getColumnModel().getColumn(1).setMinWidth(100);

        JComboBox<String> interceptCombo = new JComboBox<>(new String[]{
            PinManager.INTERCEPT_REQ_RES, PinManager.INTERCEPT_REQ_ONLY, PinManager.INTERCEPT_RES_ONLY
        });
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(interceptCombo));
        

        javax.swing.table.DefaultTableCellRenderer centerRenderer = new javax.swing.table.DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        table.getColumnModel().getColumn(1).setCellRenderer(centerRenderer);

        tableModel.addTableModelListener(e -> {
            int row = e.getFirstRow();
            int column = e.getColumn();
            if (row >= 0 && row < sortedRowIdentities.size()) {
                PinRuleId id = sortedRowIdentities.get(row);
                int origIndex = findPinIndex(pinManager, id);
                if (origIndex >= 0) {
                    if (column == 0) {
                        pinManager.setPinEnabled(origIndex, (Boolean) tableModel.getValueAt(row, 0));
                    } else if (column == 1) {
                        String val = (String) tableModel.getValueAt(row, 1);
                        pinManager.setInterceptRequest(origIndex, val.contains("Req"));
                        pinManager.setInterceptResponse(origIndex, val.contains("Res"));
                    }
                }
            }
        });

        JPanel toolbarPanel = new JPanel();
        toolbarPanel.setLayout(new BoxLayout(toolbarPanel, BoxLayout.X_AXIS));
        toolbarPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
        ));

        JLabel titleLabel = new JLabel("Breakpoints");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));

        JButton addButton = new JButton("Add");
        addButton.addActionListener(e -> {
            String pattern = JOptionPane.showInputDialog(mainPanel, "Enter URL pattern to intercept:\n(e.g. example.com/api/login)");
            if (pattern != null && !pattern.trim().isEmpty()) {
                pinManager.addPin(pattern.trim());
            }
        });

        JButton removeButton = new JButton("Remove");
        removeButton.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            List<PinRuleId> toRemove = new ArrayList<>();
            for (int r : rows) {
                if (r < sortedRowIdentities.size()) {
                    toRemove.add(sortedRowIdentities.get(r));
                }
            }

            for (PinRuleId id : toRemove) {
                int idx = findPinIndex(pinManager, id);
                if (idx >= 0) {
                    pinManager.removePin(idx);
                }
            }
        });

        toolbarPanel.add(titleLabel);
        toolbarPanel.add(Box.createHorizontalGlue());
        toolbarPanel.add(addButton);
        toolbarPanel.add(Box.createHorizontalStrut(4));
        toolbarPanel.add(removeButton);

        pinsTopPanel.add(toolbarPanel, BorderLayout.NORTH);
        pinsTopPanel.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel keywordsTopPanel = new JPanel(new BorderLayout());
        
        this.keywordModel = new DefaultTableModel(new String[]{"Active", "Regex", "Keyword"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 || columnIndex == 1 ? Boolean.class : String.class;
            }
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0 || column == 1;
            }
        };
        this.kwTable = new JTable(keywordModel);
        kwTable.getColumnModel().getColumn(0).setMaxWidth(50);
        kwTable.getColumnModel().getColumn(0).setMinWidth(50);
        kwTable.getColumnModel().getColumn(1).setMaxWidth(50);
        kwTable.getColumnModel().getColumn(1).setMinWidth(50);

        keywordModel.addTableModelListener(e -> {
            int row = e.getFirstRow();
            int column = e.getColumn();
            if (row >= 0 && row < pinManager.getKeywordRules().size()) {
                if (column == 0) {
                    pinManager.setKeywordEnabled(row, (Boolean) keywordModel.getValueAt(row, 0));
                } else if (column == 1) {
                    pinManager.setKeywordRegex(row, (Boolean) keywordModel.getValueAt(row, 1));
                }
            }
        });

        JPanel kwToolbarPanel = new JPanel();
        kwToolbarPanel.setLayout(new BoxLayout(kwToolbarPanel, BoxLayout.X_AXIS));
        kwToolbarPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
        ));

        JLabel kwTitleLabel = new JLabel("Live Grep");
        kwTitleLabel.setFont(kwTitleLabel.getFont().deriveFont(Font.BOLD));

        JButton addKwBtn = new JButton("Add");
        addKwBtn.addActionListener(e -> {
            String kw = JOptionPane.showInputDialog(mainPanel, "Enter keyword (or pattern) to flag in responses:");
            if (kw != null && !kw.trim().isEmpty()) {
                int resp = JOptionPane.showConfirmDialog(mainPanel, "Use Regular Expression matching?", "Regex", JOptionPane.YES_NO_OPTION);
                pinManager.addKeyword(kw.trim(), resp == JOptionPane.YES_OPTION);
            }
        });

        JButton remKwBtn = new JButton("Remove");
        remKwBtn.addActionListener(e -> {
            int[] rows = kwTable.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) {
                pinManager.removeKeyword(rows[i]);
            }
        });

        kwToolbarPanel.add(kwTitleLabel);
        kwToolbarPanel.add(Box.createHorizontalGlue());
        kwToolbarPanel.add(addKwBtn);
        kwToolbarPanel.add(Box.createHorizontalStrut(4));
        kwToolbarPanel.add(remKwBtn);

        keywordsTopPanel.add(kwToolbarPanel, BorderLayout.NORTH);
        keywordsTopPanel.add(new JScrollPane(kwTable), BorderLayout.CENTER);

        JSplitPane topSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pinsTopPanel, keywordsTopPanel);
        topSplitPane.setResizeWeight(0.7);

        HttpRequestEditor reqEditor = api.userInterface().createHttpRequestEditor();
        HttpResponseEditor resEditor = api.userInterface().createHttpResponseEditor();

        JSplitPane editorSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        editorSplitPane.setLeftComponent(reqEditor.uiComponent());
        editorSplitPane.setRightComponent(resEditor.uiComponent());
        editorSplitPane.setResizeWeight(0.5);

        JPanel detailsPanel = new JPanel(new BorderLayout());

        JPanel detailToolbar = new JPanel();
        detailToolbar.setLayout(new BoxLayout(detailToolbar, BoxLayout.X_AXIS));
        detailToolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)
        ));

        JButton addBpBtn = new JButton("Add Breakpoint for Selected");
        addBpBtn.setEnabled(false);
        addBpBtn.addActionListener(e -> {
            if (selectedTraffic != null) {
                pinManager.addPin(selectedTraffic.getHost() + selectedTraffic.getPath(), true);
            }
        });
        detailToolbar.add(addBpBtn);
        detailToolbar.add(Box.createHorizontalGlue());

        detailsPanel.add(editorSplitPane, BorderLayout.CENTER);
        detailsPanel.add(detailToolbar, BorderLayout.SOUTH);
        detailsPanel.setVisible(false);

        JSplitPane graphDetailSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        graphDetailSplit.setResizeWeight(0.5);

        trafficPlotPanel = new TrafficPlotPanel(entry -> {
            selectedTraffic = entry;
            if (entry != null) {
                if (entry.getRequest() != null) {
                    reqEditor.setRequest(entry.getRequest());
                } else {
                    reqEditor.setRequest(burp.api.montoya.http.message.requests.HttpRequest.httpRequest(""));
                }
                
                if (entry.getResponse() != null) {
                    resEditor.setResponse(entry.getResponse());
                    if (!entry.getMatchedKeywords().isEmpty()) {
                        resEditor.setSearchExpression(entry.getMatchedKeywords().iterator().next());
                    } else {
                        resEditor.setSearchExpression("");
                    }
                } else {
                    resEditor.setResponse(burp.api.montoya.http.message.responses.HttpResponse.httpResponse(""));
                    resEditor.setSearchExpression("");
                }
                
                addBpBtn.setEnabled(true);
                if (!detailsPanel.isVisible()) {
                    detailsPanel.setVisible(true);
                    graphDetailSplit.setDividerLocation(0.6);
                    mainPanel.revalidate();
                    mainPanel.repaint();
                }
            } else {
                addBpBtn.setEnabled(false);
                if (detailsPanel.isVisible()) {
                    detailsPanel.setVisible(false);
                    mainPanel.revalidate();
                    mainPanel.repaint();
                }
            }
        });

        trafficPlotPanel.setOnBreakpointAdd(entry -> {
            if (entry != null) {
                if (entry.isBreakpoint()) {
                    List<PinManager.PinRule> currentPins = pinManager.getPins();
                    String hostPath = entry.getHost() + entry.getPath();
                    for (int idx = 0; idx < currentPins.size(); idx++) {
                        PinManager.PinRule pin = currentPins.get(idx);
                        if (PinManager.matchesPinPattern(pin, hostPath, entry.getUrl())) {
                            pinManager.removePin(idx);
                            break;
                        }
                    }
                } else {
                    pinManager.addPin(entry.getHost() + entry.getPath(), true);
                }
            }
        });

        JScrollPane graphScroll = new JScrollPane(trafficPlotPanel);
        graphScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        graphScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JPanel graphToolbar = new JPanel();
        graphToolbar.setLayout(new BoxLayout(graphToolbar, BoxLayout.X_AXIS));
        graphToolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)
        ));

        JLabel inScopeSym = new JLabel("\u2501\u2501");
        inScopeSym.setFont(new Font("SansSerif", Font.BOLD, 12));
        inScopeSym.setForeground(new Color(0, 120, 215));
        JLabel inScopeTxt = new JLabel("In Scope");
        inScopeTxt.setFont(new Font("SansSerif", Font.PLAIN, 11));

        JLabel outScopeSym = new JLabel("- - -");
        outScopeSym.setFont(new Font("SansSerif", Font.BOLD, 12));
        outScopeSym.setForeground(new Color(160, 160, 160));
        JLabel outScopeTxt = new JLabel("Out of Scope");
        outScopeTxt.setFont(new Font("SansSerif", Font.PLAIN, 11));

        JLabel bpSym = new JLabel("\u2588\u2588");
        bpSym.setFont(new Font("SansSerif", Font.BOLD, 12));
        bpSym.setForeground(new Color(255, 100, 30));
        JLabel bpTxt = new JLabel("Breakpoint");
        bpTxt.setFont(new Font("SansSerif", Font.PLAIN, 11));

        graphToolbar.add(inScopeSym);
        graphToolbar.add(Box.createHorizontalStrut(4));
        graphToolbar.add(inScopeTxt);
        graphToolbar.add(Box.createHorizontalStrut(16));

        graphToolbar.add(outScopeSym);
        graphToolbar.add(Box.createHorizontalStrut(4));
        graphToolbar.add(outScopeTxt);
        graphToolbar.add(Box.createHorizontalStrut(16));

        graphToolbar.add(bpSym);
        graphToolbar.add(Box.createHorizontalStrut(4));
        graphToolbar.add(bpTxt);

        graphToolbar.add(Box.createHorizontalGlue());
        
        JTextField filterField = new JTextField(10);
        filterField.setMaximumSize(new Dimension(150, 24));
        filterField.putClientProperty("JTextField.placeholderText", "Filter...");
        filterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateFilter(); }
            private void updateFilter() {
                trafficPlotPanel.setFilterText(filterField.getText());
            }
        });
        graphToolbar.add(filterField);
        graphToolbar.add(Box.createHorizontalStrut(6));
        
        JToggleButton scopeBtn = new JToggleButton("Only In-Scope");
        scopeBtn.addActionListener(e -> {
            trafficPlotPanel.setShowOnlyInScope(scopeBtn.isSelected());
        });
        graphToolbar.add(scopeBtn);
        graphToolbar.add(Box.createHorizontalStrut(6));

        JToggleButton groupTypeBtn = new JToggleButton("Sort by Type");
        groupTypeBtn.addActionListener(e -> {
            trafficPlotPanel.setGroupByType(groupTypeBtn.isSelected());
        });
        graphToolbar.add(groupTypeBtn);
        graphToolbar.add(Box.createHorizontalStrut(6));

        JToggleButton dataFlowBtn = new JToggleButton("Data Flow Links");
        dataFlowBtn.addActionListener(e -> {
            trafficPlotPanel.setShowDataFlowLinks(dataFlowBtn.isSelected());
        });
        graphToolbar.add(dataFlowBtn);
        graphToolbar.add(Box.createHorizontalStrut(6));

        JButton clearBtn = new JButton("Clear Graph");
        clearBtn.addActionListener(e -> {
            pinManager.clearTrafficLog();
            trafficPlotPanel.updateLog(pinManager.getTrafficLog());
            selectedTraffic = null;
            addBpBtn.setEnabled(false);
        });
        graphToolbar.add(clearBtn);

        JPanel graphPanel = new JPanel(new BorderLayout());
        graphPanel.add(graphToolbar, BorderLayout.NORTH);
        graphPanel.add(graphScroll, BorderLayout.CENTER);
        graphDetailSplit.setTopComponent(graphPanel);
        graphDetailSplit.setBottomComponent(detailsPanel);

        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplitPane, graphDetailSplit);
        mainSplitPane.setResizeWeight(0.15);
        mainPanel.add(mainSplitPane, BorderLayout.CENTER);

        pinManager.addListener(() -> updateTable(pinManager));
        updateTable(pinManager);

        pinManager.addTrafficListener(() -> {
            SwingUtilities.invokeLater(() -> {
                trafficPlotPanel.updateLog(pinManager.getTrafficLog());
                updateTable(pinManager);
            });
        });
    }

    private static int findPinIndex(PinManager pinManager, PinRuleId id) {
        List<PinManager.PinRule> pins = pinManager.getPins();
        for (int i = 0; i < pins.size(); i++) {
            PinManager.PinRule pin = pins.get(i);
            if (pin.getPattern().equals(id.pattern) && pin.isExact() == id.isExact) {
                return i;
            }
        }
        return -1;
    }

    private int trafficOrder(PinManager.PinRule pin, List<TrafficEntry> log) {
        String hostPath;
        for (int i = 0; i < log.size(); i++) {
            TrafficEntry entry = log.get(i);
            hostPath = entry.getHost() + entry.getPath();
            if (PinManager.matchesPinPattern(pin, hostPath, entry.getUrl())) return i;
        }
        return Integer.MAX_VALUE;
    }

    private void updateTable(PinManager pinManager) {
        SwingUtilities.invokeLater(() -> {
            int sel = table.getSelectedRow();
            tableModel.setRowCount(0);
            sortedRowIdentities.clear();
            // Build index list sorted by traffic timeline order
            List<PinManager.PinRule> original = pinManager.getPins();
            List<TrafficEntry> log = pinManager.getTrafficLog();
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < original.size(); i++) indices.add(i);
            indices.sort((a, b) -> Integer.compare(trafficOrder(original.get(a), log), trafficOrder(original.get(b), log)));

            for (int origIdx : indices) {
                PinManager.PinRule pin = original.get(origIdx);
                sortedRowIdentities.add(new PinRuleId(pin.getPattern(), pin.isExact()));

                String interceptType;
                if (pin.isInterceptRequest() && pin.isInterceptResponse()) interceptType = PinManager.INTERCEPT_REQ_RES;
                else if (pin.isInterceptRequest()) interceptType = PinManager.INTERCEPT_REQ_ONLY;
                else if (pin.isInterceptResponse()) interceptType = PinManager.INTERCEPT_RES_ONLY;
                else interceptType = PinManager.INTERCEPT_REQ_RES;
                String displayPattern = pin.isExact() ? "(Exact) " + pin.getPattern() : pin.getPattern();
                tableModel.addRow(new Object[]{pin.isEnabled(), interceptType, displayPattern});
            }
            if (sel >= 0 && sel < tableModel.getRowCount()) {
                table.setRowSelectionInterval(sel, sel);
            }

            int kwSel = kwTable.getSelectedRow();
            keywordModel.setRowCount(0);
            for (PinManager.KeywordRule kw : pinManager.getKeywordRules()) {
                keywordModel.addRow(new Object[]{kw.isEnabled(), kw.isRegex(), kw.getKeyword()});
            }
            if (kwSel >= 0 && kwSel < keywordModel.getRowCount()) {
                kwTable.setRowSelectionInterval(kwSel, kwSel);
            }
        });
    }

    public void shutdown() {
        trafficPlotPanel.shutdown();
    }

    public Component getPanel() {
        return mainPanel;
    }
}
