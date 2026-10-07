package com.zebrunner.mcloud.grid.servlets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.GridFixtures;
import com.zebrunner.mcloud.grid.MobileRemoteProxy;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.TestSession;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class AllSessionsServletTest {

    @Test
    public void listsActiveSessionsWithTheirDevice() throws Exception {
        MobileRemoteProxy proxy = GridFixtures.proxy(GridFixtures.registry(), "http://node-1:4723", GridFixtures.androidNodeCaps("udid-1"));
        Map<String, Object> requested = new HashMap<>();
        requested.put("platformName", "Android");
        requested.put("appium:app", "/apps/demo.apk");
        TestSession session = proxy.getNewSession(requested);
        GridRegistry registry = ServletStubs.stub(GridRegistry.class,
                (method, args) -> "getActiveSessions".equals(method) ? Set.of(session) : null);

        ServletStubs.Response response = new ServletStubs.Response();
        new AllSessionsServlet(registry).doGet(ServletStubs.request(), response.servletResponse);

        Assert.assertEquals(response.calls.get("setStatus"), 200);
        JsonNode sessions = new ObjectMapper().readTree(response.body()).get("value");
        Assert.assertEquals(sessions.size(), 1);
        // no Appium session id yet: the internal id of the hub
        Assert.assertEquals(sessions.get(0).get("id").asText(), session.getInternalKey());
        JsonNode capabilities = sessions.get(0).get("capabilities");
        Assert.assertEquals(capabilities.get("udid").asText(), "udid-1", capabilities.toString());
        Assert.assertEquals(capabilities.get("deviceName").asText(), "Pixel-udid-1");
        Assert.assertEquals(capabilities.get("platformVersion").asText(), "13");
        Assert.assertEquals(capabilities.get("platformName").asText(), "ANDROID");
        Assert.assertEquals(capabilities.get("appium:app").asText(), "/apps/demo.apk");
    }

    @Test
    public void noSessions() throws Exception {
        GridRegistry registry = ServletStubs.stub(GridRegistry.class,
                (method, args) -> "getActiveSessions".equals(method) ? Set.of() : null);
        ServletStubs.Response response = new ServletStubs.Response();

        new AllSessionsServlet(registry).doGet(ServletStubs.request(), response.servletResponse);

        Assert.assertEquals(response.body(), "{\"value\":[]}");
    }
}
