```text
      __               ____  _                   _       __               
     / /              / __ \(_)___  ____  ____  (_)___  / /_              
    / /___________   / /_/ / / __ \/ __ \/ __ \/ / __ \/ __/  ____________
   / /_____/_____/  / ____/ / / / / /_/ / /_/ / / / / / /_   /_____/_____/
  / /              /_/   /_/_/ /_/ .___/\____/_/_/ /_/\__/                
 /_/                            /_/                                       
```

![Java 17+](https://img.shields.io/badge/Java-17%2B-blue.svg)
![Burp Suite](https://img.shields.io/badge/Burp_Suite-Community_%7C_Pro-orange.svg)
![Build](https://img.shields.io/badge/Build-Gradle-brightgreen.svg)
![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)

**Pinpoint** is a Burp Suite extension designed to streamline the targeting and manipulation of multi-step logic flaws.

Setting breakpoints in Chrome Developer Tools provides a highly targeted debugging workflow. Replicating this natively in Burp Suite requires manually configuring regular expression matching rules or relying on the global Proxy Intercept, which can arbitrarily pause background traffic and disrupt the application state.

Pinpoint ports the endpoint-specific breakpoint paradigm of browser developer tools into Burp Suite. It replaces the flat HTTP history table with an interactive visual node graph, allowing users to intercept specific requests and responses independent of Burp's global proxy configuration.

## Screenshots

*To be added soon.*

## Key Features

* **Visual Traffic Graph**  
  Replaces the traditional HTTP history table with an interactive, node-based map of your live traffic.

* **Point-and-Click Breakpoints**  
  Double-click any URL on the graph to instantly set a breakpoint on it. No manual regex rules required.

* **Isolated Intercept Queue**  
  Only your targeted endpoints pause. Background traffic automatically flows through without hanging your browser.

* **Automatic Response Pairing**  
  When you forward a paused request, the extension automatically catches and displays the exact matching HTTP response.

* **Native Embedded Editors**  
  Modify headers and bodies directly within the extension using Burp's built-in message editors.

## Requirements

* **Java 17+** 
* **Burp Suite Professional or Community** (Must support the newer Montoya API)

## Installation

The easiest way to get it running is to grab the pre-compiled JAR file from the **[Releases](https://github.com/smavromatis/pinpoint/releases)** tab.

1. Download the latest .jar file from the **[Releases](https://github.com/smavromatis/pinpoint/releases)** tab.
2. Open Burp Suite.
3. Head to `Extensions` -> `Installed` -> `Add`.
4. Select the JAR file and you're good to go!

If you want to build it yourself, just clone the repo and run `./gradlew build` (or open it in IntelliJ/Eclipse). It'll spit out the JAR in the `build/libs/` folder.

## How to use it

1. **Define your Scope:** First, ensure your target application is properly added to Burp Suite's Target Scope. Pinpoint respects your scope rules, so a clean scope ensures your visual graph isn't cluttered with background noise!
2. Click on the new **Pinpoint** tab group in Burp Suite. You'll notice it is split into two sub-tabs: **Intercept Queue** and **BreakPoint Flow**.
3. Go to the **BreakPoint Flow** tab. As you browse your target application, watch your live traffic automatically map out into a visual node graph.
4. Found an endpoint you want to manipulate? **Double-click** its node in the graph. It will light up orange to indicate a breakpoint has been structurally set.
5. Switch over to the **Intercept Queue** tab and click the toggle button so it reads **"Intercept is on"**.
6. Trigger the request again in your browser. Pinpoint will instantly catch it and pause it in the queue table.
7. Use the embedded editors right there in the **Intercept Queue** tab to modify the request or response, then click **Forward**! 

## License

This project is licensed under the [GNU General Public License v3 (GPL-3.0)](LICENSE). 

Copyright (c) 2026 Stylianos Mavromatis.

Happy bug hunting! If you encounter any issues with the tool or can think of any improvements, please feel free to let me know!
