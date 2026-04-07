package com.pinpoint.model;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class PendingItem implements Comparable<PendingItem> {

    private static final AtomicLong counter = new AtomicLong(0);

    private final String id;
    private final boolean isRequest;
    private final String url;
    

    private final HttpRequest originalRequest;
    private final HttpResponse originalResponse;
    

    private final CompletableFuture<HttpRequest> requestFuture;
    private final CompletableFuture<HttpResponse> responseFuture;

    private final long timestamp;

    public PendingItem(String id, HttpRequest request) {
        this.id = id;
        this.isRequest = true;
        this.url = request.url();
        this.originalRequest = request;
        this.originalResponse = null;
        this.requestFuture = new CompletableFuture<>();
        this.responseFuture = null;
        this.timestamp = counter.getAndIncrement();
    }

    public PendingItem(String id, HttpRequest request, HttpResponse response) {
        this.id = id;
        this.isRequest = false;
        this.url = request.url();
        this.originalRequest = request;
        this.originalResponse = response;
        this.requestFuture = null;
        this.responseFuture = new CompletableFuture<>();
        this.timestamp = counter.getAndIncrement();
    }

    public String getId() {
        return id;
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

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public int compareTo(PendingItem o) {
        return Long.compare(this.timestamp, o.timestamp);
    }

    @Override
    public String toString() {
        return (isRequest ? "[Req] " : "[Resp] ") + id + " - " + url;
    }
}
