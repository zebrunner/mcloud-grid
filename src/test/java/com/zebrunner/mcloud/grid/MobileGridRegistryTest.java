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
        config.registry = MobileGridRegistry.class.getName();
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
     * A node whose preparation of the device is controlled by the test.
     */
    static final class TestProxy extends MobileRemoteProxy {
        final CountDownLatch prepareStarted = new CountDownLatch(1);
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile CountDownLatch proceed = new CountDownLatch(0);
        volatile boolean ready = true;
        volatile RuntimeException failure;
        volatile RuntimeException claimFailure;

        TestProxy(RegistrationRequest request, GridRegistry registry) {
            super(request, registry);
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
    public void hubUsesTheRegistry() {
        Assert.assertTrue(registry instanceof MobileGridRegistry, registry.getClass().getName());
    }

    @Test
    public void mobileProxyIsAdded() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps("emulator-5554"));

        registry.add(proxy);

        Assert.assertSame(registry.getProxyById(NODE_URL), proxy);
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
