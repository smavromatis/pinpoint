package com.pinpoint.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TrafficUtilsTest {

    @Test
    public void testParseHostAndPath_ValidUrl() {
        String[] result = TrafficUtils.parseHostAndPath("https://api.github.com/v3/users");
        assertEquals("api.github.com", result[0]);
        assertEquals("/v3/users", result[1]);
    }

    @Test
    public void testParseHostAndPath_InvalidUrl() {
        String[] result = TrafficUtils.parseHostAndPath("://bad url with spaces");
        assertEquals("-", result[0]);
        assertEquals("/", result[1]);
    }

    @Test
    public void testParseHostAndPath_RealWorldQueriesAndPaths() {
        String[][] cases = {
            {"https://shop.example.com/api/search?filter={\"q\":\"shoes\"}", "shop.example.com", "/api/search"},
            {"https://shop.example.com/graphql?operationName=Search&variables={\"t\":1}", "shop.example.com", "/graphql"},
            {"https://shop.example.com/api/search?q=a|b&sort=[price]", "shop.example.com", "/api/search"},
            {"https://shop.example.com:8443/api/x#frag", "shop.example.com", "/api/x"},
            {"https://user:pw@shop.example.com/a%20b/c", "shop.example.com", "/a b/c"},
            {"https://shop.example.com/items/caf\u00e9 menu.json", "shop.example.com", "/items/caf\u00e9 menu.json"},
            {"https://shop.example.com", "shop.example.com", "/"},
            {"http://[::1]:8080/health", "[::1]", "/health"},
        };
        for (String[] c : cases) {
            String[] result = TrafficUtils.parseHostAndPath(c[0]);
            assertEquals(c[1], result[0], c[0]);
            assertEquals(c[2], result[1], c[0]);
        }
    }

    @Test
    public void testGetFileType_Endpoints() {
        assertEquals("Endpoints", TrafficUtils.getFileType("/api/login"));
        assertEquals("Endpoints", TrafficUtils.getFileType("/index.php"));
    }

    /** One "pixel" per character, so widths in these tests are just string lengths. */
    private static String label(String path, int maxChars) {
        return TrafficUtils.endpointLabel(path, String::length, maxChars);
    }

    @Test
    public void testEndpointLabel_ShortPathShownInFull() {
        assertEquals("/api/login", label("/api/login", 25));
        assertEquals("/", label("/", 25));
        assertEquals("/", label("", 25));
    }

    @Test
    public void testEndpointLabel_KeepsAsManyParentSegmentsAsFit() {
        assertEquals("…/users/8812/orders", label("/api/v2/users/8812/orders", 20));
        assertEquals("…/orders", label("/api/v2/users/8812/orders", 10));
    }

    @Test
    public void testEndpointLabel_IdKeepsItsParentAndLongIdsAreShortened() {
        assertEquals("…/v2/users/550e…0000", label("/api/v2/users/550e8400-e29b-41d4-a716-446655440000", 20));
        assertEquals("…/users/8812", label("/api/users/8812", 12));
    }

    @Test
    public void testEndpointLabel_DifferentIdsNeverLookAlike() {
        assertNotEquals(label("/api/users/1/orders", 30), label("/api/users/2/orders", 30));
        assertNotEquals(label("/api/v1/12345/67890/config.json", 30), label("/api/v1/12345/67891/config.json", 30));
        assertNotEquals(label("/static/main.a1b2c3d4.js", 30), label("/static/main.e5f6a7b8.js", 30));
    }

    @Test
    public void testDisplaySegment_ShortensLongHashesButKeepsShortOnes() {
        assertEquals("main.a1b2c3d4.js", TrafficUtils.displaySegment("main.a1b2c3d4.js"));
        assertEquals("chunk-3f9a…3c.js", TrafficUtils.displaySegment("chunk-3f9a8b7c6d5e4f3c.js"));
        assertEquals("507f…9011", TrafficUtils.displaySegment("507f1f77bcf86cd799439011"));
        assertEquals("8812", TrafficUtils.displaySegment("8812"));
    }

    @Test
    public void testEndpointLabel_LongNameShortenedInTheMiddle() {
        String result = label("/reports/generate-quarterly-invoice-summary.json", 20);
        assertEquals(20, result.length());
        assertTrue(result.startsWith("…/generate"));
        assertTrue(result.endsWith(".json"));
    }

    @Test
    public void testNormalizeSegment() {
        assertEquals(":id", TrafficUtils.normalizeSegment("12345"));
        assertEquals(":id", TrafficUtils.normalizeSegment("507f1f77bcf86cd799439011"));
        assertEquals(":id", TrafficUtils.normalizeSegment("eyJhbGciOiJIUzI1NiJ9abc"));
        assertEquals("login", TrafficUtils.normalizeSegment("login"));
        assertEquals("v2", TrafficUtils.normalizeSegment("v2"));
        assertEquals("authentication-callback", TrafficUtils.normalizeSegment("authentication-callback"));
    }

    @Test
    public void testGetFileType_ImagesAndFonts() {
        assertEquals("Images", TrafficUtils.getFileType("/assets/logo.png"));
        assertEquals("Images", TrafficUtils.getFileType("/images/bg.webp?v=123"));
        assertEquals("Fonts", TrafficUtils.getFileType("/fonts/inter.woff2"));
    }
}
