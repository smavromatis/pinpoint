package com.pinpoint.ui;

import com.pinpoint.model.FlowTracker;
import com.pinpoint.model.PinManager;
import com.pinpoint.model.TrafficEntry;
import com.pinpoint.model.ViewFilter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public class TrafficPlotPanel extends JPanel implements Scrollable {

    // Geometry, scaled with Burp's UI font (see updateMetrics); each lane has a full-width title bar
    private int laneTitleH, cardW, cardH, cardGap, laneGap, pad, nestIndent;

    // Live activity: how long a node glows after a hit, and the animation frame interval
    private static final int GLOW_MS = 1500;
    private static final int GLOW_FRAME_MS = 33;
    private static final int TRAIL_LENGTH = 8;

    private final List<TrafficEntry> log = new ArrayList<>();
    private final List<PinManager.Hit> recentHits = new ArrayList<>();
    private final javax.swing.Timer glowTimer;
    // Tracked by identity (not card index) so highlights survive re-layouts and log trimming
    private TrafficEntry hoveredEntry = null;
    private TrafficEntry selectedEntry = null;
    // Per-card breakpoint toggle (DevTools-gutter style), hit areas rebuilt on every paint
    // One per card (same index as cardRects): a node can appear in several flow lanes
    private final List<Rectangle> breakpointDotRects = new ArrayList<>();
    private TrafficEntry hoveredDotEntry = null;
    private final Consumer<TrafficEntry> onNodeClicked;
    private Consumer<TrafficEntry> onBreakpointAdd;
    // Shared with step mode via onFilterChanged, so hidden traffic is never held
    private ViewFilter filter;
    private Consumer<ViewFilter> onFilterChanged;

    private final List<Rectangle> cardRects = new ArrayList<>();
    private final List<Integer> cardToLogIndex = new ArrayList<>();
    private final Map<TrafficEntry, Integer> cardIndexByEntry = new IdentityHashMap<>();
    // Title bar of each lane (hit area for collapse / menu / tooltip), and the whole lane (for stripes)
    private final Map<String, Rectangle> laneHeaderRects = new LinkedHashMap<>();
    private final Map<String, Rectangle> laneRects = new HashMap<>();
    private final List<String> cardLaneKeys = new ArrayList<>();
    private final List<String> cardLabels = new ArrayList<>();

    // Flow lanes: one row per detected flow (page load, route change, action), steps left to right
    private static final String FLOW_LANE_PREFIX = "flow:";
    private static final String OTHER_FLOW_LANE = FLOW_LANE_PREFIX + "\u0000other";
    private record FlowLane(String title, String subtitle, boolean inScope) {}

    /** Order of the rows: most recent run first, order first seen, or the user's own (by dragging). */
    public enum LaneOrder { NEWEST, FIRST_SEEN, CUSTOM }
    private LaneOrder laneOrder = LaneOrder.NEWEST;
    private Consumer<LaneOrder> onLaneOrderChanged;
    // The user's arrangement (lane keys); only the relative order of siblings matters
    private final List<String> customOrder = new ArrayList<>();
    // While the user works in the graph (pointer over it, a message held, a drag), rows don't move:
    // the last top-level order is kept and new rows are added at the bottom
    private List<String> lastTopOrder = new ArrayList<>();
    private List<String> displayOrder = new ArrayList<>();
    private boolean pointerInside;
    private boolean orderFrozen;
    private boolean forceReorder;
    // When each lane last started a run (flows) or was hit (hosts), and every lane's cards,
    // including lanes hidden inside a collapsed page (for the collapsed-activity hint)
    private final Map<String, Long> laneRecency = new HashMap<>();
    private final Map<String, List<Integer>> laneEntries = new HashMap<>();
    // Dragging a lane's title bar to reorder it among its siblings
    private String pressedLane;
    private Point pressPoint;
    private boolean draggingLane;
    private int dragY;
    private String dropBefore;     // sibling the dragged lane goes in front of; null = after the last one
    private int dropLineY = -1;
    private boolean flowMode = false;
    private final List<FlowTracker.FlowView> flows = new ArrayList<>();
    private final Map<String, FlowLane> flowLanes = new HashMap<>();
    // Actions are nested under the page they happened on: indented, and hidden when the page is collapsed
    private final Map<String, Integer> laneIndent = new HashMap<>();
    private final Map<String, String> laneParent = new HashMap<>();
    // "+ Scope" buttons on out-of-scope lane headers, keyed by lane; rebuilt on every paint
    private final Map<String, Rectangle> scopeButtonRects = new HashMap<>();
    private Consumer<String> onIncludeHost;
    private Consumer<String> onExcludeHost;

    public TrafficPlotPanel(Consumer<TrafficEntry> onNodeClicked, ViewFilter initialFilter) {
        this.onNodeClicked = onNodeClicked;
        this.filter = initialFilter;
        updateMetrics();

        // Runs only while some node is glowing; stops itself once everything has faded
        this.glowTimer = new javax.swing.Timer(GLOW_FRAME_MS, e -> {
            repaint();
            if (!hasActiveGlow()) ((javax.swing.Timer) e.getSource()).stop();
        });

        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int idx = getCardAt(e.getX(), e.getY());
                TrafficEntry entry = idx >= 0 && idx < cardToLogIndex.size() ? log.get(cardToLogIndex.get(idx)) : null;
                String scopeLane = getScopeButtonAt(e.getPoint());
                TrafficEntry dotEntry = getBreakpointDotAt(e.getPoint());
                setCursor(Cursor.getPredefinedCursor(scopeLane != null || dotEntry != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                if (scopeLane != null) {
                    setToolTipText("Add " + hostOfLane(scopeLane) + " to Burp's target scope");
                } else if (dotEntry != null) {
                    setToolTipText(dotEntry.isBreakpoint() ? "Remove breakpoint" : "Set breakpoint");
                } else if (flowLaneAt(e.getPoint()) != null) {
                    FlowLane lane = flowLanes.get(flowLaneAt(e.getPoint()));
                    setToolTipText(lane.title() + "  \u2014  " + lane.subtitle() + "   (click to collapse, drag to reorder)");
                } else if (laneTitleAt(e.getPoint()) != null) {
                    setToolTipText("Click to collapse, drag to reorder, right-click for scope options");
                } else if (entry != hoveredEntry || entry == null || hoveredDotEntry != null) {
                    setToolTipText(entry != null ? entry.getHost() + entry.getPath() : null);
                }
                if (entry != hoveredEntry || dotEntry != hoveredDotEntry) {
                    hoveredEntry = entry;
                    hoveredDotEntry = dotEntry;
                    repaint();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                pointerInside = true;
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoveredEntry = null;
                hoveredDotEntry = null;
                pointerInside = false;
                // Apply any reordering held back while the pointer was over the graph
                relayout();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                String lane = SwingUtilities.isLeftMouseButton(e) ? laneTitleAt(e.getPoint()) : null;
                pressedLane = lane != null && !lane.equals(OTHER_FLOW_LANE) && getScopeButtonAt(e.getPoint()) == null ? lane : null;
                pressPoint = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressedLane == null) return;
                if (!draggingLane && pressPoint.distance(e.getPoint()) < Theme.px(6)) return;
                draggingLane = true;
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                dragY = e.getY();
                updateDropTarget();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (draggingLane) {
                    finishLaneDrag();
                    return;
                }
                pressedLane = null;
                // Breakpoint dot: toggles without selecting the node or opening the editor
                TrafficEntry dotEntry = getBreakpointDotAt(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && dotEntry != null) {
                    if (onBreakpointAdd != null) onBreakpointAdd.accept(dotEntry);
                    return;
                }
                String scopeLane = getScopeButtonAt(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && scopeLane != null) {
                    if (onIncludeHost != null) onIncludeHost.accept(hostOfLane(scopeLane));
                    return;
                }
                String titleLane = laneTitleAt(e.getPoint());
                if (titleLane != null) {
                    if (SwingUtilities.isRightMouseButton(e)) showLaneContextMenu(e, titleLane);
                    else if (SwingUtilities.isLeftMouseButton(e)) applyFilter(filter.withLaneToggled(titleLane));
                    return;
                }

                if (SwingUtilities.isRightMouseButton(e)) {
                    int idx = getCardAt(e.getX(), e.getY());
                    if (idx >= 0 && idx < cardToLogIndex.size()) {
                        showContextMenu(e, log.get(cardToLogIndex.get(idx)));
                    }
                    return;
                }
                
                int idx = getCardAt(e.getX(), e.getY());
                if (idx >= 0 && idx < cardToLogIndex.size()) {
                    // Double-click toggles breakpoint
                    if (e.getClickCount() >= 2) {
                        if (onBreakpointAdd != null) {
                            onBreakpointAdd.accept(log.get(cardToLogIndex.get(idx)));
                        }
                    } else {
                        selectedEntry = log.get(cardToLogIndex.get(idx));
                        repaint();
                        if (onNodeClicked != null) {
                            onNodeClicked.accept(selectedEntry);
                        }
                    }
                } else {
                    selectedEntry = null;
                    repaint();
                    if (onNodeClicked != null) {
                        onNodeClicked.accept(null);
                    }
                }
            }

        };
        addMouseMotionListener(ma);
        addMouseListener(ma);
    }

    public TrafficEntry getSelectedEntry() {
        return selectedEntry;
    }

    /** Highlights a node without notifying onNodeClicked (e.g. when a held message is shown). */
    public void setSelectedEntry(TrafficEntry entry) {
        if (entry != selectedEntry) {
            selectedEntry = entry;
            repaint();
        }
    }

    /** Lane header "+ Scope" button / context menu: called with the lane's host. */
    public void setOnIncludeHost(Consumer<String> handler) {
        this.onIncludeHost = handler;
    }

    public void setOnExcludeHost(Consumer<String> handler) {
        this.onExcludeHost = handler;
    }

    private static String hostOfLane(String laneKey) {
        int split = laneKey.indexOf("::");
        return split >= 0 ? laneKey.substring(0, split) : laneKey;
    }

    private TrafficEntry getBreakpointDotAt(Point p) {
        for (int i = 0; i < breakpointDotRects.size() && i < cardToLogIndex.size(); i++) {
            if (breakpointDotRects.get(i).contains(p)) return log.get(cardToLogIndex.get(i));
        }
        return null;
    }

    private String laneTitleAt(Point p) {
        for (Map.Entry<String, Rectangle> lane : laneHeaderRects.entrySet()) {
            if (lane.getValue().contains(p)) return lane.getKey();
        }
        return null;
    }

    private String flowLaneAt(Point p) {
        String lane = flowMode ? laneTitleAt(p) : null;
        return lane != null && flowLanes.containsKey(lane) ? lane : null;
    }

    /** Scope colours only mean something when out-of-scope traffic is shown next to in-scope traffic. */
    private boolean showScopeColors() {
        return !filter.onlyInScope();
    }

    private static boolean isFlowLane(String laneKey) {
        return laneKey.startsWith(FLOW_LANE_PREFIX);
    }

    /** Lanes become one row per detected flow instead of one per host. */
    public void setFlowMode(boolean flowMode) {
        this.flowMode = flowMode;
        layoutCards();
        revalidate();
        repaint();
    }

    private String getScopeButtonAt(Point p) {
        for (Map.Entry<String, Rectangle> e : scopeButtonRects.entrySet()) {
            if (e.getValue().contains(p)) return e.getKey();
        }
        return null;
    }

    private boolean isHostInScope(String host) {
        for (TrafficEntry e : log) {
            if (e.getHost().equals(host) && e.isInScope()) return true;
        }
        return false;
    }

    private void showLaneContextMenu(MouseEvent e, String laneKey) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem collapse = new JMenuItem(filter.collapsedLanes().contains(laneKey) ? "Expand lane" : "Collapse lane");
        collapse.addActionListener(a -> applyFilter(filter.withLaneToggled(laneKey)));
        if (isFlowLane(laneKey)) {
            popup.add(collapse);
            popup.show(this, e.getX(), e.getY());
            return;
        }
        String host = hostOfLane(laneKey);
        JMenuItem include = new JMenuItem("Add " + host + " to scope");
        include.addActionListener(a -> { if (onIncludeHost != null) onIncludeHost.accept(host); });
        JMenuItem exclude = new JMenuItem("Exclude " + host + " from scope");
        exclude.addActionListener(a -> { if (onExcludeHost != null) onExcludeHost.accept(host); });
        boolean inScope = isHostInScope(host);
        include.setEnabled(!inScope);
        exclude.setEnabled(inScope);
        popup.add(include);
        popup.add(exclude);
        popup.addSeparator();
        popup.add(collapse);
        popup.show(this, e.getX(), e.getY());
    }

    /** Text shortened with an ellipsis to fit maxWidth in the current font. */
    private static String fitText(Graphics2D g2, String text, int maxWidth) {
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(text) <= maxWidth) return text;
        for (int len = text.length() - 1; len > 0; len--) {
            String candidate = text.substring(0, len) + "\u2026";
            if (fm.stringWidth(candidate) <= maxWidth) return candidate;
        }
        return "\u2026";
    }

    /** Scales geometry with Burp's UI font, so cards and titles grow with Burp's font-size setting. */
    private void updateMetrics() {
        FontMetrics path = getFontMetrics(Theme.mono(Font.BOLD, 0));
        FontMetrics info = getFontMetrics(Theme.font(Font.PLAIN, -3));
        FontMetrics badge = getFontMetrics(Theme.font(Font.PLAIN, -4));
        FontMetrics title = getFontMetrics(Theme.font(Font.BOLD, -1));
        cardW = Theme.px(220);
        cardH = Math.max(Theme.px(52), Theme.px(6) + path.getHeight() + info.getHeight() + badge.getHeight() + Theme.px(4));
        cardGap = Theme.px(Theme.SPACE_L);
        laneGap = Theme.px(Theme.SPACE_XS);
        pad = Theme.px(10);
        laneTitleH = Math.max(Theme.px(24), title.getHeight() + Theme.px(Theme.SPACE_M));
        nestIndent = Theme.px(22);
    }

    public void setOnBreakpointAdd(Consumer<TrafficEntry> handler) {
        this.onBreakpointAdd = handler;
    }

    public void setOnFilterChanged(Consumer<ViewFilter> handler) {
        this.onFilterChanged = handler;
    }

    public void setShowOnlyInScope(boolean showOnlyInScope) {
        applyFilter(filter.withOnlyInScope(showOnlyInScope));
    }

    public void setFilterText(String text) {
        applyFilter(filter.withText(text));
    }

    public void setGroupByType(boolean groupByType) {
        applyFilter(filter.withGroupByType(groupByType));
    }

    public void setFocusMode(boolean focusMode) {
        applyFilter(filter.withFocusMode(focusMode));
    }

    /** Which file types Focus hides (see ViewFilter.ALL_TYPES). */
    public void setFocusHiddenTypes(Set<String> hiddenTypes) {
        applyFilter(filter.withFocusHiddenTypes(hiddenTypes));
    }

    private void applyFilter(ViewFilter newFilter) {
        this.filter = newFilter;
        if (onFilterChanged != null) onFilterChanged.accept(newFilter);
        layoutCards();
        revalidate();
        repaint();
    }

    private void showContextMenu(MouseEvent e, TrafficEntry entry) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem bpItem = new JMenuItem(entry.isBreakpoint() ? "Remove Breakpoint" : "Add Breakpoint Here");
        bpItem.addActionListener(a -> {
            if (onBreakpointAdd != null) onBreakpointAdd.accept(entry);
        });
        popup.add(bpItem);
        popup.show(this, e.getX(), e.getY());
    }

    private int getCardAt(int mx, int my) {
        for (int i = 0; i < cardRects.size(); i++) {
            if (cardRects.get(i).contains(mx, my)) return i;
        }
        return -1;
    }

    public void updateLog(List<TrafficEntry> newLog, List<PinManager.Hit> newRecentHits, List<FlowTracker.FlowView> newFlows) {
        this.log.clear();
        this.log.addAll(newLog);
        this.flows.clear();
        this.flows.addAll(newFlows);
        this.recentHits.clear();
        this.recentHits.addAll(newRecentHits);
        if (hasActiveGlow() && !glowTimer.isRunning()) glowTimer.start();
        if (selectedEntry != null && !containsIdentity(log, selectedEntry)) selectedEntry = null;
        if (hoveredEntry != null && !containsIdentity(log, hoveredEntry)) hoveredEntry = null;
        layoutCards();
        revalidate();
        repaint();
    }

    private static boolean containsIdentity(List<TrafficEntry> entries, TrafficEntry target) {
        for (TrafficEntry e : entries) {
            if (e == target) return true;
        }
        return false;
    }

    private boolean hasActiveGlow() {
        long now = System.currentTimeMillis();
        for (TrafficEntry e : log) {
            if (now - e.getLastActivityMillis() < GLOW_MS || e.isPaused()) return true;
        }
        return false;
    }

    /** 1.0 right after the hit, fading linearly to 0.0 after GLOW_MS. */
    private static float glowStrength(long activityMillis, long now) {
        long age = Math.max(0, now - activityMillis);
        if (age >= GLOW_MS) return 0f;
        return 1f - (float) age / GLOW_MS;
    }

    /** Requests glow blue; responses glow by status class. */
    private static Color activityColor(TrafficEntry entry) {
        if (entry.getLastActivity() == TrafficEntry.Activity.REQUEST) return Theme.inScope();
        int status = entry.getStatusCode();
        if (status >= 400) return Theme.error();
        if (status >= 300) return Theme.redirect();
        return Theme.success();
    }

    /** Point on the border of r along the line from its center toward (tx, ty). */
    private static double[] edgePoint(Rectangle r, double tx, double ty) {
        double cx = r.getCenterX(), cy = r.getCenterY();
        double dx = tx - cx, dy = ty - cy;
        if (dx == 0 && dy == 0) return new double[]{cx, cy};
        double sx = dx == 0 ? Double.MAX_VALUE : (r.width / 2.0 + 3) / Math.abs(dx);
        double sy = dy == 0 ? Double.MAX_VALUE : (r.height / 2.0 + 3) / Math.abs(dy);
        double s = Math.min(sx, sy);
        return new double[]{cx + dx * s, cy + dy * s};
    }

    /**
     * Arrows between consecutive recent hits on visible cards, fading with the later hit's glow.
     * Hidden hits are skipped, so the trail connects straight across them.
     */
    private void drawTrail(Graphics2D g2, long now) {
        List<PinManager.Hit> visible = new ArrayList<>();
        for (PinManager.Hit hit : recentHits) {
            if (cardIndexByEntry.containsKey(hit.entry())) visible.add(hit);
        }
        int start = Math.max(0, visible.size() - TRAIL_LENGTH);
        TrafficEntry prev = null;
        for (PinManager.Hit hit : visible.subList(start, visible.size())) {
            TrafficEntry cur = hit.entry();
            if (prev != null && prev != cur) {
                float strength = glowStrength(hit.millis(), now);
                if (strength > 0) {
                    drawTrailArrow(g2, cardRects.get(cardIndexByEntry.get(prev)), cardRects.get(cardIndexByEntry.get(cur)), strength);
                }
            }
            prev = cur;
        }
    }

    /** Pulsing orange halo and a pause badge for a node holding a request and/or response. */
    private void drawPaused(Graphics2D g2, TrafficEntry entry, Rectangle r, long now) {
        float pulse = 0.55f + 0.45f * (float) Math.sin(now / 200.0);
        int halo = Theme.px(3);
        g2.setColor(Theme.withAlpha(Theme.accent(), (int) (pulse * 200)));
        g2.setStroke(new BasicStroke(Theme.px(4)));
        g2.draw(new RoundRectangle2D.Float(r.x - halo, r.y - halo, r.width + 2 * halo, r.height + 2 * halo, Theme.px(12), Theme.px(12)));
        g2.setStroke(new BasicStroke(1f));

        int reqs = entry.getPausedRequests();
        int resps = entry.getPausedResponses();
        String label = "\u23F8 " + (reqs > 0 && resps > 0 ? "REQ+RES" : reqs > 0 ? "REQ" : "RES");
        int total = reqs + resps;
        if (total > 1) label += " \u00D7" + total;
        g2.setFont(Theme.font(Font.BOLD, -3));
        FontMetrics fm = g2.getFontMetrics();
        int bw = fm.stringWidth(label) + Theme.px(Theme.SPACE_M);
        int bh = fm.getHeight();
        int bx = r.x + r.width - bw - Theme.px(6);
        int by = r.y + r.height - bh - Theme.px(3);
        g2.setColor(Theme.accent());
        g2.fillRoundRect(bx, by, bw, bh, Theme.px(6), Theme.px(6));
        g2.setColor(Color.WHITE);
        g2.drawString(label, bx + Theme.px(Theme.SPACE_S), by + fm.getAscent());
    }

    private void drawTrailArrow(Graphics2D g2, Rectangle from, Rectangle to, float strength) {
        double[] p1 = edgePoint(from, to.getCenterX(), to.getCenterY());
        double[] p2 = edgePoint(to, from.getCenterX(), from.getCenterY());
        // Bend the arrow sideways so it doesn't sit on top of the lane connectors
        double mx = (p1[0] + p2[0]) / 2, my = (p1[1] + p2[1]) / 2;
        double dx = p2[0] - p1[0], dy = p2[1] - p1[1];
        double len = Math.max(1, Math.hypot(dx, dy));
        double bend = Math.min(30, len / 4);
        double ctrlX = mx - dy / len * bend, ctrlY = my + dx / len * bend;

        g2.setColor(Theme.withAlpha(Theme.trail(), (int) (strength * 220)));
        g2.setStroke(new BasicStroke(Theme.px(2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
        path.moveTo(p1[0], p1[1]);
        path.quadTo(ctrlX, ctrlY, p2[0], p2[1]);
        g2.draw(path);

        // Arrowhead aligned with the curve's tangent at the end
        double ax = p2[0] - ctrlX, ay = p2[1] - ctrlY;
        double alen = Math.max(1, Math.hypot(ax, ay));
        ax /= alen;
        ay /= alen;
        int size = Theme.px(7);
        int[] xs = {(int) p2[0], (int) (p2[0] - ax * size - ay * size * 0.6), (int) (p2[0] - ax * size + ay * size * 0.6)};
        int[] ys = {(int) p2[1], (int) (p2[1] - ay * size + ax * size * 0.6), (int) (p2[1] - ay * size - ax * size * 0.6)};
        g2.fillPolygon(xs, ys, 3);
        g2.setStroke(new BasicStroke(1f));
    }

    public void shutdown() {
        glowTimer.stop();
    }

    private void layoutCards() {
        updateMetrics();
        cardRects.clear();
        cardToLogIndex.clear();
        cardIndexByEntry.clear();
        laneHeaderRects.clear();
        laneRects.clear();
        cardLaneKeys.clear();
        cardLabels.clear();

        laneRecency.clear();
        LinkedHashMap<String, List<Integer>> grouped = flowMode ? groupByFlow() : groupByHost();
        laneEntries.clear();
        laneEntries.putAll(grouped);
        if (!flowMode) {
            for (Map.Entry<String, List<Integer>> g : grouped.entrySet()) {
                long latest = 0;
                for (int idx : g.getValue()) latest = Math.max(latest, log.get(idx).getLastActivityMillis());
                laneRecency.put(g.getKey(), latest);
            }
        }
        LinkedHashMap<String, List<Integer>> hostGroups = orderLanes(grouped);

        int availableWidth = getWidth();
        if (getParent() instanceof JViewport) {
            availableWidth = ((JViewport) getParent()).getWidth();
        }
        int minWidth = cardW + cardGap * 2;
        if (availableWidth <= minWidth) {
            availableWidth = minWidth;
        }

        int y = pad;
        int maxX = minWidth;
        for (Map.Entry<String, List<Integer>> group : hostGroups.entrySet()) {
            List<Integer> indices = group.getValue();
            String groupKey = group.getKey();
            int startY = y;
            
            String parent = laneParent.get(groupKey);
            if (parent != null && filter.collapsedLanes().contains(parent)) continue;
            int indent = laneIndent.getOrDefault(groupKey, 0);

            laneHeaderRects.put(groupKey, new Rectangle(indent, startY, availableWidth - indent, laneTitleH));
            if (filter.collapsedLanes().contains(groupKey)) {
                laneRects.put(groupKey, new Rectangle(0, startY, availableWidth, laneTitleH));
                y += laneTitleH + laneGap;
                continue;
            }

            int x = cardGap + indent;
            int currentY = startY + laneTitleH + 4;
            int rows = 1;

            int step = 0;
            for (int idx : indices) {
                step++;
                if (x + cardW > availableWidth - cardGap && x > cardGap + indent) {
                    x = cardGap + indent;
                    currentY += cardH + cardGap;
                    rows++;
                }
                cardIndexByEntry.putIfAbsent(log.get(idx), cardRects.size());
                cardRects.add(new Rectangle(x, currentY, cardW, cardH));
                cardToLogIndex.add(idx);
                cardLaneKeys.add(groupKey);
                // Flow lanes number their steps; host lanes show the node's position in the log
                boolean numbered = flowMode && !groupKey.equals(OTHER_FLOW_LANE);
                cardLabels.add(numbered ? String.valueOf(step) : "#" + (idx + 1));
                x += cardW + cardGap;
                maxX = Math.max(maxX, x);
            }
            
            int laneHeight = laneTitleH + 4 + rows * (cardH + cardGap);
            laneRects.put(groupKey, new Rectangle(0, startY, availableWidth, laneHeight));

            y += laneHeight + laneGap;
        }

        setPreferredSize(new Dimension(maxX, Math.max(y + pad, 120)));
    }

    /**
     * Puts lanes in display order: top-level lanes by the chosen order, each followed by its
     * nested actions; "Other traffic" last. While frozen, rows keep their places and new ones
     * go at the bottom, so nothing moves under the user's pointer.
     */
    private LinkedHashMap<String, List<Integer>> orderLanes(LinkedHashMap<String, List<Integer>> grouped) {
        List<String> tops = new ArrayList<>();
        Map<String, List<String>> children = new HashMap<>();
        for (String key : grouped.keySet()) {
            String parent = laneParent.get(key);
            if (parent != null && grouped.containsKey(parent)) children.computeIfAbsent(parent, k -> new ArrayList<>()).add(key);
            else if (!key.equals(OTHER_FLOW_LANE)) tops.add(key);
        }

        List<String> orderedTops;
        boolean frozen = !forceReorder && (pointerInside || orderFrozen || draggingLane);
        if (frozen && !lastTopOrder.isEmpty()) {
            orderedTops = new ArrayList<>();
            for (String key : lastTopOrder) if (tops.contains(key)) orderedTops.add(key);
            for (String key : tops) if (!orderedTops.contains(key)) orderedTops.add(key);
        } else {
            orderedTops = sorted(tops);
        }
        lastTopOrder = new ArrayList<>(orderedTops);
        forceReorder = false;

        LinkedHashMap<String, List<Integer>> ordered = new LinkedHashMap<>();
        for (String top : orderedTops) {
            ordered.put(top, grouped.get(top));
            List<String> kids = children.getOrDefault(top, List.of());
            // Actions keep their order within a page, unless the user arranged them
            for (String kid : laneOrder == LaneOrder.CUSTOM ? sorted(kids) : kids) ordered.put(kid, grouped.get(kid));
        }
        if (grouped.containsKey(OTHER_FLOW_LANE)) ordered.put(OTHER_FLOW_LANE, grouped.get(OTHER_FLOW_LANE));
        displayOrder = new ArrayList<>(ordered.keySet());
        return ordered;
    }

    private List<String> sorted(List<String> keys) {
        List<String> result = new ArrayList<>(keys);
        Comparator<String> newest = Comparator.comparingLong((String k) -> laneRecency.getOrDefault(k, 0L)).reversed();
        switch (laneOrder) {
            case NEWEST -> result.sort(newest);
            case FIRST_SEEN -> { }
            case CUSTOM -> {
                // Rows the user hasn't placed yet (new ones) go first, newest first; the rest keep the user's order
                List<String> unplaced = new ArrayList<>(), placed = new ArrayList<>();
                for (String k : result) (customOrder.contains(k) ? placed : unplaced).add(k);
                unplaced.sort(newest);
                placed.sort(Comparator.comparingInt(customOrder::indexOf));
                result.clear();
                result.addAll(unplaced);
                result.addAll(placed);
            }
        }
        return result;
    }

    public void setOnLaneOrderChanged(Consumer<LaneOrder> handler) {
        this.onLaneOrderChanged = handler;
    }

    public LaneOrder getLaneOrder() {
        return laneOrder;
    }

    /** Chosen in the Lanes menu: applies right away, even with the pointer over the graph. */
    public void setLaneOrder(LaneOrder order) {
        if (order == LaneOrder.CUSTOM && customOrder.isEmpty()) customOrder.addAll(displayOrder);
        laneOrder = order;
        forceReorder = true;
        relayout();
        if (onLaneOrderChanged != null) onLaneOrderChanged.accept(order);
    }

    /** While something is held for editing, rows stay put; the order catches up afterwards. */
    public void setOrderFrozen(boolean frozen) {
        if (orderFrozen == frozen) return;
        orderFrozen = frozen;
        if (!frozen) relayout();
    }

    private void relayout() {
        layoutCards();
        revalidate();
        repaint();
    }

    /** Siblings of a lane in display order: other top-level lanes, or the actions of the same page. */
    private List<String> siblingsOf(String lane) {
        String parent = laneParent.get(lane);
        List<String> siblings = new ArrayList<>();
        for (String key : displayOrder) {
            if (key.equals(OTHER_FLOW_LANE)) continue;
            if (Objects.equals(laneParent.get(key), parent)) siblings.add(key);
        }
        return siblings;
    }

    /** Vertical extent of a lane on screen, including the actions nested under a page. */
    private int[] laneBlock(String lane) {
        Rectangle r = laneRects.get(lane);
        if (r == null) return null;
        int top = r.y, bottom = r.y + r.height;
        for (Map.Entry<String, String> child : laneParent.entrySet()) {
            Rectangle cr = child.getValue().equals(lane) ? laneRects.get(child.getKey()) : null;
            if (cr != null) bottom = Math.max(bottom, cr.y + cr.height);
        }
        return new int[]{top, bottom};
    }

    private void updateDropTarget() {
        dropBefore = null;
        dropLineY = -1;
        int lastBottom = -1;
        for (String sibling : siblingsOf(pressedLane)) {
            int[] block = laneBlock(sibling);
            if (block == null) continue;
            if (dropBefore == null && dragY < (block[0] + block[1]) / 2) {
                dropBefore = sibling;
                dropLineY = block[0];
            }
            lastBottom = Math.max(lastBottom, block[1]);
        }
        if (dropBefore == null) dropLineY = lastBottom;
    }

    /** Dropping a dragged lane switches the order to Custom, starting from what's on screen. */
    private void finishLaneDrag() {
        String moved = pressedLane;
        String before = dropBefore;
        pressedLane = null;
        draggingLane = false;
        dropLineY = -1;
        setCursor(Cursor.getDefaultCursor());
        if (moved == null || moved.equals(before)) {
            repaint();
            return;
        }
        List<String> siblings = siblingsOf(moved);
        customOrder.clear();
        customOrder.addAll(displayOrder);
        customOrder.remove(moved);
        int index = before != null ? customOrder.indexOf(before)
                : customOrder.indexOf(siblings.get(siblings.size() - 1)) + 1;
        customOrder.add(Math.max(0, Math.min(index, customOrder.size())), moved);
        laneOrder = LaneOrder.CUSTOM;
        forceReorder = true;
        relayout();
        if (onLaneOrderChanged != null) onLaneOrderChanged.accept(laneOrder);
    }

    /** What's happening inside a collapsed lane (and the actions hidden with it). */
    private record HiddenActivity(float glow, Color color, boolean held) {}

    private HiddenActivity hiddenActivity(String lane, long now) {
        List<Integer> indices = new ArrayList<>(laneEntries.getOrDefault(lane, List.of()));
        for (Map.Entry<String, String> child : laneParent.entrySet()) {
            if (child.getValue().equals(lane)) indices.addAll(laneEntries.getOrDefault(child.getKey(), List.of()));
        }
        float glow = 0;
        Color color = null;
        boolean held = false;
        for (int idx : indices) {
            if (idx >= log.size()) continue;
            TrafficEntry e = log.get(idx);
            held |= e.isPaused();
            float g = glowStrength(e.getLastActivityMillis(), now);
            if (g > glow) {
                glow = g;
                color = activityColor(e);
            }
        }
        return new HiddenActivity(glow, color, held);
    }

    private LinkedHashMap<String, List<Integer>> groupByHost() {
        LinkedHashMap<String, List<Integer>> tempGroups = new LinkedHashMap<>();
        for (int i = 0; i < log.size(); i++) {
            TrafficEntry e = log.get(i);
            if (!filter.matches(e)) continue;

            if (e.isInScope()) {
                String key = filter.laneKey(e.getHost(), e.getPath());
                tempGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
        }
        if (!filter.onlyInScope()) {
            for (int i = 0; i < log.size(); i++) {
                TrafficEntry e = log.get(i);
                if (!filter.matches(e)) continue;

                if (!e.isInScope()) {
                    String key = filter.laneKey(e.getHost(), e.getPath());
                    tempGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
                }
            }
        }

        LinkedHashMap<String, List<Integer>> hostGroups = new LinkedHashMap<>();
        if (filter.groupByType()) {
            String[] typeOrder = {"Endpoints", "JavaScript", "CSS", "Images", "Fonts", "Other"};
            List<String> hosts = new ArrayList<>();
            for (String k : tempGroups.keySet()) {
                String h = k.contains("::") ? k.split("::")[0] : k;
                if (!hosts.contains(h)) hosts.add(h);
            }
            for (String h : hosts) {
                for (String t : typeOrder) {
                    String k = h + "::" + t;
                    if (tempGroups.containsKey(k)) hostGroups.put(k, tempGroups.get(k));
                }
            }
        } else {
            hostGroups.putAll(tempGroups);
        }
        return hostGroups;
    }

    /** One lane per flow (its latest run's steps, in order), then an "Other traffic" lane for the rest. */
    private LinkedHashMap<String, List<Integer>> groupByFlow() {
        flowLanes.clear();
        laneIndent.clear();
        laneParent.clear();
        Map<String, Integer> actionCounts = new HashMap<>();
        for (FlowTracker.FlowView flow : flows) {
            if (flow.parentKey() != null) actionCounts.merge(flow.parentKey(), 1, Integer::sum);
        }
        Map<TrafficEntry, Integer> logIndex = new IdentityHashMap<>();
        for (int i = 0; i < log.size(); i++) logIndex.put(log.get(i), i);

        LinkedHashMap<String, List<Integer>> groups = new LinkedHashMap<>();
        Set<TrafficEntry> assigned = Collections.newSetFromMap(new IdentityHashMap<>());
        for (FlowTracker.FlowView flow : flows) {
            List<Integer> steps = new ArrayList<>();
            boolean inScope = false;
            for (TrafficEntry step : flow.steps()) {
                Integer idx = logIndex.get(step);
                if (idx == null) continue;   // evicted from the log
                assigned.add(step);
                if (!filter.matches(step)) continue;
                steps.add(idx);
                inScope |= step.isInScope();
            }
            if (steps.isEmpty()) continue;
            String laneKey = FLOW_LANE_PREFIX + flow.key();
            groups.put(laneKey, steps);
            String parentLane = flow.parentKey() != null ? FLOW_LANE_PREFIX + flow.parentKey() : null;
            if (parentLane != null && groups.containsKey(parentLane)) {
                laneIndent.put(laneKey, nestIndent);
                laneParent.put(laneKey, parentLane);
            }
            String runs = flow.runs() > 1 ? "\u00D7" + flow.runs() + " \u00B7 " : "";
            int actions = actionCounts.getOrDefault(flow.key(), 0);
            String nested = actions == 0 ? "" : " \u00B7 " + actions + (actions == 1 ? " action" : " actions");
            flowLanes.put(laneKey, new FlowLane(flow.name(), runs + flow.kind().label + nested, inScope));
            laneRecency.merge(laneKey, flow.lastStartMillis(), Math::max);
            if (laneParent.containsKey(laneKey)) laneRecency.merge(laneParent.get(laneKey), flow.lastStartMillis(), Math::max);
        }

        List<Integer> other = new ArrayList<>();
        boolean otherInScope = false;
        for (int i = 0; i < log.size(); i++) {
            TrafficEntry e = log.get(i);
            if (!assigned.contains(e) && filter.matches(e)) {
                other.add(i);
                otherInScope |= e.isInScope();
            }
        }
        if (!other.isEmpty()) {
            groups.put(OTHER_FLOW_LANE, other);
            flowLanes.put(OTHER_FLOW_LANE, new FlowLane("Other traffic", "not part of a detected flow", otherInScope));
        }
        return groups;
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        layoutCards();
    }

    @Override
    protected void paintComponent(Graphics g) {
        setBackground(Theme.background());
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int fullW = Math.max(getWidth(), (int) getPreferredSize().getWidth());

        if (log.isEmpty()) {
            drawCenteredMessage(g2, "No traffic captured yet. Browse with the proxy to build the map.");
            return;
        }

        // Draw lanes: alternating stripes, each with a full-width title bar
        scopeButtonRects.clear();
        int laneIdx = 0;
        for (Map.Entry<String, Rectangle> laneEntry : laneHeaderRects.entrySet()) {
            String groupKey = laneEntry.getKey();
            Rectangle lane = laneRects.getOrDefault(groupKey, laneEntry.getValue());
            if (laneIdx % 2 == 1) {
                g2.setColor(Theme.overlay(8));
                g2.fillRect(0, lane.y, fullW, lane.height);
            }
            g2.setColor(Theme.separator());
            g2.drawLine(0, lane.y + lane.height, fullW, lane.y + lane.height);
            if (laneParent.containsKey(groupKey)) {
                // Guide line tying an action to the page above it
                g2.setColor(Theme.overlay(45));
                g2.setStroke(new BasicStroke(Theme.px(2)));
                g2.drawLine(nestIndent / 2, lane.y, nestIndent / 2, lane.y + lane.height);
                g2.setStroke(new BasicStroke(1f));
            }

            FlowLane flowLane = flowLanes.get(groupKey);
            if (isFlowLane(groupKey) && flowLane != null) {
                drawLaneTitle(g2, groupKey, laneEntry.getValue(), fullW, flowLane.title(), flowLane.subtitle(), flowLane.inScope(), null);
            } else {
                String host = hostOfLane(groupKey);
                String type = groupKey.contains("::") ? groupKey.substring(groupKey.indexOf("::") + 2).toUpperCase() : null;
                boolean inScope = isHostInScope(host);
                // "+ Scope" pill on out-of-scope host lanes: one click adds the host to Burp's scope
                String pillLane = !inScope && host.length() > 1 ? groupKey : null;
                drawLaneTitle(g2, groupKey, laneEntry.getValue(), fullW, host, type, inScope, pillLane);
            }
            if (filter.collapsedLanes().contains(groupKey)) {
                drawHiddenActivity(g2, laneEntry.getValue(), fullW, hiddenActivity(groupKey, System.currentTimeMillis()));
            }
            laneIdx++;
        }

        // Draw connectors between consecutive steps in a lane row
        g2.setStroke(new BasicStroke(1f));
        int arrow = Theme.px(5);
        for (int i = 0; i < cardRects.size() - 1; i++) {
            if (cardLaneKeys.get(i).equals(cardLaneKeys.get(i + 1))) {
                Rectangle from = cardRects.get(i);
                Rectangle to = cardRects.get(i + 1);
                if (from.y == to.y && from.x < to.x) {
                    int fy = from.y + from.height / 2;
                    g2.setColor(Theme.overlay(70));
                    g2.drawLine(from.x + from.width + 1, fy, to.x - 1, fy);
                    g2.fillPolygon(new int[]{to.x, to.x - arrow, to.x - arrow}, new int[]{fy, fy - arrow / 2 - 1, fy + arrow / 2 + 1}, 3);
                }
            }
        }

        long now = System.currentTimeMillis();

        // Draw cards
        breakpointDotRects.clear();
        for (int i = 0; i < cardRects.size(); i++) {
            int logIdx = cardToLogIndex.get(i);
            TrafficEntry entry = log.get(logIdx);
            Rectangle r = cardRects.get(i);
            drawCard(g2, entry, r, entry == hoveredEntry, entry == selectedEntry, cardLabels.get(i), now);
        }

        // Live trail on top so it stays visible across lanes; flow lanes already show the order
        if (!flowMode) drawTrail(g2, now);
        if (draggingLane) drawDragFeedback(g2, fullW);

        if (flowMode && laneHeaderRects.isEmpty()) {
            drawCenteredMessage(g2, "No flows yet. Load or refresh a page, or use the app, to see its flows.");
        }
    }

    // Card labels depend only on path, font and width; computing them is the costliest part of
    // drawing a card, and paint runs ~30x/s while nodes glow. Bounded, and reset on font change.
    private final Map<String, String> labelCache = new HashMap<>();
    private Font labelCacheFont;

    private String cardLabel(String path, FontMetrics fm, int maxWidth) {
        if (!fm.getFont().equals(labelCacheFont) || labelCache.size() > 4000) {
            labelCache.clear();
            labelCacheFont = fm.getFont();
        }
        return labelCache.computeIfAbsent(maxWidth + "|" + path,
                k -> com.pinpoint.utils.TrafficUtils.endpointLabel(path, fm::stringWidth, maxWidth));
    }

    /**
     * Subtle hint that something inside a collapsed lane is active: a coloured strip and tint that
     * fade like the card glow; a pulsing orange strip and "held" label when a message is waiting.
     */
    private void drawHiddenActivity(Graphics2D g2, Rectangle bar, int fullW, HiddenActivity activity) {
        if (!activity.held() && activity.glow() <= 0) return;
        Color color = activity.held() ? Theme.accent() : activity.color();
        float strength = activity.held() ? 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 200.0) : activity.glow();
        g2.setColor(Theme.withAlpha(color, (int) (strength * 40)));
        g2.fillRect(bar.x, bar.y, fullW - bar.x, bar.height);
        g2.setColor(Theme.withAlpha(color, (int) (strength * 230)));
        g2.fillRect(bar.x, bar.y, Theme.px(4), bar.height);
        if (activity.held()) {
            String label = "\u23F8 held";
            g2.setFont(Theme.font(Font.BOLD, -3));
            FontMetrics fm = g2.getFontMetrics();
            int x = bar.x + bar.width - fm.stringWidth(label) - Theme.px(Theme.SPACE_L);
            g2.setColor(Theme.accent());
            g2.drawString(label, x, bar.y + (bar.height + fm.getAscent() - fm.getDescent()) / 2);
        }
    }

    /** While dragging a lane: an insertion line where it will land, and its title following the pointer. */
    private void drawDragFeedback(Graphics2D g2, int fullW) {
        Rectangle bar = laneHeaderRects.get(pressedLane);
        if (bar == null) return;
        if (dropLineY >= 0) {
            g2.setColor(Theme.accent());
            g2.fillRect(bar.x, dropLineY - 1, fullW - bar.x, Theme.px(3));
        }
        int y = dragY - bar.height / 2;
        g2.setColor(Theme.withAlpha(Theme.surface(), 220));
        g2.fillRect(bar.x, y, fullW - bar.x, bar.height);
        g2.setColor(Theme.accent());
        g2.drawRect(bar.x, y, fullW - bar.x - 1, bar.height - 1);
        FlowLane lane = flowLanes.get(pressedLane);
        String title = lane != null ? lane.title() : hostOfLane(pressedLane);
        g2.setFont(Theme.font(Font.BOLD, -1));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(Theme.foreground());
        g2.drawString(title, bar.x + Theme.px(Theme.SPACE_L), y + (bar.height + fm.getAscent() - fm.getDescent()) / 2);
    }

    private void drawCenteredMessage(Graphics2D g2, String message) {
        g2.setColor(Theme.muted());
        g2.setFont(Theme.font(Font.ITALIC, 0));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(message, Math.max(Theme.px(Theme.SPACE_L), (getWidth() - fm.stringWidth(message)) / 2), getHeight() / 2);
    }

    /**
     * A lane's title bar: collapse arrow, scope dot, the full title, a muted subtitle after it
     * (flow runs and kind, or the file type), and optionally a "+ Scope" pill right after that.
     */
    private void drawLaneTitle(Graphics2D g2, String laneKey, Rectangle bar, int fullW, String title,
                               String subtitle, boolean inScope, String scopePillLane) {
        boolean scopeColors = showScopeColors();
        Color accent = !scopeColors ? Theme.foreground() : inScope ? Theme.inScope() : Theme.outOfScope();
        g2.setColor(scopeColors ? Theme.withAlpha(accent, inScope ? 22 : 12) : Theme.overlay(10));
        g2.fillRect(bar.x, bar.y, fullW - bar.x, bar.height);

        Font titleFont = Theme.font(Font.BOLD, -1);
        FontMetrics tm = g2.getFontMetrics(titleFont);
        int baseline = bar.y + (bar.height + tm.getAscent() - tm.getDescent()) / 2;
        int x = bar.x + Theme.px(Theme.SPACE_M);

        g2.setFont(Theme.font(Font.PLAIN, -3));
        g2.setColor(Theme.muted());
        String arrow = filter.collapsedLanes().contains(laneKey) ? "\u25B6" : "\u25BC";
        g2.drawString(arrow, x, baseline);
        x += g2.getFontMetrics().stringWidth(arrow) + Theme.px(Theme.SPACE_M);
        if (scopeColors) {
            int dot = Theme.px(8);
            g2.setColor(accent);
            g2.fillOval(x, bar.y + (bar.height - dot) / 2, dot, dot);
            x += dot + Theme.px(6);
        }

        int available = Math.max(Theme.px(60), bar.x + bar.width - x - Theme.px(90));
        g2.setFont(titleFont);
        g2.setColor(accent);
        String shownTitle = fitText(g2, title, available);
        g2.drawString(shownTitle, x, baseline);
        x += tm.stringWidth(shownTitle) + Theme.px(10);

        if (subtitle != null && !subtitle.isEmpty()) {
            g2.setFont(Theme.font(Font.PLAIN, -3));
            g2.setColor(Theme.muted());
            g2.drawString(subtitle, x, baseline);
            x += g2.getFontMetrics().stringWidth(subtitle) + Theme.px(10);
        }

        if (scopePillLane != null) {
            g2.setFont(Theme.font(Font.BOLD, -3));
            FontMetrics pm = g2.getFontMetrics();
            String pillText = "+ Scope";
            int pw = pm.stringWidth(pillText) + Theme.px(10);
            int ph = pm.getHeight() + Theme.px(2);
            Rectangle pill = new Rectangle(x, bar.y + (bar.height - ph) / 2, pw, ph);
            scopeButtonRects.put(scopePillLane, pill);
            g2.setColor(Theme.inScope());
            g2.drawRoundRect(pill.x, pill.y, pill.width, pill.height, ph, ph);
            g2.drawString(pillText, pill.x + Theme.px(5), pill.y + Theme.px(1) + pm.getAscent());
        }
    }

    /**
     * One node: path (status colour), status history and hit count, method and REQ/RES, keyword
     * badge, step / log number and breakpoint dot. All positions derive from font metrics so the
     * card scales with Burp's font size; all colours come from the theme.
     */
    private void drawCard(Graphics2D g2, TrafficEntry entry, Rectangle r, boolean hovered, boolean selected, String label, long now) {
        int radius = Theme.px(Theme.RADIUS);
        Color selBg = Theme.selectionBackground();
        Color selFg = Theme.selectionForeground();

        // Live activity halo, drawn behind the card
        float glow = glowStrength(entry.getLastActivityMillis(), now);
        Color glowColor = glow > 0 ? activityColor(entry) : null;
        if (glowColor != null) {
            int halo = Theme.px(3);
            g2.setColor(Theme.withAlpha(glowColor, (int) (glow * 140)));
            g2.setStroke(new BasicStroke(Theme.px(5)));
            g2.draw(new RoundRectangle2D.Float(r.x - halo, r.y - halo, r.width + 2 * halo, r.height + 2 * halo, radius + 4, radius + 4));
            g2.setStroke(new BasicStroke(1f));
        }

        // Fill
        g2.setColor(selected ? selBg : hovered ? Theme.blend(Theme.surface(), selBg, 0.15f) : Theme.surface());
        g2.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, radius, radius));
        if (glowColor != null && !selected) {
            g2.setColor(Theme.withAlpha(glowColor, (int) (glow * 45)));
            g2.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, radius, radius));
        }

        // Border: breakpoint = accent thick, in-scope = solid, out-of-scope = dashed
        if (entry.isBreakpoint()) {
            g2.setStroke(new BasicStroke(Theme.px(2) + 0.5f));
            g2.setColor(Theme.accent());
        } else if (entry.isInScope()) {
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(showScopeColors() ? Theme.inScope() : Theme.neutralBorder());
        } else {
            float dash = Theme.px(4);
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{dash, dash}, 0f));
            g2.setColor(Theme.outOfScope());
        }
        g2.draw(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, radius, radius));
        g2.setStroke(new BasicStroke(1f));

        Font pathFont = Theme.mono(Font.BOLD, 0);
        Font infoFont = Theme.font(Font.PLAIN, -3);
        Font badgeFont = Theme.font(Font.PLAIN, -4);
        Font seqFont = Theme.font(Font.BOLD, -3);
        FontMetrics pathFm = g2.getFontMetrics(pathFont);
        FontMetrics infoFm = g2.getFontMetrics(infoFont);
        FontMetrics badgeFm = g2.getFontMetrics(badgeFont);
        int padX = Theme.px(6);
        int line1 = r.y + Theme.px(4) + pathFm.getAscent();
        int line2 = line1 + pathFm.getDescent() + infoFm.getAscent();
        int line3 = line2 + infoFm.getDescent() + badgeFm.getAscent();
        Color secondary = selected ? Theme.withAlpha(selFg, 200) : Theme.muted();

        // Top right: step / log number
        g2.setFont(seqFont);
        int seqX = r.x + r.width - g2.getFontMetrics().stringWidth(label) - padX;
        g2.setColor(secondary);
        g2.drawString(label, seqX, line1);

        // Breakpoint dot: solid when set; an empty circle on hover invites a click to set one
        int dotSize = Theme.px(10);
        int dotX = seqX - dotSize - Theme.px(4);
        int dotY = line1 - pathFm.getAscent() / 2 - dotSize / 2;
        int hit = Theme.px(3);
        breakpointDotRects.add(new Rectangle(dotX - hit, dotY - hit, dotSize + 2 * hit, dotSize + 2 * hit));
        boolean dotHovered = entry == hoveredDotEntry;
        if (entry.isBreakpoint()) {
            g2.setColor(dotHovered ? Theme.accent().darker() : Theme.accent());
            g2.fillOval(dotX, dotY, dotSize, dotSize);
        } else if (hovered || dotHovered) {
            g2.setColor(dotHovered ? Theme.accent() : secondary);
            g2.setStroke(new BasicStroke(dotHovered ? 2f : 1.2f));
            g2.drawOval(dotX, dotY, dotSize, dotSize);
            g2.setStroke(new BasicStroke(1f));
        }

        // Path: normal text for 2xx (the common case), only redirects and errors are coloured so they
        // stand out; always leaves room for the breakpoint dot
        Color textPrimary;
        if (selected) textPrimary = selFg;
        else if (!entry.isInScope()) textPrimary = Theme.outOfScope();
        else if (entry.getStatusCode() >= 400) textPrimary = Theme.error();
        else if (entry.getStatusCode() >= 300) textPrimary = Theme.redirect();
        else textPrimary = Theme.foreground();
        g2.setFont(pathFont);
        g2.setColor(textPrimary);
        int pathMaxWidth = dotX - Theme.px(4) - (r.x + padX);
        String path = cardLabel(entry.getPath(), pathFm, pathMaxWidth);
        g2.drawString(path, r.x + padX, line1);

        // Status history + hits
        StringBuilder info = new StringBuilder();
        if (entry.getStatusHistory() != null && entry.getStatusHistory().size() > 1) {
            for (int i = 0; i < entry.getStatusHistory().size(); i++) {
                info.append(entry.getStatusHistory().get(i));
                if (i < entry.getStatusHistory().size() - 1) info.append(" \u2192 ");
            }
            info.append("  ");
        } else if (entry.getStatusCode() > 0) {
            info.append(entry.getStatusCode()).append("  ");
        }
        info.append("\u00D7").append(entry.getHitCount());
        g2.setFont(infoFont);
        g2.setColor(secondary);
        g2.drawString(info.toString(), r.x + padX, line2);

        // Live-grep keyword badge, right-aligned on the info line
        if (entry.getMatchedKeywords() != null && !entry.getMatchedKeywords().isEmpty()) {
            g2.setFont(Theme.font(Font.BOLD, -4));
            FontMetrics km = g2.getFontMetrics();
            String matchText = fitText(g2, String.join(", ", entry.getMatchedKeywords()), r.width / 2);
            int kw = km.stringWidth(matchText) + Theme.px(6);
            int kh = km.getHeight();
            int kx = r.x + r.width - kw - padX;
            int ky = line2 - infoFm.getAscent();
            g2.setColor(Theme.error());
            g2.fillRoundRect(kx, ky, kw, kh, Theme.px(4), Theme.px(4));
            g2.setColor(Color.WHITE);
            g2.drawString(matchText, kx + Theme.px(3), ky + km.getAscent());
        }

        // Method and whether a response was seen
        String badge = entry.getResponse() != null ? "REQ+RES" : "REQ";
        if (entry.getRequest() != null) badge = entry.getRequest().method() + " \u00B7 " + badge;
        g2.setFont(badgeFont);
        g2.setColor(secondary);
        g2.drawString(badge, r.x + padX, line3);

        if (entry.isPaused()) drawPaused(g2, entry, r, now);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 40;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 200;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        if (getParent() instanceof JViewport) {
            return (((JViewport) getParent()).getWidth() > cardW + cardGap * 2);
        }
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        // Stretch to fill the visible area when the content is shorter, so a click anywhere
        // below the last row still reaches the graph (and deselects / closes the details)
        if (getParent() instanceof JViewport) {
            return ((JViewport) getParent()).getHeight() > getPreferredSize().height;
        }
        return false;
    }
}
