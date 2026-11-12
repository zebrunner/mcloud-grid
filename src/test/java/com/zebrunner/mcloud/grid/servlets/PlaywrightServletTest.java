package com.zebrunner.mcloud.grid.servlets;

import com.zebrunner.mcloud.grid.util.NodeRegistrationToken;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.exceptions.UpgradeException;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PlaywrightServletTest {
    private static final String TOKEN = "mcn1.node-1.s3cret";
    private static final String USER_AGENT = "Playwright/1.65.0 (arm64; macOS 15.0) node/24.0";

    private Server node;
    private Server hub;
    private WebSocketClient client;
    private final Map<String, String> nodeHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private volatile Session nodeSession;

    /**
     * The WebSocket of the driver on the node: answers every message with the same one.
     */
    public class Echo implements WebSocketListener {
        private Session session;

        @Override
        public void onWebSocketConnect(Session connected) {
            session = connected;
            nodeSession = connected;
            for (String header : new String[] {"User-Agent", NodeRegistrationToken.HEADER, "x-playwright-launch-options", "Cookie"}) {
                String value = connected.getUpgradeRequest().getHeader(header);
                if (value != null) {
                    nodeHeaders.put(header, value);
                }
            }
        }

        @Override
        public void onWebSocketText(String message) {
            try {
                session.getRemote().sendString(message);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {
            try {
                session.getRemote().sendBytes(ByteBuffer.wrap(payload, offset, len));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * The Playwright client: keeps the messages and the close status it gets.
     */
    public static class Recorder implements WebSocketListener {
        final BlockingQueue<Object> messages = new LinkedBlockingQueue<>();
        final CountDownLatch closedLatch = new CountDownLatch(1);
        volatile int closeStatus;

        @Override
        public void onWebSocketText(String message) {
            messages.add(message);
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {
            messages.add(ByteBuffer.wrap(payload, offset, len));
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            closeStatus = statusCode;
            closedLatch.countDown();
        }
    }

    @BeforeClass
    public void setUp() throws Exception {
        node = new Server(0);
        ServletContextHandler nodeContext = new ServletContextHandler();
        JettyWebSocketServletContainerInitializer.configure(nodeContext, (context, container) -> {
            container.setMaxTextMessageSize(64L * 1024 * 1024);
            container.setMaxBinaryMessageSize(64L * 1024 * 1024);
            container.addMapping(PlaywrightServlet.NODE_PATH + "appium-1", (req, resp) -> new Echo());
        });
        node.setHandler(nodeContext);
        node.start();
        int nodePort = ((ServerConnector) node.getConnectors()[0]).getLocalPort();

        // the hub of Selenium: a context without the WebSocket support of Jetty, the servlet is initialized lazily
        hub = new Server(0);
        ServletContextHandler hubContext = new ServletContextHandler();
        hubContext.addServlet(new ServletHolder(new PlaywrightServlet(sessionId -> {
            if (!sessionId.startsWith("appium-")) {
                return Optional.empty();
            }
            URI uri = URI.create("ws://127.0.0.1:" + nodePort + PlaywrightServlet.NODE_PATH + sessionId);
            return Optional.of(new PlaywrightServlet.Target("udid-1", uri, NodeRegistrationToken.of(TOKEN)::authenticate,
                    open::incrementAndGet, closed::incrementAndGet));
        })), "/grid/admin/PlaywrightServlet/*");
        hub.setHandler(hubContext);
        hub.start();

        client = new WebSocketClient();
        client.setMaxTextMessageSize(64L * 1024 * 1024);
        client.setMaxBinaryMessageSize(64L * 1024 * 1024);
        client.start();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        client.stop();
        hub.stop();
        node.stop();
    }

    private URI hubUri(String sessionId) {
        return URI.create("ws://127.0.0.1:" + ((ServerConnector) hub.getConnectors()[0]).getLocalPort()
                + "/grid/admin/PlaywrightServlet/" + sessionId);
    }

    private Session connect(String sessionId, Recorder recorder) throws Exception {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setHeader("User-Agent", USER_AGENT);
        request.setHeader("x-playwright-launch-options", "{}");
        request.setHeader("Cookie", "stf-session=secret");
        return client.connect(recorder, hubUri(sessionId), request).get(10, TimeUnit.SECONDS);
    }

    private static int upgradeStatus(Throwable error) {
        Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
        Assert.assertTrue(cause instanceof UpgradeException, "upgrade failure expected, got " + cause);
        return ((UpgradeException) cause).getResponseStatusCode();
    }

    @Test
    public void passesTheMessagesWithTheHeadersOfPlaywrightAndTheNodeToken() throws Exception {
        int opened = open.get();
        int closedBefore = closed.get();
        Recorder recorder = new Recorder();
        Session session = connect("appium-1", recorder);

        session.getRemote().sendString("{\"id\":1,\"method\":\"initialize\"}");
        Assert.assertEquals(recorder.messages.poll(10, TimeUnit.SECONDS), "{\"id\":1,\"method\":\"initialize\"}");

        // a screenshot of Playwright is one big message, bigger than the frames of Jetty
        StringBuilder big = new StringBuilder();
        while (big.length() < 5 * 1024 * 1024) {
            big.append("0123456789abcdef\u00e9\u4e2d");
        }
        session.getRemote().sendString(big.toString());
        Assert.assertEquals(recorder.messages.poll(20, TimeUnit.SECONDS), big.toString());

        byte[] binary = new byte[3 * 1024 * 1024];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        session.getRemote().sendBytes(ByteBuffer.wrap(binary));
        Assert.assertEquals(recorder.messages.poll(20, TimeUnit.SECONDS), ByteBuffer.wrap(binary));

        Assert.assertEquals(nodeHeaders.get("User-Agent"), USER_AGENT);
        Assert.assertEquals(nodeHeaders.get(NodeRegistrationToken.HEADER), TOKEN);
        Assert.assertEquals(nodeHeaders.get("x-playwright-launch-options"), "{}");
        Assert.assertNull(nodeHeaders.get("Cookie"), "only the headers of Playwright go to the node");
        Assert.assertEquals(open.get(), opened + 1);

        session.close(StatusCode.NORMAL, "done");
        Assert.assertTrue(recorder.closedLatch.await(10, TimeUnit.SECONDS));
        waitFor(() -> closed.get() == closedBefore + 1 && !nodeSession.isOpen());
    }

    @Test
    public void closeOfTheNodeClosesTheClient() throws Exception {
        Recorder recorder = new Recorder();
        connect("appium-1", recorder);
        waitFor(() -> nodeSession != null && nodeSession.isOpen());
        // the driver closes its WebSocket when the session ends
        nodeSession.close(StatusCode.SHUTDOWN, "session deleted");
        Assert.assertTrue(recorder.closedLatch.await(10, TimeUnit.SECONDS));
        Assert.assertEquals(recorder.closeStatus, StatusCode.SHUTDOWN);
    }

    @Test
    public void unknownSessionIsNotFound() {
        ExecutionException error = Assert.expectThrows(ExecutionException.class, () -> connect("no-such-session", new Recorder()));
        Assert.assertEquals(upgradeStatus(error), 404);
    }

    @Test
    public void sessionTheNodeDoesNotServeIsBadGateway() {
        // a session of another automation: the node has no Playwright WebSocket for it
        ExecutionException error = Assert.expectThrows(ExecutionException.class, () -> connect("appium-2", new Recorder()));
        Assert.assertEquals(upgradeStatus(error), 502);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                Assert.fail("condition not met in 10 s");
            }
            Thread.sleep(50);
        }
    }
}
