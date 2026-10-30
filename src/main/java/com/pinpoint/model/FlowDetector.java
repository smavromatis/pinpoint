package com.pinpoint.model;

import com.pinpoint.utils.TrafficUtils;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which requests start a new user flow (a page load, a route change, a click...) and names
 * it. A proxy never sees clicks, so this reads the signals browsers and frameworks put on requests,
 * most reliable first; a quiet gap between requests is only the last resort.
 * Not thread-safe: {@link FlowTracker} serializes calls.
 */
public final class FlowDetector {

    public enum Kind {
        RELOAD("reload"),
        OPEN("opened"),
        NAVIGATE("page load"),
        SUBMIT("form submit"),
        ROUTE("route change"),
        ACTION("action");

        public final String label;

        Kind(String label) {
            this.label = label;
        }

        boolean isPageLoad() {
            return this == RELOAD || this == OPEN || this == NAVIGATE || this == SUBMIT;
        }
    }

    /** A request that starts a flow: its kind, a key that groups repeat runs, and a display name. */
    public record Start(Kind kind, String key, String name) {}

    /** The parts of a request the detector looks at. Header names must be lower-case. */
    public record Request(String method, String path, String query, Map<String, String> headers,
                          String body, long millis) {
        String header(String name) {
            return headers.get(name);
        }
    }

    // Last resort for apps with no other signal: a fetch after this much silence is a new action
    static final long QUIET_GAP_MS = 1000;
    // Mutations right after a page load are the app bootstrapping, not the user acting
    static final long BOOTSTRAP_MS = 750;
    // A navigation to a redirect's Location within this window continues the same flow
    static final long REDIRECT_WINDOW_MS = 5000;

    // Sec-Fetch-Dest values for sub-resources, which never start a flow
    private static final Set<String> STATIC_DESTS = Set.of("image", "script", "style", "font", "audio",
            "video", "track", "manifest", "worker", "sharedworker", "serviceworker", "object", "embed",
            "report", "xslt", "audioworklet", "paintworklet", "json", "webidentity");

    private static final Pattern GRAPHQL_OPERATION = Pattern.compile("\"operationName\"\\s*:\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");
    private static final Pattern GRAPHQL_MUTATION = Pattern.compile("\"query\"\\s*:\\s*\"\\s*mutation\\b");
    private static final Pattern QUERY_OPERATION = Pattern.compile("(?:^|&)operationName=([A-Za-z_][A-Za-z0-9_]*)");

    private long lastRequestMillis = -1;
    private long lastPageLoadMillis = -1;
    private boolean anyStart = false;
    private String currentTraceId;
    private String redirectTargetPath;
    private long redirectMillis;

    public Start onRequest(Request r) {
        long gap = lastRequestMillis < 0 ? Long.MAX_VALUE : r.millis() - lastRequestMillis;
        lastRequestMillis = Math.max(lastRequestMillis, r.millis());

        String traceId = sentryTraceId(r);
        boolean newTrace = traceId != null && currentTraceId != null && !traceId.equals(currentTraceId);
        if (traceId != null) currentTraceId = traceId;

        Start start = classify(r, gap, newTrace);
        if (start != null) {
            anyStart = true;
            if (start.kind().isPageLoad()) lastPageLoadMillis = r.millis();
        }
        return start;
    }

    /** Remembers where a page load redirects, so following the redirect doesn't start a new flow. */
    public void onResponse(Request r, int status, String location) {
        if (status >= 300 && status < 400 && location != null && isDocumentNavigation(r)) {
            redirectTargetPath = pathOf(location);
            redirectMillis = r.millis();
        }
    }

    private Start classify(Request r, long gap, boolean newTrace) {
        String method = r.method().toUpperCase(Locale.ROOT);
        String dest = r.header("sec-fetch-dest");
        // Keys group repeat runs by route (/items/:id); names show the latest run's real path
        String route = routeOf(r.path());
        String shown = r.path() == null || r.path().isEmpty() ? "/" : r.path();

        // Never a start: speculative loads, CORS preflights, sub-resources, third-party calls
        if (isSpeculative(r) || method.equals("OPTIONS")) return null;
        if (dest != null && STATIC_DESTS.contains(dest)) return null;
        if (dest == null && ViewFilter.STATIC_TYPES.contains(TrafficUtils.getFileType(r.path()))) return null;
        if ("cross-site".equals(r.header("sec-fetch-site"))) return null;

        // 1. Browser page loads: Sec-Fetch-Mode/Dest say exactly when a top-level page is requested
        if (isDocumentNavigation(r)) {
            if (redirectTargetPath != null && redirectTargetPath.equals(r.path())
                    && r.millis() - redirectMillis <= REDIRECT_WINDOW_MS) {
                redirectTargetPath = null;
                return null;
            }
            Kind kind = !method.equals("GET") ? Kind.SUBMIT
                    : isReload(r) ? Kind.RELOAD
                    : "none".equals(r.header("sec-fetch-site")) ? Kind.OPEN
                    : Kind.NAVIGATE;
            String name = kind == Kind.SUBMIT ? method + " " + shown : shown;
            return new Start(kind, "page " + method + " " + route, name);
        }

        // 2. Framework requests that are, by definition, a route change or a user action
        String nextAction = r.header("next-action");
        if (nextAction != null) {
            return new Start(Kind.ACTION, "next-action " + nextAction, "Server action " + shown);
        }
        if ("1".equals(r.header("rsc"))) {
            return new Start(Kind.ROUTE, "route " + route, shown);
        }
        if (r.path().endsWith(".data") || (r.query() != null && r.query().contains("_data="))) {
            String targetPath = r.path().endsWith(".data") ? r.path().substring(0, r.path().length() - 5) : r.path();
            String target = routeOf(targetPath);
            return new Start(method.equals("GET") ? Kind.ROUTE : Kind.ACTION, "route " + method + " " + target,
                    method.equals("GET") ? targetPath : method + " " + targetPath);
        }
        if ("true".equalsIgnoreCase(r.header("x-inertia"))) {
            String component = r.header("x-inertia-partial-component");
            if (component != null) return new Start(Kind.ACTION, "inertia-partial " + component, "Reload " + component);
            return method.equals("GET")
                    ? new Start(Kind.ROUTE, "route " + route, shown)
                    : new Start(Kind.ACTION, "inertia " + method + " " + route, method + " " + shown);
        }
        if ("true".equalsIgnoreCase(r.header("hx-request"))) {
            String trigger = r.header("hx-trigger");
            String triggerName = r.header("hx-trigger-name");
            String name = trigger != null ? "#" + trigger : triggerName != null ? triggerName : method + " " + shown;
            return new Start(Kind.ACTION, "htmx " + name, name);
        }
        String turboFrame = r.header("turbo-frame");
        if (turboFrame != null) {
            return new Start(Kind.ACTION, "turbo-frame " + turboFrame, "Frame #" + turboFrame);
        }

        boolean bootstrapping = lastPageLoadMillis >= 0 && r.millis() - lastPageLoadMillis < BOOTSTRAP_MS;
        String operation = graphqlOperation(r);

        // 3. GraphQL mutations are user actions, named by their operation
        if (operation != null && r.body() != null && GRAPHQL_MUTATION.matcher(r.body()).find() && !bootstrapping) {
            return new Start(Kind.ACTION, "graphql " + operation, operation);
        }

        // 4. Instrumented apps: Sentry starts a new trace per page load, route change and interaction
        if (newTrace && isFetch(r)) {
            return actionStart(r, method, shown, operation);
        }

        // 5. Non-GET calls from scripts are almost always the user doing something
        if (isFetch(r) && !method.equals("GET") && !method.equals("HEAD") && operation == null && !bootstrapping) {
            return actionStart(r, method, shown, null);
        }

        // 6. Last resort: a fetch after a quiet period, or the very first request seen
        if (isFetch(r) && (gap >= QUIET_GAP_MS || !anyStart)) {
            return actionStart(r, method, shown, operation);
        }
        if (!anyStart) {
            return actionStart(r, method, shown, operation);
        }
        return null;
    }

    private static Start actionStart(Request r, String method, String shownPath, String operation) {
        return new Start(Kind.ACTION, requestKey(r), operation != null ? operation : method + " " + shownPath);
    }

    /** Groups repeats of the same call; also used by FlowTracker to recognize background polling. */
    public static String requestKey(Request r) {
        String operation = graphqlOperation(r);
        if (operation != null) return "graphql " + operation;
        return "call " + r.method().toUpperCase(Locale.ROOT) + " " + routeOf(r.path());
    }

    static boolean isDocumentNavigation(Request r) {
        String mode = r.header("sec-fetch-mode");
        String dest = r.header("sec-fetch-dest");
        if (mode != null || dest != null) {
            return "navigate".equals(mode) && (dest == null || "document".equals(dest));
        }
        // Browsers without Fetch Metadata: an HTML request that isn't an XHR
        String accept = r.header("accept");
        return accept != null && accept.contains("text/html") && r.header("x-requested-with") == null;
    }

    private static boolean isFetch(Request r) {
        String dest = r.header("sec-fetch-dest");
        if (dest != null) return dest.equals("empty");
        return !isDocumentNavigation(r);
    }

    private static boolean isReload(Request r) {
        String cacheControl = r.header("cache-control");
        String pragma = r.header("pragma");
        return (cacheControl != null && (cacheControl.contains("max-age=0") || cacheControl.contains("no-cache")))
                || (pragma != null && pragma.contains("no-cache"));
    }

    private static boolean isSpeculative(Request r) {
        String secPurpose = r.header("sec-purpose");
        String purpose = r.header("purpose");
        return (secPurpose != null && secPurpose.contains("prefetch"))
                || (purpose != null && purpose.contains("prefetch"))
                || r.header("next-router-prefetch") != null
                || "prefetch".equals(r.header("x-moz"));
    }

    private static String graphqlOperation(Request r) {
        if (r.body() != null && !r.body().isEmpty()) {
            Matcher m = GRAPHQL_OPERATION.matcher(r.body());
            if (m.find()) return m.group(1);
        }
        if (r.query() != null) {
            Matcher m = QUERY_OPERATION.matcher(r.query());
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** The trace id from a Sentry "sentry-trace: traceid-spanid-sampled" header. */
    private static String sentryTraceId(Request r) {
        String header = r.header("sentry-trace");
        if (header == null) return null;
        int dash = header.indexOf('-');
        return dash > 0 ? header.substring(0, dash) : header;
    }

    /** "/api/users/8812/orders" → "/api/users/:id/orders", so repeat runs group together. */
    static String routeOf(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) return "/";
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            sb.append('/').append(TrafficUtils.normalizeSegment(segment));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String pathOf(String location) {
        try {
            String path = URI.create(location.trim()).getRawPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (IllegalArgumentException e) {
            int q = location.indexOf('?');
            return q >= 0 ? location.substring(0, q) : location;
        }
    }
}
