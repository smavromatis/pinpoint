package com.pinpoint.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups traffic into flows: each flow starts at a request {@link FlowDetector} recognizes as a
 * start, and its steps are the distinct graph nodes hit until the next start, in order. Repeat
 * runs of the same flow (e.g. refreshing a page) replace its steps, so each flow shows its latest run.
 * Pages (loads, reloads, submits, route changes) are top level; actions (tabs, searches, saves)
 * are nested under the page they happened on, found from their Referer or else the latest page.
 * Thread-safe: called from proxy threads, read from the EDT.
 */
public final class FlowTracker {

    /** An immutable view of one flow for the UI; parentKey is the page an action is nested under, or null. */
    public record FlowView(String key, FlowDetector.Kind kind, String name, int runs, List<TrafficEntry> steps,
                           String parentKey) {}

    private static final int MAX_FLOWS = 50;
    private static final int MAX_STEPS = 40;
    // A call that repeats this regularly is background polling, not the user
    private static final int POLL_SAMPLES = 4;
    private static final double POLL_TOLERANCE = 0.25;
    private static final long POLL_MIN_INTERVAL_MS = 1500;

    private static final class Flow {
        final String key;
        FlowDetector.Kind kind;
        String name;
        String parentKey;
        int runs;
        final List<TrafficEntry> steps = new ArrayList<>();

        Flow(String key) {
            this.key = key;
        }
    }

    // Bookkeeping per distinct route/call; capped (oldest first) so a large app can't grow these forever
    private static final int MAX_TRACKED_KEYS = 1000;

    private final FlowDetector detector = new FlowDetector();
    private final LinkedHashMap<String, Flow> flows = new LinkedHashMap<>();
    private final Map<String, Deque<Long>> startTimes = boundedMap();
    private final Set<String> backgroundKeys = Collections.newSetFromMap(boundedMap());
    private Flow current;
    // The page actions belong to: the latest page flow, or the one their Referer points at
    private Flow currentPage;
    private final Map<String, String> pageKeyByRoute = boundedMap();

    public synchronized void onRequest(TrafficEntry entry, FlowDetector.Request request) {
        String requestKey = FlowDetector.requestKey(request);
        FlowDetector.Start start = detector.onRequest(request);

        // Background polling never starts a flow and never pollutes another flow's steps
        if (backgroundKeys.contains(requestKey)) return;
        if (start != null && start.kind() == FlowDetector.Kind.ACTION && isPolling(start.key(), request.millis())) {
            backgroundKeys.add(requestKey);
            backgroundKeys.add(start.key());
            Flow removed = flows.remove(start.key());
            if (removed == current) current = null;
            if (removed == currentPage) currentPage = null;
            return;
        }

        if (start != null) {
            Flow flow = flows.get(start.key());
            if (flow == null) {
                flow = new Flow(start.key());
                flows.put(start.key(), flow);
                trimFlows();
            }
            flow.kind = start.kind();
            flow.name = start.name();
            flow.runs++;
            flow.steps.clear();
            current = flow;
            if (isPage(start.kind())) {
                flow.parentKey = null;
                currentPage = flow;
                pageKeyByRoute.put(FlowDetector.routeOf(request.path()), flow.key);
            } else {
                flow.parentKey = pageFor(request, flow);
            }
        }

        if (current != null && current.steps.size() < MAX_STEPS && !containsIdentity(current.steps, entry)) {
            current.steps.add(entry);
        }
    }

    public synchronized void onResponse(FlowDetector.Request request, int status, String location) {
        detector.onResponse(request, status, location);
    }

    /**
     * Flows in first-seen order, each with the steps of its latest run, and each page directly
     * followed by the actions nested under it.
     */
    public synchronized List<FlowView> snapshot() {
        Map<String, List<Flow>> children = new HashMap<>();
        for (Flow f : flows.values()) {
            if (isNested(f)) children.computeIfAbsent(f.parentKey, k -> new ArrayList<>()).add(f);
        }
        List<FlowView> views = new ArrayList<>(flows.size());
        for (Flow f : flows.values()) {
            if (isNested(f)) continue;
            views.add(view(f, null));
            for (Flow child : children.getOrDefault(f.key, List.of())) {
                views.add(view(child, f.key));
            }
        }
        return views;
    }

    private boolean isNested(Flow f) {
        return f.parentKey != null && flows.containsKey(f.parentKey);
    }

    private static FlowView view(Flow f, String parentKey) {
        return new FlowView(f.key, f.kind, f.name, f.runs, List.copyOf(f.steps), parentKey);
    }

    private static boolean isPage(FlowDetector.Kind kind) {
        return kind.isPageLoad() || kind == FlowDetector.Kind.ROUTE;
    }

    /** The page an action happened on: the page its Referer names if we've seen it, else the latest page. */
    private String pageFor(FlowDetector.Request request, Flow action) {
        String referer = request.header("referer");
        if (referer != null) {
            String pageKey = pageKeyByRoute.get(FlowDetector.routeOf(com.pinpoint.utils.TrafficUtils.parseHostAndPath(referer)[1]));
            if (pageKey != null && flows.containsKey(pageKey) && !pageKey.equals(action.key)) return pageKey;
        }
        return currentPage != null && currentPage != action ? currentPage.key : null;
    }

    /** A node left the graph: drop it from every flow so flows don't keep it (and its messages) alive. */
    public synchronized void forget(TrafficEntry entry) {
        for (Flow f : flows.values()) {
            f.steps.removeIf(step -> step == entry);
        }
    }

    private static <K, V> Map<K, V> boundedMap() {
        return new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > MAX_TRACKED_KEYS;
            }
        };
    }

    public synchronized void clear() {
        flows.clear();
        startTimes.clear();
        backgroundKeys.clear();
        pageKeyByRoute.clear();
        current = null;
        currentPage = null;
    }

    /** True once a call has started flows at a steady interval, i.e. it's on a timer. */
    private boolean isPolling(String key, long millis) {
        Deque<Long> times = startTimes.computeIfAbsent(key, k -> new ArrayDeque<>());
        times.addLast(millis);
        while (times.size() > POLL_SAMPLES) times.removeFirst();
        if (times.size() < POLL_SAMPLES) return false;

        List<Long> t = new ArrayList<>(times);
        long min = Long.MAX_VALUE, max = 0, sum = 0;
        for (int i = 1; i < t.size(); i++) {
            long interval = t.get(i) - t.get(i - 1);
            min = Math.min(min, interval);
            max = Math.max(max, interval);
            sum += interval;
        }
        double mean = (double) sum / (t.size() - 1);
        return mean >= POLL_MIN_INTERVAL_MS && (max - min) <= mean * POLL_TOLERANCE * 2;
    }

    private void trimFlows() {
        Iterator<Map.Entry<String, Flow>> it = flows.entrySet().iterator();
        while (flows.size() > MAX_FLOWS && it.hasNext()) {
            if (it.next().getValue() != current) it.remove();
        }
    }

    private static boolean containsIdentity(List<TrafficEntry> list, TrafficEntry entry) {
        for (TrafficEntry e : list) {
            if (e == entry) return true;
        }
        return false;
    }
}
