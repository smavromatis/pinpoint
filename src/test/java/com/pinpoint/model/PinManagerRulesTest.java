package com.pinpoint.model;

import burp.api.montoya.http.message.responses.HttpResponse;
import com.pinpoint.model.PinManager.MatchType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

public class PinManagerRulesTest {

    private static final String HOST_PATH = "app.example.com/api/users/8812/orders";
    private static final String URL = "https://app.example.com/api/users/8812/orders?page=2";

    private static boolean matches(String pattern, MatchType type) {
        return PinManager.matchesPinPattern(new PinManager.PinRule(pattern, type, true, true, true), HOST_PATH, URL);
    }

    @Test
    public void testMatchTypes() {
        assertTrue(matches("/users/", MatchType.CONTAINS));
        assertFalse(matches("/admin/", MatchType.CONTAINS));
        assertTrue(matches(HOST_PATH, MatchType.EXACT));
        assertTrue(matches(URL, MatchType.EXACT));
        assertFalse(matches("app.example.com/api/users", MatchType.EXACT));
        assertTrue(matches("/api/users/\\d+/orders", MatchType.REGEX));
        assertFalse(matches("/api/users/[a-z]+/orders$", MatchType.REGEX));
        assertFalse(matches("([unclosed", MatchType.REGEX));   // invalid regex never matches
    }

    @Test
    public void testAddValidatesAndRejectsDuplicates() {
        PinManager pm = new PinManager(null);
        assertNull(pm.addPin("/api/login", MatchType.CONTAINS, true, false));
        assertNotNull(pm.addPin("/api/login", MatchType.CONTAINS, true, true));     // duplicate
        assertNull(pm.addPin("/api/login", MatchType.EXACT, true, true));           // same text, other type: fine
        assertNotNull(pm.addPin("  ", MatchType.CONTAINS, true, true));
        String error = pm.addPin("([unclosed", MatchType.REGEX, true, true);
        assertNotNull(error);
        assertTrue(error.startsWith("Invalid regex"));
        assertEquals(2, pm.getPins().size());
        assertTrue(pm.getPins().get(0).isInterceptRequest());
        assertFalse(pm.getPins().get(0).isInterceptResponse());
    }

    @Test
    public void testEditKeepsOtherSettingsAndValidates() {
        PinManager pm = new PinManager(null);
        pm.addPin("/api/a", MatchType.CONTAINS, false, true);
        pm.addPin("/api/b", MatchType.CONTAINS, true, true);
        pm.setPinEnabled(0, false);

        assertNull(pm.updatePin(0, "/api/users/\\d+", MatchType.REGEX));
        PinManager.PinRule edited = pm.getPins().get(0);
        assertEquals("/api/users/\\d+", edited.getPattern());
        assertEquals(MatchType.REGEX, edited.getMatchType());
        assertFalse(edited.isEnabled());
        assertFalse(edited.isInterceptRequest());
        assertTrue(edited.isInterceptResponse());

        assertNotNull(pm.updatePin(0, "/api/b", MatchType.CONTAINS));   // would duplicate row 1
        assertNotNull(pm.updatePin(1, "(", MatchType.REGEX));
        assertEquals("/api/b", pm.getPins().get(1).getPattern());

        pm.setPinIntercept(1, false, true);
        assertFalse(pm.getPins().get(1).isInterceptRequest());
    }

    @Test
    public void testSavedMatchTypesFromOlderVersionsStillLoad() {
        assertEquals(MatchType.EXACT, PinManager.parseMatchType("true"));
        assertEquals(MatchType.CONTAINS, PinManager.parseMatchType("false"));
        assertEquals(MatchType.REGEX, PinManager.parseMatchType("REGEX"));
        assertEquals(MatchType.CONTAINS, PinManager.parseMatchType("garbage"));
    }

    @Test
    public void testCountPinMatchesPreview() {
        PinManager pm = new PinManager(null);
        pm.logTraffic("https://app.example.com/api/users/1/orders", true, null, null, true, false);
        pm.logTraffic("https://app.example.com/api/users/2/orders", true, null, null, true, false);
        pm.logTraffic("https://app.example.com/api/products", true, null, null, true, false);
        assertEquals(2, pm.countPinMatches("/api/users/\\d+/orders", MatchType.REGEX));
        assertEquals(3, pm.countPinMatches("/api/", MatchType.CONTAINS));
        assertEquals(1, pm.countPinMatches("app.example.com/api/products", MatchType.EXACT));
        assertEquals(0, pm.countPinMatches("(", MatchType.REGEX));
    }

    private static HttpResponse response(String fullText) {
        return (HttpResponse) Proxy.newProxyInstance(PinManagerRulesTest.class.getClassLoader(), new Class<?>[]{HttpResponse.class},
                (p, m, a) -> switch (m.getName()) {
                    case "toString" -> fullText;
                    case "bodyToString" -> fullText.substring(fullText.indexOf("\r\n\r\n") + 4);
                    case "statusCode" -> (short) 200;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> null;
                });
    }

    @Test
    public void testGrepValidatesAndSearchesHeadersAndBody() {
        PinManager pm = new PinManager(null);
        assertNull(pm.addKeyword("session=", false));
        assertNotNull(pm.addKeyword("session=", false));                    // duplicate
        assertTrue(pm.addKeyword("([bad", true).startsWith("Invalid regex"));
        assertNull(pm.addKeyword("\"role\"\\s*:\\s*\"admin\"", true));

        // Plain text that isn't a valid regex can't be switched to regex
        assertNull(pm.addKeyword("price[", false));
        assertNotNull(pm.setKeywordRegex(2, true));
        assertFalse(pm.getKeywordRules().get(2).isRegex());

        String raw = "HTTP/1.1 200 OK\r\nSet-Cookie: session=abc123; HttpOnly\r\n\r\n{\"role\": \"admin\"}";
        pm.logTraffic("https://app.example.com/api/me", false, null, response(raw), true, false);
        TrafficEntry me = pm.getTrafficLog().get(0);
        assertTrue(me.getMatchedKeywords().contains("session="), "header match");
        assertTrue(me.getMatchedKeywords().contains("\"role\"\\s*:\\s*\"admin\""), "body regex match");
        assertEquals(1, pm.countKeywordHits("session="));
        assertEquals(1, pm.countKeywordPreview("HttpOnly", false));
        assertEquals(0, pm.countKeywordPreview("(", true));

        assertNull(pm.updateKeyword(0, "Set-Cookie"));
        assertEquals("Set-Cookie", pm.getKeywordRules().get(0).getKeyword());
        assertNotNull(pm.updateKeyword(0, ""));
    }
}
