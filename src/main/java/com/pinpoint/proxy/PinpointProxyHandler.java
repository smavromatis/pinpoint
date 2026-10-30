package com.pinpoint.proxy;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyRequestHandler;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;

import com.pinpoint.model.PendingItem;
import com.pinpoint.model.PinManager;
import com.pinpoint.model.TrafficEntry;
import com.pinpoint.ui.MainUI;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PinpointProxyHandler implements ProxyRequestHandler, ProxyResponseHandler {

    private static final long INTERCEPT_TIMEOUT_MINUTES = 5;

    private final MontoyaApi api;
    private final PinManager pinManager;
    private final MainUI mainUI;

    public PinpointProxyHandler(MontoyaApi api, PinManager pinManager, MainUI mainUI) {
        this.api = api;
        this.pinManager = pinManager;
        this.mainUI = mainUI;
    }

    @Override
    public ProxyRequestReceivedAction handleRequestReceived(InterceptedRequest request) {
        String url = request.url();
        boolean inScope = api.scope().isInScope(url);
        boolean byBreakpoint = pinManager.isRequestPinned(url);
        boolean byStep = pinManager.shouldStepHold(url, inScope);
        boolean held = byBreakpoint || byStep;
        TrafficEntry entry = pinManager.logTraffic(url, true, request, null, inScope, held);

        if (!held) {
            return ProxyRequestReceivedAction.continueWith(request);
        }

        api.logging().logToOutput("Pinpoint holding request: " + url);

        PendingItem pendingItem = new PendingItem(request, entry, byBreakpoint, byStep, inScope);
        pinManager.setPaused(entry, true, true);
        mainUI.addPendingItem(pendingItem);

        try {
            HttpRequest editedRequest = pendingItem.getRequestFuture()
                    .get(INTERCEPT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (editedRequest != null) {
                pinManager.markReleased(entry, true);
                return ProxyRequestReceivedAction.continueWith(editedRequest, request.annotations());
            } else {
                return ProxyRequestReceivedAction.drop();
            }
        } catch (TimeoutException e) {
            api.logging().logToError("Pinpoint: Request intercept timed out after " + INTERCEPT_TIMEOUT_MINUTES + " min, forwarding original.");
            mainUI.removePendingItem(pendingItem);
            pinManager.markReleased(entry, true);
            return ProxyRequestReceivedAction.continueWith(request);
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            api.logging().logToError("Pinpoint: Request wait interrupted, discarding edit.");
            mainUI.removePendingItem(pendingItem);
            pinManager.markReleased(entry, true);
            return ProxyRequestReceivedAction.continueWith(request);
        } finally {
            pinManager.setPaused(entry, true, false);
        }
    }

    @Override
    public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest request) {
        return ProxyRequestToBeSentAction.continueWith(request);
    }

    @Override
    public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse response) {
        HttpRequest initiating = response.initiatingRequest();
        String url = initiating != null ? initiating.url() : "-";
        boolean inScope = api.scope().isInScope(url);
        boolean byBreakpoint = initiating != null && pinManager.isResponsePinned(url);
        boolean byStep = initiating != null && pinManager.shouldStepHold(url, inScope);
        boolean held = byBreakpoint || byStep;
        TrafficEntry entry = pinManager.logTraffic(url, false, initiating, response, inScope, held);

        if (!held) {
            return ProxyResponseReceivedAction.continueWith(response);
        }

        api.logging().logToOutput("Pinpoint holding response: " + url);

        PendingItem pendingItem = new PendingItem(initiating, response, entry, byBreakpoint, byStep, inScope);
        pinManager.setPaused(entry, false, true);
        mainUI.addPendingItem(pendingItem);

        try {
            HttpResponse editedResponse = pendingItem.getResponseFuture()
                    .get(INTERCEPT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (editedResponse != null) {
                pinManager.markReleased(entry, false);
                return ProxyResponseReceivedAction.continueWith(editedResponse, response.annotations());
            } else {
                return ProxyResponseReceivedAction.drop();
            }
        } catch (TimeoutException e) {
            api.logging().logToError("Pinpoint: Response intercept timed out after " + INTERCEPT_TIMEOUT_MINUTES + " min, forwarding original.");
            mainUI.removePendingItem(pendingItem);
            pinManager.markReleased(entry, false);
            return ProxyResponseReceivedAction.continueWith(response);
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            api.logging().logToError("Pinpoint: Response wait interrupted, discarding edit.");
            mainUI.removePendingItem(pendingItem);
            pinManager.markReleased(entry, false);
            return ProxyResponseReceivedAction.continueWith(response);
        } finally {
            pinManager.setPaused(entry, false, false);
        }
    }

    @Override
    public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse response) {
        return ProxyResponseToBeSentAction.continueWith(response);
    }
}
