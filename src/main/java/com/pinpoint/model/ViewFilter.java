package com.pinpoint.model;

import com.pinpoint.utils.TrafficUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the flow graph currently shows. Immutable so the proxy threads can read it safely;
 * step mode holds exactly the traffic this filter shows, so hidden traffic is skipped.
 */
public record ViewFilter(boolean focusMode, Set<String> focusHiddenTypes, boolean onlyInScope, String text,
                         boolean groupByType, Set<String> collapsedLanes) {

    /** Static assets: page-load noise rather than application logic. Hidden by focus mode by default. */
    public static final Set<String> STATIC_TYPES = Set.of("JavaScript", "CSS", "Images", "Fonts");

    /** Every file type the graph tells apart (see TrafficUtils.getFileType), in menu order. */
    public static final List<String> ALL_TYPES = List.of("Endpoints", "Other", "JavaScript", "CSS", "Images", "Fonts");

    public static final ViewFilter DEFAULT = new ViewFilter(true, STATIC_TYPES, false, "", false, Set.of());

    public ViewFilter {
        focusHiddenTypes = Set.copyOf(focusHiddenTypes);
        text = text == null ? "" : text.toLowerCase();
        collapsedLanes = Set.copyOf(collapsedLanes);
    }

    /** Whether an entry gets a card (collapsed lanes still count: they keep their header). */
    public boolean matches(String path, String url, boolean inScope, Collection<String> keywords) {
        if (onlyInScope && !inScope) return false;
        if (focusMode && focusHiddenTypes.contains(TrafficUtils.getFileType(path))) return false;
        if (text.isEmpty()) return true;
        if (url.toLowerCase().contains(text)) return true;
        for (String k : keywords) {
            if (k.toLowerCase().contains(text)) return true;
        }
        return false;
    }

    /** Whether an entry is actually visible as a card: matches and its lane isn't collapsed. */
    public boolean shows(String host, String path, String url, boolean inScope, Collection<String> keywords) {
        return matches(path, url, inScope, keywords) && !collapsedLanes.contains(laneKey(host, path));
    }

    public boolean matches(TrafficEntry e) {
        return matches(e.getPath(), e.getUrl(), e.isInScope(), e.getMatchedKeywords());
    }

    public String laneKey(String host, String path) {
        return groupByType ? host + "::" + TrafficUtils.getFileType(path) : host;
    }

    public ViewFilter withFocusMode(boolean v) { return new ViewFilter(v, focusHiddenTypes, onlyInScope, text, groupByType, collapsedLanes); }
    public ViewFilter withFocusHiddenTypes(Set<String> v) { return new ViewFilter(focusMode, v, onlyInScope, text, groupByType, collapsedLanes); }
    public ViewFilter withOnlyInScope(boolean v) { return new ViewFilter(focusMode, focusHiddenTypes, v, text, groupByType, collapsedLanes); }
    public ViewFilter withText(String v) { return new ViewFilter(focusMode, focusHiddenTypes, onlyInScope, v, groupByType, collapsedLanes); }
    public ViewFilter withGroupByType(boolean v) { return new ViewFilter(focusMode, focusHiddenTypes, onlyInScope, text, v, collapsedLanes); }

    public ViewFilter withLaneToggled(String laneKey) {
        Set<String> lanes = new HashSet<>(collapsedLanes);
        if (!lanes.remove(laneKey)) lanes.add(laneKey);
        return new ViewFilter(focusMode, focusHiddenTypes, onlyInScope, text, groupByType, lanes);
    }
}
