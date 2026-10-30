package com.pinpoint.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.BooleanSupplier;

/**
 * Wraps the graph's scroll pane and floats a small, semi-transparent "Legend" pill in its
 * bottom-right corner. Hovering the pill shows the legend as a card above it. Both stay put while
 * the graph scrolls, and the card is drawn inside the graph area, so it never leaves Burp's window.
 */
final class GraphLegendOverlay extends JLayeredPane {

    private final JScrollPane scroll;
    private final Pill pill = new Pill();
    private final Card card = new Card();
    private final BooleanSupplier onlyInScope;

    GraphLegendOverlay(JScrollPane scroll, BooleanSupplier onlyInScope) {
        this.scroll = scroll;
        this.onlyInScope = onlyInScope;
        add(scroll, DEFAULT_LAYER);
        add(pill, PALETTE_LAYER);
        add(card, POPUP_LAYER);
        card.setVisible(false);

        pill.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                card.rebuild(GraphLegendOverlay.this.onlyInScope.getAsBoolean());
                card.setVisible(true);
                pill.hovered = true;
                revalidate();
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                card.setVisible(false);
                pill.hovered = false;
                repaint();
            }
        });

        // Keep the pill clear of scrollbars as they appear and disappear
        ComponentAdapter relayout = new ComponentAdapter() {
            @Override public void componentShown(ComponentEvent e) { revalidate(); }
            @Override public void componentHidden(ComponentEvent e) { revalidate(); }
        };
        scroll.getVerticalScrollBar().addComponentListener(relayout);
        scroll.getHorizontalScrollBar().addComponentListener(relayout);
    }

    @Override
    public boolean isOptimizedDrawingEnabled() {
        return false;   // children overlap
    }

    @Override
    public Dimension getPreferredSize() {
        return scroll.getPreferredSize();
    }

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        scroll.setBounds(0, 0, w, h);
        JScrollBar vbar = scroll.getVerticalScrollBar(), hbar = scroll.getHorizontalScrollBar();
        int right = vbar.isVisible() ? vbar.getWidth() : 0;
        int bottom = hbar.isVisible() ? hbar.getHeight() : 0;
        int margin = Theme.px(Theme.SPACE_M);

        Dimension p = pill.getPreferredSize();
        int px = w - right - margin - p.width;
        int py = h - bottom - margin - p.height;
        pill.setBounds(px, py, p.width, p.height);

        Dimension c = card.getPreferredSize();
        int cx = Math.max(margin, px + p.width - c.width);
        int cy = Math.max(margin, py - Theme.px(Theme.SPACE_S) - c.height);
        card.setBounds(cx, cy, c.width, c.height);
    }

    /** The always-visible, semi-transparent "Legend" pill. */
    private static final class Pill extends JComponent {
        boolean hovered;

        Pill() {
            setToolTipText(null);
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(Theme.font(Font.BOLD, -2));
            return new Dimension(fm.stringWidth("Legend") + Theme.px(20), fm.getHeight() + Theme.px(Theme.SPACE_S * 2));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(Theme.withAlpha(Theme.surface(), hovered ? 240 : 150));
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, h, h));
            g2.setColor(Theme.withAlpha(Theme.separator(), hovered ? 255 : 160));
            g2.draw(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, h, h));
            g2.setFont(Theme.font(Font.BOLD, -2));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(hovered ? Theme.foreground() : Theme.withAlpha(Theme.muted(), 200));
            g2.drawString("Legend", (w - fm.stringWidth("Legend")) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** The legend card shown while the pill is hovered. */
    private static final class Card extends JPanel {
        Card() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            int pad = Theme.px(Theme.SPACE_M);
            setBorder(BorderFactory.createEmptyBorder(pad, pad + Theme.px(2), pad, pad + Theme.px(Theme.SPACE_S)));
        }

        /** Scope rows are left out while Only In-Scope is on: scope colours aren't used then. */
        void rebuild(boolean onlyInScope) {
            removeAll();
            JLabel title = new JLabel("Legend");
            title.setFont(Theme.font(Font.BOLD, -2));
            title.setForeground(Theme.muted());
            title.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.px(Theme.SPACE_S), 0));
            add(title);
            if (!onlyInScope) {
                add(row(Swatch.SOLID_LINE, "In scope"));
                add(row(Swatch.DASHED_LINE, "Out of scope"));
            }
            add(row(Swatch.DOT, "Breakpoint (click a node's dot to toggle)"));
            add(row(Swatch.RING, "Held message"));
            add(row(Swatch.GLOW, "Recently hit (blue request, green/red response)"));
            add(row(Swatch.REDIRECT, "Name in amber: redirect (3xx)"));
            add(row(Swatch.ERROR, "Name in red: error (4xx / 5xx)"));
            add(row(Swatch.TRAIL, "Recent order across hosts (Flows off)"));
            revalidate();
        }

        private static JComponent row(Icon icon, String text) {
            JLabel label = new JLabel(text, icon, SwingConstants.LEADING);
            label.setFont(Theme.font(Font.PLAIN, -2));
            label.setForeground(Theme.foreground());
            label.setIconTextGap(Theme.px(Theme.SPACE_M));
            label.setBorder(BorderFactory.createEmptyBorder(Theme.px(Theme.SPACE_XS), 0, Theme.px(Theme.SPACE_XS), 0));
            return label;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int r = Theme.px(Theme.RADIUS + 2);
            g2.setColor(Theme.withAlpha(Theme.surface(), 245));
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, r, r));
            g2.setColor(Theme.separator());
            g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, r, r));
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Small swatches matching how the graph draws each thing, painted with the current theme. */
    private enum Swatch implements Icon {
        SOLID_LINE, DASHED_LINE, DOT, RING, GLOW, REDIRECT, ERROR, TRAIL;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getIconWidth(), h = getIconHeight(), cy = y + h / 2, d = Theme.px(9);
            switch (this) {
                case SOLID_LINE -> {
                    g2.setColor(Theme.inScope());
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawRoundRect(x + 1, y + 2, w - 3, h - 5, 5, 5);
                }
                case DASHED_LINE -> {
                    g2.setColor(Theme.outOfScope());
                    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
                    g2.drawRoundRect(x + 1, y + 2, w - 3, h - 5, 5, 5);
                }
                case DOT -> {
                    g2.setColor(Theme.accent());
                    g2.fillOval(x + (w - d) / 2, cy - d / 2, d, d);
                }
                case RING -> {
                    g2.setColor(Theme.accent());
                    g2.setStroke(new BasicStroke(2.5f));
                    g2.drawRoundRect(x + 1, y + 2, w - 3, h - 5, 5, 5);
                }
                case GLOW -> {
                    g2.setColor(Theme.withAlpha(Theme.inScope(), 120));
                    g2.setStroke(new BasicStroke(4f));
                    g2.drawRoundRect(x + 2, y + 3, w - 5, h - 7, 6, 6);
                }
                case REDIRECT, ERROR -> {
                    g2.setFont(Theme.mono(Font.BOLD, -3));
                    g2.setColor(this == REDIRECT ? Theme.redirect() : Theme.error());
                    String sample = this == REDIRECT ? "3xx" : "4xx";
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(sample, x + (w - fm.stringWidth(sample)) / 2, cy + (fm.getAscent() - fm.getDescent()) / 2);
                }
                case TRAIL -> {
                    g2.setColor(Theme.trail());
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawLine(x + 2, cy, x + w - 6, cy);
                    g2.fillPolygon(new int[]{x + w - 1, x + w - 7, x + w - 7}, new int[]{cy, cy - 4, cy + 4}, 3);
                }
            }
            g2.dispose();
        }

        @Override public int getIconWidth() { return Theme.px(24); }
        @Override public int getIconHeight() { return Theme.px(14); }
    }
}
