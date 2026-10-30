package com.pinpoint.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FlowDetectorTest {

    private static FlowDetector.Request req(String method, String path, long millis, String... headers) {
        return reqWithBody(method, path, null, "", millis, headers);
    }

    private static FlowDetector.Request reqWithBody(String method, String path, String query, String body, long millis, String... headers) {
        Map<String, String> h = new HashMap<>();
        for (int i = 0; i < headers.length; i += 2) h.put(headers[i], headers[i + 1]);
        return new FlowDetector.Request(method, path, query, h, body, millis);
    }

    private static final String[] PAGE = {"sec-fetch-mode", "navigate", "sec-fetch-dest", "document", "sec-fetch-site", "same-origin"};
    private static final String[] FETCH = {"sec-fetch-mode", "cors", "sec-fetch-dest", "empty", "sec-fetch-site", "same-origin"};

    private static String[] with(String[] base, String... extra) {
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    @Test
    public void testPageLoadKinds() {
        FlowDetector d = new FlowDetector();
        assertEquals(FlowDetector.Kind.NAVIGATE, d.onRequest(req("GET", "/dashboard", 0, PAGE)).kind());
        assertEquals(FlowDetector.Kind.RELOAD, d.onRequest(req("GET", "/dashboard", 5000, with(PAGE, "cache-control", "max-age=0"))).kind());
        assertEquals(FlowDetector.Kind.SUBMIT, d.onRequest(req("POST", "/login", 9000, PAGE)).kind());
        FlowDetector.Start opened = d.onRequest(req("GET", "/settings", 12000,
                "sec-fetch-mode", "navigate", "sec-fetch-dest", "document", "sec-fetch-site", "none"));
        assertEquals(FlowDetector.Kind.OPEN, opened.kind());
        assertEquals("/settings", opened.name());
    }

    @Test
    public void testReloadAndClickOfSamePageGroupTogether() {
        FlowDetector d = new FlowDetector();
        String clicked = d.onRequest(req("GET", "/users/12", 0, PAGE)).key();
        String reloaded = d.onRequest(req("GET", "/users/99", 5000, with(PAGE, "cache-control", "max-age=0"))).key();
        assertEquals(clicked, reloaded);
    }

    @Test
    public void testSubResourcesDuringPageLoadContinueTheFlow() {
        FlowDetector d = new FlowDetector();
        d.onRequest(req("GET", "/dashboard", 0, PAGE));
        assertNull(d.onRequest(req("GET", "/app.js", 50, "sec-fetch-dest", "script")));
        assertNull(d.onRequest(req("GET", "/logo.png", 60, "sec-fetch-dest", "image")));
        assertNull(d.onRequest(req("GET", "/api/me", 200, FETCH)));
        assertNull(d.onRequest(req("POST", "/api/session/refresh", 300, FETCH)));  // bootstrap mutation
    }

    @Test
    public void testNeverStartsOnPrefetchPreflightOrThirdParty() {
        FlowDetector d = new FlowDetector();
        d.onRequest(req("GET", "/", 0, PAGE));
        assertNull(d.onRequest(req("GET", "/next", 5000, with(PAGE, "sec-purpose", "prefetch"))));
        assertNull(d.onRequest(req("OPTIONS", "/api/x", 10000, FETCH)));
        assertNull(d.onRequest(req("POST", "/g/collect", 15000,
                "sec-fetch-mode", "no-cors", "sec-fetch-dest", "empty", "sec-fetch-site", "cross-site")));
    }

    @Test
    public void testRedirectAfterLoginContinuesTheSameFlow() {
        FlowDetector d = new FlowDetector();
        FlowDetector.Request login = req("POST", "/login", 0, PAGE);
        assertEquals(FlowDetector.Kind.SUBMIT, d.onRequest(login).kind());
        d.onResponse(login, 302, "https://app.example.com/dashboard");
        assertNull(d.onRequest(req("GET", "/dashboard", 100, PAGE)));
    }

    @Test
    public void testFrameworkSignals() {
        FlowDetector d = new FlowDetector();
        d.onRequest(req("GET", "/", 0, PAGE));

        FlowDetector.Start next = d.onRequest(req("GET", "/products/42", 100, with(FETCH, "rsc", "1")));
        assertEquals(FlowDetector.Kind.ROUTE, next.kind());
        assertEquals("/products/42", next.name());

        assertNull(d.onRequest(req("GET", "/cart", 150, with(FETCH, "rsc", "1", "next-router-prefetch", "1"))));

        FlowDetector.Start remix = d.onRequest(req("GET", "/orders.data", 200, FETCH));
        assertEquals(FlowDetector.Kind.ROUTE, remix.kind());
        assertEquals("/orders", remix.name());

        FlowDetector.Start htmx = d.onRequest(req("GET", "/search", 250, with(FETCH, "hx-request", "true", "hx-trigger", "search-box")));
        assertEquals("#search-box", htmx.name());

        FlowDetector.Start inertia = d.onRequest(req("GET", "/events", 300, with(FETCH, "x-inertia", "true",
                "x-inertia-partial-component", "Events")));
        assertEquals("Reload Events", inertia.name());

        assertEquals("Frame #comments", d.onRequest(req("GET", "/posts/1/comments", 350, with(FETCH, "turbo-frame", "comments"))).name());
    }

    @Test
    public void testGraphqlNamesAndMutations() {
        FlowDetector d = new FlowDetector();
        d.onRequest(req("GET", "/", 0, PAGE));
        // A query during page load is part of the load
        assertNull(d.onRequest(reqWithBody("POST", "/graphql", null,
                "{\"operationName\":\"GetFeed\",\"query\":\"query GetFeed { feed { id } }\"}", 100, FETCH)));
        // A mutation is a user action, named by its operation
        FlowDetector.Start like = d.onRequest(reqWithBody("POST", "/graphql", null,
                "{\"operationName\":\"LikePost\",\"query\":\"mutation LikePost($id: ID!) { like(id: $id) }\"}", 3000, FETCH));
        assertEquals(FlowDetector.Kind.ACTION, like.kind());
        assertEquals("LikePost", like.name());
        // A query after a quiet period is named by its operation too
        FlowDetector.Start profile = d.onRequest(reqWithBody("POST", "/graphql", null,
                "{\"operationName\":\"GetUserProfile\",\"query\":\"query GetUserProfile { me { id } }\"}", 9000, FETCH));
        assertEquals("GetUserProfile", profile.name());
    }

    @Test
    public void testMutationIsAnActionEvenWithoutQuietGap() {
        FlowDetector d = new FlowDetector();
        d.onRequest(req("GET", "/", 0, PAGE));
        d.onRequest(req("GET", "/api/items", 2000, FETCH));
        FlowDetector.Start save = d.onRequest(req("PUT", "/api/items/7", 2100, FETCH));
        assertEquals("PUT /api/items/7", save.name());
    }

    @Test
    public void testNewSentryTraceStartsAnAction() {
        FlowDetector d = new FlowDetector();
        d.onRequest(req("GET", "/", 0, PAGE));
        assertNull(d.onRequest(req("GET", "/api/a", 100, with(FETCH, "sentry-trace", "aaaa-1111-1"))));
        assertNull(d.onRequest(req("GET", "/api/b", 150, with(FETCH, "sentry-trace", "aaaa-2222-1"))));
        assertNotNull(d.onRequest(req("GET", "/api/c", 300, with(FETCH, "sentry-trace", "bbbb-3333-1"))));
    }

    @Test
    public void testQuietGapIsOnlyTheFallback() {
        FlowDetector d = new FlowDetector();
        d.onRequest(req("GET", "/", 0, PAGE));
        assertNull(d.onRequest(req("GET", "/api/a", 900, FETCH)));
        assertNull(d.onRequest(req("GET", "/api/b", 1500, FETCH)));
        FlowDetector.Start tab = d.onRequest(req("GET", "/api/tab/billing", 4000, FETCH));
        assertEquals("GET /api/tab/billing", tab.name());
    }
}
