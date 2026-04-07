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
        boolean isPinned = pinManager.isRequestPinned(request.url());
        boolean inScope = api.scope().isInScope(request.url());
        pinManager.logTraffic(String.valueOf(request.messageId()), request.url(), true, isPinned, request, null, inScope);

        if (!isPinned) {
            return ProxyRequestReceivedAction.continueWith(request);
        }

        api.logging().logToOutput("Pinpoint matching request: " + request.url());

        PendingItem pendingItem = new PendingItem(String.valueOf(request.messageId()), request);
        mainUI.addPendingItem(pendingItem);

        try {
            HttpRequest editedRequest = pendingItem.getRequestFuture()
                    .get(INTERCEPT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (editedRequest != null) {
                return ProxyRequestReceivedAction.continueWith(editedRequest, request.annotations());
            } else {
                return ProxyRequestReceivedAction.drop();
            }
        } catch (TimeoutException e) {
            api.logging().logToError("Pinpoint: Request intercept timed out after " + INTERCEPT_TIMEOUT_MINUTES + " min, forwarding original.");
            mainUI.removePendingItem(pendingItem);
            return ProxyRequestReceivedAction.continueWith(request);
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            api.logging().logToError("Pinpoint: Request wait interrupted, discarding edit.");
            mainUI.removePendingItem(pendingItem);
            return ProxyRequestReceivedAction.continueWith(request);
        }
    }

    @Override
    public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest request) {
        return ProxyRequestToBeSentAction.continueWith(request);
    }

    @Override
    public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse response) {
        boolean isPinned = false;
        String url = "-";
        if (response.initiatingRequest() != null) {
            url = response.initiatingRequest().url();
            isPinned = pinManager.isResponsePinned(url);
        }
        boolean inScope2 = api.scope().isInScope(url);
        pinManager.logTraffic(String.valueOf(response.messageId()), url, false, isPinned, response.initiatingRequest(), response, inScope2);

        if (!isPinned) {
            return ProxyResponseReceivedAction.continueWith(response);
        }

        api.logging().logToOutput("Pinpoint matching response: " + response.initiatingRequest().url());
        
        PendingItem pendingItem = new PendingItem(String.valueOf(response.messageId()), response.initiatingRequest(), response);
        mainUI.addPendingItem(pendingItem);

        try {
            HttpResponse editedResponse = pendingItem.getResponseFuture()
                    .get(INTERCEPT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (editedResponse != null) {
                return ProxyResponseReceivedAction.continueWith(editedResponse, response.annotations());
            } else {
                return ProxyResponseReceivedAction.drop();
            }
        } catch (TimeoutException e) {
            api.logging().logToError("Pinpoint: Response intercept timed out after " + INTERCEPT_TIMEOUT_MINUTES + " min, forwarding original.");
            mainUI.removePendingItem(pendingItem);
            return ProxyResponseReceivedAction.continueWith(response);
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            api.logging().logToError("Pinpoint: Response wait interrupted, discarding edit.");
            mainUI.removePendingItem(pendingItem);
            return ProxyResponseReceivedAction.continueWith(response);
        }
    }

    @Override
    public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse response) {
        return ProxyResponseToBeSentAction.continueWith(response);
    }
}
