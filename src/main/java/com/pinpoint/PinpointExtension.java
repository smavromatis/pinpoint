package com.pinpoint;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.pinpoint.ui.MainUI;
import com.pinpoint.proxy.PinpointProxyHandler;
import com.pinpoint.proxy.PinContextMenuProvider;
import com.pinpoint.model.PinManager;

public class PinpointExtension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Pinpoint");

        PinManager pinManager = new PinManager(api);
        MainUI mainUI = new MainUI(api, pinManager);
        PinpointProxyHandler proxyHandler = new PinpointProxyHandler(api, pinManager, mainUI);

        api.userInterface().registerSuiteTab("Pinpoint", mainUI.getUiComponent());
        api.userInterface().registerContextMenuItemsProvider(new PinContextMenuProvider(api, pinManager));
        
        api.proxy().registerRequestHandler(proxyHandler);
        api.proxy().registerResponseHandler(proxyHandler);

        api.extension().registerUnloadingHandler(() -> {
            pinManager.shutdown();
            mainUI.shutdown();
        });

        String banner = """
              __               ____  _                   _       __               
             / /              / __ \\(_)___  ____  ____  (_)___  / /_              
            / /___________   / /_/ / / __ \\/ __ \\/ __ \\/ / __ \\/ __/  ____________
           / /_____/_____/  / ____/ / / / / /_/ / /_/ / / / / / /_   /_____/_____/
          / /              /_/   /_/_/ /_/ .___/\\____/_/_/ /_/\\__/                
         /_/                            /_/                                       
        
        Pinpoint v1.0.0
        Copyright (c) 2026 Stylianos Mavromatis - MIT License
        """;
        api.logging().logToOutput(banner);
        api.logging().logToOutput("Pinpoint initialized successfully!");
    }
}
