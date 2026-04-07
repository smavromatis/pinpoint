package com.pinpoint.ui;

import burp.api.montoya.MontoyaApi;
import com.pinpoint.model.PendingItem;
import com.pinpoint.model.PinManager;

import javax.swing.*;
import java.awt.*;

public class MainUI {

    private final MontoyaApi api;
    private final PinManager pinManager;
    private final JPanel mainPanel;
    
    private final InterceptPanel interceptPanel;
    private final PinsPanel pinsPanel;
    private final JTabbedPane tabbedPane;

    public MainUI(MontoyaApi api, PinManager pinManager) {
        this.api = api;
        this.pinManager = pinManager;

        this.mainPanel = new JPanel(new BorderLayout());
        this.tabbedPane = new JTabbedPane();

        this.interceptPanel = new InterceptPanel(api, pinManager);
        this.pinsPanel = new PinsPanel(api, pinManager);

        tabbedPane.addTab("Intercept Queue", interceptPanel.getPanel());
        tabbedPane.addTab("BreakPoint Flow", pinsPanel.getPanel());

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        pinManager.addListener(() -> {
            SwingUtilities.invokeLater(() -> {
                boolean on = pinManager.isInterceptEnabled();
                updateSuiteTabTitle(on);
            });
        });
    }

    private void updateSuiteTabTitle(boolean interceptOn) {
        Container parent = mainPanel.getParent();
        while (parent != null) {
            if (parent instanceof JTabbedPane) {
                JTabbedPane burpTabs = (JTabbedPane) parent;
                for (int i = 0; i < burpTabs.getTabCount(); i++) {
                    Component tab = burpTabs.getComponentAt(i);
                    if (tab == mainPanel) {
                        burpTabs.setTitleAt(i, interceptOn ? "\u25CF Pinpoint" : "Pinpoint");
                        return;
                    }
                }
            }
            parent = parent.getParent();
        }
    }

    public Component getUiComponent() {
        return mainPanel;
    }

    public void addPendingItem(PendingItem item) {
        SwingUtilities.invokeLater(() -> interceptPanel.addItem(item));
    }

    public void removePendingItem(PendingItem item) {
        interceptPanel.removePendingItem(item);
    }

    public void shutdown() {
        pinsPanel.shutdown();
    }
}
