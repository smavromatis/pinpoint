package com.pinpoint.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.pinpoint.model.PendingItem;
import com.pinpoint.model.PinManager;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class InterceptPanel {

    private final JPanel mainPanel;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final CopyOnWriteArrayList<PendingItem> pendingItems = new CopyOnWriteArrayList<>();

    private final MontoyaApi api;
    private final PinManager pinManager;

    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;

    private volatile PendingItem currentItem;
    private final JToggleButton interceptToggleButton;

    private volatile String lastForwardedUrl = null;

    public InterceptPanel(MontoyaApi api, PinManager pinManager) {
        this.api = api;
        this.pinManager = pinManager;
        this.mainPanel = new JPanel(new BorderLayout());

        this.tableModel = new DefaultTableModel(new String[]{"#", "Type", "Method", "Host", "Path"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        this.table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(0).setMinWidth(30);
        table.getColumnModel().getColumn(1).setMaxWidth(100);
        table.getColumnModel().getColumn(1).setMinWidth(80);
        table.getColumnModel().getColumn(2).setMaxWidth(60);
        table.getColumnModel().getColumn(2).setMinWidth(50);
        table.getColumnModel().getColumn(3).setPreferredWidth(150);
        table.getColumnModel().getColumn(4).setPreferredWidth(300);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = table.getSelectedRow();
                if (row >= 0 && row < pendingItems.size()) {
                    loadItem(pendingItems.get(row));
                }
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(0, 120));

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
        ));

        this.interceptToggleButton = new JToggleButton("Intercept is off");
        this.interceptToggleButton.setSelected(false);
        interceptToggleButton.addActionListener(e -> {
            boolean isOn = interceptToggleButton.isSelected();
            interceptToggleButton.setText(isOn ? "Intercept is on" : "Intercept is off");
            pinManager.setInterceptEnabled(isOn);
        });

        pinManager.addListener(() -> {
            SwingUtilities.invokeLater(() -> {
                boolean isOn = pinManager.isInterceptEnabled();
                if (interceptToggleButton.isSelected() != isOn) {
                    interceptToggleButton.setSelected(isOn);
                    interceptToggleButton.setText(isOn ? "Intercept is on" : "Intercept is off");
                }
                if (!isOn) {
                    flushAll();
                }
            });
        });

        JButton forwardButton = new JButton("Forward");
        JButton dropButton = new JButton("Drop");
        forwardButton.addActionListener(e -> forwardCurrent());
        dropButton.addActionListener(e -> dropCurrent());

        toolbar.add(interceptToggleButton);
        toolbar.add(Box.createHorizontalStrut(12));
        toolbar.add(new JSeparator(SwingConstants.VERTICAL) {
            @Override public Dimension getMaximumSize() { return new Dimension(2, 24); }
        });
        toolbar.add(Box.createHorizontalStrut(12));
        toolbar.add(forwardButton);
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(dropButton);
        toolbar.add(Box.createHorizontalGlue());

        this.requestEditor = api.userInterface().createHttpRequestEditor();
        this.responseEditor = api.userInterface().createHttpResponseEditor();

        JSplitPane editorSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        editorSplitPane.setLeftComponent(requestEditor.uiComponent());
        editorSplitPane.setRightComponent(responseEditor.uiComponent());
        editorSplitPane.setResizeWeight(0.5);

        JPanel topSection = new JPanel(new BorderLayout());
        topSection.add(tableScroll, BorderLayout.CENTER);
        topSection.add(toolbar, BorderLayout.SOUTH);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        mainSplit.setTopComponent(topSection);
        mainSplit.setBottomComponent(editorSplitPane);
        mainSplit.setResizeWeight(0.2);

        mainPanel.add(mainSplit, BorderLayout.CENTER);
    }

    public void addItem(PendingItem item) {
        if (!pinManager.isInterceptEnabled()) {
            if (item.isRequest()) item.getRequestFuture().complete(item.getOriginalRequest());
            else item.getResponseFuture().complete(item.getOriginalResponse());
            return;
        }

        SwingUtilities.invokeLater(() -> {
            boolean isFollowUp = false;
            if (lastForwardedUrl != null && !item.isRequest() && item.getOriginalRequest() != null) {
                String itemUrl = item.getOriginalRequest().url();
                if (itemUrl != null && itemUrl.equals(lastForwardedUrl)) {
                    isFollowUp = true;
                }
            }

            if (isFollowUp) {
                pendingItems.add(0, item);
                refreshTable();
                table.setRowSelectionInterval(0, 0);
                lastForwardedUrl = null;
            } else {

                int insertIdx = pendingItems.size();
                for (int i = 0; i < pendingItems.size(); i++) {
                    if (pendingItems.get(i).compareTo(item) > 0) {
                        insertIdx = i;
                        break;
                    }
                }
                pendingItems.add(insertIdx, item);
                refreshTable();
                if (table.getSelectedRow() == -1) {
                    table.setRowSelectionInterval(0, 0);
                }
            }
        });
    }

    private void refreshTable() {
        tableModel.setRowCount(0);
        java.util.List<PendingItem> snapshot = new java.util.ArrayList<>(pendingItems);
        for (PendingItem item : snapshot) {
            String type = item.isRequest() ? "\u27A1 Request" : "\u2B05 Response";
            String method = "";
            String host = "";
            String path = "";
            if (item.getOriginalRequest() != null) {
                method = item.getOriginalRequest().method();
                try {
                    java.net.URI u = new java.net.URI(item.getOriginalRequest().url());
                    host = u.getHost() != null ? u.getHost() : "";
                    path = u.getPath() != null ? u.getPath() : "";
                    String query = u.getQuery();
                    if (query != null) path += "?" + query;
                } catch (Exception ex) {
                    path = item.getOriginalRequest().url();
                }
            }
            tableModel.addRow(new Object[]{item.getId(), type, method, host, path});
        }
    }

    private void loadItem(PendingItem item) {
        this.currentItem = item;
        if (item == null) {
            requestEditor.setRequest(burp.api.montoya.http.message.requests.HttpRequest.httpRequest(""));
            responseEditor.setResponse(burp.api.montoya.http.message.responses.HttpResponse.httpResponse(""));
            return;
        }

        if (item.isRequest()) {
            requestEditor.setRequest(item.getOriginalRequest());
            responseEditor.setResponse(burp.api.montoya.http.message.responses.HttpResponse.httpResponse(""));
        } else {
            requestEditor.setRequest(item.getOriginalRequest());
            responseEditor.setResponse(item.getOriginalResponse());
        }
    }

    private void forwardCurrent() {
        PendingItem item = currentItem;
        if (item == null) return;
        if (item.isRequest()) {
            if (item.getOriginalRequest() != null) {
                lastForwardedUrl = item.getOriginalRequest().url();
            }
            item.getRequestFuture().complete(requestEditor.getRequest());
        } else {
            lastForwardedUrl = null;
            item.getResponseFuture().complete(responseEditor.getResponse());
        }
        completeCurrent(item);
    }

    private void dropCurrent() {
        PendingItem item = currentItem;
        if (item == null) return;
        if (item.isRequest()) {
            item.getRequestFuture().complete(null);
        } else {
            item.getResponseFuture().complete(null);
        }
        completeCurrent(item);
    }

    private void completeCurrent(PendingItem item) {
        pendingItems.remove(item);
        this.currentItem = null;

        refreshTable();

        if (!pendingItems.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        } else {
            loadItem(null);
        }
    }

    private void flushAll() {
        if (pendingItems.isEmpty()) return;
        java.util.List<PendingItem> snapshot = new java.util.ArrayList<>(pendingItems);
        pendingItems.clear();
        this.currentItem = null;

        // Complete futures after clearing so re-entrant removePendingItem calls are no-ops
        for (PendingItem item : snapshot) {
            if (item.isRequest()) {
                item.getRequestFuture().complete(item.getOriginalRequest());
            } else {
                item.getResponseFuture().complete(item.getOriginalResponse());
            }
        }
        refreshTable();
        loadItem(null);
    }

    public void removePendingItem(PendingItem item) {
        if (item == null) return;
        SwingUtilities.invokeLater(() -> {
            if (pendingItems.remove(item)) {
                if (currentItem == item) {
                    currentItem = null;
                    if (!pendingItems.isEmpty()) {
                        table.setRowSelectionInterval(0, 0);
                    } else {
                        loadItem(null);
                    }
                }
                refreshTable();
            }
        });
    }

    public Component getPanel() {
        return mainPanel;
    }
}
