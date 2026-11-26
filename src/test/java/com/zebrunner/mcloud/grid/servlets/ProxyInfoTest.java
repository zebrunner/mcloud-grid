package com.zebrunner.mcloud.grid.servlets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.GridFixtures;
import org.openqa.grid.internal.GridRegistry;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;

public class ProxyInfoTest {
    private static final String NODE_1 = "http://node-1:4723";
    private static final String NODE_2 = "http://node-2:4723";

    private GridRegistry registry;

    @BeforeMethod
    public void registerNodes() {
        registry = GridFixtures.registry();
        registry.add(GridFixtures.proxy(registry, NODE_1, GridFixtures.androidNodeCaps("udid-1")));
        registry.add(GridFixtures.proxy(registry, NODE_2, GridFixtures.iosNodeCaps("udid-2")));
    }

    @AfterMethod(alwaysRun = true)
    public void stopRegistry() {
        registry.stop();
    }

    private ServletStubs.Response get(Map<String, String> parameters) throws Exception {
        ServletStubs.Response response = new ServletStubs.Response();
        new ProxyInfo(registry).doGet(ServletStubs.request(Map.of(), parameters), response.servletResponse);
        Assert.assertEquals(response.calls.get("setContentType"), "application/json");
        Assert.assertEquals(response.calls.get("setStatus"), 200);
        return response;
    }

    @Test
    public void answersWhetherTheNodeOfTheIdIsRegistered() throws Exception {
        String body = get(Map.of("id", NODE_1)).body();

        JsonNode json = new ObjectMapper().readTree(body);
        Assert.assertTrue(json.get("success").asBoolean(), body);
        Assert.assertEquals(json.get("id").asText(), NODE_1);
        Assert.assertEquals(json.get("msg").asText(), "The node is registered.");
        Assert.assertEquals(json.size(), 3, body);
        for (String detail : new String[] {"udid-1", "Pixel", "ANDROID", "capabilities", "configuration", "request", NODE_2}) {
            Assert.assertFalse(body.contains(detail), "'" + detail + "' in " + body);
        }
    }

    @Test
    public void anUnknownIdIsNotRegistered() throws Exception {
        for (String id : new String[] {"none", NODE_1 + "/wd/hub", "http://node-1:4724", "node-1"}) {
            String body = get(Map.of("id", id)).body();
            JsonNode json = new ObjectMapper().readTree(body);
            Assert.assertFalse(json.get("success").asBoolean(), id + ": " + body);
            Assert.assertEquals(json.get("msg").asText(), "The node is not registered.");
            Assert.assertFalse(body.contains("node-2") || body.contains("udid"), body);
        }
    }

    @Test
    public void withoutAnIdNoNodeIsTold() throws Exception {
        for (Map<String, String> parameters : java.util.List.of(Map.<String, String>of(), Map.of("id", ""))) {
            String body = get(parameters).body();
            JsonNode json = new ObjectMapper().readTree(body);
            Assert.assertFalse(json.get("success").asBoolean(), body);
            Assert.assertTrue(json.get("msg").asText().contains("?id=<node id>"), body);
            Assert.assertFalse(body.contains("node-1") || body.contains("node-2"), body);
        }
    }
}
