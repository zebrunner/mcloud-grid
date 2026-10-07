package com.zebrunner.mcloud.grid.servlets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.GridFixtures;
import org.openqa.grid.internal.GridRegistry;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;


public class ProxyInfoTest {

    @Test
    public void returnsRegistrationRequestsOfAllProxies() throws Exception {
        GridRegistry registry = GridFixtures.registry();
        registry.add(GridFixtures.proxy(registry, "http://node-1:4723", GridFixtures.androidNodeCaps("udid-1")));
        registry.add(GridFixtures.proxy(registry, "http://node-2:4723", GridFixtures.iosNodeCaps("udid-2")));

        StringWriter body = new StringWriter();
        Map<String, Object> calls = new HashMap<>();
        PrintWriter writer = new PrintWriter(body);
        HttpServletResponse response = stub(HttpServletResponse.class, (method, args) -> {
            if ("getWriter".equals(method)) {
                return writer;
            }
            if (args != null && args.length == 1) {
                calls.put(method, args[0]);
            }
            return null;
        });

        new ProxyInfo(registry).doGet(stub(HttpServletRequest.class, (method, args) -> null), response);

        Assert.assertEquals(calls.get("setContentType"), "application/json");
        JsonNode json = new ObjectMapper().readTree(body.toString());
        Assert.assertTrue(json.isArray());
        Assert.assertEquals(json.size(), 2);
        Assert.assertTrue(body.toString().contains("\"remoteHost\":\"http://node-1:4723\""));
        Assert.assertTrue(body.toString().contains("\"remoteHost\":\"http://node-2:4723\""));
        registry.stop();
    }

    interface Handler {
        Object handle(String method, Object[] args);
    }

    private static <T> T stub(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> handler.handle(method.getName(), args)));
    }
}
