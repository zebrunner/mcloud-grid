package com.zebrunner.mcloud.grid.servlets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.GridFixtures;
import com.zebrunner.mcloud.grid.IgnoredDevices;
import com.zebrunner.mcloud.grid.MobileRemoteProxy;
import org.openqa.grid.internal.ExternalSessionKey;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.TestSession;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class DevicesServletTest {
    private GridRegistry registry;
    private MobileRemoteProxy android;
    private MobileRemoteProxy ios;

    @BeforeMethod
    public void setUp() {
        IgnoredDevices.clear();
        registry = GridFixtures.registry();
        android = GridFixtures.proxy(registry, "http://node-1:4723", GridFixtures.androidNodeCaps("udid-1"));
        ios = GridFixtures.proxy(registry, "http://node-2:4723", GridFixtures.iosNodeCaps("udid-2"));
        registry.add(android);
        registry.add(ios);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        IgnoredDevices.clear();
        registry.stop();
    }

    private Map<String, JsonNode> devices() throws Exception {
        ServletStubs.Response response = new ServletStubs.Response();
        new DevicesServlet(registry).doGet(ServletStubs.request(), response.servletResponse);
        Assert.assertEquals(response.calls.get("setStatus"), 200);
        Map<String, JsonNode> byUdid = new HashMap<>();
        new ObjectMapper().readTree(response.body()).get("value").forEach(device -> byUdid.put(device.get("udid").asText(), device));
        return byUdid;
    }

    @Test
    public void freeDevicesWithTheirCapabilities() throws Exception {
        Map<String, JsonNode> devices = devices();

        Assert.assertEquals(devices.keySet(), java.util.Set.of("udid-1", "udid-2"));
        JsonNode device = devices.get("udid-1");
        Assert.assertEquals(device.get("status").asText(), "free");
        Assert.assertEquals(device.get("deviceName").asText(), "Pixel-udid-1");
        Assert.assertEquals(device.get("platformName").asText(), "ANDROID");
        Assert.assertEquals(device.get("platformVersion").asText(), "13");
        Assert.assertEquals(device.get("deviceType").asText(), "phone");
        Assert.assertEquals(device.get("node").asText(), "http://node-1:4723");
        Assert.assertTrue(device.get("session").isNull());
        Assert.assertTrue(device.get("ignored").isNull());
    }

    @Test
    public void busyDeviceShowsItsSession() throws Exception {
        Map<String, Object> requested = new HashMap<>();
        requested.put("platformName", "iOS");
        TestSession session = ios.getNewSession(requested);
        session.setExternalKey(new ExternalSessionKey("appium-session-1"));
        session.put("lastCommand", "GET /session/appium-session-1/source");

        JsonNode device = devices().get("udid-2");

        Assert.assertEquals(device.get("status").asText(), "busy");
        Assert.assertEquals(device.get("session").get("id").asText(), "appium-session-1");
        Assert.assertEquals(device.get("session").get("internalId").asText(), session.getInternalKey());
        Assert.assertEquals(device.get("session").get("lastCommand").asText(), "GET /session/appium-session-1/source");
        Assert.assertTrue(device.get("session").get("inactivitySeconds").asLong() >= 0);
        Assert.assertEquals(devices().get("udid-1").get("status").asText(), "free");
    }

    @Test
    public void ignoredDeviceShowsTheReason() throws Exception {
        IgnoredDevices.ignore("udid-1", Duration.ofMinutes(3), "device is reserved in STF by john");

        JsonNode device = devices().get("udid-1");

        Assert.assertEquals(device.get("status").asText(), "ignored");
        Assert.assertEquals(device.get("ignored").get("reason").asText(), "device is reserved in STF by john");
        Assert.assertTrue(device.get("ignored").get("secondsLeft").asLong() > 170);
        Assert.assertFalse(device.get("ignored").get("until").asText().isEmpty());
    }

    @Test
    public void deviceTypeIsRenderedInLowerCase() throws Exception {
        registry.stop();
        registry = GridFixtures.registry();
        Map<String, Object> caps = GridFixtures.androidNodeCaps("udid-1");
        caps.put("zebrunner:deviceType", "Phone");
        android = GridFixtures.proxy(registry, "http://node-1:4723", caps);
        registry.add(android);

        JsonNode device = devices().get("udid-1");

        Assert.assertEquals(device.get("deviceType").asText(), "phone");
    }
}
