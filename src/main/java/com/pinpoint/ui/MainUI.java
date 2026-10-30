package com.pinpoint.ui;

import burp.api.montoya.MontoyaApi;
import com.pinpoint.model.PendingItem;
import com.pinpoint.model.PinManager;

import javax.swing.*;
import java.awt.*;

public class MainUI {

    private final JPanel mainPanel;

    private final InterceptPanel interceptPanel;
    private final PinsPanel pinsPanel;

    public MainUI(MontoyaApi api, PinManager pinManager) {
        this.mainPanel = new JPanel(new BorderLayout());

        // Single view: held messages are edited in the flow view's bottom area
        this.interceptPanel = new InterceptPanel(api, pinManager);
        this.pinsPanel = new PinsPanel(api, pinManager, interceptPanel);

        mainPanel.add(pinsPanel.getPanel(), BorderLayout.CENTER);

        pinManager.addListener(() -> {
            SwingUtilities.invokeLater(() -> {
                boolean on = pinManager.isInterceptEnabled() || pinManager.isStepMode();
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
                        burpTabs.setTitleAt(i, interceptOn ? "● Pinpoint" : "Pinpoint");
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
        // Unblock proxy threads still waiting on held messages before the extension goes away
        interceptPanel.releaseAllOriginal();
        pinsPanel.shutdown();
    }
}
