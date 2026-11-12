package com.zebrunner.mcloud.grid;

import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.ExternalSessionKey;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.SessionTerminationReason;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.utils.configuration.GridHubConfiguration;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;
import org.openqa.grid.web.Hub;
import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.web.servlet.handler.RequestHandler;
import org.openqa.grid.web.servlet.handler.RequestType;
import org.openqa.grid.web.servlet.handler.SeleniumBasedRequest;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The registry of the hub config (generate_config): only MobileRemoteProxy nodes, the devices prepared outside of
 * the lock of the registry, the timeouts of the hub, the devices of lost nodes returned, the queue safe for the console.
 */
public class MobileGridRegistryTest {
    private static final String NODE_URL = "http://localhost:4723";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private GridRegistry registry;

    @BeforeMethod
    public void setUp() {
        IgnoredDevices.clear();
        GridHubConfiguration config = new GridHubConfiguration();
        config.capabilityMatcher = new MobileCapabilityMatcher();
        config.registry = StfConfiguredRegistry.class.getName();
        config.port = 0;
        config.timeout = 77;
        config.browserTimeout = 900;
        config.newSessionWaitTimeout = (int) WAIT.toMillis();
        config.throwOnCapabilityNotPresent = false;
        registry = new Hub(config).getRegistry();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        // the queue interrupts the threads that made its requests: this one
        registry.clearNewSessionRequests();
        registry.stop();
        Thread.interrupted();
        IgnoredDevices.clear();
    }

    /**
     * The registry as the hub with STF_URL and STF_TOKEN starts it; the unit tests run without STF.
     */
    public static final class StfConfiguredRegistry extends MobileGridRegistry {
        @Override
        protected boolean isStfConfigured() {
            return true;
        }
    }

    /**
     * A node whose preparation of the device is controlled by the test.
     */
    static final class TestProxy extends MobileRemoteProxy {
        final CountDownLatch prepareStarted = new CountDownLatch(1);
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile CountDownLatch proceed = new CountDownLatch(0);
        volatile boolean ready = true;
        volatile RuntimeException failure;
        volatile RuntimeException claimFailure;
        volatile RuntimeException afterSessionFailure;

        TestProxy(RegistrationRequest request, GridRegistry registry) {
            super(request, registry);
        }

        @Override
        public void afterSession(TestSession session) {
            events.add("afterSession");
            if (afterSessionFailure != null) {
                throw afterSessionFailure;
            }
            super.afterSession(session);
        }

        @Override
        public TestSession claim(Map<String, Object> requestedCapability) {
            if (claimFailure != null) {
                throw claimFailure;
            }
            return super.claim(requestedCapability);
        }

        @Override
        public boolean prepare(TestSession session) {
            events.add("prepare");
            prepareStarted.countDown();
            try {
                proceed.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (failure != null) {
                throw failure;
            }
            return ready;
        }

        @Override
        public void abandon(TestSession session) {
            events.add("abandon");
        }

        @Override
        public void releaseLostSession(TestSession session, SessionTerminationReason reason) {
            events.add("lost " + reason);
        }
    }

    private TestProxy node(String url, String udid) {
        RegistrationRequest request = GridFixtures.registrationRequest(url, GridFixtures.androidNodeCaps(udid));
        request.getConfiguration().timeout = 150;
        request.getConfiguration().browserTimeout = 0;
        TestProxy proxy = new TestProxy(request, registry);
        registry.add(proxy);
        return proxy;
    }

    /**
     * A new session request of a client, as the DriverServlet of the hub makes it.
     */
    private RequestHandler request(String platform) {
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("platformName", platform);
        return request(capabilities);
    }

    private RequestHandler request(Map<String, Object> capabilities) {
        HttpServletRequest servletRequest = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> null);
        SeleniumBasedRequest request = new SeleniumBasedRequest(servletRequest, registry, RequestType.START_SESSION, capabilities) {
            @Override
            public RequestType extractRequestType() {
                return RequestType.START_SESSION;
            }

            @Override
            public ExternalSessionKey extractSession() {
                return null;
            }

            @Override
            public Map<String, Object> extractDesiredCapability() {
                return capabilities;
            }
        };
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {HttpServletResponse.class}, (proxy, method, args) -> null);
        RequestHandler handler = new RequestHandler(request, response, registry);
        registry.addNewSessionRequest(handler);
        return handler;
    }

    private static TestSession boundSession(RequestHandler handler) throws Exception {
        handler.waitForSessionBound();
        return handler.getSession();
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant until = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(until)) {
                Assert.fail("Timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    @Test
    public void playwrightSessionIsRefusedBeforeTheQueueWhileTheOptionIsOff() {
        TestProxy proxy = node(NODE_URL, "emulator-5554");
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("platformName", "Android");
        capabilities.put("appium:automationName", "Playwright");

        GridException error = Assert.expectThrows(GridException.class, () -> request(capabilities));

        Assert.assertEquals(error.getMessage(), PlaywrightSessions.DISABLED_MESSAGE);
        Assert.assertEquals(registry.getNewSessionRequestCount(), 0);
        Assert.assertFalse(proxy.isBusy(), "no device is taken for it");
    }

    @Test
    public void playwrightSessionGetsADeviceWithTheOption() throws Exception {
        PlaywrightSessions.setEnabled(true);
        try {
            node(NODE_URL, "emulator-5554");
            Map<String, Object> capabilities = new HashMap<>();
            capabilities.put("platformName", "Android");
            capabilities.put("appium:automationName", "Playwright");

            Assert.assertNotNull(boundSession(request(capabilities)));
        } finally {
            PlaywrightSessions.setEnabled(false);
        }
    }

    @Test
    public void hubUsesTheRegistry() {
        Assert.assertTrue(registry instanceof MobileGridRegistry, registry.getClass().getName());
    }

    @Test
    public void hubStartsWithoutStfAndLogsIt() {
        GridHubConfiguration config = new GridHubConfiguration();
        config.registry = MobileGridRegistry.class.getName();
        config.port = 0;
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(MobileGridRegistry.class.getName());
        logger.addHandler(handler);
        try {
            // the unit tests run without STF_URL and STF_TOKEN: the first start of MCloud, the nodes are refused (NodeAuthenticationTest)
            Hub hub = new Hub(config);
            hub.getRegistry().stop();
        } finally {
            logger.removeHandler(handler);
        }
        Assert.assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.SEVERE
                && r.getMessage().startsWith("[CONFIGURATION] STF_URL and STF_TOKEN are not set")), records.toString());

        Assert.assertTrue(MobileGridRegistry.checkNodeRegistration(true));
        Assert.assertFalse(MobileGridRegistry.checkNodeRegistration(false));
    }

    @Test
    public void mobileProxyIsAdded() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps("emulator-5554"));

        registry.add(proxy);

        Assert.assertSame(registry.getProxyById(NODE_URL), proxy);
    }

    @Test
    public void onlyTheRegisteredProxyIsRemovedBySameIdentity() {
        MobileRemoteProxy old = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps("emulator-5554"));
        registry.add(old);
        MobileRemoteProxy current = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps("emulator-5554"));
        registry.add(current);

        // a late check of the replaced node does not remove the node registered again with its id
        Assert.assertFalse(((MobileGridRegistry) registry).removeIfSame(old));
        Assert.assertSame(registry.getProxyById(NODE_URL), current);

        Assert.assertTrue(((MobileGridRegistry) registry).removeIfSame(current));
        Assert.assertNull(registry.getProxyById(NODE_URL));
    }

    @Test
    public void proxyOfAnotherClassIsRejected() {
        RegistrationRequest request = GridFixtures.registrationRequest(NODE_URL, GridFixtures.androidNodeCaps("emulator-5554"));
        request.getConfiguration().proxy = DefaultRemoteProxy.class.getName();
        RemoteProxy proxy = DefaultRemoteProxy.getNewInstance(request, registry);

        registry.add(proxy);

        Assert.assertNull(registry.getProxyById(NODE_URL));
    }

    @Test
    public void proxyOfAnotherClassDoesNotReplaceRegisteredNode() {
        MobileRemoteProxy registered = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps("emulator-5554"));
        registry.add(registered);
        RegistrationRequest request = GridFixtures.registrationRequest("http://attacker:4723", GridFixtures.androidNodeCaps("emulator-5554"));
        request.getConfiguration().id = NODE_URL;
        request.getConfiguration().proxy = DefaultRemoteProxy.class.getName();

        registry.add(DefaultRemoteProxy.getNewInstance(request, registry));

        Assert.assertSame(registry.getProxyById(NODE_URL), registered);
        Assert.assertEquals(registry.getAllProxies().size(), 1);
    }

    @Test
    public void timeoutsOfTheHubReplaceTheOnesOfTheNode() {
        TestProxy proxy = node(NODE_URL, "emulator-5554");

        Assert.assertEquals(proxy.getConfig().timeout, Integer.valueOf(77));
        Assert.assertEquals(proxy.getConfig().browserTimeout, Integer.valueOf(900));
    }

    @Test
    public void requestGetsAPreparedDevice() throws Exception {
        TestProxy proxy = node(NODE_URL, "emulator-5554");

        TestSession session = boundSession(request("Android"));

        Assert.assertSame(session.getSlot().getProxy(), proxy);
        Assert.assertEquals(registry.getActiveSessions().size(), 1);
        Assert.assertEquals(registry.getNewSessionRequestCount(), 0);
        Assert.assertEquals(proxy.events, List.of("prepare"));
    }

    @Test
    public void slowPreparationDoesNotStopTheRegistry() throws Exception {
        TestProxy slow = node(NODE_URL, "emulator-5554");
        slow.proceed = new CountDownLatch(1);
        RequestHandler first = request("Android");
        Assert.assertTrue(slow.prepareStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        // while the device of the first request is prepared: a new node, a new request, its device and its session
        TestProxy fast = node("http://localhost:4724", "emulator-5556");
        TestSession second = boundSession(request("Android"));
        Assert.assertSame(second.getSlot().getProxy(), fast);
        registry.terminate(second, SessionTerminationReason.CLIENT_STOPPED_SESSION);
        await("the end of the second session", () -> fast.getTotalUsed() == 0);

        slow.proceed.countDown();
        Assert.assertSame(boundSession(first).getSlot().getProxy(), slow);
    }

    @Test
    public void deviceThatIsNotReadyIsReleasedAndTheRequestGetsAnother() throws Exception {
        TestProxy notReady = node(NODE_URL, "emulator-5554");
        notReady.ready = false;
        RequestHandler handler = request("Android");
        Assert.assertTrue(notReady.prepareStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS));
        await("the release of the slot", () -> notReady.getTotalUsed() == 0);
        Assert.assertEquals(registry.getActiveSessions().size(), 0);
        Assert.assertEquals(registry.getNewSessionRequestCount(), 1);

        TestProxy ready = node("http://localhost:4724", "emulator-5556");

        Assert.assertSame(boundSession(handler).getSlot().getProxy(), ready);
        // the device that failed the request is not claimed for it again at once
        Assert.assertEquals(notReady.events, List.of("prepare"));
    }

    @Test
    public void failedPreparationReleasesTheSlot() throws Exception {
        TestProxy failing = node(NODE_URL, "emulator-5554");
        failing.failure = new NumberFormatException("For input string: \"1h\"");
        request("Android");

        Assert.assertTrue(failing.prepareStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS));
        await("the release of the slot", () -> failing.getTotalUsed() == 0);
        Assert.assertEquals(registry.getActiveSessions().size(), 0);
    }

    @Test
    public void failedClaimOfADeviceKeepsTheOthers() throws Exception {
        TestProxy failing = node(NODE_URL, "emulator-5554");
        failing.claimFailure = new IllegalStateException("broken node");
        TestProxy working = node("http://localhost:4724", "emulator-5556");

        Assert.assertSame(boundSession(request("Android")).getSlot().getProxy(), working);
        Assert.assertEquals(failing.getTotalUsed(), 0);
    }

    @Test
    public void requestBeingPreparedIsNotInTheQueueOfTheConsole() throws Exception {
        TestProxy proxy = node(NODE_URL, "emulator-5554");
        proxy.proceed = new CountDownLatch(1);
        request("Android");
        Assert.assertTrue(proxy.prepareStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        Assert.assertFalse(registry.getDesiredCapabilities().iterator().hasNext());
        Assert.assertEquals(registry.getNewSessionRequestCount(), 1);
        proxy.proceed.countDown();
    }

    @Test
    public void deviceOfARequestGoneDuringThePreparationIsReturned() throws Exception {
        TestProxy proxy = node(NODE_URL, "emulator-5554");
        proxy.proceed = new CountDownLatch(1);
        RequestHandler handler = request("Android");
        Assert.assertTrue(proxy.prepareStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        // the request timed out or its client is gone
        registry.removeNewSessionRequest(handler);
        proxy.proceed.countDown();

        await("the return of the device", () -> proxy.events.contains("abandon"));
        await("the release of the slot", () -> proxy.getTotalUsed() == 0);
        Assert.assertEquals(registry.getActiveSessions().size(), 0);
    }

    @Test
    public void deviceOfALostNodeIsReturned() throws Exception {
        TestProxy proxy = node(NODE_URL, "emulator-5554");
        boundSession(request("Android"));

        registry.removeIfPresent(proxy);

        await("the return of the device", () -> proxy.events.contains("lost " + SessionTerminationReason.PROXY_REREGISTRATION));
        Assert.assertEquals(registry.getActiveSessions().size(), 0);
        Assert.assertNull(registry.getProxyById(NODE_URL));
    }

    @Test
    public void deviceOfANodeLostDuringThePreparationIsReturnedOnce() throws Exception {
        TestProxy proxy = node(NODE_URL, "emulator-5554");
        proxy.proceed = new CountDownLatch(1);
        RequestHandler handler = request("Android");
        Assert.assertTrue(proxy.prepareStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        registry.removeIfPresent(proxy);
        proxy.proceed.countDown();

        await("the return of the device", () -> proxy.events.contains("abandon"));
        Assert.assertFalse(proxy.events.stream().anyMatch(event -> event.startsWith("lost")), proxy.events.toString());
        Assert.assertEquals(registry.getActiveSessions().size(), 0);
        Assert.assertEquals(registry.getNewSessionRequestCount(), 1);
        Assert.assertNull(handler.getRequest().getDesiredCapabilities().get("zebrunner:slotCapabilities"));
    }

    @Test
    public void endedSessionFreesTheDevice() throws Exception {
        TestProxy proxy = node(NODE_URL, "emulator-5554");
        TestSession session = boundSession(request("Android"));

        registry.terminate(session, SessionTerminationReason.CLIENT_STOPPED_SESSION);

        await("the end of the session", () -> registry.getActiveSessions().isEmpty() && proxy.getTotalUsed() == 0);
        Assert.assertSame(boundSession(request("Android")).getSlot().getProxy(), proxy);
    }

    @Test
    public void lateTerminationDoesNotEndTheNextSessionOfTheDevice() throws Exception {
        TestProxy proxy = node(NODE_URL, "emulator-5554");
        TestSession first = boundSession(request("Android"));
        registry.terminate(first, SessionTerminationReason.CLIENT_STOPPED_SESSION);
        await("the end of the first session", () -> proxy.getTotalUsed() == 0);
        TestSession second = boundSession(request("Android"));

        // e.g. a command of the client after the end of the first session
        registry.terminate(first, SessionTerminationReason.CLIENT_STOPPED_SESSION);
        Thread.sleep(300);

        Assert.assertSame(second.getSlot().getSession(), second);
        Assert.assertEquals(proxy.events.stream().filter("afterSession"::equals).count(), 1);
        registry.terminate(second, SessionTerminationReason.CLIENT_STOPPED_SESSION);
        await("the end of the second session", () -> registry.getActiveSessions().isEmpty() && proxy.getTotalUsed() == 0);
    }

    @Test
    public void failedAfterSessionFreesTheDevice() throws Exception {
        TestProxy proxy = node(NODE_URL, "emulator-5554");
        proxy.afterSessionFailure = new IllegalStateException("afterSession failed");
        TestSession session = boundSession(request("Android"));

        registry.terminate(session, SessionTerminationReason.TIMEOUT);

        await("the end of the session", () -> registry.getActiveSessions().isEmpty() && proxy.getTotalUsed() == 0);
        proxy.afterSessionFailure = null;
        Assert.assertSame(boundSession(request("Android")).getSlot().getProxy(), proxy);
    }

    @Test
    public void removalOfARequestKeepsTheOthers() throws Exception {
        // the requests without a session are equal for RequestHandler.equals: the queue of Selenium removed the first one
        RequestHandler first = request("Android");
        RequestHandler second = request("Android");

        Assert.assertTrue(registry.removeNewSessionRequest(second));
        Assert.assertFalse(registry.removeNewSessionRequest(second));
        Assert.assertEquals(registry.getNewSessionRequestCount(), 1);

        TestProxy proxy = node(NODE_URL, "emulator-5554");
        Assert.assertSame(boundSession(first).getSlot().getProxy(), proxy);
        Assert.assertEquals(registry.getNewSessionRequestCount(), 0);
    }

    @Test
    public void requestLeavingTheQueueWithoutADeviceLogsWhy() {
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(MobileGridRegistry.class.getName());
        Level level = logger.getLevel();
        // logging.properties of the tests keeps only SEVERE
        logger.setLevel(Level.WARNING);
        logger.addHandler(capture);
        try {
            IgnoredDevices.ignore("emulator-5554", Duration.ofHours(1), "low battery: 12% (discharging), below 15%");
            node(NODE_URL, "emulator-5554");
            RequestHandler handler = request("Android");

            Assert.assertTrue(registry.removeNewSessionRequest(handler));

            String warning = warnings.stream().filter(message -> message.startsWith("No device was found")).findFirst().orElse("");
            Assert.assertTrue(warning.contains("matching devices: 1, busy: 0, down: 0, ignored: 1"), warning);
            Assert.assertTrue(warning.contains("emulator-5554"), warning);
            Assert.assertTrue(warning.contains("low battery: 12% (discharging), below 15%"), warning);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(level);
        }
    }

    @Test
    public void queuedRequestsOfTheConsoleHaveNoSecretsAndAreEscaped() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("platformName", "Android");
        request.put("zebrunner:STF_TOKEN", "personal-token");
        request.put("appium:deviceName", "<img src=x onerror=alert(1)>");

        String html = new MobileGridRegistry.ConsoleCapabilities(new DesiredCapabilities(request)).toString();

        Assert.assertFalse(html.contains("personal-token"), html);
        Assert.assertTrue(html.contains("*****"), html);
        Assert.assertFalse(html.contains("<img"), html);
        Assert.assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), html);
    }

    @Test
    public void queueOfTheConsole() {
        Assert.assertFalse(registry.getDesiredCapabilities().iterator().hasNext());
        request("iOS");

        Assert.assertTrue(registry.getDesiredCapabilities().iterator().next().toString().contains("platformName"));
    }
}
