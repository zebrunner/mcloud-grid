package com.zebrunner.mcloud.grid.servlets;

import com.zebrunner.mcloud.grid.MobileRemoteProxy;
import com.zebrunner.mcloud.grid.PlaywrightSessions;
import com.zebrunner.mcloud.grid.util.EnvUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The WebSocket of a Playwright session: ws://hub:4444/grid/admin/PlaywrightServlet/&lt;Appium session id&gt;.
 * <p>
 * A Playwright session is an Appium session of the 'Playwright' driver of zebrunner/appium: the hub queues it, reserves
 * the device in STF and releases it as any other session, the driver serves the Playwright server of the device on the
 * WebSocket /mcloud/playwright/&lt;session id&gt; of its Appium. This servlet passes the WebSocket of the client on to
 * that one with the node token of the node (the client cannot reach the node itself) and the headers Playwright checks
 * (its version in User-Agent).
 * <p>
 * The Playwright sessions are an option of the farm (PLAYWRIGHT_ENABLED, see {@link PlaywrightSessions}): without it the
 * WebSocket is refused with 501 and the hub refuses the new Playwright sessions.
 * <p>
 * While a WebSocket of the session is open the session does not time out: the Playwright client sends no WebDriver
 * commands. The hub pings both sides instead (Playwright sends no pings itself), a side that does not answer for
 * PLAYWRIGHT_IDLE_TIMEOUT is closed together with the other one. When the last WebSocket of a session is closed the
 * inactivity of the session counts again from then: a session the client did not delete is released after the timeout
 * of the hub (GRID_TIMEOUT), as a WebDriver session without commands.
 */
public class PlaywrightServlet extends JettyWebSocketServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(PlaywrightServlet.class.getName());

    /** The WebSocket of the driver on the Appium of the node, followed by the session id. */
    public static final String NODE_PATH = "/mcloud/playwright/";
    /** Headers of the client the node gets: the version check of Playwright (User-Agent) and its options. */
    static final List<String> FORWARDED_HEADERS = List.of("User-Agent", "x-playwright-browser", "x-playwright-launch-options",
            "x-playwright-proxy");

    private static final Duration IDLE_TIMEOUT = EnvUtils.getDurationInSeconds("PLAYWRIGHT_IDLE_TIMEOUT", Duration.ofSeconds(120));
    private static final Duration PING_PERIOD = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    // the messages of Playwright are big (screenshots, traces): they are passed on in parts, the limit is a safety net
    private static final long MAX_MESSAGE_SIZE = 1024L * 1024 * 1024;
    private static final String CONNECTIONS = "PLAYWRIGHT_CONNECTIONS";

    private final transient Function<String, Optional<Target>> locator;
    private final transient BooleanSupplier enabled;
    private transient WebSocketClient client;
    private transient ScheduledExecutorService pings;

    public PlaywrightServlet() {
        this(null, PlaywrightSessions::isEnabled);
    }

    /**
     * @param locator the session of an id, null for the sessions of the registry of the hub
     * @param enabled whether the Playwright sessions are enabled (PLAYWRIGHT_ENABLED)
     */
    PlaywrightServlet(Function<String, Optional<Target>> locator, BooleanSupplier enabled) {
        this.locator = locator;
        this.enabled = enabled;
    }

    @Override
    public void init() throws ServletException {
        ServletContext context = getServletContext();
        // the hub of Selenium creates its context without the WebSocket support of Jetty
        WebSocketServerComponents.ensureWebSocketComponents(ContextHandler.getContextHandler(context).getServer(), context);
        JettyWebSocketServerContainer.ensureContainer(context);
        client = new WebSocketClient();
        client.setIdleTimeout(IDLE_TIMEOUT);
        client.setConnectTimeout(CONNECT_TIMEOUT.toMillis());
        client.setMaxTextMessageSize(MAX_MESSAGE_SIZE);
        client.setMaxBinaryMessageSize(MAX_MESSAGE_SIZE);
        client.setStopAtShutdown(false);
        try {
            client.start();
        } catch (Exception e) {
            throw new ServletException("Could not start the WebSocket client of the Playwright sessions", e);
        }
        pings = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Playwright pings");
            thread.setDaemon(true);
            return thread;
        });
        super.init();
    }

    @Override
    public void destroy() {
        super.destroy();
        if (pings != null) {
            pings.shutdownNow();
        }
        if (client != null) {
            try {
                client.stop();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Could not stop the WebSocket client of the Playwright sessions", e);
            }
        }
    }

    @Override
    protected void configure(JettyWebSocketServletFactory factory) {
        factory.setIdleTimeout(IDLE_TIMEOUT);
        factory.setMaxTextMessageSize(MAX_MESSAGE_SIZE);
        factory.setMaxBinaryMessageSize(MAX_MESSAGE_SIZE);
        factory.setCreator(this::connect);
    }

    /**
     * Connects to the node of the session first, so a client gets the reason as the HTTP status of its upgrade.
     */
    private Object connect(JettyServerUpgradeRequest request, JettyServerUpgradeResponse response) {
        if (!enabled.getAsBoolean()) {
            sendError(response, HttpServletResponse.SC_NOT_IMPLEMENTED, PlaywrightSessions.DISABLED_MESSAGE);
            return null;
        }
        String sessionId = StringUtils.strip(StringUtils.defaultString(request.getHttpServletRequest().getPathInfo()), "/");
        Optional<Target> found = StringUtils.isBlank(sessionId) || sessionId.contains("/") ? Optional.empty() : locate(sessionId);
        if (found.isEmpty()) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, String.format("No running session '%s' on the grid", sessionId));
            return null;
        }
        Target target = found.get();
        ClientUpgradeRequest upgrade = new ClientUpgradeRequest();
        for (String header : FORWARDED_HEADERS) {
            String value = request.getHeader(header);
            if (value != null) {
                upgrade.setHeader(header, value);
            }
        }
        target.authenticate.accept(upgrade::setHeader);
        Bridge bridge = new Bridge(target);
        try {
            client.connect(bridge.node, target.uri, upgrade).get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException | ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            LOGGER.warning(() -> String.format("[%s] The node did not accept the Playwright connection of the session %s: %s",
                    target.name, sessionId, cause));
            sendError(response, HttpServletResponse.SC_BAD_GATEWAY, String.format("The node of the session '%s' did not accept the Playwright "
                    + "connection: %s. Is it a session of the 'Playwright' automation?", sessionId, cause.getMessage()));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "The hub is stopping");
            return null;
        }
        return bridge.client;
    }

    private static void sendError(JettyServerUpgradeResponse response, int status, String message) {
        try {
            response.sendError(status, message);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not answer the WebSocket upgrade", e);
        }
    }

    private Optional<Target> locate(String sessionId) {
        if (locator != null) {
            return locator.apply(sessionId);
        }
        GridRegistry registry = (GridRegistry) getServletContext().getAttribute(GridRegistry.KEY);
        if (registry == null) {
            return Optional.empty();
        }
        for (RemoteProxy proxy : registry.getAllProxies()) {
            if (!(proxy instanceof MobileRemoteProxy)) {
                continue;
            }
            for (TestSlot slot : proxy.getTestSlots()) {
                TestSession session = slot.getSession();
                if (session != null && session.getExternalKey() != null
                        && (sessionId.equals(session.getExternalKey().getKey()) || sessionId.equals(session.getInternalKey()))) {
                    return Optional.of(Target.of((MobileRemoteProxy) proxy, session));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The WebSocket of a session on its node.
     */
    static final class Target {
        final String name;
        final URI uri;
        final Consumer<BiConsumer<String, String>> authenticate;
        final Runnable opened;
        final Runnable closed;

        Target(String name, URI uri, Consumer<BiConsumer<String, String>> authenticate, Runnable opened, Runnable closed) {
            this.name = name;
            this.uri = uri;
            this.authenticate = authenticate;
            this.opened = opened;
            this.closed = closed;
        }

        static Target of(MobileRemoteProxy proxy, TestSession session) {
            URL node = proxy.getRemoteHost();
            String scheme = "https".equalsIgnoreCase(node.getProtocol()) ? "wss" : "ws";
            URI uri = URI.create(String.format("%s://%s:%d%s%s", scheme, node.getHost(), node.getPort(), NODE_PATH,
                    session.getExternalKey().getKey()));
            return new Target(proxy.getUdid(), uri, proxy::authenticate, () -> count(session, 1), () -> count(session, -1));
        }

        // the session does not time out while a WebSocket of it is open
        private static void count(TestSession session, int delta) {
            synchronized (session) {
                AtomicInteger connections = (AtomicInteger) session.get(CONNECTIONS);
                if (connections == null) {
                    connections = new AtomicInteger();
                    session.put(CONNECTIONS, connections);
                }
                session.setIgnoreTimeout(connections.addAndGet(delta) > 0);
            }
        }
    }

    /**
     * The two WebSockets of a Playwright connection: the one of the client and the one of the node.
     */
    private final class Bridge {
        private final Target target;
        private final Side client = new Side("client");
        private final Side node = new Side("node");
        private final AtomicBoolean opened = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> ping;

        Bridge(Target target) {
            this.target = target;
            client.peer = node;
            node.peer = client;
        }

        void opened() {
            opened.set(true);
            target.opened.run();
            ping = pings.scheduleWithFixedDelay(() -> {
                client.ping();
                node.ping();
            }, PING_PERIOD.toMillis(), PING_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
            LOGGER.info(() -> String.format("[%s] Playwright connection to %s is open.", target.name, target.uri.getPath()));
        }

        void close(Side by, int status, String reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (ping != null) {
                ping.cancel(false);
            }
            by.peer.close(status, reason);
            if (opened.get()) {
                target.closed.run();
            }
            LOGGER.info(() -> String.format("[%s] Playwright connection to %s is closed by the %s: %s %s.", target.name,
                    target.uri.getPath(), by.name, status, StringUtils.defaultString(reason)));
        }

        /**
         * One WebSocket: passes the frames it gets on to the other one, as soon as that one is open.
         */
        private final class Side implements WebSocketPartialListener {
            private final String name;
            private final CompletableFuture<Session> session = new CompletableFuture<>();
            private Side peer;

            Side(String name) {
                this.name = name;
            }

            @Override
            public void onWebSocketConnect(Session connected) {
                session.complete(connected);
                if (this == client) {
                    opened();
                }
            }

            @Override
            public void onWebSocketPartialText(String payload, boolean fin) {
                Session other = peer.open();
                if (other != null) {
                    try {
                        other.getRemote().sendPartialString(payload, fin);
                    } catch (IOException e) {
                        onWebSocketError(e);
                    }
                }
            }

            @Override
            public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin) {
                Session other = peer.open();
                if (other != null) {
                    try {
                        other.getRemote().sendPartialBytes(payload, fin);
                    } catch (IOException e) {
                        onWebSocketError(e);
                    }
                }
            }

            @Override
            public void onWebSocketClose(int statusCode, String reason) {
                Bridge.this.close(this, statusCode, reason);
            }

            @Override
            public void onWebSocketError(Throwable cause) {
                LOGGER.fine(() -> String.format("[%s] Playwright connection to %s failed on the %s side: %s", target.name,
                        target.uri.getPath(), name, cause));
                Bridge.this.close(this, StatusCode.SERVER_ERROR, cause.getMessage());
                close(StatusCode.SERVER_ERROR, cause.getMessage());
            }

            /**
             * @return the session once it is open (the client is upgraded after the node is connected), null if it is not
             */
            private Session open() {
                try {
                    Session connected = session.get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    return connected.isOpen() ? connected : null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (ExecutionException | TimeoutException e) {
                    return null;
                }
            }

            private void ping() {
                Session connected = session.getNow(null);
                if (connected != null && connected.isOpen()) {
                    try {
                        connected.getRemote().sendPing(ByteBuffer.allocate(0));
                    } catch (IOException e) {
                        LOGGER.fine(() -> String.format("[%s] Could not ping the %s side: %s", target.name, name, e));
                    }
                }
            }

            private void close(int status, String reason) {
                Session connected = session.getNow(null);
                if (connected != null && connected.isOpen()) {
                    // 1005 (no status) and 1006 (abnormal) are not sent in a close frame
                    int code = StatusCode.isTransmittable(status) ? status : (status == StatusCode.NO_CODE ? StatusCode.NORMAL : StatusCode.SERVER_ERROR);
                    connected.close(code, StringUtils.abbreviate(StringUtils.defaultString(reason), 120));
                }
            }
        }
    }
}
