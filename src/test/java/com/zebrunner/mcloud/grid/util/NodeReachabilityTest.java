package com.zebrunner.mcloud.grid.util;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.ServerSocket;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;

public class NodeReachabilityTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Test
    public void listeningNodeIsReachable() throws Exception {
        try (ServerSocket node = new ServerSocket(0)) {
            Assert.assertEquals(NodeReachability.check(new URL("http://localhost:" + node.getLocalPort()), TIMEOUT), Optional.empty());
        }
    }

    @Test
    public void closedPortIsNotReachable() throws Exception {
        int port;
        try (ServerSocket node = new ServerSocket(0)) {
            port = node.getLocalPort();
        }
        Optional<String> reason = NodeReachability.check(new URL("http://localhost:" + port), TIMEOUT);
        Assert.assertTrue(reason.isPresent());
        Assert.assertTrue(reason.get().contains("ConnectException"), reason.get());
    }

    @Test
    public void unresolvableHostIsNotReachable() throws Exception {
        Optional<String> reason = NodeReachability.check(new URL("http://node.invalid:4723"), TIMEOUT);
        Assert.assertEquals(reason, Optional.of("host 'node.invalid' cannot be resolved"));
    }

    @Test
    public void silentAddressTimesOut() throws Exception {
        // TEST-NET-1 (RFC 5737) is not routed, the connection attempt hangs until the timeout
        long start = System.nanoTime();
        Optional<String> reason = NodeReachability.check(new URL("http://192.0.2.1:4723"), Duration.ofMillis(500));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        Assert.assertTrue(reason.isPresent());
        Assert.assertTrue(elapsedMs < 5000, "check took " + elapsedMs + "ms");
    }
}
