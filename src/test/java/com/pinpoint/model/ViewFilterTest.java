package com.pinpoint.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ViewFilterTest {

    private static final String HOST = "app.example.com";

    private static boolean shows(ViewFilter f, String path, boolean inScope) {
        return f.shows(HOST, path, "https://" + HOST + path, inScope, List.of());
    }

    @Test
    public void testFocusModeHidesStaticAssetsOnly() {
        ViewFilter f = ViewFilter.DEFAULT;
        assertTrue(f.focusMode());
        assertFalse(shows(f, "/static/app.js", true));
        assertFalse(shows(f, "/styles/main.css", true));
        assertFalse(shows(f, "/img/logo.png", true));
        assertFalse(shows(f, "/fonts/inter.woff2", true));
        assertTrue(shows(f, "/api/login", true));
        assertTrue(shows(f, "/index.php", true));
        assertTrue(shows(f.withFocusMode(false), "/static/app.js", true));
    }

    @Test
    public void testFocusHidesExactlyTheChosenTypes() {
        ViewFilter f = ViewFilter.DEFAULT.withFocusHiddenTypes(Set.of("Images", "Endpoints"));
        assertFalse(shows(f, "/img/logo.png", true));
        assertFalse(shows(f, "/api/login", true));
        assertTrue(shows(f, "/static/app.js", true));     // JS now shown
        assertTrue(shows(f, "/styles/main.css", true));
        // Choices only apply while focus is on
        assertTrue(shows(f.withFocusMode(false), "/img/logo.png", true));
    }

    @Test
    public void testOnlyInScope() {
        ViewFilter f = ViewFilter.DEFAULT.withOnlyInScope(true);
        assertTrue(shows(f, "/api/login", true));
        assertFalse(shows(f, "/api/login", false));
    }

    @Test
    public void testTextFilterMatchesUrlOrKeywords() {
        ViewFilter f = ViewFilter.DEFAULT.withText("LOGIN");
        assertTrue(shows(f, "/api/login", true));
        assertFalse(shows(f, "/api/profile", true));
        assertTrue(f.shows(HOST, "/api/profile", "https://" + HOST + "/api/profile", true, Set.of("login_token")));
    }

    @Test
    public void testCollapsedLaneIsHiddenButStillMatches() {
        ViewFilter f = ViewFilter.DEFAULT.withLaneToggled(HOST);
        assertTrue(f.matches("/api/login", "https://" + HOST + "/api/login", true, List.of()));
        assertFalse(shows(f, "/api/login", true));
        assertTrue(shows(f.withLaneToggled(HOST), "/api/login", true));
    }

    @Test
    public void testLaneKeyFollowsGroupByType() {
        assertEquals(HOST, ViewFilter.DEFAULT.laneKey(HOST, "/api/login"));
        assertEquals(HOST + "::Endpoints", ViewFilter.DEFAULT.withGroupByType(true).laneKey(HOST, "/api/login"));
    }
}
