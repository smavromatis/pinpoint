### Pinpoint v2.1.0

Pinpoint is a Burp Suite extension that shows your traffic as a map and lets you pause only the requests you choose, like breakpoints in browser DevTools.

#### What's new
* Newest first: the rows you used most recently move to the top. A page moves up when you load it again or use one of its actions.
* Rows stay still while you work: nothing moves while your pointer is over the map or while a message is paused. The order updates when you go back to the browser.
* Reorder rows yourself: drag a row's title to move it. The Lanes button then shows "Custom". Choose "Newest first" or "First seen" in the Lanes menu to switch back.
* Activity in collapsed rows: a collapsed row is briefly highlighted when something inside it is hit, and shows "held" when a paused message is waiting inside it.

#### Installation
1. Download Pinpoint-2.1.0.jar from this release.
2. Open Burp Suite -> Extensions -> Installed -> Add.
3. Select the JAR file. If an older version is loaded, remove it first.

Requires Java 17+ and a Burp Suite version that supports the Montoya API.
