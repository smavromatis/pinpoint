package com.pinpoint.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;

/**
 * A toggle button with a built-in dropdown arrow: clicking the label toggles it as usual,
 * clicking the ▾ part opens a menu of options instead (and doesn't toggle).
 */
final class SplitToggleButton extends JToggleButton {

    private final JPopupMenu menu;

    SplitToggleButton(String text, boolean selected, JPopupMenu menu) {
        super(text, selected);
        this.menu = menu;
    }

    private int arrowZone() {
        return Theme.px(18);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Make room for the arrow on the right, inside the button's own border
        Insets m = getMargin() != null ? getMargin() : new Insets(2, 8, 2, 8);
        setMargin(new Insets(m.top, m.left, m.bottom, m.right + Theme.px(18)));
    }

    private boolean inArrow(MouseEvent e) {
        return e.getX() >= getWidth() - arrowZone();
    }

    @Override
    protected void processMouseEvent(MouseEvent e) {
        int id = e.getID();
        boolean click = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED || id == MouseEvent.MOUSE_CLICKED;
        if (click && inArrow(e)) {
            if (id == MouseEvent.MOUSE_PRESSED && isEnabled()) menu.show(this, 0, getHeight());
            e.consume();
            return;
        }
        super.processMouseEvent(e);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int zoneX = getWidth() - arrowZone();
        int inset = Theme.px(6);
        g2.setColor(Theme.withAlpha(getForeground(), 70));
        g2.drawLine(zoneX, inset, zoneX, getHeight() - inset);

        int size = Theme.px(4);
        int cx = zoneX + arrowZone() / 2;
        int cy = getHeight() / 2 + 1;
        g2.setColor(isEnabled() ? getForeground() : Theme.muted());
        g2.fillPolygon(new int[]{cx - size, cx + size, cx}, new int[]{cy - size / 2, cy - size / 2, cy + size / 2 + 1}, 3);
        g2.dispose();
    }
}
