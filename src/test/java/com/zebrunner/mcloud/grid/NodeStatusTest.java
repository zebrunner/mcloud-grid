package com.zebrunner.mcloud.grid;

import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.internal.GridRegistry;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The status of a node (the polling of the node, the console, /wd/hub/status) waits seconds, not the milliseconds of
 * nodeStatusCheckTimeout taken as seconds by Selenium 3.141.59 (5000 s for a node that does not answer).
 */
public class NodeStatusTest {
    private static final String UDID = "emulator-5554";
    // TEST-NET-1 (RFC 5737): not routed, the connection gets no answer
    private static final String UNROUTED_NODE_URL = "http://192.0.2.1:4723";
    // the longest a check waits for a status: longer means the timeouts of the status are not the ones of the hub
    private static final long MAX_WAIT_SECONDS = 15;

    private GridRegistry registry;
    private ExecutorService executor;
    private FakeNode node;

    @BeforeMethod
    public void setUp() {
        registry = GridFixtures.registry();
        // a status that does not time out must not keep the JVM of the tests
        executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "node status");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        registry.stop();
        if (node != null) {
            node.close();
            node = null;
        }
        executor.shutdownNow();
    }

    private MobileRemoteProxy proxy(String nodeUrl, int nodeStatusCheckTimeout) {
        RegistrationRequest request = GridFixtures.registrationRequest(nodeUrl, GridFixtures.androidNodeCaps(UDID));
        request.getConfiguration().nodeStatusCheckTimeout = nodeStatusCheckTimeout;
        return new MobileRemoteProxy(request, registry);
    }

    /**
     * @return the time the status of the proxy took, it fails
     */
    private Duration failingStatus(MobileRemoteProxy proxy) throws Exception {
        long start = System.nanoTime();
        Future<?> status = executor.submit(proxy::getProxyStatus);
        try {
            status.get(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
            Assert.fail("the status of a node that does not answer succeeded");
        } catch (TimeoutException e) {
            Assert.fail("the status of a node that does not answer took more than " + MAX_WAIT_SECONDS + " s");
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause() instanceof GridException, String.valueOf(e.getCause()));
        }
        return Duration.ofNanos(System.nanoTime() - start);
    }

    @Test
    public void statusOfAFrozenNodeTimesOut() throws Exception {
        node = FakeNode.frozen();
        MobileRemoteProxy proxy = proxy(node.url(), 1000);

        Duration took = failingStatus(proxy);

        Assert.assertTrue(took.compareTo(Duration.ofSeconds(4)) < 0, "took " + took);
        Assert.assertTrue(node.connections() > 0, "the hub connected to the node");
    }

    @Test
    public void statusOfAnUnroutedNodeTimesOut() throws Exception {
        // the default nodeStatusCheckTimeout of zebrunner/appium; the connection waits STATUS_CONNECT_TIMEOUT
        MobileRemoteProxy proxy = proxy(UNROUTED_NODE_URL, 5000);

        Duration took = failingStatus(proxy);

        Assert.assertTrue(took.compareTo(MobileRemoteProxy.STATUS_CONNECT_TIMEOUT.plusSeconds(2)) < 0, "took " + took);
    }

    @Test
    public void slowNodeAnswersWithinNodeStatusCheckTimeout() throws Exception {
        node = FakeNode.answering(Duration.ofMillis(1500), "{\"value\":{\"ready\":true}}");
        MobileRemoteProxy proxy = proxy(node.url(), 3000);

        Map<String, Object> status = executor.submit(proxy::getProxyStatus).get(MAX_WAIT_SECONDS, TimeUnit.SECONDS);

        Assert.assertEquals(status, Map.of("value", Map.of("ready", true)));
    }

    @Test
    public void frozenNodeIsUnregistered() throws Exception {
        node = FakeNode.frozen();
        RegistrationRequest request = GridFixtures.registrationRequest(node.url(), GridFixtures.androidNodeCaps(UDID));
        request.getConfiguration().nodeStatusCheckTimeout = 1000;
        request.getConfiguration().nodePolling = 200;
        request.getConfiguration().downPollingLimit = 1;
        request.getConfiguration().unregisterIfStillDownAfter = 0;
        MobileRemoteProxy proxy = new MobileRemoteProxy(request, registry);
        registry.add(proxy);
        Assert.assertNotNull(registry.getProxyById(proxy.getId()));

        // down after the first poll, unregistered after the second one: 2 x (0.2 s + 1 s)
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAX_WAIT_SECONDS);
        while (registry.getProxyById(proxy.getId()) != null && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }

        Assert.assertNull(registry.getProxyById(proxy.getId()), "the frozen node is still registered");
    }

    @Test
    public void statusTimeoutIsNodeStatusCheckTimeoutInSeconds() {
        Assert.assertEquals(MobileRemoteProxy.statusReadTimeout(5000), Duration.ofSeconds(5));
        Assert.assertEquals(MobileRemoteProxy.statusReadTimeout(1500), Duration.ofSeconds(2));
        Assert.assertEquals(MobileRemoteProxy.statusReadTimeout(100), Duration.ofSeconds(1));
        Assert.assertEquals(MobileRemoteProxy.statusReadTimeout(0), Duration.ofSeconds(1));
        Assert.assertEquals(MobileRemoteProxy.statusReadTimeout(null), Duration.ofSeconds(5));
    }

    /**
     * A node on a port of this machine: frozen (it accepts the connections and never answers) or answering the status
     * after a delay.
     */
    private static final class FakeNode {
        private final ServerSocket server;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        private FakeNode(Duration delay, String status) throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        if (status != null) {
                            new Thread(() -> answer(socket, delay, status), "fake node answer").start();
                        }
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "fake node");
            thread.setDaemon(true);
            thread.start();
        }

        static FakeNode frozen() throws IOException {
            return new FakeNode(null, null);
        }

        static FakeNode answering(Duration delay, String status) throws IOException {
            return new FakeNode(delay, status);
        }

        private static void answer(Socket socket, Duration delay, String status) {
            try {
                // the request is not read: the hub sends it at once and waits for the answer
                Thread.sleep(delay.toMillis());
                byte[] body = status.getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (IOException | InterruptedException e) {
                // the hub gave up
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        int connections() {
            return sockets.size();
        }

        void close() throws IOException {
            server.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }
}
