package com.pinpoint.model;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.persistence.PersistedObject;
import com.pinpoint.utils.TrafficUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class PinManager {

    private static final String KEY_PINS = "pinpoint.pins";
    private static final String KEY_KEYWORDS = "pinpoint.keywords";
    private static final String KEY_FOCUS_MODE = "pinpoint.focusMode";
    private static final String KEY_FOCUS_HIDDEN_TYPES = "pinpoint.focusHiddenTypes";
    private static final String KEY_EDITOR_SPLIT = "pinpoint.editorSplitPermille";
    private static final String RECORD_SEP = "\u001E";
    private static final String FIELD_SEP = "\u001F";


    /** How a breakpoint pattern is compared against a request's URL and host+path. */
    public enum MatchType {
        CONTAINS("Contains"), EXACT("Exact"), REGEX("Regex");

        public final String label;

        MatchType(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static class PinRule {
        private final String pattern;
        private final MatchType matchType;
        private final Pattern compiled;   // only for REGEX
        private boolean enabled;
        private boolean interceptRequest;
        private boolean interceptResponse;

        public PinRule(String pattern, MatchType matchType, boolean enabled, boolean interceptRequest, boolean interceptResponse) {
            this.pattern = pattern;
            this.matchType = matchType;
            this.compiled = matchType == MatchType.REGEX ? compileOrNull(pattern) : null;
            this.enabled = enabled;
            this.interceptRequest = interceptRequest;
            this.interceptResponse = interceptResponse;
        }

        public String getPattern() { return pattern; }
        public MatchType getMatchType() { return matchType; }
        public boolean isExact() { return matchType == MatchType.EXACT; }
        public boolean isEnabled() { return enabled; }
        public boolean isInterceptRequest() { return interceptRequest; }
        public boolean isInterceptResponse() { return interceptResponse; }

        void setEnabled(boolean enabled) { this.enabled = enabled; }
        void setInterceptRequest(boolean v) { this.interceptRequest = v; }
        void setInterceptResponse(boolean v) { this.interceptResponse = v; }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof PinRule)) return false;
            PinRule other = (PinRule) obj;
            return other.pattern.equals(this.pattern) && other.matchType == this.matchType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pattern, matchType);
        }
    }

    private static Pattern compileOrNull(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** Null if the regex is valid, else a short, human-readable reason. */
    public static String regexError(String regex) {
        try {
            Pattern.compile(regex);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription() + (e.getIndex() >= 0 ? " (at position " + e.getIndex() + ")" : "");
        }
    }

    public static class KeywordRule {
        private final String keyword;
        private boolean enabled;
        private boolean isRegex;
        private Pattern compiledPattern;

        public KeywordRule(String keyword, boolean enabled, boolean isRegex) {
            this.keyword = keyword;
            this.enabled = enabled;
            this.isRegex = isRegex;
            compilePattern();
        }

        public String getKeyword() { return keyword; }
        public boolean isEnabled() { return enabled; }
        public boolean isRegex() { return isRegex; }
        public Pattern getCompiledPattern() { return compiledPattern; }

        void setEnabled(boolean enabled) { this.enabled = enabled; }
        void setRegex(boolean isRegex) {
            this.isRegex = isRegex;
            compilePattern();
        }

        private void compilePattern() {
            if (isRegex) {
                try {
                    this.compiledPattern = Pattern.compile(keyword);
                } catch (PatternSyntaxException e) {
                    this.compiledPattern = null;
                }
            } else {
                this.compiledPattern = null;
            }
        }
    }

    private final List<PinRule> pins = new CopyOnWriteArrayList<>();
    private final List<KeywordRule> keywordRules = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** A request hitting a node at a point in time; the ordered recent hits form the live "trail". */
    public record Hit(TrafficEntry entry, long millis) {}

    // Kept longer than the drawn trail so hidden (filtered) hits don't crowd out visible ones
    private static final int MAX_RECENT_HITS = 64;
    private final Deque<Hit> recentHits = new ArrayDeque<>();

    private volatile boolean stepMode = false;

    private static final int MAX_FLOW_BODY_BYTES = 16 * 1024;
    private final FlowTracker flowTracker = new FlowTracker();
    private volatile ViewFilter viewFilter = ViewFilter.DEFAULT;
    private volatile int editorSplitPermille = 500;

    private final List<TrafficEntry> trafficLog = new CopyOnWriteArrayList<>();
    private final List<Runnable> trafficListeners = new CopyOnWriteArrayList<>();

    private final Object pinLock = new Object();
    private final Object keywordLock = new Object();

    private volatile boolean interceptEnabled = false;
    private static final int MAX_TRAFFIC_LOG_SIZE = 500;

    private final ExecutorService keywordSearchExecutor = Executors.newSingleThreadExecutor();

    private final MontoyaApi api;

    public PinManager(MontoyaApi api) {
        this.api = api;
        loadSettings();
    }

    public void shutdown() {
        keywordSearchExecutor.shutdownNow();
    }

    private void loadSettings() {
        if (api == null) return;
        PersistedObject data = api.persistence().extensionData();

        // Focus mode is a per-user preference, not per-project
        Boolean focusMode = api.persistence().preferences().getBoolean(KEY_FOCUS_MODE);
        if (focusMode != null) {
            viewFilter = viewFilter.withFocusMode(focusMode);
        }
        // Which file types Focus hides; stored as a comma list of type names ("" = hide nothing)
        String hiddenTypes = api.persistence().preferences().getString(KEY_FOCUS_HIDDEN_TYPES);
        if (hiddenTypes != null) {
            Set<String> types = new HashSet<>();
            for (String t : hiddenTypes.split(",")) {
                if (ViewFilter.ALL_TYPES.contains(t)) types.add(t);
            }
            viewFilter = viewFilter.withFocusHiddenTypes(types);
        }
        Integer split = api.persistence().preferences().getInteger(KEY_EDITOR_SPLIT);
        if (split != null && split >= 100 && split <= 900) {
            editorSplitPermille = split;
        }

        String savedPins = data.getString(KEY_PINS);
        if (savedPins != null && !savedPins.isEmpty()) {
            pins.clear();
            for (String record : savedPins.split(RECORD_SEP)) {
                String[] fields = record.split(FIELD_SEP);
                if (fields.length == 5) {
                    pins.add(new PinRule(
                        fields[0],
                        parseMatchType(fields[1]),
                        Boolean.parseBoolean(fields[2]),
                        Boolean.parseBoolean(fields[3]),
                        Boolean.parseBoolean(fields[4])
                    ));
                }
            }
            updateInterceptState();
        }

        String savedKeywords = data.getString(KEY_KEYWORDS);
        if (savedKeywords != null && !savedKeywords.isEmpty()) {
            keywordRules.clear();
            for (String record : savedKeywords.split(RECORD_SEP)) {
                String[] fields = record.split(FIELD_SEP);
                if (fields.length >= 2) {
                    boolean isRegex = fields.length >= 3 && Boolean.parseBoolean(fields[2]);
                    keywordRules.add(new KeywordRule(fields[0], Boolean.parseBoolean(fields[1]), isRegex));
                }
            }
            updateSearchKeywords();
        }
    }

    /** Saved as the enum name; older versions saved "true"/"false" for exact / contains. */
    static MatchType parseMatchType(String saved) {
        if ("true".equals(saved)) return MatchType.EXACT;
        if ("false".equals(saved)) return MatchType.CONTAINS;
        try {
            return MatchType.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return MatchType.CONTAINS;
        }
    }

    private void savePins() {
        if (api == null) return;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (PinRule p : pins) {
            if (!first) sb.append(RECORD_SEP);
            sb.append(p.getPattern()).append(FIELD_SEP)
              .append(p.getMatchType().name()).append(FIELD_SEP)
              .append(p.isEnabled()).append(FIELD_SEP)
              .append(p.isInterceptRequest()).append(FIELD_SEP)
              .append(p.isInterceptResponse());
            first = false;
        }
        api.persistence().extensionData().setString(KEY_PINS, sb.toString());
    }

    private void saveKeywords() {
        if (api == null) return;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (KeywordRule k : keywordRules) {
            if (!first) sb.append(RECORD_SEP);
            sb.append(k.getKeyword()).append(FIELD_SEP)
              .append(k.isEnabled()).append(FIELD_SEP)
              .append(k.isRegex());
            first = false;
        }
        api.persistence().extensionData().setString(KEY_KEYWORDS, sb.toString());
    }

    /** Adds a grep rule; returns null on success, or why it wasn't added (empty, duplicate, bad regex). */
    public String addKeyword(String kw, boolean isRegex) {
        String error = validateKeyword(kw, isRegex, -1);
        if (error != null) return error;
        synchronized (keywordLock) {
            keywordRules.add(new KeywordRule(kw, true, isRegex));
            saveKeywords();
        }
        updateSearchKeywords();
        return null;
    }

    /** Changes a grep rule's text, keeping its other settings. */
    public String updateKeyword(int index, String kw) {
        synchronized (keywordLock) {
            if (index < 0 || index >= keywordRules.size()) return "That rule no longer exists.";
            KeywordRule old = keywordRules.get(index);
            String error = validateKeyword(kw, old.isRegex(), index);
            if (error != null) return error;
            keywordRules.set(index, new KeywordRule(kw, old.isEnabled(), old.isRegex()));
            saveKeywords();
        }
        updateSearchKeywords();
        return null;
    }

    private String validateKeyword(String kw, boolean isRegex, int ignoreIndex) {
        if (kw == null || kw.isEmpty()) return "The keyword can't be empty.";
        if (isRegex) {
            String regexError = regexError(kw);
            if (regexError != null) return "Invalid regex: " + regexError;
        }
        for (int i = 0; i < keywordRules.size(); i++) {
            if (i != ignoreIndex && keywordRules.get(i).getKeyword().equals(kw)) return "This keyword already exists.";
        }
        return null;
    }

    /** How many graph nodes' latest responses a grep rule currently flags. */
    public int countKeywordHits(String keyword) {
        int n = 0;
        for (TrafficEntry e : trafficLog) {
            if (e.getMatchedKeywords().contains(keyword)) n++;
        }
        return n;
    }

    /** How many captured responses a not-yet-added rule would flag (for the editor's live preview). */
    public int countKeywordPreview(String keyword, boolean isRegex) {
        if (keyword == null || keyword.isEmpty() || (isRegex && regexError(keyword) != null)) return 0;
        KeywordRule probe = new KeywordRule(keyword, true, isRegex);
        int n = 0;
        for (TrafficEntry e : trafficLog) {
            if (e.getResponse() != null && keywordMatches(probe, searchableText(e.getResponse()))) n++;
        }
        return n;
    }

    /** Grep looks at the whole response: status line, headers (e.g. Set-Cookie) and body. */
    private static String searchableText(HttpResponse response) {
        String text = response.toString();
        return text != null ? text : "";
    }

    private static boolean keywordMatches(KeywordRule rule, String text) {
        if (rule.isRegex()) {
            Pattern compiled = rule.getCompiledPattern();
            return compiled != null && compiled.matcher(text).find();
        }
        return text.contains(rule.getKeyword());
    }

    public void removeKeyword(int index) {
        synchronized (keywordLock) {
            if (index >= 0 && index < keywordRules.size()) {
                keywordRules.remove(index);
                saveKeywords();
            }
        }
        updateSearchKeywords();
    }

    public void setKeywordEnabled(int index, boolean enabled) {
        synchronized (keywordLock) {
            if (index >= 0 && index < keywordRules.size()) {
                keywordRules.get(index).setEnabled(enabled);
                saveKeywords();
            }
        }
        updateSearchKeywords();
    }

    /** Switches a rule between plain text and regex; refuses (with the reason) if the text isn't a valid regex. */
    public String setKeywordRegex(int index, boolean isRegex) {
        synchronized (keywordLock) {
            if (index < 0 || index >= keywordRules.size()) return "That rule no longer exists.";
            if (isRegex) {
                String regexError = regexError(keywordRules.get(index).getKeyword());
                if (regexError != null) return "Invalid regex: " + regexError;
            }
            keywordRules.get(index).setRegex(isRegex);
            saveKeywords();
        }
        updateSearchKeywords();
        return null;
    }

    public List<KeywordRule> getKeywordRules() {
        return Collections.unmodifiableList(keywordRules);
    }

    public void updateSearchKeywords() {
        keywordSearchExecutor.submit(() -> {
            for (TrafficEntry e : trafficLog) {
                updateKeywordsForEntry(e);
            }
            notifyTrafficListeners();
        });
        notifyListeners();
    }

    private void updateKeywordsForEntry(TrafficEntry entry) {
        entry.setMatchedKeywords(entry.getResponse() != null ? matchKeywords(entry.getResponse()) : Set.of());
    }

    /** The enabled grep rules a response matches. Without enabled rules, the response isn't even read. */
    private Set<String> matchKeywords(HttpResponse response) {
        List<KeywordRule> active = new ArrayList<>();
        for (KeywordRule rule : keywordRules) {
            if (rule.isEnabled()) active.add(rule);
        }
        if (active.isEmpty()) return Set.of();
        String text = searchableText(response);
        Set<String> matched = new HashSet<>();
        for (KeywordRule rule : active) {
            if (keywordMatches(rule, text)) matched.add(rule.getKeyword());
        }
        return matched;
    }

    /**
     * Records a request or response on its graph node and returns the node.
     * When {@code held} is true the message is paused at a breakpoint or step, so its glow and
     * trail hit are deferred until {@link #markReleased} is called as it actually goes out.
     */
    public TrafficEntry logTraffic(String url, boolean isRequest, HttpRequest req, HttpResponse res,
                                   boolean inScope, boolean held) {
        String[] parsed = TrafficUtils.parseHostAndPath(url);
        String host = parsed[0];
        String path = parsed[1];
        long now = System.currentTimeMillis();
        TrafficEntry.Activity activity = isRequest ? TrafficEntry.Activity.REQUEST : TrafficEntry.Activity.RESPONSE;

        // Grep the full response as it arrives (cheap no-op without rules); only a capped copy is kept
        Set<String> matched = !isRequest && res != null ? matchKeywords(res) : null;
        HttpRequest storedReq = req != null ? capBody(req) : null;
        HttpResponse storedRes = res != null ? capBody(res) : null;

        // Find-or-create is atomic so two simultaneous first hits can't create two nodes; the slower
        // work (grep, flow detection) stays outside the lock
        TrafficEntry entry;
        boolean created = false;
        synchronized (trafficLog) {
            entry = findEntry(host, path);
            if (entry == null) {
                entry = new TrafficEntry(path, host, url, matchesAnyPin(host, path, url), inScope, storedReq, storedRes);
                trafficLog.add(entry);
                created = true;
                if (trafficLog.size() > MAX_TRAFFIC_LOG_SIZE) {
                    evictLeastUseful(entry);
                }
            }
        }

        if (!held) {
            entry.markActivity(activity, now);
            if (isRequest) recordHit(entry, now);
        }
        if (!created) {
            entry.incrementHitCount();
            entry.setBreakpoint(matchesAnyPin(entry.getHost(), entry.getPath(), entry.getUrl()));
            entry.setInScope(inScope);
            if (isRequest && storedReq != null) entry.setRequest(storedReq);
            if (!isRequest && storedRes != null) {
                entry.setResponse(storedRes);
                entry.recordStatus(storedRes.statusCode());
            }
        }
        if (matched != null) entry.setMatchedKeywords(matched);
        trackFlow(entry, isRequest, req, res, now);
        notifyTrafficListeners();
        return entry;
    }

    private TrafficEntry findEntry(String host, String path) {
        for (TrafficEntry e : trafficLog) {
            if (e.getHost().equals(host) && e.getPath().equals(path)) return e;
        }
        return null;
    }

    // The graph keeps the latest request/response per node for viewing; huge bodies (bundles,
    // downloads, uploads) are cut to this size so 500 nodes can't hold hundreds of MB
    private static final int MAX_STORED_BODY_BYTES = 1024 * 1024;

    private static HttpRequest capBody(HttpRequest req) {
        burp.api.montoya.core.ByteArray body = req.body();
        if (body == null || body.length() <= MAX_STORED_BODY_BYTES) return req;
        return req.withBody(body.subArray(0, MAX_STORED_BODY_BYTES));
    }

    private static HttpResponse capBody(HttpResponse res) {
        burp.api.montoya.core.ByteArray body = res.body();
        if (body == null || body.length() <= MAX_STORED_BODY_BYTES) return res;
        return res.withBody(body.subArray(0, MAX_STORED_BODY_BYTES));
    }

    /**
     * Drops one node to stay under the cap. Not simply the oldest: a page load can bring hundreds
     * of hidden assets and third-party calls, which would otherwise push every visible endpoint out.
     * Breakpoint and paused nodes are never dropped.
     */
    private void evictLeastUseful(TrafficEntry justAdded) {
        TrafficEntry victim = null;
        int victimTier = Integer.MAX_VALUE;
        long victimActivity = Long.MAX_VALUE;
        for (TrafficEntry e : trafficLog) {
            if (e == justAdded || e.isBreakpoint() || e.isPaused()) continue;
            int tier = evictionTier(e);
            long activity = Math.max(e.getLastActivityMillis(), e.getFirstSeen());
            if (tier < victimTier || (tier == victimTier && activity < victimActivity)) {
                victim = e;
                victimTier = tier;
                victimActivity = activity;
            }
        }
        if (victim != null) {
            trafficLog.remove(victim);
            flowTracker.forget(victim);
            synchronized (recentHits) {
                TrafficEntry dropped = victim;
                recentHits.removeIf(hit -> hit.entry() == dropped);
            }
        }
    }

    /** Lower tiers are dropped first. */
    private int evictionTier(TrafficEntry e) {
        if (!viewFilter.matches(e)) return 0;          // currently hidden in the graph
        if (!e.isInScope()) return 1;                   // third-party / out of scope
        if (ViewFilter.STATIC_TYPES.contains(TrafficUtils.getFileType(e.getPath()))) return 2;
        return 3;                                       // visible, in-scope endpoints: kept longest
    }

    /** Feeds requests (and redirect responses) to flow detection, using arrival time so held messages keep their order. */
    private void trackFlow(TrafficEntry entry, boolean isRequest, HttpRequest req, HttpResponse res, long now) {
        if (req == null) return;
        FlowDetector.Request flowRequest = toFlowRequest(req, now);
        if (isRequest) {
            flowTracker.onRequest(entry, flowRequest);
        } else if (res != null) {
            flowTracker.onResponse(flowRequest, res.statusCode(), res.headerValue("Location"));
        }
    }

    private static FlowDetector.Request toFlowRequest(HttpRequest req, long now) {
        Map<String, String> headers = new HashMap<>();
        for (HttpHeader h : req.headers()) {
            headers.putIfAbsent(h.name().toLowerCase(Locale.ROOT), h.value());
        }
        // Only small bodies are read (for GraphQL operation names); uploads are skipped
        String body = req.body().length() <= MAX_FLOW_BODY_BYTES ? req.bodyToString() : "";
        return new FlowDetector.Request(req.method(), req.pathWithoutQuery(), req.query(), headers, body, now);
    }

    public List<FlowTracker.FlowView> getFlows() {
        return flowTracker.snapshot();
    }

    /** A held message was forwarded: light its node up and add it to the trail now. */
    public void markReleased(TrafficEntry entry, boolean isRequest) {
        long now = System.currentTimeMillis();
        entry.markActivity(isRequest ? TrafficEntry.Activity.REQUEST : TrafficEntry.Activity.RESPONSE, now);
        if (isRequest) recordHit(entry, now);
        notifyTrafficListeners();
    }

    public void setPaused(TrafficEntry entry, boolean isRequest, boolean paused) {
        if (paused) entry.addPaused(isRequest);
        else entry.removePaused(isRequest);
        notifyTrafficListeners();
    }

    /** Step mode holds exactly what the graph shows: hidden traffic is skipped. */
    public boolean shouldStepHold(String url, boolean inScope) {
        if (!stepMode) return false;
        String[] parsed = TrafficUtils.parseHostAndPath(url);
        Collection<String> keywords = List.of();
        for (TrafficEntry e : trafficLog) {
            if (e.getHost().equals(parsed[0]) && e.getPath().equals(parsed[1])) {
                keywords = e.getMatchedKeywords();
                break;
            }
        }
        return viewFilter.shows(parsed[0], parsed[1], url, inScope, keywords);
    }

    public boolean isStepMode() {
        return stepMode;
    }

    public void setStepMode(boolean stepMode) {
        this.stepMode = stepMode;
        notifyListeners();
    }

    /** Request/response split as a fraction of the width; half unless the user moved it. */
    public double getEditorSplitRatio() {
        return editorSplitPermille / 1000.0;
    }

    public void setEditorSplitRatio(double ratio) {
        int permille = (int) Math.round(Math.max(0.1, Math.min(0.9, ratio)) * 1000);
        if (permille == editorSplitPermille) return;
        editorSplitPermille = permille;
        if (api != null) api.persistence().preferences().setInteger(KEY_EDITOR_SPLIT, permille);
    }

    public ViewFilter getViewFilter() {
        return viewFilter;
    }

    public void setViewFilter(ViewFilter filter) {
        boolean focusChanged = filter.focusMode() != viewFilter.focusMode();
        boolean hiddenTypesChanged = !filter.focusHiddenTypes().equals(viewFilter.focusHiddenTypes());
        this.viewFilter = filter;
        if (focusChanged && api != null) {
            api.persistence().preferences().setBoolean(KEY_FOCUS_MODE, filter.focusMode());
        }
        if (hiddenTypesChanged && api != null) {
            api.persistence().preferences().setString(KEY_FOCUS_HIDDEN_TYPES, String.join(",", new TreeSet<>(filter.focusHiddenTypes())));
        }
        // Items held by step mode may now be hidden, so let the queue re-evaluate them
        notifyListeners();
    }

    /** Whether a held item should stay held under the current intercept/step settings. */
    public boolean isStillHeld(PendingItem item) {
        if (item.isHeldByBreakpoint() && interceptEnabled) return true;
        if (!item.isHeldByStep() || !stepMode) return false;
        HttpRequest req = item.getOriginalRequest();
        return req != null && shouldStepHold(req.url(), item.isInScope());
    }

    /** Re-checks every node against Burp's scope, e.g. after the user changed it. */
    public void refreshScope() {
        if (api == null) return;
        for (TrafficEntry entry : trafficLog) {
            entry.setInScope(api.scope().isInScope(entry.getUrl()));
        }
        notifyTrafficListeners();
    }

    public void refreshTrafficBreakpoints() {
        for (TrafficEntry entry : trafficLog) {
            entry.setBreakpoint(matchesAnyPin(entry.getHost(), entry.getPath(), entry.getUrl()));
        }
        notifyTrafficListeners();
    }

    public boolean matchesAnyPin(String host, String path, String rawUrl) {
        String hostPath = host + path;
        for (PinRule pin : pins) {
            if (!pin.isEnabled()) continue;
            if (matchesPinPattern(pin, hostPath, rawUrl)) return true;
        }
        return false;
    }

    public static boolean matchesPinPattern(PinRule pin, String hostPath, String rawUrl) {
        return switch (pin.getMatchType()) {
            case EXACT -> hostPath.equals(pin.getPattern()) || rawUrl.equals(pin.getPattern());
            case CONTAINS -> rawUrl.contains(pin.getPattern()) || hostPath.contains(pin.getPattern());
            case REGEX -> pin.compiled != null && (pin.compiled.matcher(hostPath).find() || pin.compiled.matcher(rawUrl).find());
        };
    }

    /** How many graph nodes a breakpoint pattern would match (for the editor's live preview). */
    public int countPinMatches(String pattern, MatchType matchType) {
        if (pattern == null || pattern.isEmpty()) return 0;
        return countPinMatches(new PinRule(pattern, matchType, true, true, true));
    }

    /** How many graph nodes an existing breakpoint matches (reuses its compiled regex). */
    public int countPinMatches(PinRule rule) {
        int n = 0;
        for (TrafficEntry e : trafficLog) {
            if (matchesPinPattern(rule, e.getHost() + e.getPath(), e.getUrl())) n++;
        }
        return n;
    }

    private boolean checkPinnedForIntercept(String url, boolean isRequest) {
        if (!interceptEnabled) return false;
        String[] parsed = TrafficUtils.parseHostAndPath(url);
        String host = parsed[0];
        String path = parsed[1];
        String hostPath = host + path;

        for (PinRule pin : pins) {
            if (!pin.isEnabled()) continue;
            boolean intercept = isRequest ? pin.isInterceptRequest() : pin.isInterceptResponse();
            if (!intercept) continue;
            if (matchesPinPattern(pin, hostPath, url)) return true;
        }
        return false;
    }

    public List<TrafficEntry> getTrafficLog() {
        return Collections.unmodifiableList(trafficLog);
    }

    public void clearTrafficLog() {
        trafficLog.clear();
        flowTracker.clear();
        synchronized (recentHits) {
            recentHits.clear();
        }
        notifyTrafficListeners();
    }

    private void recordHit(TrafficEntry entry, long millis) {
        synchronized (recentHits) {
            recentHits.addLast(new Hit(entry, millis));
            while (recentHits.size() > MAX_RECENT_HITS) recentHits.removeFirst();
        }
    }

    /** Most recent request hits, oldest first. */
    public List<Hit> getRecentHits() {
        synchronized (recentHits) {
            return new ArrayList<>(recentHits);
        }
    }

    public void addTrafficListener(Runnable listener) {
        trafficListeners.add(listener);
    }

    private void notifyTrafficListeners() {
        for (Runnable listener : trafficListeners) {
            listener.run();
        }
    }

    /** Adds a breakpoint; returns null on success, or why it wasn't added (duplicate, bad regex). */
    public String addPin(String pattern, MatchType matchType, boolean interceptRequest, boolean interceptResponse) {
        String error = validatePin(pattern, matchType, -1);
        if (error != null) return error;
        synchronized (pinLock) {
            pins.add(new PinRule(pattern, matchType, true, interceptRequest, interceptResponse));
            savePins();
            updateInterceptState();
        }
        notifyListeners();
        refreshTrafficBreakpoints();
        return null;
    }

    /** Changes a breakpoint's pattern and match type, keeping its other settings. */
    public String updatePin(int index, String pattern, MatchType matchType) {
        String error = validatePin(pattern, matchType, index);
        if (error != null) return error;
        synchronized (pinLock) {
            if (index < 0 || index >= pins.size()) return "That breakpoint no longer exists.";
            PinRule old = pins.get(index);
            pins.set(index, new PinRule(pattern, matchType, old.isEnabled(), old.isInterceptRequest(), old.isInterceptResponse()));
            savePins();
        }
        notifyListeners();
        refreshTrafficBreakpoints();
        return null;
    }

    private String validatePin(String pattern, MatchType matchType, int ignoreIndex) {
        if (pattern == null || pattern.isBlank()) return "The pattern can't be empty.";
        if (matchType == MatchType.REGEX) {
            String regexError = regexError(pattern);
            if (regexError != null) return "Invalid regex: " + regexError;
        }
        for (int i = 0; i < pins.size(); i++) {
            PinRule p = pins.get(i);
            if (i != ignoreIndex && p.getPattern().equals(pattern) && p.getMatchType() == matchType) {
                return "This breakpoint already exists.";
            }
        }
        return null;
    }

    public void removePin(int index) {
        synchronized (pinLock) {
            if (index >= 0 && index < pins.size()) {
                pins.remove(index);
                savePins();
                updateInterceptState();
            }
        }
        notifyListeners();
        refreshTrafficBreakpoints();
    }

    public List<PinRule> getPins() {
        return Collections.unmodifiableList(pins);
    }

    public boolean isRequestPinned(String url) {
        return checkPinnedForIntercept(url, true);
    }

    public boolean isResponsePinned(String url) {
        return checkPinnedForIntercept(url, false);
    }

    public void setPinEnabled(int index, boolean enabled) {
        synchronized (pinLock) {
            if (index >= 0 && index < pins.size()) {
                pins.get(index).setEnabled(enabled);
                savePins();
                updateInterceptState();
            }
        }
        notifyListeners();
        refreshTrafficBreakpoints();
    }

    /** What a breakpoint holds: the request, the response, or both. */
    public void setPinIntercept(int index, boolean interceptRequest, boolean interceptResponse) {
        synchronized (pinLock) {
            if (index >= 0 && index < pins.size()) {
                pins.get(index).setInterceptRequest(interceptRequest);
                pins.get(index).setInterceptResponse(interceptResponse);
                savePins();
            }
        }
        notifyListeners();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    public boolean isInterceptEnabled() {
        return interceptEnabled;
    }

    public void setInterceptEnabled(boolean interceptEnabled) {
        this.interceptEnabled = interceptEnabled;
        notifyListeners();
    }

    private void updateInterceptState() {
        boolean hasEnabledPin = false;
        for (PinRule pin : pins) {
            if (pin.isEnabled()) {
                hasEnabledPin = true;
                break;
            }
        }
        if (hasEnabledPin && !interceptEnabled) {
            setInterceptEnabled(true);
        } else if (!hasEnabledPin && interceptEnabled) {
            setInterceptEnabled(false);
        }
    }
}
