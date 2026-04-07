package com.pinpoint.model;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.persistence.PersistedObject;
import com.pinpoint.utils.TrafficUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class PinManager {

    private static final String KEY_PINS = "pinpoint.pins";
    private static final String KEY_KEYWORDS = "pinpoint.keywords";
    private static final String RECORD_SEP = "\u001E";
    private static final String FIELD_SEP = "\u001F";

    public static final String INTERCEPT_REQ_RES = "Req + Res";
    public static final String INTERCEPT_REQ_ONLY = "Req Only";
    public static final String INTERCEPT_RES_ONLY = "Res Only";

    public static class PinRule {
        private final String pattern;
        private final boolean isExact;
        private boolean enabled;
        private boolean interceptRequest;
        private boolean interceptResponse;

        public PinRule(String pattern, boolean isExact, boolean enabled, boolean interceptRequest, boolean interceptResponse) {
            this.pattern = pattern;
            this.isExact = isExact;
            this.enabled = enabled;
            this.interceptRequest = interceptRequest;
            this.interceptResponse = interceptResponse;
        }

        public String getPattern() { return pattern; }
        public boolean isExact() { return isExact; }
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
            return other.pattern.equals(this.pattern) && other.isExact == this.isExact;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pattern, isExact);
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

    private final List<TrafficEntry> trafficLog = new CopyOnWriteArrayList<>();
    private final List<Runnable> trafficListeners = new CopyOnWriteArrayList<>();

    private final Object pinLock = new Object();
    private final Object keywordLock = new Object();

    private volatile boolean interceptEnabled = false;
    private static final int MAX_TRAFFIC_LOG_SIZE = 250;

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

        String savedPins = data.getString(KEY_PINS);
        if (savedPins != null && !savedPins.isEmpty()) {
            pins.clear();
            for (String record : savedPins.split(RECORD_SEP)) {
                String[] fields = record.split(FIELD_SEP);
                if (fields.length == 5) {
                    pins.add(new PinRule(
                        fields[0],
                        Boolean.parseBoolean(fields[1]),
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

    private void savePins() {
        if (api == null) return;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (PinRule p : pins) {
            if (!first) sb.append(RECORD_SEP);
            sb.append(p.getPattern()).append(FIELD_SEP)
              .append(p.isExact()).append(FIELD_SEP)
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

    public void addKeyword(String kw, boolean isRegex) {
        synchronized (keywordLock) {
            for (KeywordRule r : keywordRules) {
                if (r.getKeyword().equals(kw)) return;
            }
            keywordRules.add(new KeywordRule(kw, true, isRegex));
            saveKeywords();
        }
        updateSearchKeywords();
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

    public void setKeywordRegex(int index, boolean isRegex) {
        synchronized (keywordLock) {
            if (index >= 0 && index < keywordRules.size()) {
                keywordRules.get(index).setRegex(isRegex);
                saveKeywords();
            }
        }
        updateSearchKeywords();
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
        entry.getMatchedKeywords().clear();
        if (entry.getResponse() != null && entry.getResponse().bodyToString() != null) {
            String body = entry.getResponse().bodyToString();
            for (KeywordRule rule : keywordRules) {
                if (!rule.isEnabled()) continue;
                if (rule.isRegex()) {
                    Pattern compiled = rule.getCompiledPattern();
                    if (compiled != null && compiled.matcher(body).find()) {
                        entry.getMatchedKeywords().add(rule.getKeyword());
                    }
                } else {
                    if (body.contains(rule.getKeyword())) {
                        entry.getMatchedKeywords().add(rule.getKeyword());
                    }
                }
            }
        }
    }

    public void logTraffic(String id, String url, boolean isRequest, boolean isBreakpoint,
                           HttpRequest req, HttpResponse res, boolean inScope) {
        String[] parsed = TrafficUtils.parseHostAndPath(url);
        String host = parsed[0];
        String path = parsed[1];

        for (TrafficEntry existing : trafficLog) {
            if (existing.getHost().equals(host) && existing.getPath().equals(path)) {
                existing.incrementHitCount();
                existing.setBreakpoint(matchesAnyPin(existing.getHost(), existing.getPath(), existing.getUrl()));
                if (!isRequest && res != null) {
                    existing.setResponse(res);
                    int newStatus = res.statusCode();
                    existing.setStatusCode(newStatus);
                    if (existing.getStatusHistory().isEmpty() || existing.getStatusHistory().get(existing.getStatusHistory().size() - 1) != newStatus) {
                        existing.getStatusHistory().add(newStatus);
                    }
                    updateKeywordsForEntry(existing);
                }
                if (isRequest && req != null) {
                    existing.setRequest(req);
                }
                existing.setInScope(inScope);
                notifyTrafficListeners();
                return;
            }
        }

        boolean actuallyPinned = matchesAnyPin(host, path, url);
        TrafficEntry newEntry = new TrafficEntry(path, host, url, actuallyPinned, inScope, req, res);
        if (!isRequest && res != null) {
            updateKeywordsForEntry(newEntry);
        }
        trafficLog.add(newEntry);
        if (trafficLog.size() > MAX_TRAFFIC_LOG_SIZE) {
            trafficLog.remove(0);
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
        if (pin.isExact()) {
            return hostPath.equals(pin.getPattern()) || rawUrl.equals(pin.getPattern());
        } else {
            return rawUrl.contains(pin.getPattern()) || hostPath.contains(pin.getPattern());
        }
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
        notifyTrafficListeners();
    }

    public void addTrafficListener(Runnable listener) {
        trafficListeners.add(listener);
    }

    private void notifyTrafficListeners() {
        for (Runnable listener : trafficListeners) {
            listener.run();
        }
    }

    public void addPin(String pattern) {
        addPin(pattern, false);
    }

    public void addPin(String pattern, boolean isExact) {
        synchronized (pinLock) {
            PinRule rule = new PinRule(pattern, isExact, true, true, true);
            if (!pins.contains(rule)) {
                pins.add(rule);
                savePins();
                updateInterceptState();
            }
        }
        notifyListeners();
        refreshTrafficBreakpoints();
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
        refreshTrafficBreakpoints();
    }

    public void setInterceptRequest(int index, boolean interceptRequest) {
        synchronized (pinLock) {
            if (index >= 0 && index < pins.size()) {
                pins.get(index).setInterceptRequest(interceptRequest);
                savePins();
            }
        }
    }

    public void setInterceptResponse(int index, boolean interceptResponse) {
        synchronized (pinLock) {
            if (index >= 0 && index < pins.size()) {
                pins.get(index).setInterceptResponse(interceptResponse);
                savePins();
            }
        }
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
