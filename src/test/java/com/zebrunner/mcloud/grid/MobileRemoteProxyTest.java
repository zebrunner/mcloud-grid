package com.zebrunner.mcloud.grid;

import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.TestSession;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Proxy behavior with STF integration disabled (no STF_URL/STF_TOKEN in env).
 */
public class MobileRemoteProxyTest {
    private static final String UDID = "emulator-5554";
    private static final String NODE_URL = "http://localhost:4723";

    private GridRegistry registry;

    @BeforeMethod
    public void setUp() {
        registry = GridFixtures.registry();
        MobileRemoteProxy.DEVICE_IGNORE_AUTOMATION_TIMERS.clear();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        MobileRemoteProxy.DEVICE_IGNORE_AUTOMATION_TIMERS.clear();
    }

    private static Map<String, Object> request(String platform) {
        Map<String, Object> caps = new HashMap<>();
        caps.put("platformName", platform);
        return caps;
    }

    @Test
    public void nodeWithoutUdidIsRejected() {
        Map<String, Object> caps = GridFixtures.androidNodeCaps(UDID);
        caps.remove("appium:udid");
        Assert.expectThrows(GridException.class, () -> GridFixtures.proxy(registry, NODE_URL, caps));
    }

    @Test
    public void nodeWithoutDeviceNameIsRejected() {
        Map<String, Object> caps = GridFixtures.androidNodeCaps(UDID);
        caps.remove("appium:deviceName");
        Assert.expectThrows(GridException.class, () -> GridFixtures.proxy(registry, NODE_URL, caps));
    }

    @Test
    public void unprefixedNodeCapabilitiesAreAccepted() {
        Map<String, Object> caps = new HashMap<>();
        caps.put("platformName", "ANDROID");
        caps.put("udid", UDID);
        caps.put("deviceName", "Pixel");
        Assert.assertNotNull(GridFixtures.proxy(registry, NODE_URL, caps));
    }

    @Test
    public void nodeOfUnknownPlatformIsAccepted() {
        Map<String, Object> caps = GridFixtures.androidNodeCaps(UDID);
        caps.put("platformName", "Tizen");
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, caps);

        Assert.assertNotNull(proxy.getNewSession(request("Tizen")));
    }

    @Test
    public void createsSessionForMatchingRequest() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps(UDID));

        TestSession session = proxy.getNewSession(request("Android"));

        Assert.assertNotNull(session);
        Assert.assertEquals(proxy.getTotalUsed(), 1);
    }

    @Test
    public void doesNotCreateSessionForOtherPlatform() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps(UDID));

        Assert.assertNull(proxy.getNewSession(request("iOS")));
        Assert.assertEquals(proxy.getTotalUsed(), 0);
    }

    @Test
    public void allowsOnlyOneSessionPerDevice() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps(UDID));

        Assert.assertNotNull(proxy.getNewSession(request("Android")));
        Assert.assertNull(proxy.getNewSession(request("Android")));
    }

    @Test
    public void deviceIsSkippedWhileIgnoreTimerIsActive() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps(UDID));
        MobileRemoteProxy.DEVICE_IGNORE_AUTOMATION_TIMERS.put(UDID, Duration.ofMillis(System.currentTimeMillis()).plusMinutes(5));

        Assert.assertNull(proxy.getNewSession(request("Android")));
        Assert.assertTrue(MobileRemoteProxy.DEVICE_IGNORE_AUTOMATION_TIMERS.containsKey(UDID));
    }

    @Test
    public void expiredIgnoreTimerIsRemoved() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps(UDID));
        MobileRemoteProxy.DEVICE_IGNORE_AUTOMATION_TIMERS.put(UDID, Duration.ofMillis(System.currentTimeMillis()).minusSeconds(1));

        Assert.assertNotNull(proxy.getNewSession(request("Android")));
        Assert.assertFalse(MobileRemoteProxy.DEVICE_IGNORE_AUTOMATION_TIMERS.containsKey(UDID));
    }

    @Test
    public void ignoreTimerOfAnotherDeviceDoesNotAffectThisOne() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps(UDID));
        MobileRemoteProxy.DEVICE_IGNORE_AUTOMATION_TIMERS.put("other", Duration.ofMillis(System.currentTimeMillis()).plusMinutes(5));

        Assert.assertNotNull(proxy.getNewSession(request("Android")));
    }

    @Test
    public void tvosDeviceOverridesPlatformName() {
        Map<String, Object> caps = GridFixtures.iosNodeCaps(UDID);
        caps.put("zebrunner:deviceType", "tvOS");
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, caps);

        TestSession session = proxy.getNewSession(request("iOS"));
        Assert.assertNotNull(session);
        proxy.beforeSession(session);

        Assert.assertEquals(session.getRequestedCapabilities().get("platformName"), "tvOS");
    }

    @Test
    public void nonTvosDeviceKeepsPlatformName() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.iosNodeCaps(UDID));

        TestSession session = proxy.getNewSession(request("iOS"));
        proxy.beforeSession(session);

        Assert.assertEquals(session.getRequestedCapabilities().get("platformName"), "iOS");
    }

    @Test
    public void newCommandTimeoutIsNotLimitedByDefault() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.iosNodeCaps(UDID));
        Map<String, Object> requested = request("iOS");
        requested.put("appium:newCommandTimeout", 86400);
        TestSession session = proxy.getNewSession(requested);

        proxy.beforeSession(session);

        Assert.assertEquals(session.getRequestedCapabilities().get("appium:newCommandTimeout"), 86400);
    }

    @Test
    public void afterSessionWithoutStfDoesNothing() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps(UDID));
        TestSession session = proxy.getNewSession(request("Android"));

        proxy.afterSession(session);
        proxy.beforeRelease(session);
    }

    @Test
    public void releaseWithoutExternalKeyIgnoresDevice() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps(UDID));
        TestSession session = proxy.getNewSession(request("Android"));

        proxy.beforeRelease(session);

        Assert.assertTrue(MobileRemoteProxy.DEVICE_IGNORE_AUTOMATION_TIMERS.containsKey(UDID));
    }
}
