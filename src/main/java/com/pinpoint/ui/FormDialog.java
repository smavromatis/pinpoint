package com.pinpoint.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;

/**
 * A small modal form with OK / Cancel. Unlike JOptionPane it sizes itself from its content (so
 * wrapped help text never pushes the buttons out of view), and a failed submit shows its error
 * inside the dialog and keeps the user's input. Enter submits, Escape cancels.
 */
final class FormDialog {

    /** Width of the form's text column, so wrapped text and previews size predictably. */
    static int textWidth() {
        return Theme.px(400);
    }

    private FormDialog() {
    }

    /**
     * Shows the form; {@code submit} returns null on success or an error to show. Returns true if
     * the form was submitted successfully, false if cancelled.
     */
    static boolean show(Component parent, String title, JComponent form, JComponent focus, String okText,
                        Supplier<String> submit) {
        Window owner = parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        boolean[] submitted = {false};

        JTextArea error = wrappingText("", false);
        error.setForeground(Theme.error());
        error.setVisible(false);
        JButton ok = new JButton(okText);
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            String message = submit.get();
            if (message == null) {
                submitted[0] = true;
                dialog.dispose();
            } else {
                error.setText(message);
                fitHeight(error);
                error.setVisible(true);
                dialog.pack();
            }
        });
        cancel.addActionListener(e -> dialog.dispose());

        dialog.setContentPane(content(form, error, ok, cancel));
        dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setMinimumSize(dialog.getSize());
        dialog.setLocationRelativeTo(parent);
        SwingUtilities.invokeLater(focus::requestFocusInWindow);
        dialog.setVisible(true);   // blocks until closed
        return submitted[0];
    }

    /** The dialog's layout: form on top, then the error line, then right-aligned buttons. */
    static JPanel content(JComponent form, JComponent error, JButton ok, JButton cancel) {
        int pad = Theme.px(Theme.SPACE_L);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.px(Theme.SPACE_S), 0));
        buttons.add(cancel);
        buttons.add(ok);

        JPanel south = new JPanel(new BorderLayout(0, Theme.px(Theme.SPACE_S)));
        south.add(error, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, Theme.px(Theme.SPACE_M)));
        content.setBorder(BorderFactory.createEmptyBorder(pad, pad, pad, pad));
        content.add(form, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        return content;
    }

    /** Read-only text that wraps at the form's text width and reports its real height. */
    static JTextArea wrappingText(String text, boolean muted) {
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(Theme.font(Font.PLAIN, -2));
        if (muted) area.setForeground(Theme.muted());
        fitHeight(area);
        return area;
    }

    /**
     * A wrapping JTextArea only knows its height once it has a width; set the width first, then
     * pin the preferred size so layouts (and pack) see the true height.
     */
    static void fitHeight(JTextArea area) {
        area.setSize(new Dimension(textWidth(), Short.MAX_VALUE));
        area.setPreferredSize(null);
        Dimension d = area.getPreferredSize();
        area.setPreferredSize(new Dimension(textWidth(), d.height));
    }

    /** A live preview line of fixed size (two lines), so updating it never resizes the dialog. */
    static JTextArea previewText() {
        JTextArea area = wrappingText(" ", true);
        FontMetrics fm = area.getFontMetrics(area.getFont());
        area.setPreferredSize(new Dimension(textWidth(), fm.getHeight() * 2));
        return area;
    }

    static void setPreview(JTextArea preview, String text, boolean isError) {
        preview.setText(text);
        preview.setForeground(isError ? Theme.error() : Theme.muted());
        preview.setCaretPosition(0);
    }

    /** Two-column label/field grid for the form body. */
    static JPanel grid(Object[]... rows) {
        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        int gap = Theme.px(Theme.SPACE_S);
        c.insets = new Insets(gap, 0, gap, gap * 2);
        c.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < rows.length; i++) {
            c.gridy = i;
            if (rows[i][0] != null) {
                c.gridx = 0;
                c.weightx = 0;
                c.fill = GridBagConstraints.NONE;
                grid.add(new JLabel((String) rows[i][0]), c);
            }
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            grid.add((Component) rows[i][1], c);
        }
        return grid;
    }
}
