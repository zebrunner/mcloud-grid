package com.zebrunner.mcloud.grid.servlets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.GridFixtures;
import org.openqa.grid.internal.GridRegistry;
import org.testng.Assert;
import org.testng.annotations.Test;



public class ProxyInfoTest {

    @Test
    public void returnsRegistrationRequestsOfAllProxies() throws Exception {
        GridRegistry registry = GridFixtures.registry();
        registry.add(GridFixtures.proxy(registry, "http://node-1:4723", GridFixtures.androidNodeCaps("udid-1")));
        registry.add(GridFixtures.proxy(registry, "http://node-2:4723", GridFixtures.iosNodeCaps("udid-2")));

        ServletStubs.Response response = new ServletStubs.Response();

        new ProxyInfo(registry).doGet(ServletStubs.request(), response.servletResponse);

        Assert.assertEquals(response.calls.get("setContentType"), "application/json");
        Assert.assertEquals(response.calls.get("setStatus"), 200);
        String body = response.body();
        JsonNode json = new ObjectMapper().readTree(body);
        Assert.assertTrue(json.isArray());
        Assert.assertEquals(json.size(), 2);
        Assert.assertTrue(body.contains("\"remoteHost\":\"http://node-1:4723\""));
        Assert.assertTrue(body.contains("\"remoteHost\":\"http://node-2:4723\""));
        JsonNode capabilities = null;
        for (JsonNode proxy : json) {
            if ("http://node-1:4723".equals(proxy.get("configuration").get("remoteHost").asText())) {
                capabilities = proxy.get("configuration").get("capabilities").get(0);
            }
        }
        Assert.assertNotNull(capabilities);
        Assert.assertNotNull(capabilities.get("appium:udid"), "capability values are serialized: " + capabilities);
        Assert.assertEquals(capabilities.get("appium:udid").asText(), "udid-1");
        Assert.assertEquals(capabilities.get("platformName").asText(), "ANDROID");
        Assert.assertEquals(capabilities.get("appium:deviceName").asText(), "Pixel-udid-1");
        registry.stop();
    }
}
