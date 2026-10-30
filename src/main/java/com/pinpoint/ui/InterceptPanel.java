package com.pinpoint.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.pinpoint.model.PendingItem;
import com.pinpoint.model.PinManager;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The held-message area of the flow view: every request/response paused by a breakpoint or
 * step, one at a time, with the held side editable and the other side shown read-only.
 * All methods except {@link #releaseAllOriginal()} run on the EDT.
 */
public class InterceptPanel {

    private static final String CARD_EDIT = "edit";
    private static final String CARD_VIEW = "view";

    private final JPanel mainPanel;
    private final PinManager pinManager;

    // Sorted by arrival; copy-on-write so the unload handler can release items from any thread
    private final List<PendingItem> pendingItems = new CopyOnWriteArrayList<>();

    // Editable editors for the held side, read-only ones for the context side
    private final HttpRequestEditor requestEditor;
    private final HttpRequestEditor requestView;
    private final HttpResponseEditor responseEditor;
    private final HttpResponseEditor responseView;
    private final CardLayout requestCards = new CardLayout();
    private final CardLayout responseCards = new CardLayout();
    private final JPanel requestCardPanel = new JPanel(requestCards);
    private final JPanel responseCardPanel = new JPanel(responseCards);
    private final JLabel requestHeader = new JLabel();
    private final JLabel responseHeader = new JLabel();

    // "2 of 5" between ◀ ▶; the whole group only shows when more than one message is held
    private final JLabel positionLabel = new JLabel();
    private JPanel navGroup;
    private final JButton prevButton = new JButton("◀");
    private final JButton nextButton = new JButton("▶");
    // Forward uses Burp's orange accent; Drop is red because it's destructive
    private final ActionButton forwardButton = new ActionButton("\u25B6  Forward", "F10", Theme::accent);
    private final ActionButton dropButton = new ActionButton("\u2715  Drop", null, Theme::danger);

    private PendingItem currentItem;
    private String lastForwardedUrl;
    private Runnable queueListener;
    private Consumer<PendingItem> onCurrentChanged;
    private Runnable onClose;

    public InterceptPanel(MontoyaApi api, PinManager pinManager) {
        this.pinManager = pinManager;
        this.mainPanel = new JPanel(new BorderLayout());

        this.requestEditor = api.userInterface().createHttpRequestEditor();
        this.requestView = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        this.responseEditor = api.userInterface().createHttpResponseEditor();
        this.responseView = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        requestCardPanel.add(requestEditor.uiComponent(), CARD_EDIT);
        requestCardPanel.add(requestView.uiComponent(), CARD_VIEW);
        responseCardPanel.add(responseEditor.uiComponent(), CARD_EDIT);
        responseCardPanel.add(responseView.uiComponent(), CARD_VIEW);

        JSplitPane editorSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                withHeader(requestHeader, requestCardPanel), withHeader(responseHeader, responseCardPanel));
        editorSplitPane.setResizeWeight(0.5);
        EditorSplit.install(editorSplitPane, pinManager);

        prevButton.setToolTipText("Previous held message");
        nextButton.setToolTipText("Next held message");
        prevButton.addActionListener(e -> navigate(-1));
        nextButton.addActionListener(e -> navigate(1));
        forwardButton.setToolTipText("Send this message, with your edits (F10)");
        forwardButton.addActionListener(e -> forwardCurrent());
        dropButton.setToolTipText("Discard this message");
        dropButton.addActionListener(e -> dropCurrent());
        JButton closeButton = new JButton("\u2715");
        closeButton.setToolTipText("Close and return to the graph (messages stay held; click a paused node to reopen)");
        closeButton.addActionListener(e -> {
            if (onClose != null) onClose.run();
        });

        // One control row: [◀ 2 of 5 ▶] on the left (only with several held), big Forward / Drop
        // centered like game controls, ✕ on the right
        navGroup = row(prevButton, Theme.gap(6), positionLabel, Theme.gap(6), nextButton);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, Theme.px(14), 0));
        actions.setOpaque(false);
        actions.add(forwardButton);
        actions.add(dropButton);
        JPanel controls = new CenteredRow(navGroup, actions, row(closeButton));
        controls.setBorder(Theme.bar(true, true));
        mainPanel.add(controls, BorderLayout.NORTH);
        mainPanel.add(editorSplitPane, BorderLayout.CENTER);

        // Intercept/step mode or the view filter changed: release whatever is no longer held
        pinManager.addListener(() -> SwingUtilities.invokeLater(this::releaseNoLongerHeld));

        showItem(null);
    }

    private static JPanel row(Component... components) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        for (Component c : components) panel.add(c);
        return panel;
    }

    /** Left and right groups at the edges, center group centered on the full width. */
    private static class CenteredRow extends JPanel {
        private final JComponent left, center, right;

        CenteredRow(JComponent left, JComponent center, JComponent right) {
            super(null);
            this.left = left;
            this.center = center;
            this.right = right;
            add(left);
            add(center);
            add(right);
        }

        @Override
        public Dimension getPreferredSize() {
            Insets in = getInsets();
            Dimension l = left.getPreferredSize(), c = center.getPreferredSize(), r = right.getPreferredSize();
            int h = Math.max(l.height, Math.max(c.height, r.height));
            return new Dimension(in.left + l.width + c.width + r.width + 24 + in.right, in.top + h + in.bottom);
        }

        @Override
        public void doLayout() {
            Insets in = getInsets();
            int h = getHeight() - in.top - in.bottom;
            Dimension l = left.getPreferredSize(), c = center.getPreferredSize(), r = right.getPreferredSize();
            center.setBounds(Math.max(in.left, (getWidth() - c.width) / 2), in.top + (h - c.height) / 2, c.width, c.height);
            left.setBounds(in.left, in.top + (h - l.height) / 2, l.width, l.height);
            right.setBounds(getWidth() - in.right - r.width, in.top + (h - r.height) / 2, r.width, r.height);
        }
    }

    /**
     * A large, colored, custom-painted button (Burp's look and feel ignores button backgrounds),
     * with an optional keyboard hint, and hover / pressed / disabled states.
     */
    private static class ActionButton extends JButton {
        private final java.util.function.Supplier<Color> base;
        private final String keyHint;

        ActionButton(String text, String keyHint, java.util.function.Supplier<Color> base) {
            super(text);
            this.base = base;
            this.keyHint = keyHint;
            setForeground(Color.WHITE);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        private Font labelFont() {
            return Theme.font(Font.BOLD, 1);
        }

        private Font hintFont() {
            return Theme.font(Font.PLAIN, -2);
        }

        private int hintWidth() {
            return keyHint != null ? getFontMetrics(hintFont()).stringWidth(keyHint) + Theme.px(14) : 0;
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(labelFont());
            int width = Math.max(Theme.px(150), fm.stringWidth(getText()) + hintWidth() + Theme.px(40));
            return new Dimension(width, Math.max(Theme.px(36), fm.getHeight() + Theme.px(Theme.SPACE_L)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            ButtonModel m = getModel();
            Color color = base.get();
            Color fill = !isEnabled() ? Theme.overlay(40)
                    : m.isPressed() ? color.darker()
                    : m.isRollover() ? Theme.blend(color, Color.WHITE, 0.15f)
                    : color;
            int radius = Theme.px(Theme.RADIUS + 2);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);

            Font font = labelFont();
            FontMetrics fm = g2.getFontMetrics(font);
            FontMetrics hm = g2.getFontMetrics(hintFont());
            int textX = (getWidth() - fm.stringWidth(getText()) - hintWidth()) / 2;
            int baseline = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g2.setColor(isEnabled() ? Color.WHITE : Theme.muted());
            g2.setFont(font);
            g2.drawString(getText(), textX, baseline);

            if (keyHint != null) {
                // Keyboard hint in a small outlined "key cap"
                int kx = textX + fm.stringWidth(getText()) + Theme.px(10);
                int kw = hm.stringWidth(keyHint) + Theme.px(Theme.SPACE_M);
                int kh = hm.getHeight();
                int ky = (getHeight() - kh) / 2;
                g2.setColor(isEnabled() ? new Color(255, 255, 255, 170) : Theme.muted());
                g2.drawRoundRect(kx, ky, kw, kh, Theme.px(6), Theme.px(6));
                g2.setFont(hintFont());
                g2.drawString(keyHint, kx + Theme.px(Theme.SPACE_S), ky + hm.getAscent());
            }
            g2.dispose();
        }
    }

    private static JPanel withHeader(JLabel header, JComponent content) {
        header.setBorder(BorderFactory.createEmptyBorder(Theme.px(Theme.SPACE_S), Theme.px(Theme.SPACE_M), Theme.px(Theme.SPACE_S), Theme.px(Theme.SPACE_M)));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(header, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    public Component getPanel() {
        return mainPanel;
    }

    /** Called on the EDT whenever the set of held items changes. */
    public void setQueueListener(Runnable listener) {
        this.queueListener = listener;
    }

    /** Called on the EDT when a different held item becomes current (null when none is left). */
    public void setOnCurrentChanged(Consumer<PendingItem> listener) {
        this.onCurrentChanged = listener;
    }

    /** Called when the user closes the held-message area (nothing is released). */
    public void setOnClose(Runnable listener) {
        this.onClose = listener;
    }

    public int getHeldCount() {
        return pendingItems.size();
    }

    public PendingItem getCurrentItem() {
        return currentItem;
    }

    public void addItem(PendingItem item) {
        if (!pinManager.isStillHeld(item)) {
            completeWithOriginal(item);
            return;
        }

        int insertIdx = pendingItems.size();
        for (int i = 0; i < pendingItems.size(); i++) {
            if (pendingItems.get(i).compareTo(item) > 0) {
                insertIdx = i;
                break;
            }
        }
        pendingItems.add(insertIdx, item);

        // The response to the request just forwarded follows it, unless the user is mid-edit elsewhere
        boolean isFollowUp = !item.isRequest() && item.getUrl().equals(lastForwardedUrl);
        if (isFollowUp) lastForwardedUrl = null;
        if (currentItem == null || (isFollowUp && !isCurrentItemModified())) {
            showItem(item);
        } else {
            updateStatus();
        }
        fireQueueChanged();
    }

    /** Shows the oldest held item on the given graph node; returns false if it holds none. */
    public boolean showItemFor(com.pinpoint.model.TrafficEntry entry) {
        for (PendingItem item : pendingItems) {
            if (item.getEntry() == entry) {
                showItem(item);
                return true;
            }
        }
        return false;
    }

    private void navigate(int delta) {
        int idx = pendingItems.indexOf(currentItem) + delta;
        if (idx >= 0 && idx < pendingItems.size()) showItem(pendingItems.get(idx));
    }

    private boolean isCurrentItemModified() {
        PendingItem item = currentItem;
        if (item == null) return false;
        return item.isRequest() ? requestEditor.isModified() : responseEditor.isModified();
    }

    /** Keeps unsent edits on the item so switching away and back never loses them. */
    private void saveDraft(PendingItem item) {
        if (item == null || !pendingItems.contains(item)) return;
        if (item.isRequest() && requestEditor.isModified()) {
            item.setDraftRequest(requestEditor.getRequest());
        } else if (!item.isRequest() && responseEditor.isModified()) {
            item.setDraftResponse(responseEditor.getResponse());
        }
    }

    private void showItem(PendingItem item) {
        if (item != null && item == currentItem) {
            updateStatus();
            return;
        }
        saveDraft(currentItem);
        currentItem = item;

        if (item == null) {
            requestView.setRequest(HttpRequest.httpRequest(""));
            responseView.setResponse(HttpResponse.httpResponse(""));
            requestCards.show(requestCardPanel, CARD_VIEW);
            responseCards.show(responseCardPanel, CARD_VIEW);
            setHeader(requestHeader, "Request", false);
            setHeader(responseHeader, "Response", false);
        } else if (item.isRequest()) {
            HttpRequest draft = item.getDraftRequest();
            requestEditor.setRequest(draft != null ? draft : item.getOriginalRequest());
            responseView.setResponse(HttpResponse.httpResponse(""));
            requestCards.show(requestCardPanel, CARD_EDIT);
            responseCards.show(responseCardPanel, CARD_VIEW);
            setHeader(requestHeader, "✎ Request — held by " + holdReason(item) + ", editable", true);
            setHeader(responseHeader, "Response — not received yet", false);
        } else {
            HttpResponse draft = item.getDraftResponse();
            requestView.setRequest(item.getOriginalRequest());
            responseEditor.setResponse(draft != null ? draft : item.getOriginalResponse());
            requestCards.show(requestCardPanel, CARD_VIEW);
            responseCards.show(responseCardPanel, CARD_EDIT);
            setHeader(requestHeader, "Request — already sent", false);
            setHeader(responseHeader, "✎ Response — held by " + holdReason(item) + ", editable", true);
        }
        updateStatus();
        if (onCurrentChanged != null) onCurrentChanged.accept(item);
    }

    private static void setHeader(JLabel header, String text, boolean editable) {
        header.setText(text);
        header.setForeground(editable ? Theme.accent() : Theme.muted());
        header.setFont(header.getFont().deriveFont(editable ? Font.BOLD : Font.PLAIN));
    }

    private static String holdReason(PendingItem item) {
        return item.isHeldByBreakpoint() ? "breakpoint" : "step";
    }

    private void updateStatus() {
        PendingItem item = currentItem;
        int idx = pendingItems.indexOf(item);
        prevButton.setEnabled(idx > 0);
        nextButton.setEnabled(idx >= 0 && idx < pendingItems.size() - 1);
        forwardButton.setEnabled(item != null);
        dropButton.setEnabled(item != null);
        positionLabel.setText((idx + 1) + " of " + pendingItems.size());
        navGroup.setVisible(item != null && pendingItems.size() > 1);
    }

    private void fireQueueChanged() {
        if (queueListener != null) queueListener.run();
    }

    /** What to send for an item: the live editor for the current one, else its draft or original. */
    private Object editedMessage(PendingItem item) {
        if (item == currentItem) {
            return item.isRequest() ? requestEditor.getRequest() : responseEditor.getResponse();
        }
        if (item.isRequest()) {
            return item.getDraftRequest() != null ? item.getDraftRequest() : item.getOriginalRequest();
        }
        return item.getDraftResponse() != null ? item.getDraftResponse() : item.getOriginalResponse();
    }

    private static void complete(PendingItem item, Object message) {
        if (item.isRequest()) item.getRequestFuture().complete((HttpRequest) message);
        else item.getResponseFuture().complete((HttpResponse) message);
    }

    private static void completeWithOriginal(PendingItem item) {
        complete(item, item.isRequest() ? item.getOriginalRequest() : item.getOriginalResponse());
    }

    /** Sends the current held message with any edits (Forward button / F10). */
    public void forwardCurrent() {
        PendingItem item = currentItem;
        if (item == null) return;
        release(item, editedMessage(item));
    }

    private void dropCurrent() {
        PendingItem item = currentItem;
        if (item == null) return;
        release(item, null);
    }

    /** Completes one held item (null message = drop) and moves on to the next one. */
    private void release(PendingItem item, Object message) {
        if (message != null) {
            lastForwardedUrl = item.isRequest() ? item.getUrl() : null;
        }
        pendingItems.remove(item);
        complete(item, message);
        if (item == currentItem) {
            currentItem = null;
            showItem(pendingItems.isEmpty() ? null : pendingItems.get(0));
        } else {
            updateStatus();
        }
        fireQueueChanged();
    }

    /** Forwards (with edits) every item whose hold reason no longer applies. */
    private void releaseNoLongerHeld() {
        boolean changed = false;
        for (PendingItem item : pendingItems) {
            if (!pinManager.isStillHeld(item)) {
                Object message = editedMessage(item);
                pendingItems.remove(item);
                complete(item, message);
                if (item == currentItem) currentItem = null;
                changed = true;
            }
        }
        if (!changed) return;
        if (currentItem == null) {
            showItem(pendingItems.isEmpty() ? null : pendingItems.get(0));
        } else {
            updateStatus();
        }
        fireQueueChanged();
    }

    /** Timeout: the proxy thread already forwarded the original, so just forget the item. */
    public void removePendingItem(PendingItem item) {
        if (item == null) return;
        SwingUtilities.invokeLater(() -> {
            if (!pendingItems.remove(item)) return;
            if (item == currentItem) {
                currentItem = null;
                showItem(pendingItems.isEmpty() ? null : pendingItems.get(0));
            } else {
                updateStatus();
            }
            fireQueueChanged();
        });
    }

    /** Extension unload: unblock every waiting proxy thread with the original message. */
    public void releaseAllOriginal() {
        for (PendingItem item : pendingItems) {
            completeWithOriginal(item);
        }
        pendingItems.clear();
    }
}
