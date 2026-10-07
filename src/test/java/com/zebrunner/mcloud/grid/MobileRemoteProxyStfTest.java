package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.integration.client.STFClient;
import com.zebrunner.mcloud.grid.integration.client.StfStub;
import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.TestSession;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.zebrunner.mcloud.grid.integration.client.StfStub.BOT_USER;
import static com.zebrunner.mcloud.grid.integration.client.StfStub.DEFAULT_TOKEN;
import static com.zebrunner.mcloud.grid.integration.client.StfStub.device;

/**
 * Proxy behavior with STF integration and Appium status check enabled (see 'stf-tests' surefire execution).
 * The same WireMock server plays both STF and the Appium node.
 */
@Test(groups = "stf", singleThreaded = true)
public class MobileRemoteProxyStfTest {
    private static final String ANDROID_UDID = "emulator-5554";
    private static final String IOS_UDID = "00008110-000A";

    private final StfStub stf = new StfStub();
    private GridRegistry registry;
    private String nodeUrl;

    @BeforeClass(alwaysRun = true)
    public void startStf() {
        stf.start();
        nodeUrl = stf.server().baseUrl();
    }

    @AfterClass(alwaysRun = true)
    public void stopStf() {
        stf.stop();
    }

    @BeforeMethod(alwaysRun = true)
    public void reset() {
        stf.reset();
        STFClient.clearCache();
        IgnoredDevices.clear();
        registry = GridFixtures.registry();
        stf.user(DEFAULT_TOKEN, BOT_USER);
        stf.devices(device(ANDROID_UDID), device(IOS_UDID));
        stf.server().stubFor(post("/api/v1/user/devices").willReturn(okJson("{\"success\":true}")));
        stf.server().stubFor(post(urlMatching("/api/v1/user/devices/.*/remoteConnect")).willReturn(okJson("{\"success\":true}")));
        stf.server().stubFor(delete(urlMatching("/api/v1/user/devices/.*")).willReturn(okJson("{\"success\":true}")));
        stf.server().stubFor(get("/wd/hub/status-adb").willReturn(aResponse().withStatus(200)));
        stf.server().stubFor(get("/wd/hub/status-wda").willReturn(aResponse().withStatus(200)));
    }

    private static Map<String, Object> request(String platform) {
        Map<String, Object> caps = new HashMap<>();
        caps.put("platformName", platform);
        return caps;
    }

    @SuppressWarnings("unchecked")
    public void sessionReservesDeviceAndExposesSlotCapabilities() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.androidNodeCaps(ANDROID_UDID));
        Map<String, Object> requested = request("Android");

        TestSession session = proxy.getNewSession(requested);

        Assert.assertNotNull(session);
        stf.server().verify(getRequestedFor(urlEqualTo("/wd/hub/status-adb")));
        stf.server().verify(postRequestedFor(urlEqualTo("/api/v1/user/devices")));
        Map<String, Object> slotCaps = (Map<String, Object>) requested.get("zebrunner:slotCapabilities");
        Assert.assertNotNull(slotCaps);
        Assert.assertEquals(slotCaps.get("appium:udid"), ANDROID_UDID);
    }

    public void iosNodeUsesWdaStatusCheck() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.iosNodeCaps(IOS_UDID));

        Assert.assertNotNull(proxy.getNewSession(request("iOS")));
        stf.server().verify(getRequestedFor(urlEqualTo("/wd/hub/status-wda")));
        stf.server().verify(0, getRequestedFor(urlEqualTo("/wd/hub/status-adb")));
    }

    public void failedAppiumCheckReleasesSlotAndIgnoresDevice() {
        stf.server().stubFor(get("/wd/hub/status-adb").willReturn(aResponse().withStatus(500).withBody("adb offline")));
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.androidNodeCaps(ANDROID_UDID));

        Assert.assertNull(proxy.getNewSession(request("Android")));
        Assert.assertEquals(proxy.getTotalUsed(), 0);
        Assert.assertEquals(IgnoredDevices.get(ANDROID_UDID).map(IgnoredDevices.Entry::getReason).orElse(null), "Appium status check failed");
        stf.server().verify(0, postRequestedFor(urlEqualTo("/api/v1/user/devices")));
    }

    public void failedStfReservationReleasesSlot() {
        stf.devices(device(ANDROID_UDID).owner("someone-else"));
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.androidNodeCaps(ANDROID_UDID));

        Assert.assertNull(proxy.getNewSession(request("Android")));
        Assert.assertEquals(proxy.getTotalUsed(), 0);
    }

    public void nodeOfOtherPlatformRegistersWithoutAppiumCheck() {
        Map<String, Object> caps = GridFixtures.androidNodeCaps(ANDROID_UDID);
        caps.put("platformName", "WINDOWS");
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, caps);

        Assert.assertNotNull(proxy.getNewSession(request("WINDOWS")));
        stf.server().verify(0, getRequestedFor(urlMatching("/wd/hub/status-.*")));
    }

    public void unreachableNodeIsRejected() throws Exception {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String unreachable = "http://localhost:" + port;

        GridException e = Assert.expectThrows(GridException.class,
                () -> GridFixtures.proxy(registry, unreachable, GridFixtures.androidNodeCaps(ANDROID_UDID)));
        Assert.assertTrue(e.getMessage().contains("Node " + unreachable + " is not reachable from the hub"), e.getMessage());
    }

    public void newCommandTimeoutIsLimited() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.iosNodeCaps(IOS_UDID));
        Map<String, Object> requested = request("iOS");
        requested.put("appium:newCommandTimeout", 3600);
        TestSession session = proxy.getNewSession(requested);

        proxy.beforeSession(session);

        // MAX_NEW_COMMAND_TIMEOUT=300 in the 'stf-tests' execution
        Assert.assertEquals(session.getRequestedCapabilities().get("appium:newCommandTimeout"), 300L);
    }

    public void newCommandTimeoutWithinLimitIsKept() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.iosNodeCaps(IOS_UDID));
        Map<String, Object> requested = request("iOS");
        requested.put("appium:newCommandTimeout", "120");
        TestSession session = proxy.getNewSession(requested);

        proxy.beforeSession(session);

        Assert.assertEquals(session.getRequestedCapabilities().get("appium:newCommandTimeout"), "120");
    }

    public void disabledNewCommandTimeoutIsLimited() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.iosNodeCaps(IOS_UDID));
        Map<String, Object> requested = request("iOS");
        requested.put("newCommandTimeout", 0);
        TestSession session = proxy.getNewSession(requested);

        proxy.beforeSession(session);

        Assert.assertEquals(session.getRequestedCapabilities().get("newCommandTimeout"), 300L);
    }

    public void repeatedRegistrationOfKnownNodeSkipsStfCheck() {
        registry.add(GridFixtures.proxy(registry, nodeUrl, GridFixtures.androidNodeCaps(ANDROID_UDID)));
        stf.server().resetRequests();

        // the node registers again every few seconds
        GridFixtures.proxy(registry, nodeUrl, GridFixtures.androidNodeCaps(ANDROID_UDID));

        stf.server().verify(0, getRequestedFor(urlMatching("/api/v1/devices.*")));
    }

    public void newNodeIsCheckedInStf() {
        GridFixtures.proxy(registry, nodeUrl, GridFixtures.androidNodeCaps(ANDROID_UDID));

        stf.server().verify(1, getRequestedFor(urlEqualTo("/api/v1/devices/" + ANDROID_UDID)));
    }

    public void nodeOutsideOfAllowedNetworksIsRejected() {
        // NODE_ALLOWED_NETWORKS=127.0.0.0/8, ::1 in the 'stf-tests' execution
        GridException e = Assert.expectThrows(GridException.class,
                () -> GridFixtures.proxy(registry, "http://192.0.2.10:4723", GridFixtures.androidNodeCaps(ANDROID_UDID)));
        Assert.assertTrue(e.getMessage().contains("is not in NODE_ALLOWED_NETWORKS (127.0.0.0/8, ::1)"), e.getMessage());
    }

    public void nodeNotPresentInStfIsRejected() {
        Assert.expectThrows(GridException.class,
                () -> GridFixtures.proxy(registry, nodeUrl, GridFixtures.androidNodeCaps("not-in-stf")));
    }

    public void afterSessionReturnsDeviceToStf() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.androidNodeCaps(ANDROID_UDID));
        TestSession session = proxy.getNewSession(request("Android"));

        proxy.afterSession(session);

        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + ANDROID_UDID + "/remoteConnect")));
        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + ANDROID_UDID)));
    }

    public void timedOutSessionReturnsDeviceOnlyOnce() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.androidNodeCaps(ANDROID_UDID));
        TestSession session = proxy.getNewSession(request("Android"));

        // grid calls both on client inactivity timeout
        proxy.beforeRelease(session);
        proxy.afterSession(session);

        stf.server().verify(1, deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + ANDROID_UDID + "/remoteConnect")));
        stf.server().verify(1, deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + ANDROID_UDID)));
    }

    public void sessionWithoutReservationFlagIsReturned() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.androidNodeCaps(ANDROID_UDID));
        // session created without going through MobileRemoteProxy.getNewSession
        TestSession session = proxy.getTestSlots().get(0).getNewSession(request("Android"));

        proxy.afterSession(session);

        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + ANDROID_UDID)));
    }

    public void remoteConnectIsStoppedWithTokenOfReservation() {
        stf.user("personal-token", "john");
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.androidNodeCaps(ANDROID_UDID));
        Map<String, Object> requested = request("Android");
        requested.put("zebrunner:STF_TOKEN", "personal-token");
        TestSession session = proxy.getNewSession(requested);
        Assert.assertNotNull(session);

        proxy.afterSession(session);

        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + ANDROID_UDID + "/remoteConnect"))
                .withHeader("Authorization", equalTo("Bearer personal-token")));
        stf.server().verify(0, deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + ANDROID_UDID)));
    }

    public void deviceReservedWithPersonalTokenIsNotReturned() {
        stf.user("personal-token", "john");
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, nodeUrl, GridFixtures.iosNodeCaps(IOS_UDID));
        Map<String, Object> requested = request("iOS");
        requested.put("zebrunner:STF_TOKEN", "personal-token");
        TestSession session = proxy.getNewSession(requested);
        Assert.assertNotNull(session);

        proxy.afterSession(session);

        stf.server().verify(0, deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + IOS_UDID)));
    }
}
