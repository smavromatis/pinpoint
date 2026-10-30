package com.pinpoint.ui;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Pinpoint's look, derived from Burp's current theme and font size so the extension feels native
 * in light and dark mode and follows Burp's "font size" setting. Values are read on every call,
 * so a theme switch in Burp applies on the next repaint.
 */
final class Theme {

    private Theme() {
    }

    // Sizes below are designed at this font size and scale with Burp's actual UI font
    private static final float DESIGN_FONT_SIZE = 13f;

    // Spacing scale, in design pixels (use px() to scale)
    static final int SPACE_XS = 2;
    static final int SPACE_S = 4;
    static final int SPACE_M = 8;
    static final int SPACE_L = 12;
    static final int SPACE_XL = 16;
    static final int RADIUS = 8;

    // ---- Fonts -------------------------------------------------------------------------------

    static Font baseFont() {
        Font f = UIManager.getFont("Label.font");
        return f != null ? f : new Font(Font.DIALOG, Font.PLAIN, (int) DESIGN_FONT_SIZE);
    }

    /** How much bigger than the design size Burp's UI font is (never below 1). */
    static float scale() {
        return Math.max(1f, baseFont().getSize2D() / DESIGN_FONT_SIZE);
    }

    static int px(int designPixels) {
        return Math.round(designPixels * scale());
    }

    // Looked up for every card on every animation frame: keyed by small ints, cleared on font change
    private static final Map<Integer, Font> fontCache = new HashMap<>();
    private static Font cachedBase;

    /** Burp's UI font in a style, {@code delta} points from its size (at the design size). */
    static Font font(int style, int delta) {
        return cached(baseFont(), false, style, delta);
    }

    /** A monospaced font matching Burp's UI font size, for paths. */
    static Font mono(int style, int delta) {
        return cached(baseFont(), true, style, delta);
    }

    private static synchronized Font cached(Font base, boolean mono, int style, int delta) {
        if (!base.equals(cachedBase)) {
            fontCache.clear();
            cachedBase = base;
        }
        int key = (mono ? 1 << 16 : 0) | (style << 8) | (delta + 128);
        return fontCache.computeIfAbsent(key, k -> {
            float size = Math.max(8f, base.getSize2D() + delta * scale());
            return mono ? new Font(Font.MONOSPACED, style, Math.round(size)) : base.deriveFont(style, size);
        });
    }

    // ---- Neutral colours ---------------------------------------------------------------------

    static Color background() {
        return uiColor("Panel.background", new Color(242, 242, 242));
    }

    static Color foreground() {
        return uiColor("Label.foreground", new Color(30, 30, 30));
    }

    /** Secondary text: readable on both themes (some themes' disabled colour is too faint). */
    static Color muted() {
        return blend(foreground(), background(), 0.45f);
    }

    static boolean isDark() {
        Color bg = background();
        return (0.2126 * bg.getRed() + 0.7152 * bg.getGreen() + 0.0722 * bg.getBlue()) / 255 < 0.45;
    }

    /** Card / surface fill: slightly lifted from the background in dark mode, white-ish in light. */
    static Color surface() {
        Color list = UIManager.getColor("List.background");
        if (isDark()) return blend(background(), Color.WHITE, 0.06f);
        return list != null ? list : Color.WHITE;
    }

    /** A translucent wash of the text colour: darkens light themes, lightens dark ones. */
    static Color overlay(int alpha) {
        Color fg = foreground();
        return new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), Math.max(0, Math.min(255, isDark() ? alpha + alpha / 2 : alpha)));
    }

    static Color separator() {
        return uiColor("Separator.foreground", overlay(40));
    }

    static Color selectionBackground() {
        return uiColor("List.selectionBackground", new Color(51, 153, 255));
    }

    static Color selectionForeground() {
        return uiColor("List.selectionForeground", Color.WHITE);
    }

    // ---- Semantic accents, tuned per theme for contrast ---------------------------------------

    static Color inScope()      { return isDark() ? new Color(88, 166, 255) : new Color(0, 120, 215); }
    static Color outOfScope()   { return muted(); }
    static Color neutralBorder(){ return blend(foreground(), background(), 0.65f); }
    /** Burp's orange: breakpoints, held messages, editable areas. */
    static Color accent()       { return new Color(255, 102, 51); }
    static Color success()      { return isDark() ? new Color(95, 195, 105) : new Color(30, 135, 45); }
    static Color redirect()     { return isDark() ? new Color(225, 185, 70) : new Color(170, 120, 0); }
    static Color error()        { return isDark() ? new Color(240, 105, 105) : new Color(195, 45, 45); }
    static Color trail()        { return isDark() ? new Color(180, 135, 255) : new Color(135, 75, 215); }
    /** Fill for destructive buttons (white text on top), deep enough for contrast in both themes. */
    static Color danger()       { return new Color(200, 55, 55); }

    // ---- Helpers ------------------------------------------------------------------------------

    static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    /** {@code t} = 0 gives a, 1 gives b. */
    static Color blend(Color a, Color b, float t) {
        float u = 1 - t;
        return new Color(Math.round(a.getRed() * u + b.getRed() * t),
                Math.round(a.getGreen() * u + b.getGreen() * t),
                Math.round(a.getBlue() * u + b.getBlue() * t));
    }

    /** Standard bar padding with a separator line on the given edges. */
    static Border bar(boolean top, boolean bottom) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(top ? 1 : 0, 0, bottom ? 1 : 0, 0, separator()),
                BorderFactory.createEmptyBorder(px(SPACE_S), px(SPACE_M), px(SPACE_S), px(SPACE_M)));
    }

    static Component gap(int designPixels) {
        return Box.createHorizontalStrut(px(designPixels));
    }

    /** A thin vertical divider between toolbar groups. */
    static JComponent divider() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(1, px(20));
            }
        };
        JPanel wrap = new JPanel();
        wrap.setOpaque(false);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.X_AXIS));
        wrap.add(gap(SPACE_M));
        wrap.add(sep);
        wrap.add(gap(SPACE_M));
        return wrap;
    }

    private static Color uiColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }
}
