package com.pinpoint.model;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class PendingItem implements Comparable<PendingItem> {

    private static final AtomicLong counter = new AtomicLong(0);

    private final boolean isRequest;
    private final String url;


    private final HttpRequest originalRequest;
    private final HttpResponse originalResponse;


    private final CompletableFuture<HttpRequest> requestFuture;
    private final CompletableFuture<HttpResponse> responseFuture;

    private final long timestamp;

    // Why this item is held; it is released once neither reason applies any more
    private final boolean heldByBreakpoint;
    private final boolean heldByStep;
    private final boolean inScope;
    private final TrafficEntry entry;

    // Unsent edits, kept when the user navigates away from this item so they're never lost
    private volatile HttpRequest draftRequest;
    private volatile HttpResponse draftResponse;

    public PendingItem(HttpRequest request, TrafficEntry entry,
                       boolean heldByBreakpoint, boolean heldByStep, boolean inScope) {
        this.isRequest = true;
        this.url = request.url();
        this.originalRequest = request;
        this.originalResponse = null;
        this.requestFuture = new CompletableFuture<>();
        this.responseFuture = null;
        this.timestamp = counter.getAndIncrement();
        this.heldByBreakpoint = heldByBreakpoint;
        this.heldByStep = heldByStep;
        this.inScope = inScope;
        this.entry = entry;
    }

    public PendingItem(HttpRequest request, HttpResponse response, TrafficEntry entry,
                       boolean heldByBreakpoint, boolean heldByStep, boolean inScope) {
        this.isRequest = false;
        this.url = request.url();
        this.originalRequest = request;
        this.originalResponse = response;
        this.requestFuture = null;
        this.responseFuture = new CompletableFuture<>();
        this.timestamp = counter.getAndIncrement();
        this.heldByBreakpoint = heldByBreakpoint;
        this.heldByStep = heldByStep;
        this.inScope = inScope;
        this.entry = entry;
    }

    public boolean isRequest() {
        return isRequest;
    }

    public String getUrl() {
        return url;
    }

    public HttpRequest getOriginalRequest() {
        return originalRequest;
    }

    public HttpResponse getOriginalResponse() {
        return originalResponse;
    }

    public CompletableFuture<HttpRequest> getRequestFuture() {
        return requestFuture;
    }

    public CompletableFuture<HttpResponse> getResponseFuture() {
        return responseFuture;
    }

    public boolean isHeldByBreakpoint() {
        return heldByBreakpoint;
    }

    public boolean isHeldByStep() {
        return heldByStep;
    }

    public boolean isInScope() {
        return inScope;
    }

    /** The graph node this message belongs to. */
    public TrafficEntry getEntry() {
        return entry;
    }

    public HttpRequest getDraftRequest() { return draftRequest; }
    public void setDraftRequest(HttpRequest draft) { this.draftRequest = draft; }
    public HttpResponse getDraftResponse() { return draftResponse; }
    public void setDraftResponse(HttpResponse draft) { this.draftResponse = draft; }

    @Override
    public int compareTo(PendingItem o) {
        return Long.compare(this.timestamp, o.timestamp);
    }
}
