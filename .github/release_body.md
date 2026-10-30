### Pinpoint v2.0.0

Pinpoint is a Burp Suite extension designed to streamline the targeting and manipulation of multi-step logic flaws by porting the endpoint-specific breakpoint paradigm of browser developer tools into Burp Suite.

Version 2.0.0 reworks Pinpoint around a single live map of the application: everything (browsing, breakpoints, editing held messages) now happens in one view.

#### What's new
* Live flow map: traffic is grouped into flows automatically. Page loads, reloads, form submits and route changes become sections, with the actions taken on each page (tabs, searches, saves) nested underneath and their steps numbered in call order. Flows are detected from browser and framework signals (Fetch Metadata headers, Next.js, Remix, Inertia, htmx, Turbo, GraphQL operations, Sentry traces), and background polling is ignored.
* Live activity: nodes light up as they are hit, so you can watch the app move from step to step.
* In-graph interception: the separate Intercept Queue tab is gone. When a breakpoint holds a message, its node pulses and the request/response area below the graph becomes editable, with large Forward (F10) and Drop buttons. Edits are kept per message while you move between held messages.
* Step Mode: hold every visible request and response and release them one at a time with Forward.
* Breakpoint dots: click the dot on any node to set or remove a breakpoint, like in browser DevTools.
* Breakpoints and Live Grep sidebar: Contains, Exact and Regex breakpoints that hold the request, the response or both; grep rules that search response headers and bodies. Every rule shows how many nodes it matches and can be edited in place, with validation and a live preview when adding.
* Focus: hide file types you don't need (JS, CSS, images and fonts by default), with a dropdown to choose exactly which types are shown.
* Lanes: group the map by flow, by host, or by host and file type.
* Scope from the graph: add a host to Burp's target scope with one click, and the graph follows scope changes made in Burp.
* Readable node names that keep real IDs and fit the card width, with redirects and errors highlighted.
* Follows Burp's theme (including dark mode) and font size.

#### Fixes
* Edits to a held message are no longer lost when another message arrives.
* Search and API requests with characters such as { } | [ ] in the query string are no longer merged into a single node.
* Visible endpoints are no longer pushed out of the graph by a flood of static assets.
* Unloading the extension releases any held messages immediately.
* Lower memory use: stored message bodies are capped, and data for removed nodes is released.

#### Installation
1. Download Pinpoint-2.0.0.jar from this release.
2. Open Burp Suite -> Extensions -> Installed -> Add.
3. Select the JAR file. If an older version is loaded, remove it first.

Requires Java 17+ and a Burp Suite version that supports the Montoya API.
