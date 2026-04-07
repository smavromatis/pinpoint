package com.pinpoint.model;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.util.concurrent.atomic.AtomicInteger;

public class TrafficEntry {
    private String path;
    private String host;
    private String url;
    private volatile boolean isBreakpoint;
    private final long firstSeen;

    private volatile HttpRequest request;
    private volatile HttpResponse response;

    private final AtomicInteger hitCount;
    private volatile boolean inScope;
    private volatile int statusCode;
    
    private final java.util.List<Integer> statusHistory = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.Set<String> matchedKeywords = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
    public void setUrl(String url) { this.url = url; }
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
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
    public java.util.List<Integer> getStatusHistory() { return statusHistory; }
    public java.util.Set<String> getMatchedKeywords() { return matchedKeywords; }
}
