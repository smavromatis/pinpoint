package com.pinpoint.proxy;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import com.pinpoint.model.PinManager;
import com.pinpoint.utils.TrafficUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class PinContextMenuProvider implements ContextMenuItemsProvider {

    private final MontoyaApi api;
    private final PinManager pinManager;

    public PinContextMenuProvider(MontoyaApi api, PinManager pinManager) {
        this.api = api;
        this.pinManager = pinManager;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<Component> menuItems = new ArrayList<>();

        burp.api.montoya.http.message.HttpRequestResponse reqRes = null;

        if (event.selectedRequestResponses() != null && !event.selectedRequestResponses().isEmpty()) {
            reqRes = event.selectedRequestResponses().get(0);
        } else if (event.messageEditorRequestResponse().isPresent()) {
            reqRes = event.messageEditorRequestResponse().get().requestResponse();
        }

        if (reqRes == null || reqRes.request() == null) {
            return menuItems;
        }

        final burp.api.montoya.http.message.HttpRequestResponse finalReqRes = reqRes;

        JMenuItem pinItem = new JMenuItem("Send to Pinpoint");
        pinItem.addActionListener(l -> {
            // Same rule as clicking a node's breakpoint dot: exact host + path
            String[] hostPath = TrafficUtils.parseHostAndPath(finalReqRes.request().url());
            String pattern = hostPath[0] + hostPath[1];
            String error = pinManager.addPin(pattern, PinManager.MatchType.EXACT, true, true);
            api.logging().logToOutput(error == null ? "Pinpoint breakpoint set: " + pattern : "Pinpoint: " + error + " (" + pattern + ")");
        });
        menuItems.add(pinItem);

        return menuItems;
    }
}
