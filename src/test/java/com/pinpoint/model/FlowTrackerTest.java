package com.pinpoint.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FlowTrackerTest {

    private static final Map<String, String> PAGE = Map.of("sec-fetch-mode", "navigate", "sec-fetch-dest", "document", "sec-fetch-site", "same-origin");
    private static final Map<String, String> FETCH = Map.of("sec-fetch-mode", "cors", "sec-fetch-dest", "empty", "sec-fetch-site", "same-origin");

    private static TrafficEntry entry(String path) {
        return new TrafficEntry(path, "app.example.com", "https://app.example.com" + path, false, true, null, null);
    }

    private static FlowDetector.Request req(String method, String path, Map<String, String> headers, long millis) {
        return new FlowDetector.Request(method, path, null, headers, "", millis);
    }

    @Test
    public void testStepsInOrderAndRepeatRunsReplaceSteps() {
        FlowTracker tracker = new FlowTracker();
        TrafficEntry page = entry("/dashboard"), me = entry("/api/me"), feed = entry("/api/feed");

        tracker.onRequest(page, req("GET", "/dashboard", PAGE, 0));
        tracker.onRequest(me, req("GET", "/api/me", FETCH, 100));
        tracker.onRequest(feed, req("GET", "/api/feed", FETCH, 200));
        tracker.onRequest(me, req("GET", "/api/me", FETCH, 300));   // repeat within the run: not a new step

        List<FlowTracker.FlowView> flows = tracker.snapshot();
        assertEquals(1, flows.size());
        assertEquals(List.of(page, me, feed), flows.get(0).steps());
        assertEquals(1, flows.get(0).runs());

        // Reload: same flow, second run, steps replaced by the new run
        tracker.onRequest(page, req("GET", "/dashboard", Map.of("sec-fetch-mode", "navigate", "sec-fetch-dest", "document",
                "sec-fetch-site", "same-origin", "cache-control", "max-age=0"), 10_000));
        tracker.onRequest(feed, req("GET", "/api/feed", FETCH, 10_100));
        flows = tracker.snapshot();
        assertEquals(1, flows.size());
        assertEquals(2, flows.get(0).runs());
        assertEquals(FlowDetector.Kind.RELOAD, flows.get(0).kind());
        assertEquals(List.of(page, feed), flows.get(0).steps());
    }

    private static Map<String, String> fetchFrom(String refererPath) {
        return Map.of("sec-fetch-mode", "cors", "sec-fetch-dest", "empty", "sec-fetch-site", "same-origin",
                "referer", "https://app.example.com" + refererPath);
    }

    @Test
    public void testActionsAreNestedUnderTheirPage() {
        FlowTracker tracker = new FlowTracker();
        TrafficEntry dashboard = entry("/dashboard"), billing = entry("/api/billing"),
                settings = entry("/settings"), search = entry("/api/search");

        tracker.onRequest(dashboard, req("GET", "/dashboard", PAGE, 0));
        tracker.onRequest(billing, req("GET", "/api/billing", fetchFrom("/dashboard"), 3000));   // tab on dashboard
        tracker.onRequest(settings, req("GET", "/settings", PAGE, 6000));
        tracker.onRequest(search, req("GET", "/api/search", fetchFrom("/settings"), 9000));      // action on settings

        List<FlowTracker.FlowView> flows = tracker.snapshot();
        assertEquals(List.of("/dashboard", "GET /api/billing", "/settings", "GET /api/search"),
                flows.stream().map(FlowTracker.FlowView::name).toList());
        assertNull(flows.get(0).parentKey());
        assertEquals(flows.get(0).key(), flows.get(1).parentKey());
        assertNull(flows.get(2).parentKey());
        assertEquals(flows.get(2).key(), flows.get(3).parentKey());
    }

    @Test
    public void testRefererPicksThePageEvenWhenAnotherPageLoadedLater() {
        FlowTracker tracker = new FlowTracker();
        tracker.onRequest(entry("/dashboard"), req("GET", "/dashboard", PAGE, 0));
        tracker.onRequest(entry("/reports"), req("GET", "/reports", PAGE, 3000));
        // An action from a dashboard tab still open in another browser tab
        tracker.onRequest(entry("/api/refresh"), req("POST", "/api/refresh", fetchFrom("/dashboard"), 6000));

        List<FlowTracker.FlowView> flows = tracker.snapshot();
        assertEquals(List.of("/dashboard", "POST /api/refresh", "/reports"),
                flows.stream().map(FlowTracker.FlowView::name).toList());
    }

    @Test
    public void testActionWithoutRefererGoesUnderTheLatestPage() {
        FlowTracker tracker = new FlowTracker();
        tracker.onRequest(entry("/inbox"), req("GET", "/inbox", PAGE, 0));
        tracker.onRequest(entry("/api/archive"), req("POST", "/api/archive", FETCH, 3000));
        List<FlowTracker.FlowView> flows = tracker.snapshot();
        assertEquals(flows.get(0).key(), flows.get(1).parentKey());
    }

    @Test
    public void testSteadyPollingIsIgnored() {
        FlowTracker tracker = new FlowTracker();
        TrafficEntry page = entry("/inbox"), poll = entry("/api/unread");
        tracker.onRequest(page, req("GET", "/inbox", PAGE, 0));

        // Polled every 5s: after a few rounds it's recognized as background and dropped
        for (int i = 1; i <= 5; i++) {
            tracker.onRequest(poll, req("GET", "/api/unread", FETCH, i * 5000L));
        }
        List<FlowTracker.FlowView> flows = tracker.snapshot();
        assertEquals(1, flows.size());
        assertEquals("/inbox", flows.get(0).name());
    }
}
