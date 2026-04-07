package com.pinpoint.ui;

import com.pinpoint.model.TrafficEntry;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public class TrafficPlotPanel extends JPanel implements Scrollable {

    private static final int LANE_HEADER_W = 180;
    private static final int CARD_W = 220;
    private static final int CARD_H = 52;
    private static final int CARD_GAP = 12;
    private static final int LANE_GAP = 2;
    private static final int PAD = 10;

    private static final Color COLOR_IN_SCOPE = new Color(0, 120, 215);
    private static final Color COLOR_OUT_SCOPE = new Color(160, 160, 160);
    private static final Color COLOR_BREAKPOINT = new Color(255, 100, 30);
    private static final Color COLOR_SUCCESS = new Color(40, 150, 40);
    private static final Color COLOR_REDIRECT = new Color(200, 150, 0);
    private static final Color COLOR_ERROR = new Color(200, 50, 50);

    private final List<TrafficEntry> log = new ArrayList<>();
    private int hoveredIndex = -1;
    private int selectedIndex = -1;
    private final Consumer<TrafficEntry> onNodeClicked;
    private Consumer<TrafficEntry> onBreakpointAdd;
    private boolean showOnlyInScope = false;
    private boolean groupByType = false;
    private boolean showDataFlowLinks = false;
    private String currentFilter = "";
    private final java.util.Set<String> collapsedLanes = new java.util.HashSet<>();
    private final List<int[]> dataFlowEdges = new ArrayList<>();
    
    private final java.util.concurrent.ExecutorService dataFlowExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> currentDataFlowTask = null;

    private final List<Rectangle> cardRects = new ArrayList<>();
    private final List<Integer> cardToLogIndex = new ArrayList<>();
    private final Map<String, Rectangle> laneHeaderRects = new LinkedHashMap<>();

    public TrafficPlotPanel(Consumer<TrafficEntry> onNodeClicked) {
        this.onNodeClicked = onNodeClicked;

        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int idx = getCardAt(e.getX(), e.getY());
                if (idx != hoveredIndex) {
                    hoveredIndex = idx;
                    if (idx >= 0 && idx < cardToLogIndex.size()) {
                        TrafficEntry entry = log.get(cardToLogIndex.get(idx));
                        setToolTipText(entry.getHost() + entry.getPath());
                    } else {
                        setToolTipText(null);
                    }
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoveredIndex = -1;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getX() <= LANE_HEADER_W) {
                    for (Map.Entry<String, Rectangle> laneEntry : laneHeaderRects.entrySet()) {
                        if (laneEntry.getValue().contains(e.getPoint())) {
                            String k = laneEntry.getKey();
                            if (collapsedLanes.contains(k)) collapsedLanes.remove(k);
                            else collapsedLanes.add(k);
                            layoutCards();
                            revalidate();
                            repaint();
                            return;
                        }
                    }
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
                        selectedIndex = idx;
                        repaint();
                        if (onNodeClicked != null) {
                            onNodeClicked.accept(log.get(cardToLogIndex.get(idx)));
                        }
                    }
                } else {
                    selectedIndex = -1;
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

    public void setOnBreakpointAdd(Consumer<TrafficEntry> handler) {
        this.onBreakpointAdd = handler;
    }

    public void setShowOnlyInScope(boolean showOnlyInScope) {
        this.showOnlyInScope = showOnlyInScope;
        layoutCards();
        revalidate();
        repaint();
    }

    public void setFilterText(String text) {
        this.currentFilter = text == null ? "" : text.toLowerCase();
        layoutCards();
        revalidate();
        repaint();
    }

    public void setGroupByType(boolean groupByType) {
        this.groupByType = groupByType;
        layoutCards();
        revalidate();
        repaint();
    }

    public void setShowDataFlowLinks(boolean showDataFlowLinks) {
        this.showDataFlowLinks = showDataFlowLinks;
        if (showDataFlowLinks) {
            computeDataFlowEdgesAsync();
        } else {
            dataFlowEdges.clear();
            repaint();
        }
    }

    private boolean matchesFilter(TrafficEntry e) {
        if (currentFilter.isEmpty()) return true;
        return e.getUrl().toLowerCase().contains(currentFilter)
            || (!e.getMatchedKeywords().isEmpty()
                && e.getMatchedKeywords().stream().anyMatch(k -> k.toLowerCase().contains(currentFilter)));
    }

    private void computeDataFlowEdgesAsync() {
        if (currentDataFlowTask != null && !currentDataFlowTask.isDone()) {
            currentDataFlowTask.cancel(true);
        }
        currentDataFlowTask = dataFlowExecutor.submit(() -> {
            List<int[]> edges = new ArrayList<>();
            List<TrafficEntry> snap = new ArrayList<>(log);
            for (int i = 0; i < snap.size(); i++) {
                if (Thread.currentThread().isInterrupted()) return;
                TrafficEntry to = snap.get(i);
                if (to.getRequest() == null) continue;
                String toUrl = to.getUrl();
                int qIdx = toUrl.indexOf('?');
                if (qIdx == -1) continue;
                String query = toUrl.substring(qIdx + 1);
                if (query.length() < 3) continue;
                
                String[] params = query.split("&");
                for (int j = i - 1; j >= 0; j--) {
                    if (Thread.currentThread().isInterrupted()) return;
                    TrafficEntry from = snap.get(j);
                    if (from.getResponse() != null) {
                        String fromBody = from.getResponse().bodyToString();
                        if (fromBody == null) continue;
                        boolean matched = false;
                        for (String p : params) {
                            String[] kv = p.split("=");
                            if (kv.length == 2 && kv[1].length() >= 8) {
                                if (fromBody.contains(kv[1])) {
                                    edges.add(new int[]{j, i});
                                    matched = true;
                                    break;
                                }
                            }
                        }
                        if (matched) break;
                    }
                }
            }
            if (!Thread.currentThread().isInterrupted()) {
                SwingUtilities.invokeLater(() -> {
                    dataFlowEdges.clear();
                    dataFlowEdges.addAll(edges);
                    repaint();
                });
            }
        });
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

    /** Truncates long paths keeping the last segment visible. */
    private String shortenPath(String path, int maxLen) {
        if (path.length() <= maxLen) return path;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            String lastSegment = path.substring(lastSlash);
            if (lastSegment.length() <= maxLen - 3) return "..." + lastSegment;
            return "..." + lastSegment.substring(lastSegment.length() - (maxLen - 3));
        }
        return "..." + path.substring(path.length() - (maxLen - 3));
    }

    private int getCardAt(int mx, int my) {
        for (int i = 0; i < cardRects.size(); i++) {
            if (cardRects.get(i).contains(mx, my)) return i;
        }
        return -1;
    }

    public void updateLog(List<TrafficEntry> newLog) {
        this.log.clear();
        this.log.addAll(newLog);
        if (selectedIndex >= cardRects.size()) selectedIndex = -1;
        if (showDataFlowLinks) computeDataFlowEdgesAsync();
        layoutCards();
        revalidate();
        repaint();
    }

    public void shutdown() {
        if (currentDataFlowTask != null) {
            currentDataFlowTask.cancel(true);
        }
        dataFlowExecutor.shutdownNow();
    }

    private void layoutCards() {
        cardRects.clear();
        cardToLogIndex.clear();
        laneHeaderRects.clear();

        LinkedHashMap<String, List<Integer>> tempGroups = new LinkedHashMap<>();
        for (int i = 0; i < log.size(); i++) {
            TrafficEntry e = log.get(i);
            if (!matchesFilter(e)) continue;

            if (e.isInScope()) {
                String key = groupByType ? e.getHost() + "::" + com.pinpoint.utils.TrafficUtils.getFileType(e.getPath()) : e.getHost();
                tempGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
        }
        if (!showOnlyInScope) {
            for (int i = 0; i < log.size(); i++) {
                TrafficEntry e = log.get(i);
                if (!matchesFilter(e)) continue;

                if (!e.isInScope()) {
                    String key = groupByType ? e.getHost() + "::" + com.pinpoint.utils.TrafficUtils.getFileType(e.getPath()) : e.getHost();
                    tempGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
                }
            }
        }

        LinkedHashMap<String, List<Integer>> hostGroups = new LinkedHashMap<>();
        if (groupByType) {
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

        int availableWidth = getWidth();
        if (getParent() instanceof JViewport) {
            availableWidth = ((JViewport) getParent()).getWidth();
        }
        int minWidth = LANE_HEADER_W + CARD_W + CARD_GAP * 2;
        if (availableWidth <= minWidth) {
            availableWidth = minWidth;
        }

        int y = PAD;
        int maxX = minWidth;
        for (Map.Entry<String, List<Integer>> group : hostGroups.entrySet()) {
            List<Integer> indices = group.getValue();
            String groupKey = group.getKey();
            int startY = y;
            
            if (collapsedLanes.contains(groupKey)) {
                laneHeaderRects.put(groupKey, new Rectangle(0, startY, LANE_HEADER_W, 30));
                y += 30 + LANE_GAP;
                continue;
            }

            int x = LANE_HEADER_W + CARD_GAP;
            int currentY = startY + CARD_GAP;
            int rows = 1;

            for (int idx : indices) {
                if (x + CARD_W > availableWidth - CARD_GAP && x > LANE_HEADER_W + CARD_GAP) {
                    x = LANE_HEADER_W + CARD_GAP;
                    currentY += CARD_H + CARD_GAP;
                    rows++;
                }
                cardRects.add(new Rectangle(x, currentY, CARD_W, CARD_H));
                cardToLogIndex.add(idx);
                x += CARD_W + CARD_GAP;
                maxX = Math.max(maxX, x);
            }
            
            int laneHeight = rows * (CARD_H + CARD_GAP) + CARD_GAP;
            laneHeaderRects.put(groupKey, new Rectangle(0, startY, LANE_HEADER_W, laneHeight));

            y += laneHeight + LANE_GAP;
        }

        setPreferredSize(new Dimension(maxX, Math.max(y + PAD, 120)));
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        layoutCards();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color selBg = UIManager.getColor("List.selectionBackground");
        if (selBg == null) selBg = new Color(51, 153, 255);
        int fullW = Math.max(getWidth(), (int) getPreferredSize().getWidth());

        if (log.isEmpty()) {
            g2.setColor(Color.GRAY);
            g2.setFont(new Font("SansSerif", Font.ITALIC, 12));
            g2.drawString("No traffic captured yet. Browse with the proxy to build the flow.", getWidth() / 2 - 200, getHeight() / 2);
            return;
        }

        // Draw lanes
        int laneIdx = 0;
        for (Map.Entry<String, Rectangle> laneEntry : laneHeaderRects.entrySet()) {
            String groupKey = laneEntry.getKey();
            Rectangle hr = laneEntry.getValue();
            
            String host = groupKey;
            String type = null;
            if (groupKey.contains("::")) {
                int split = groupKey.indexOf("::");
                host = groupKey.substring(0, split);
                type = groupKey.substring(split + 2);
            }

            final String fHost = host;
            boolean isInScope = log.stream().anyMatch(e -> e.getHost().equals(fHost) && e.isInScope());

            // Lane stripe
            if (laneIdx % 2 == 1) {
                g2.setColor(new Color(0, 0, 0, 12));
                g2.fillRect(0, hr.y, fullW, hr.height);
            }

            // Lane header background
            g2.setColor(isInScope ? new Color(COLOR_IN_SCOPE.getRed(), COLOR_IN_SCOPE.getGreen(), COLOR_IN_SCOPE.getBlue(), 15)
                    : new Color(0, 0, 0, 8));
            g2.fillRect(0, hr.y, LANE_HEADER_W, hr.height);

            // Separator
            g2.setColor(new Color(0, 0, 0, 30));
            g2.drawLine(LANE_HEADER_W, hr.y, LANE_HEADER_W, hr.y + hr.height);
            g2.drawLine(0, hr.y + hr.height, fullW, hr.y + hr.height);

            // Scope dot + host label
            int dotY = hr.y + hr.height / 2 - 4;
            g2.setColor(isInScope ? COLOR_IN_SCOPE : COLOR_OUT_SCOPE);
            g2.fillOval(8, dotY, 8, 8);

            // Collapsible arrow
            g2.setFont(new Font("SansSerif", Font.BOLD, 10));
            g2.setColor(new Color(120, 120, 120, 180));
            g2.drawString(collapsedLanes.contains(groupKey) ? "▶" : "▼", LANE_HEADER_W - 16, hr.y + 16);

            g2.setFont(new Font("SansSerif", Font.BOLD, 11));
            g2.setColor(isInScope ? COLOR_IN_SCOPE : COLOR_OUT_SCOPE);
            String displayHost = host.length() > 16 ? host.substring(0, 13) + "..." : host;
            
            if (type != null) {
                g2.drawString(displayHost, 22, hr.y + hr.height / 2 - 4);
                g2.setFont(new Font("SansSerif", Font.BOLD, 9));
                g2.setColor(isInScope ? new Color(COLOR_IN_SCOPE.getRed(), COLOR_IN_SCOPE.getGreen(), COLOR_IN_SCOPE.getBlue(), 180) : new Color(130, 130, 130));
                g2.drawString(type.toUpperCase(), 22, hr.y + hr.height / 2 + 8);
            } else {
                g2.drawString(displayHost, 22, hr.y + hr.height / 2 + 4);
            }

            laneIdx++;
        }

        // Draw connectors
        g2.setStroke(new BasicStroke(1f));
        for (int i = 0; i < cardRects.size() - 1; i++) {
            int logIdx = cardToLogIndex.get(i);
            int nextLogIdx = cardToLogIndex.get(i + 1);
            if (log.get(logIdx).getHost().equals(log.get(nextLogIdx).getHost())) {
                Rectangle from = cardRects.get(i);
                Rectangle to = cardRects.get(i + 1);
                if (from.y == to.y && from.x < to.x) {
                    int fy = from.y + from.height / 2;
                    g2.setColor(new Color(0, 0, 0, 40));
                    g2.drawLine(from.x + from.width + 1, fy, to.x - 1, fy);
                    g2.fillPolygon(new int[]{to.x, to.x - 5, to.x - 5}, new int[]{fy, fy - 3, fy + 3}, 3);
                }
            }
        }

        // Draw data flow edges
        if (showDataFlowLinks && !dataFlowEdges.isEmpty()) {
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{4}, 0));
            g2.setColor(new Color(255, 140, 0, 160));
            for (int[] edge : dataFlowEdges) {
                int fIdx = cardToLogIndex.indexOf(edge[0]);
                int tIdx = cardToLogIndex.indexOf(edge[1]);
                if (fIdx >= 0 && tIdx >= 0) {
                    Rectangle fromR = cardRects.get(fIdx);
                    Rectangle toR = cardRects.get(tIdx);
                    int x1 = fromR.x + fromR.width;
                    int y1 = fromR.y + fromR.height / 2;
                    int x2 = toR.x;
                    int y2 = toR.y + toR.height / 2;
                    int ctrlX = (x1 + x2) / 2;
                    java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
                    path.moveTo(x1, y1);
                    path.curveTo(ctrlX, y1, ctrlX, y2, x2, y2);
                    g2.draw(path);
                }
            }
            g2.setStroke(new BasicStroke(1f));
        }

        // Draw cards
        for (int i = 0; i < cardRects.size(); i++) {
            int logIdx = cardToLogIndex.get(i);
            TrafficEntry entry = log.get(logIdx);
            Rectangle r = cardRects.get(i);
            drawCard(g2, entry, r, i == hoveredIndex, i == selectedIndex, selBg, logIdx);
        }
    }

    private void drawCard(Graphics2D g2, TrafficEntry entry, Rectangle r, boolean hovered, boolean selected, Color selBg, int logIdx) {
        Color cardBg = getBackground();
        if (cardBg == null) cardBg = Color.WHITE;

        // Fill
        if (selected) {
            g2.setColor(selBg);
        } else if (hovered) {
            g2.setColor(new Color(selBg.getRed(), selBg.getGreen(), selBg.getBlue(), 40));
        } else {
            g2.setColor(cardBg);
        }
        g2.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8));

        // Border: breakpoint = orange thick, in-scope = solid, out-of-scope = dashed
        if (entry.isBreakpoint()) {
            g2.setStroke(new BasicStroke(2.5f));
            g2.setColor(COLOR_BREAKPOINT);
        } else if (entry.isInScope()) {
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(COLOR_IN_SCOPE);
        } else {
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g2.setColor(COLOR_OUT_SCOPE);
        }
        g2.draw(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8));
        g2.setStroke(new BasicStroke(1f));

        // Top right seq number
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        String seqNum = "#" + (logIdx + 1);
        int snWidth = g2.getFontMetrics().stringWidth(seqNum);
        g2.setColor(selected ? new Color(255, 255, 255, 180) : new Color(150, 150, 150, 180));
        int seqX = r.x + r.width - snWidth - 6;
        g2.drawString(seqNum, seqX, r.y + 14);

        // Breakpoint indicator dot
        if (entry.isBreakpoint()) {
            g2.setColor(COLOR_BREAKPOINT);
            g2.fillOval(seqX - 12, r.y + 6, 8, 8);
        }

        // Path text - show last segment(s) so cards are distinguishable
        Color textPrimary;
        if (selected) {
            textPrimary = Color.WHITE;
        } else if (!entry.isInScope()) {
            textPrimary = COLOR_OUT_SCOPE;
        } else if (entry.getStatusCode() >= 400) {
            textPrimary = COLOR_ERROR;
        } else if (entry.getStatusCode() >= 300) {
            textPrimary = COLOR_REDIRECT;
        } else if (entry.getStatusCode() >= 200) {
            textPrimary = COLOR_SUCCESS;
        } else {
            textPrimary = getForeground();
        }
        g2.setFont(new Font("Monospaced", Font.BOLD, 12));
        g2.setColor(textPrimary);
        String path = shortenPath(entry.getPath(), 18);
        g2.drawString(path, r.x + 6, r.y + 18);

        // Keyword Badging
        if (entry.getMatchedKeywords() != null && !entry.getMatchedKeywords().isEmpty()) {
            String matchText = String.join(", ", entry.getMatchedKeywords());
            if (matchText.length() > 20) matchText = matchText.substring(0, 17) + "...";
            g2.setFont(new Font("SansSerif", Font.BOLD, 9));
            int tw = g2.getFontMetrics().stringWidth(matchText);
            
            g2.setColor(new Color(220, 50, 50));
            g2.fillRoundRect(r.x + r.width - tw - 8, r.y + 26, tw + 6, 14, 4, 4);
            g2.setColor(Color.WHITE);
            g2.drawString(matchText, r.x + r.width - tw - 5, r.y + 36);
        }

        // Info line: sparkline + hits
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
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
        Color infoColor = selected ? new Color(200, 200, 200) : (entry.isInScope() ? Color.GRAY : new Color(180, 180, 180));
        g2.setColor(infoColor);
        g2.drawString(info.toString(), r.x + 6, r.y + 32);

        // Type badge (REQ+RES or REQ)
        String badge = entry.getResponse() != null ? "REQ+RES" : "REQ";
        g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
        g2.setColor(selected ? new Color(180, 180, 180) : (entry.isInScope() ? new Color(100, 100, 100) : new Color(170, 170, 170)));
        g2.drawString(badge, r.x + 6, r.y + 44);
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
            return (((JViewport) getParent()).getWidth() > LANE_HEADER_W + CARD_W + CARD_GAP * 2);
        }
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
