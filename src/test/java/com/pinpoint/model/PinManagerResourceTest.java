package com.pinpoint.model;

import burp.api.montoya.http.message.responses.HttpResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Memory and concurrency guarantees: nothing grows without bound, nothing is read needlessly. */
public class PinManagerResourceTest {

    private static HttpResponse response(int status, boolean failIfRead) {
        return (HttpResponse) Proxy.newProxyInstance(PinManagerResourceTest.class.getClassLoader(), new Class<?>[]{HttpResponse.class},
                (p, m, a) -> switch (m.getName()) {
                    case "statusCode" -> (short) status;
                    case "toString", "bodyToString" -> {
                        if (failIfRead) throw new AssertionError("response was read");
                        yield "HTTP/1.1 " + status + "\r\n\r\n";
                    }
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> null;
                });
    }

    @Test
    public void testWithoutGrepRulesResponsesAreNotRead() {
        PinManager pm = new PinManager(null);
        // Would throw if grep converted the response to text
        pm.logTraffic("https://app.example.com/big.bin", false, null, response(200, true), true, false);
        assertEquals(1, pm.getTrafficLog().size());
    }

    @Test
    public void testStatusHistoryIsCapped() {
        PinManager pm = new PinManager(null);
        for (int i = 0; i < 100; i++) {
            pm.logTraffic("https://app.example.com/flaky", false, null, response(i % 2 == 0 ? 200 : 503, false), true, false);
        }
        TrafficEntry entry = pm.getTrafficLog().get(0);
        assertTrue(entry.getStatusHistory().size() <= 10, "history size " + entry.getStatusHistory().size());
        assertEquals(503, entry.getStatusCode());
    }

    @Test
    public void testSimultaneousFirstHitsCreateOneNode() throws Exception {
        PinManager pm = new PinManager(null);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 200; i++) {
                    pm.logTraffic("https://app.example.com/api/item/" + (i % 20), true, null, null, true, false);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(20, pm.getTrafficLog().size());
        assertEquals(threads * 200, pm.getTrafficLog().stream().mapToInt(TrafficEntry::getHitCount).sum());
    }

    @Test
    public void testForgottenNodesLeaveFlows() {
        FlowTracker tracker = new FlowTracker();
        TrafficEntry page = new TrafficEntry("/inbox", "app.example.com", "https://app.example.com/inbox", false, true, null, null);
        TrafficEntry api = new TrafficEntry("/api/mail", "app.example.com", "https://app.example.com/api/mail", false, true, null, null);
        tracker.onRequest(page, new FlowDetector.Request("GET", "/inbox", null,
                Map.of("sec-fetch-mode", "navigate", "sec-fetch-dest", "document"), "", 0));
        tracker.onRequest(api, new FlowDetector.Request("GET", "/api/mail", null,
                Map.of("sec-fetch-mode", "cors", "sec-fetch-dest", "empty"), "", 100));
        tracker.forget(api);
        assertEquals(List.of(page), tracker.snapshot().get(0).steps());
    }
}
