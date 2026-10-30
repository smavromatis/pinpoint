package com.pinpoint.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PinManagerEvictionTest {

    private static boolean hasPath(PinManager pm, String path) {
        return pm.getTrafficLog().stream().anyMatch(e -> e.getPath().equals(path));
    }

    @Test
    public void testFloodOfHiddenAssetsDoesNotEvictVisibleEndpoints() {
        PinManager pm = new PinManager(null);
        List<String> endpoints = List.of("/dashboard", "/api/me", "/api/feed", "/api/items/7");
        for (String path : endpoints) {
            pm.logTraffic("https://app.example.com" + path, true, null, null, true, false);
        }
        // A heavy page: far more hashed assets and third-party calls than the log can hold
        for (int i = 0; i < 1500; i++) {
            String url = i % 3 == 0 ? "https://cdn.example.net/p" + i + ".png" : "https://app.example.com/static/chunk-" + i + ".js";
            pm.logTraffic(url, true, null, null, i % 3 != 0, false);
        }
        for (String path : endpoints) {
            assertTrue(hasPath(pm, path), path + " was evicted");
        }
    }

    @Test
    public void testDifferentIdsAreDifferentNodes() {
        PinManager pm = new PinManager(null);
        pm.logTraffic("https://app.example.com/api/users/1/orders", true, null, null, true, false);
        pm.logTraffic("https://app.example.com/api/users/2/orders", true, null, null, true, false);
        pm.logTraffic("https://app.example.com/api/users/1/orders", true, null, null, true, false);
        assertEquals(2, pm.getTrafficLog().size());
    }

    @Test
    public void testBreakpointNodesAreNeverEvicted() {
        PinManager pm = new PinManager(null);
        pm.logTraffic("https://other.example.net/old", true, null, null, false, false);
        pm.addPin("other.example.net/old", PinManager.MatchType.EXACT, true, true);
        for (int i = 0; i < 1500; i++) {
            pm.logTraffic("https://other.example.net/api/x" + i, true, null, null, false, false);
        }
        assertTrue(hasPath(pm, "/old"));
    }
}
