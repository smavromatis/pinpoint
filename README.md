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

![Pinpoint Screenshot](assets/pinpoint.PNG)

## Key Features

* **Visual Traffic Graph**  
  Replaces the traditional HTTP history table with an interactive, node-based map of your live traffic.

* **Point-and-Click Breakpoints**  
  Click the breakpoint dot on any node in the graph to set or remove a breakpoint, like in browser DevTools. For broader rules, open **Breakpoints & Grep** and add a *Contains*, *Exact* or *Regex* breakpoint (e.g. `/api/users/\d+/orders`), choosing whether it holds the request, the response or both. Every rule shows how many nodes it matches, and can be edited in place.

* **Live Grep**  
  Flag responses whose headers or body contain some text or match a regex. Matching nodes get a badge in the graph, and each rule shows how many it has flagged.

* **Isolated, In-Graph Interception**  
  Only your targeted endpoints pause. Background traffic automatically flows through without hanging your browser, and paused nodes pulse right in the graph.

* **Live Flow & Step Mode**  
  Nodes light up as the app moves from step to step, with a trail showing the order. Step Mode holds every visible request and response so you can walk through a flow one message at a time with Forward (F10), like stepping in a debugger.

* **Automatic Flows**  
  The graph shows one row per user action by default (**Flows**; switch it off for one row per host) (page loads, reloads, form submits, route changes, clicks and searches), with each row's steps numbered in call order. Flows are detected from browser and framework signals (Fetch Metadata headers, Next.js, Remix, Inertia, htmx, Turbo, GraphQL operations, Sentry traces) and named automatically; background polling is ignored.

* **Focus Mode**  
  Hides static assets (JS, CSS, images, fonts) by default so the graph and Step Mode only show the application's logic. Click the **▾** on the Focus button to choose exactly which file types are shown; the choice is remembered.

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
2. Open the **Pinpoint** tab in Burp Suite. As you browse your target application, watch your live traffic map out into a visual node graph. Click a node to view its latest request and response (read-only) below the graph.
3. Found an endpoint you want to manipulate? Hover its node and click the small **○** in its top-right corner (or double-click the node). The dot turns solid orange to show a breakpoint is set, and **Intercept** switches on. Click the dot again to remove it.
4. Trigger the request again in your browser. The node pulses orange and the area below the graph becomes editable: the held side is marked **✎ editable**, the other side is shown read-only for context.
5. Modify the request or response, then click **Forward** or press **F10** (or **Drop**). If several messages are held, use **◀ ▶** to move between them; unsent edits are kept when you switch. Click any paused node, or the **⏸ held** button in the toolbar, to jump to a held message.
6. To walk through a whole flow, turn on **Step Mode** and press **Forward** (or **F10**) to release one message at a time. Anything hidden from the graph (by **Focus**, **Only In-Scope**, the filter, or a collapsed lane) is skipped.

## License

This project is licensed under the [GNU General Public License v3 (GPL-3.0)](LICENSE). 

Copyright (c) 2026 Stylianos Mavromatis.

Happy bug hunting! If you encounter any issues with the tool or can think of any improvements, please feel free to let me know!
