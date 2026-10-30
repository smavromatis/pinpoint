package com.pinpoint.ui;

import com.pinpoint.model.PinManager;

import javax.swing.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;

/**
 * Keeps a request/response split pane at the user's preferred ratio (half by default), shared by
 * every editor split and persisted across Burp restarts. The ratio, not a pixel position, is kept,
 * so the split stays proportional when the window is resized.
 */
final class EditorSplit {

    private EditorSplit() {
    }

    static void install(JSplitPane split, PinManager pinManager) {
        // Debounced: a drag fires many divider events, but the preference is written once
        Timer saveTimer = new Timer(500, null);
        saveTimer.setRepeats(false);
        boolean[] applying = {false};

        Runnable apply = () -> {
            if (split.getWidth() <= split.getDividerSize()) return;
            // Swing's own initial/layout positioning just before this isn't a user choice: don't save it
            saveTimer.stop();
            applying[0] = true;
            split.setDividerLocation(pinManager.getEditorSplitRatio());
            applying[0] = false;
        };

        split.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                apply.run();
            }
        });
        // Re-apply when shown again (e.g. switching between viewing and held), in case the other split moved
        split.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && split.isShowing()) {
                SwingUtilities.invokeLater(apply);
            }
        });

        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (applying[0] || !split.isShowing()) return;
            int available = split.getWidth() - split.getDividerSize();
            if (available <= 0) return;
            double ratio = (double) split.getDividerLocation() / available;
            for (java.awt.event.ActionListener l : saveTimer.getActionListeners()) saveTimer.removeActionListener(l);
            saveTimer.addActionListener(a -> pinManager.setEditorSplitRatio(ratio));
            saveTimer.restart();
        });
    }
}
