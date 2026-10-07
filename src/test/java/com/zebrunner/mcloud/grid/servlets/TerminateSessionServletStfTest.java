package com.zebrunner.mcloud.grid.servlets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.GridFixtures;
import com.zebrunner.mcloud.grid.IgnoredDevices;
import com.zebrunner.mcloud.grid.MobileRemoteProxy;
import com.zebrunner.mcloud.grid.integration.client.STFClient;
import com.zebrunner.mcloud.grid.integration.client.StfStub;
import org.openqa.grid.internal.ExternalSessionKey;
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
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.zebrunner.mcloud.grid.integration.client.StfStub.BOT_USER;
import static com.zebrunner.mcloud.grid.integration.client.StfStub.DEFAULT_TOKEN;
import static com.zebrunner.mcloud.grid.integration.client.StfStub.device;

/**
 * The same WireMock server plays STF and the Appium node.
 */
@Test(groups = "stf", singleThreaded = true)
public class TerminateSessionServletStfTest {
    private static final String UDID = "emulator-5554";
    private static final String APPIUM_SESSION = "appium-session-1";
    private static final String DEVICE_PATH = "/api/v1/user/devices/" + UDID;

    private final StfStub stf = new StfStub();
    private GridRegistry registry;
    private MobileRemoteProxy proxy;
    private TestSession session;

    @BeforeClass(alwaysRun = true)
    public void startStf() {
        stf.start();
    }

    @AfterClass(alwaysRun = true)
    public void stopStf() {
        stf.stop();
    }

    @BeforeMethod(alwaysRun = true)
    public void startSession() {
        stf.reset();
        STFClient.clearCache();
        IgnoredDevices.clear();
        stf.user(DEFAULT_TOKEN, BOT_USER);
        stf.devices(device(UDID));
        stf.server().stubFor(post("/api/v1/user/devices").willReturn(okJson("{\"success\":true}")));
        stf.server().stubFor(post(urlMatching("/api/v1/user/devices/.*/remoteConnect")).willReturn(okJson("{\"success\":true}")));
        stf.server().stubFor(delete(urlMatching("/api/v1/user/devices/.*")).willReturn(okJson("{\"success\":true}")));
        stf.server().stubFor(get("/wd/hub/status-adb").willReturn(aResponse().withStatus(200)));
        stf.server().stubFor(delete("/wd/hub/session/" + APPIUM_SESSION).willReturn(okJson("{\"value\":null}")));

        registry = GridFixtures.registry();
        proxy = GridFixtures.proxy(registry, stf.server().baseUrl(), GridFixtures.androidNodeCaps(UDID));
        registry.add(proxy);
        Map<String, Object> requested = new HashMap<>();
        requested.put("platformName", "Android");
        session = proxy.getNewSession(requested);
        Assert.assertNotNull(session);
        session.setExternalKey(new ExternalSessionKey(APPIUM_SESSION));
        stf.server().resetRequests();
    }

    private ServletStubs.Response terminate(String authorization, Map<String, String> parameters) throws Exception {
        ServletStubs.Response response = new ServletStubs.Response();
        Map<String, String> headers = authorization == null ? Map.of() : Map.of("Authorization", authorization);
        new TerminateSessionServlet(registry).doPost(ServletStubs.request(headers, parameters), response.servletResponse);
        return response;
    }

    private void awaitSlotReleased() throws InterruptedException {
        // the registry releases the slot in its own thread
        for (int i = 0; i < 50 && proxy.getTotalUsed() > 0; i++) {
            Thread.sleep(100);
        }
        Assert.assertEquals(proxy.getTotalUsed(), 0, "slot is released");
    }

    public void terminatesSessionWithTheGivenKey() throws Exception {
        ServletStubs.Response response = terminate("Bearer admin-key", Map.of("udid", UDID));

        Assert.assertEquals(response.calls.get("setStatus"), 200, response.body());
        JsonNode body = new ObjectMapper().readTree(response.body());
        Assert.assertTrue(body.get("success").asBoolean());
        Assert.assertEquals(body.get("sessionId").asText(), APPIUM_SESSION);
        stf.server().verify(deleteRequestedFor(urlEqualTo(DEVICE_PATH)).withHeader("Authorization", equalTo("Bearer admin-key")));
        stf.server().verify(deleteRequestedFor(urlEqualTo("/wd/hub/session/" + APPIUM_SESSION)));
        awaitSlotReleased();
        // afterSession must not return the device again with the grid token
        stf.server().verify(0, deleteRequestedFor(urlEqualTo(DEVICE_PATH)).withHeader("Authorization", equalTo("Bearer " + DEFAULT_TOKEN)));
    }

    public void findsSessionByAppiumSessionId() throws Exception {
        ServletStubs.Response response = terminate("Bearer admin-key", Map.of("sessionId", APPIUM_SESSION));

        Assert.assertEquals(response.calls.get("setStatus"), 200, response.body());
        awaitSlotReleased();
    }

    public void keyRefusedByStfKeepsSessionRunning() throws Exception {
        stf.server().stubFor(delete(DEVICE_PATH).withHeader("Authorization", equalTo("Bearer user-key"))
                .willReturn(aResponse().withStatus(403).withBody("{\"success\":false,\"description\":\"Not owned by you\"}")));

        ServletStubs.Response response = terminate("Bearer user-key", Map.of("udid", UDID));

        Assert.assertEquals(response.calls.get("setStatus"), 403, response.body());
        Assert.assertTrue(response.body().contains("STF admin"), response.body());
        stf.server().verify(0, deleteRequestedFor(urlEqualTo("/wd/hub/session/" + APPIUM_SESSION)));
        Assert.assertEquals(proxy.getTotalUsed(), 1);
    }

    public void invalidKey() throws Exception {
        stf.server().stubFor(delete(DEVICE_PATH).withHeader("Authorization", equalTo("Bearer wrong"))
                .willReturn(aResponse().withStatus(401)));

        Assert.assertEquals(terminate("Bearer wrong", Map.of("udid", UDID)).calls.get("setStatus"), 401);
        Assert.assertEquals(proxy.getTotalUsed(), 1);
    }

    public void keyIsRequired() throws Exception {
        Assert.assertEquals(terminate(null, Map.of("udid", UDID)).calls.get("setStatus"), 401);
        Assert.assertEquals(terminate("Bearer ", Map.of("udid", UDID)).calls.get("setStatus"), 401);
        stf.server().verify(0, deleteRequestedFor(urlMatching(".*")));
    }

    public void deviceOrSessionIsRequired() throws Exception {
        Assert.assertEquals(terminate("Bearer admin-key", Map.of()).calls.get("setStatus"), 400);
    }

    public void unknownDeviceOrSession() throws Exception {
        Assert.assertEquals(terminate("Bearer admin-key", Map.of("udid", "other")).calls.get("setStatus"), 404);
        Assert.assertEquals(terminate("Bearer admin-key", Map.of("udid", UDID, "sessionId", "other")).calls.get("setStatus"), 404);
        stf.server().verify(0, deleteRequestedFor(urlMatching(".*")));
    }
}
