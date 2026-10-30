package com.pinpoint;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.pinpoint.ui.MainUI;
import com.pinpoint.proxy.PinpointProxyHandler;
import com.pinpoint.proxy.PinContextMenuProvider;
import com.pinpoint.model.PinManager;

public class PinpointExtension implements BurpExtension {
    /** The jar's Implementation-Version (from build.gradle), or "dev" when run from classes. */
    private static String version() {
        String v = PinpointExtension.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Pinpoint");

        PinManager pinManager = new PinManager(api);
        MainUI mainUI = new MainUI(api, pinManager);
        PinpointProxyHandler proxyHandler = new PinpointProxyHandler(api, pinManager, mainUI);

        api.userInterface().registerSuiteTab("Pinpoint", mainUI.getUiComponent());
        api.userInterface().registerContextMenuItemsProvider(new PinContextMenuProvider(api, pinManager));
        
        // Keep the graph's in/out-of-scope styling in sync with Burp's Target > Scope
        api.scope().registerScopeChangeHandler(change -> pinManager.refreshScope());

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
        
        Pinpoint v%s
        Copyright (c) 2026 Stylianos Mavromatis - GNU GPL v3
        """.formatted(version());
        api.logging().logToOutput(banner);
        api.logging().logToOutput("Pinpoint initialized successfully!");
    }
}
