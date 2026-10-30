package com.pinpoint.model;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.util.concurrent.atomic.AtomicInteger;

public class TrafficEntry {
    public enum Activity { REQUEST, RESPONSE }

    private final String path;
    private final String host;
    private final String url;
    private volatile boolean isBreakpoint;
    private final long firstSeen;

    private volatile HttpRequest request;
    private volatile HttpResponse response;

    private final AtomicInteger hitCount;
    private volatile boolean inScope;
    private volatile int statusCode;

    // Wall-clock millis of the last request/response seen for this node; 0 = never
    private volatile long lastActivityMillis = 0;
    private volatile Activity lastActivity = Activity.REQUEST;

    // Status changes over time (e.g. 200 -> 403), capped so endpoints that flip forever stay small
    private static final int MAX_STATUS_HISTORY = 10;
    private final java.util.List<Integer> statusHistory = new java.util.concurrent.CopyOnWriteArrayList<>();
    // Grep rules the latest response matched; replaced as a whole, so readers never see a partial set
    private volatile java.util.Set<String> matchedKeywords = java.util.Set.of();

    public TrafficEntry(String path, String host, String url, boolean isBreakpoint, boolean inScope,
                        HttpRequest request, HttpResponse response) {
        this.path = path;
        this.host = host;
        this.url = url;
        this.isBreakpoint = isBreakpoint;
        this.inScope = inScope;
        this.request = request;
        this.response = response;
        this.firstSeen = System.currentTimeMillis();
        this.hitCount = new AtomicInteger(1);
        this.statusCode = response != null ? response.statusCode() : 0;
        if (this.statusCode > 0) this.statusHistory.add(this.statusCode);
    }

    public String getPath() { return path; }
    public String getHost() { return host; }
    public String getUrl() { return url; }
    public boolean isBreakpoint() { return isBreakpoint; }
    public void setBreakpoint(boolean breakpoint) { isBreakpoint = breakpoint; }
    public long getFirstSeen() { return firstSeen; }
    public HttpRequest getRequest() { return request; }
    public void setRequest(HttpRequest request) { this.request = request; }
    public HttpResponse getResponse() { return response; }
    public void setResponse(HttpResponse response) { this.response = response; }
    public int getHitCount() { return hitCount.get(); }
    public void incrementHitCount() { hitCount.incrementAndGet(); }
    public boolean isInScope() { return inScope; }
    public void setInScope(boolean inScope) { this.inScope = inScope; }
    public int getStatusCode() { return statusCode; }
    public java.util.List<Integer> getStatusHistory() { return statusHistory; }
    public java.util.Set<String> getMatchedKeywords() { return matchedKeywords; }
    public void setMatchedKeywords(java.util.Set<String> keywords) { this.matchedKeywords = java.util.Set.copyOf(keywords); }

    /** A new response status: becomes the current one, and is added to the history if it changed. */
    public void recordStatus(int status) {
        this.statusCode = status;
        if (statusHistory.isEmpty() || statusHistory.get(statusHistory.size() - 1) != status) {
            statusHistory.add(status);
            while (statusHistory.size() > MAX_STATUS_HISTORY) statusHistory.remove(0);
        }
    }
    public long getLastActivityMillis() { return lastActivityMillis; }
    public Activity getLastActivity() { return lastActivity; }
    public void markActivity(Activity activity, long millis) {
        this.lastActivity = activity;
        this.lastActivityMillis = millis;
    }

    // Messages currently held (breakpoint or step) on this node
    private final AtomicInteger pausedRequests = new AtomicInteger(0);
    private final AtomicInteger pausedResponses = new AtomicInteger(0);

    public void addPaused(boolean isRequest) { (isRequest ? pausedRequests : pausedResponses).incrementAndGet(); }
    public void removePaused(boolean isRequest) { (isRequest ? pausedRequests : pausedResponses).decrementAndGet(); }
    public int getPausedRequests() { return pausedRequests.get(); }
    public int getPausedResponses() { return pausedResponses.get(); }
    public boolean isPaused() { return pausedRequests.get() > 0 || pausedResponses.get() > 0; }
}
