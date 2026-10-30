package com.pinpoint.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.pinpoint.model.PendingItem;
import com.pinpoint.model.PinManager;
import com.pinpoint.model.TrafficEntry;
import com.pinpoint.model.ViewFilter;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class PinsPanel {

    private final JPanel mainPanel;
    private final RulesSidebar rulesSidebar;
    private final PinManager pinManager;
    private final TrafficPlotPanel trafficPlotPanel;

    private static final int TRAFFIC_REFRESH_MS = 200;

    private final InterceptPanel interceptPanel;
    private JButton heldBtn;
    private JButton lanesBtn;
    private static final String PREF_LANE_ORDER = "laneOrder";
    private boolean flowLanes = true;

    private static final String CARD_VIEW = "view";
    private static final String CARD_HELD = "held";
    private final CardLayout detailCards = new CardLayout();
    private final JPanel detailsPanel;
    private final JSplitPane graphDetailSplit;
    private final HttpRequestEditor viewReqEditor;
    private final HttpResponseEditor viewResEditor;
    private boolean showingHeld = false;
    private int lastDividerLocation = -1;

    private static final int SIDEBAR_DEFAULT_WIDTH = 440;
    private JPanel sidebarPanel;
    private JSplitPane mainSplitPane;
    private JToggleButton sidebarBtn;
    private int lastSidebarDividerLocation = -1;
    private final Timer trafficRefreshTimer;
    private final AtomicBoolean trafficRefreshPending = new AtomicBoolean(false);
    private long lastTrafficRefreshNanos = System.nanoTime() - TRAFFIC_REFRESH_MS * 1_000_000L; // EDT only


    public PinsPanel(MontoyaApi api, PinManager pinManager, InterceptPanel interceptPanel) {
        this.pinManager = pinManager;
        this.interceptPanel = interceptPanel;
        this.mainPanel = new JPanel(new BorderLayout());

        // Right-hand sidebar: breakpoints above live grep, collapsed by default so the graph leads
        rulesSidebar = new RulesSidebar(pinManager, this::selectedNode);
        sidebarPanel = new JPanel(new BorderLayout());
        sidebarPanel.add(rulesSidebar.getComponent(), BorderLayout.CENTER);
        sidebarPanel.setPreferredSize(new Dimension(SIDEBAR_DEFAULT_WIDTH, 0));
        sidebarPanel.setMinimumSize(new Dimension(220, 0));
        sidebarPanel.setVisible(false);

        // Bottom area: read-only view of the selected node, or the held message (editable)
        viewReqEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        viewResEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        JSplitPane editorSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        editorSplitPane.setLeftComponent(viewReqEditor.uiComponent());
        editorSplitPane.setRightComponent(viewResEditor.uiComponent());
        editorSplitPane.setResizeWeight(0.5);
        EditorSplit.install(editorSplitPane, pinManager);

        JPanel detailToolbar = new JPanel();
        detailToolbar.setLayout(new BoxLayout(detailToolbar, BoxLayout.X_AXIS));
        detailToolbar.setBorder(Theme.bar(true, true));

        JLabel viewingLabel = new JLabel("Latest request / response \u2014 read-only");
        viewingLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JButton closeViewBtn = new JButton("\u2715");
        closeViewBtn.setToolTipText("Close and return to the graph");
        closeViewBtn.addActionListener(e -> closeDetails());
        detailToolbar.add(viewingLabel);
        detailToolbar.add(Box.createHorizontalGlue());
        detailToolbar.add(closeViewBtn);

        JPanel viewPanel = new JPanel(new BorderLayout());
        viewPanel.add(editorSplitPane, BorderLayout.CENTER);
        viewPanel.add(detailToolbar, BorderLayout.NORTH);

        detailsPanel = new JPanel(detailCards);
        detailsPanel.add(viewPanel, CARD_VIEW);
        detailsPanel.add(interceptPanel.getPanel(), CARD_HELD);
        detailsPanel.setVisible(false);

        graphDetailSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        graphDetailSplit.setResizeWeight(0.5);

        trafficPlotPanel = new TrafficPlotPanel(entry -> {
            if (entry != null && interceptPanel.showItemFor(entry)) {
                // A paused node opens its held message for editing
                showHeld();
            } else if (entry != null) {
                showViewFor(entry);
            } else {
                // Clicking empty graph space puts the graph back in focus; paused nodes stay clickable
                closeDetails();
            }
        }, pinManager.getViewFilter());

        // A breakpoint/step hit (or the next held message) takes over the bottom area
        interceptPanel.setOnCurrentChanged(item -> {
            if (item != null) {
                showHeld();
            } else if (showingHeld) {
                // Last held message released: the breakpoint is done, back to the graph
                closeDetails();
            }
        });
        interceptPanel.setOnClose(this::closeDetails);
        interceptPanel.setQueueListener(() -> setHeldCount(interceptPanel.getHeldCount()));
        trafficPlotPanel.setOnFilterChanged(pinManager::setViewFilter);
        trafficPlotPanel.setOnIncludeHost(host -> changeScope(api, pinManager, host, true));
        trafficPlotPanel.setOnExcludeHost(host -> {
            // Extensions can add scope rules but never remove them, so excluding is one-way from here
            Object[] options = {"Exclude", "Cancel"};
            int choice = JOptionPane.showOptionDialog(mainPanel,
                    "Exclude " + host + " from scope?\n\n"
                    + "Pinpoint can't undo this. To revert it, delete the\n"
                    + "rule in Target > Scope settings.",
                    "Exclude from scope", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[1]);
            if (choice == 0) changeScope(api, pinManager, host, false);
        });

        trafficPlotPanel.setOnBreakpointAdd(this::toggleBreakpoint);

        JScrollPane graphScroll = new JScrollPane(trafficPlotPanel);
        graphScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        graphScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JPanel graphToolbar = new JPanel();
        graphToolbar.setLayout(new BoxLayout(graphToolbar, BoxLayout.X_AXIS));
        graphToolbar.setBorder(Theme.bar(false, true));

        // ---- Group 1: interception -------------------------------------------------------------
        JToggleButton interceptBtn = new JToggleButton(interceptLabel(pinManager.isInterceptEnabled()), pinManager.isInterceptEnabled());
        interceptBtn.setToolTipText("Hold requests/responses matching an active breakpoint");
        interceptBtn.addActionListener(e -> pinManager.setInterceptEnabled(interceptBtn.isSelected()));
        pinManager.addListener(() -> SwingUtilities.invokeLater(() -> {
            boolean on = pinManager.isInterceptEnabled();
            interceptBtn.setSelected(on);
            interceptBtn.setText(interceptLabel(on));
        }));

        // Step mode: hold every visible request/response and release them one at a time
        JToggleButton stepModeBtn = new JToggleButton("Step Mode");
        stepModeBtn.setToolTipText("Hold each visible request and response; release them one at a time with Forward (F10). Hidden traffic is skipped.");
        stepModeBtn.addActionListener(e -> pinManager.setStepMode(stepModeBtn.isSelected()));

        // Shown only while something is held: the one sign of held messages while the editor is closed
        heldBtn = new JButton() {
            @Override
            public void updateUI() {
                super.updateUI();
                setForeground(Theme.accent());
            }
        };
        heldBtn.setToolTipText("Open the held message");
        heldBtn.setVisible(false);
        heldBtn.addActionListener(e -> showHeld());
        mainPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F10, 0), "pinpoint.forward");
        mainPanel.getActionMap().put("pinpoint.forward", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { interceptPanel.forwardCurrent(); }
        });

        // ---- Group 2: what the map shows ---------------------------------------------------------
        JTextField filterField = new JTextField(12);
        filterField.setMaximumSize(new Dimension(Theme.px(180), filterField.getPreferredSize().height));
        filterField.putClientProperty("JTextField.placeholderText", "Filter\u2026");
        filterField.setToolTipText("Show only nodes whose URL or grep keyword contains this text");
        filterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateFilter(); }
            private void updateFilter() {
                trafficPlotPanel.setFilterText(filterField.getText());
            }
        });

        // Focus: the label toggles it, the arrow picks which file types it hides (rebuilt on open for live counts)
        JPopupMenu focusMenu = new JPopupMenu();
        SplitToggleButton focusBtn = new SplitToggleButton("Focus", pinManager.getViewFilter().focusMode(), focusMenu);
        focusBtn.setToolTipText("Hide file types you don't need from the graph and step mode. Click \u25BE to choose which");
        focusBtn.addActionListener(e -> trafficPlotPanel.setFocusMode(focusBtn.isSelected()));
        focusMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                buildFocusMenu(focusMenu, focusBtn, pinManager);
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        });

        JToggleButton scopeBtn = new JToggleButton("Only In-Scope");
        scopeBtn.setToolTipText("Show only traffic in Burp's target scope");
        scopeBtn.addActionListener(e -> trafficPlotPanel.setShowOnlyInScope(scopeBtn.isSelected()));

        // ---- Group 3: clearing the graph and the rules sidebar ------------------------------------
        sidebarBtn = new JToggleButton();
        sidebarBtn.setToolTipText("Show or hide the breakpoints and live grep panel");
        sidebarBtn.addActionListener(e -> setSidebarVisible(sidebarBtn.isSelected()));

        // Lanes per detected flow (page load, route change, action) instead of per host.
        // On by default: the map is built per user flow; off shows one lane per host
        // Lanes: a plain dropdown; its label always says how the graph is grouped
        JPopupMenu lanesMenu = new JPopupMenu();
        lanesBtn = new JButton();
        lanesBtn.setToolTipText("How the graph's rows are grouped");
        lanesBtn.addActionListener(e -> {
            buildLanesMenu(lanesMenu, pinManager);
            lanesMenu.show(lanesBtn, 0, lanesBtn.getHeight());
        });
        trafficPlotPanel.setOnLaneOrderChanged(order -> updateLanesLabel());
        String savedOrder = pinManager.getUiPreference(PREF_LANE_ORDER);
        if ("FIRST_SEEN".equals(savedOrder)) trafficPlotPanel.setLaneOrder(TrafficPlotPanel.LaneOrder.FIRST_SEEN);
        setLanes(true, false);

        graphToolbar.add(interceptBtn);
        graphToolbar.add(Theme.gap(Theme.SPACE_S));
        graphToolbar.add(stepModeBtn);
        graphToolbar.add(Theme.gap(Theme.SPACE_S));
        graphToolbar.add(heldBtn);
        graphToolbar.add(Theme.divider());
        graphToolbar.add(filterField);
        graphToolbar.add(Theme.gap(Theme.SPACE_S));
        graphToolbar.add(lanesBtn);
        graphToolbar.add(Theme.gap(Theme.SPACE_S));
        graphToolbar.add(focusBtn);
        graphToolbar.add(Theme.gap(Theme.SPACE_S));
        graphToolbar.add(scopeBtn);
        JButton clearBtn = new JButton("Clear Graph");
        clearBtn.setToolTipText("Remove all nodes and flows (breakpoints and grep rules are kept)");
        clearBtn.addActionListener(e -> {
            pinManager.clearTrafficLog();
            trafficPlotPanel.updateLog(pinManager.getTrafficLog(), pinManager.getRecentHits(), pinManager.getFlows());
        });

        graphToolbar.add(Box.createHorizontalGlue());
        graphToolbar.add(clearBtn);
        graphToolbar.add(Theme.gap(Theme.SPACE_S));
        graphToolbar.add(sidebarBtn);

        JPanel graphPanel = new JPanel(new BorderLayout());
        // Legend pill floats over the graph's bottom-right corner; hover it for the legend
        graphPanel.add(new GraphLegendOverlay(graphScroll, () -> pinManager.getViewFilter().onlyInScope()), BorderLayout.CENTER);
        graphDetailSplit.setTopComponent(graphPanel);
        graphDetailSplit.setBottomComponent(detailsPanel);

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphDetailSplit, sidebarPanel);
        // Extra width always goes to the graph, never the sidebar
        mainSplitPane.setResizeWeight(1.0);
        // The toolbar spans the whole tab, so opening the sidebar never squeezes it
        mainPanel.add(graphToolbar, BorderLayout.NORTH);
        mainPanel.add(mainSplitPane, BorderLayout.CENTER);

        pinManager.addListener(() -> SwingUtilities.invokeLater(() -> {
            rulesSidebar.rulesChanged();
            sidebarBtn.setText(rulesSidebar.summary());
        }));
        sidebarBtn.setText(rulesSidebar.summary());

        // Throttle traffic events: refresh immediately when idle, otherwise coalesce
        // into one trailing refresh so rebuilds happen at most once per TRAFFIC_REFRESH_MS
        trafficRefreshTimer = new Timer(TRAFFIC_REFRESH_MS, e -> refreshTraffic(pinManager));
        trafficRefreshTimer.setRepeats(false);

        pinManager.addTrafficListener(() -> {
            if (trafficRefreshPending.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(() -> scheduleTrafficRefresh(pinManager));
            }
        });
    }

    private void scheduleTrafficRefresh(PinManager pinManager) {
        long sinceLastMs = (System.nanoTime() - lastTrafficRefreshNanos) / 1_000_000;
        long waitMs = TRAFFIC_REFRESH_MS - sinceLastMs;
        if (waitMs <= 0) {
            refreshTraffic(pinManager);
        } else {
            trafficRefreshTimer.setInitialDelay((int) waitMs);
            trafficRefreshTimer.restart();
        }
    }

    private void refreshTraffic(PinManager pinManager) {
        trafficRefreshPending.set(false);
        lastTrafficRefreshNanos = System.nanoTime();
        trafficPlotPanel.updateLog(pinManager.getTrafficLog(), pinManager.getRecentHits(), pinManager.getFlows());
        rulesSidebar.refreshCounts();
    }

    private void setSidebarVisible(boolean visible) {
        if (sidebarPanel.isVisible() == visible) return;
        if (!visible) lastSidebarDividerLocation = mainSplitPane.getDividerLocation();
        sidebarPanel.setVisible(visible);
        sidebarBtn.setSelected(visible);
        if (visible) {
            // Reopen at the width the user last left it
            int location = lastSidebarDividerLocation > 0
                    ? lastSidebarDividerLocation
                    : mainSplitPane.getWidth() - SIDEBAR_DEFAULT_WIDTH;
            mainSplitPane.setDividerLocation(Math.max(0, location));
        }
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    /**
     * Adds (or excludes) every origin seen for a host, e.g. both http:// and https://,
     * as a URL prefix rule; Burp converts it when advanced scope control is on.
     */
    private void changeScope(MontoyaApi api, PinManager pinManager, String host, boolean include) {
        java.util.Set<String> origins = new java.util.LinkedHashSet<>();
        for (TrafficEntry entry : pinManager.getTrafficLog()) {
            if (!entry.getHost().equals(host)) continue;
            try {
                // Query strings often hold characters java.net.URI rejects; only the origin matters here
                java.net.URI u = new java.net.URI(entry.getUrl().split("[?#]", 2)[0]);
                if (u.getScheme() == null) continue;
                origins.add(u.getScheme() + "://" + host + (u.getPort() != -1 ? ":" + u.getPort() : "") + "/");
            } catch (java.net.URISyntaxException ignored) {
            }
        }
        if (origins.isEmpty()) origins.add("https://" + host + "/");
        List<String> stillOut = new ArrayList<>();
        for (String origin : origins) {
            if (include) api.scope().includeInScope(origin);
            else api.scope().excludeFromScope(origin);
            api.logging().logToOutput("Pinpoint: " + (include ? "added to" : "excluded from") + " scope: " + origin);
            if (include && !api.scope().isInScope(origin)) stillOut.add(origin);
        }
        pinManager.refreshScope();

        // An existing exclude rule beats the include we just added; say so instead of silently failing
        if (!stillOut.isEmpty()) {
            JOptionPane.showMessageDialog(mainPanel,
                    host + " is still out of scope.\n\n"
                    + "An exclude rule overrides it. Delete that rule in\n"
                    + "Target > Scope settings to include this host.",
                    "Still out of scope", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** The Lanes dropdown: how the graph's rows are grouped, and in what order. */
    private void buildLanesMenu(JPopupMenu menu, PinManager pinManager) {
        menu.removeAll();
        boolean byType = pinManager.getViewFilter().groupByType();
        menu.add(menuHeader("Group"));
        ButtonGroup group = new ButtonGroup();
        addLaneOption(menu, group, "By flow (pages & actions)", flowLanes, () -> setLanes(true, byType));
        addLaneOption(menu, group, "By host", !flowLanes && !byType, () -> setLanes(false, false));
        addLaneOption(menu, group, "By host and file type", !flowLanes && byType, () -> setLanes(false, true));

        menu.addSeparator();
        menu.add(menuHeader("Order"));
        ButtonGroup order = new ButtonGroup();
        TrafficPlotPanel.LaneOrder current = trafficPlotPanel.getLaneOrder();
        addLaneOption(menu, order, "Newest first", current == TrafficPlotPanel.LaneOrder.NEWEST,
                () -> setLaneOrder(TrafficPlotPanel.LaneOrder.NEWEST));
        addLaneOption(menu, order, "First seen", current == TrafficPlotPanel.LaneOrder.FIRST_SEEN,
                () -> setLaneOrder(TrafficPlotPanel.LaneOrder.FIRST_SEEN));
        addLaneOption(menu, order, "Custom (drag a row's title to move it)", current == TrafficPlotPanel.LaneOrder.CUSTOM,
                () -> setLaneOrder(TrafficPlotPanel.LaneOrder.CUSTOM));
    }

    private static JComponent menuHeader(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.font(Font.BOLD, -2));
        label.setForeground(Theme.muted());
        label.setBorder(BorderFactory.createEmptyBorder(Theme.px(Theme.SPACE_S), Theme.px(Theme.SPACE_M), Theme.px(Theme.SPACE_XS), Theme.px(Theme.SPACE_M)));
        return label;
    }

    private void setLaneOrder(TrafficPlotPanel.LaneOrder order) {
        trafficPlotPanel.setLaneOrder(order);
        // Newest / first seen are remembered; a custom arrangement only applies to this session's rows
        if (order != TrafficPlotPanel.LaneOrder.CUSTOM) pinManager.setUiPreference(PREF_LANE_ORDER, order.name());
    }

    private static void addLaneOption(JPopupMenu menu, ButtonGroup group, String text, boolean selected, Runnable action) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(text, selected);
        item.addActionListener(e -> action.run());
        group.add(item);
        menu.add(item);
    }

    private void setLanes(boolean byFlow, boolean byType) {
        flowLanes = byFlow;
        trafficPlotPanel.setGroupByType(byType);
        trafficPlotPanel.setFlowMode(byFlow);
        updateLanesLabel();
    }

    /** "Custom" once the user has arranged rows themselves; otherwise how rows are grouped. */
    private void updateLanesLabel() {
        boolean byType = pinManager.getViewFilter().groupByType();
        String text = trafficPlotPanel.getLaneOrder() == TrafficPlotPanel.LaneOrder.CUSTOM ? "Custom"
                : flowLanes ? "By flow" : byType ? "By host & type" : "By host";
        lanesBtn.setText("Lanes: " + text + "  \u25BE");
    }

    private static final Map<String, String> TYPE_LABELS = Map.of("Endpoints", "Endpoints (pages & APIs)");

    /**
     * The Focus dropdown: one checkbox per file type (checked = shown while Focus is on), with how
     * many captured nodes are of that type, and a reset to the defaults. Changing a type turns Focus on.
     */
    private void buildFocusMenu(JPopupMenu menu, SplitToggleButton focusBtn, PinManager pinManager) {
        menu.removeAll();
        Map<String, Integer> counts = new HashMap<>();
        for (TrafficEntry e : pinManager.getTrafficLog()) {
            counts.merge(com.pinpoint.utils.TrafficUtils.getFileType(e.getPath()), 1, Integer::sum);
        }

        JLabel header = new JLabel("Show while Focus is on");
        header.setFont(Theme.font(Font.BOLD, -2));
        header.setForeground(Theme.muted());
        header.setBorder(BorderFactory.createEmptyBorder(Theme.px(Theme.SPACE_S), Theme.px(Theme.SPACE_M), Theme.px(Theme.SPACE_XS), Theme.px(Theme.SPACE_M)));
        menu.add(header);

        Set<String> hidden = pinManager.getViewFilter().focusHiddenTypes();
        for (String type : ViewFilter.ALL_TYPES) {
            String label = TYPE_LABELS.getOrDefault(type, type) + "   " + counts.getOrDefault(type, 0);
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(label, !hidden.contains(type));
            item.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", true);
            item.addActionListener(e -> {
                Set<String> updated = new HashSet<>(pinManager.getViewFilter().focusHiddenTypes());
                if (item.isSelected()) updated.remove(type);
                else updated.add(type);
                applyFocusTypes(focusBtn, updated);
            });
            menu.add(item);
        }

        menu.addSeparator();
        JMenuItem defaults = new JMenuItem("Restore defaults (hide JS, CSS, images, fonts)");
        defaults.addActionListener(e -> applyFocusTypes(focusBtn, ViewFilter.STATIC_TYPES));
        menu.add(defaults);
    }

    private void applyFocusTypes(SplitToggleButton focusBtn, Set<String> hiddenTypes) {
        trafficPlotPanel.setFocusHiddenTypes(hiddenTypes);
        if (!focusBtn.isSelected()) {
            focusBtn.setSelected(true);
            trafficPlotPanel.setFocusMode(true);
        }
    }

    private static String interceptLabel(boolean on) {
        return on ? "Intercept on" : "Intercept off";
    }

    /** Number of messages currently held (breakpoints and steps); called on the EDT. */
    private void setHeldCount(int count) {
        heldBtn.setText("\u23F8 " + count + " held");
        heldBtn.setVisible(count > 0);
        // Rows don't reorder while a held message is waiting to be edited
        trafficPlotPanel.setOrderFrozen(count > 0);
    }

    private TrafficEntry selectedNode() {
        return trafficPlotPanel != null ? trafficPlotPanel.getSelectedEntry() : null;
    }

    /**
     * The node's breakpoint dot (or double-click): adds an exact host+path breakpoint, or removes it.
     * If the node is only covered by broader rules (contains / regex), those may cover other nodes
     * too, so the user is asked before they're switched off rather than silently deleted.
     */
    private void toggleBreakpoint(TrafficEntry entry) {
        if (entry == null) return;
        String hostPath = entry.getHost() + entry.getPath();
        if (!entry.isBreakpoint()) {
            pinManager.addPin(hostPath, PinManager.MatchType.EXACT, true, true);
            return;
        }
        List<PinManager.PinRule> pins = pinManager.getPins();
        List<Integer> covering = new ArrayList<>();
        for (int i = 0; i < pins.size(); i++) {
            PinManager.PinRule pin = pins.get(i);
            if (!pin.isEnabled() || !PinManager.matchesPinPattern(pin, hostPath, entry.getUrl())) continue;
            if (pin.getMatchType() == PinManager.MatchType.EXACT && pin.getPattern().equals(hostPath)) {
                pinManager.removePin(i);   // this node's own breakpoint
                return;
            }
            covering.add(i);
        }
        if (covering.isEmpty()) return;
        StringBuilder rules = new StringBuilder();
        for (int i : covering) {
            PinManager.PinRule pin = pins.get(i);
            rules.append("\n   ").append(pin.getMatchType().label).append(":  ").append(pin.getPattern())
                    .append("   (").append(pinManager.countPinMatches(pin.getPattern(), pin.getMatchType())).append(" nodes)");
        }
        Object[] options = {covering.size() == 1 ? "Turn rule off" : "Turn rules off", "Cancel"};
        int choice = JOptionPane.showOptionDialog(mainPanel,
                "This node is covered by a broader breakpoint rule:" + rules
                        + "\n\nTurning it off affects every node it matches. You can turn it back on, or\n"
                        + "edit it, under Breakpoints & Grep.",
                "Turn off breakpoint", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (choice == 0) {
            for (int i : covering) pinManager.setPinEnabled(i, false);
        }
    }

    /** Bottom area shows the current held message, editable. */
    private void showHeld() {
        PendingItem item = interceptPanel.getCurrentItem();
        if (item == null) return;
        showingHeld = true;
        detailCards.show(detailsPanel, CARD_HELD);
        trafficPlotPanel.setSelectedEntry(item.getEntry());
        setDetailsVisible(true);
    }

    /** Bottom area shows a node's latest request/response, read-only. */
    private void showViewFor(TrafficEntry entry) {
        showingHeld = false;
        viewReqEditor.setRequest(entry.getRequest() != null
                ? entry.getRequest() : burp.api.montoya.http.message.requests.HttpRequest.httpRequest(""));
        if (entry.getResponse() != null) {
            viewResEditor.setResponse(entry.getResponse());
            viewResEditor.setSearchExpression(entry.getMatchedKeywords().isEmpty() ? "" : entry.getMatchedKeywords().iterator().next());
        } else {
            viewResEditor.setResponse(burp.api.montoya.http.message.responses.HttpResponse.httpResponse(""));
            viewResEditor.setSearchExpression("");
        }
        detailCards.show(detailsPanel, CARD_VIEW);
        trafficPlotPanel.setSelectedEntry(entry);
        setDetailsVisible(true);
    }

    /** Hides the request/response area so the graph takes the whole view. Held messages stay held. */
    private void closeDetails() {
        showingHeld = false;
        trafficPlotPanel.setSelectedEntry(null);
        setDetailsVisible(false);
    }

    private void setDetailsVisible(boolean visible) {
        if (detailsPanel.isVisible() == visible) return;
        if (!visible) lastDividerLocation = graphDetailSplit.getDividerLocation();
        detailsPanel.setVisible(visible);
        // Reopen at the size the user last left it
        if (visible) {
            if (lastDividerLocation > 0) graphDetailSplit.setDividerLocation(lastDividerLocation);
            else graphDetailSplit.setDividerLocation(0.6);
        }
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    public void shutdown() {
        trafficRefreshTimer.stop();
        trafficPlotPanel.shutdown();
    }

    public Component getPanel() {
        return mainPanel;
    }
}
