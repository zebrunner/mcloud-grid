package com.zebrunner.mcloud.grid.servlets;

import com.zebrunner.mcloud.grid.GridFixtures;
import com.zebrunner.mcloud.grid.MobileRemoteProxy;
import com.zebrunner.mcloud.grid.util.NodeRegistrationToken;
import org.openqa.grid.internal.ExternalSessionKey;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.TestSession;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

public class PlaywrightTargetTest {
    private GridRegistry registry;
    private MobileRemoteProxy android;

    @BeforeMethod
    public void setUp() {
        registry = GridFixtures.registry();
        android = GridFixtures.proxy(registry, "http://node-1:4723", GridFixtures.androidNodeCaps("udid-1"));
        registry.add(android);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        registry.stop();
    }

    private TestSession session() throws InterruptedException {
        Map<String, Object> requested = new HashMap<>();
        requested.put("platformName", "Android");
        TestSession session = android.getNewSession(requested);
        session.setExternalKey(new ExternalSessionKey("appium-session-1"));
        // an inactive session
        Thread.sleep(200);
        return session;
    }

    @Test
    public void theWebSocketOfTheDriverOnTheNodeWithItsToken() throws Exception {
        PlaywrightServlet.Target target = PlaywrightServlet.Target.of(android, session());

        Assert.assertEquals(target.uri.toString(), "ws://node-1:4723/mcloud/playwright/appium-session-1");
        Assert.assertEquals(target.name, "udid-1");
        Map<String, String> headers = new HashMap<>();
        target.authenticate.accept(headers::put);
        Assert.assertEquals(headers, Map.of(NodeRegistrationToken.HEADER, GridFixtures.NODE_TOKEN));
    }

    @Test
    public void sessionDoesNotTimeOutWhileAWebSocketIsOpen() throws Exception {
        TestSession session = session();
        PlaywrightServlet.Target target = PlaywrightServlet.Target.of(android, session);
        Assert.assertTrue(session.getInactivityTime() >= 200);

        target.opened.run();
        target.opened.run();
        Assert.assertEquals(session.getInactivityTime(), 0);
        target.closed.run();
        Assert.assertEquals(session.getInactivityTime(), 0, "another WebSocket of the session is open");
        target.closed.run();
        // the inactivity counts again from the close of the last WebSocket
        Assert.assertTrue(session.getInactivityTime() < 200);
    }
}
