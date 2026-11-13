package com.zebrunner.mcloud.grid.servlets;

import com.zebrunner.mcloud.grid.GridFixtures;
import com.zebrunner.mcloud.grid.IgnoredDevices;
import com.zebrunner.mcloud.grid.MobileRemoteProxy;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.ProxySet;
import org.openqa.grid.internal.TestSession;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MetricsServletTest {

    @AfterMethod(alwaysRun = true)
    public void clear() {
        IgnoredDevices.clear();
    }

    @Test
    public void devicesByPlatformAndStatusAndQueue() throws Exception {
        GridRegistry registry = GridFixtures.registry();
        MobileRemoteProxy busy = GridFixtures.proxy(registry, "http://node-1:4723", GridFixtures.androidNodeCaps("a-1"));
        registry.add(busy);
        registry.add(GridFixtures.proxy(registry, "http://node-2:4723", GridFixtures.androidNodeCaps("a-2")));
        registry.add(GridFixtures.proxy(registry, "http://node-3:4723", GridFixtures.androidNodeCaps("a-3")));
        registry.add(GridFixtures.proxy(registry, "http://node-4:4723", GridFixtures.iosNodeCaps("i-1")));
        Map<String, Object> requested = new HashMap<>();
        requested.put("platformName", "Android");
        Assert.assertNotNull(busy.getNewSession(requested));
        IgnoredDevices.ignore("a-3", Duration.ofMinutes(1), "Appium status check failed");

        ServletStubs.Response response = new ServletStubs.Response();
        new MetricsServlet(registry).doGet(ServletStubs.request(), response.servletResponse);
        String metrics = response.body();

        Assert.assertEquals(response.calls.get("setStatus"), 200);
        Assert.assertEquals(response.calls.get("setContentType"), "text/plain; version=0.0.4");
        Assert.assertTrue(metrics.contains("mcloud_grid_devices{platform=\"ANDROID\",status=\"free\"} 1\n"), metrics);
        Assert.assertTrue(metrics.contains("mcloud_grid_devices{platform=\"ANDROID\",status=\"busy\"} 1\n"), metrics);
        Assert.assertTrue(metrics.contains("mcloud_grid_devices{platform=\"ANDROID\",status=\"ignored\"} 1\n"), metrics);
        Assert.assertTrue(metrics.contains("mcloud_grid_devices{platform=\"ANDROID\",status=\"down\"} 0\n"), metrics);
        Assert.assertTrue(metrics.contains("mcloud_grid_devices{platform=\"IOS\",status=\"free\"} 1\n"
                + "mcloud_grid_devices{platform=\"IOS\",status=\"busy\"} 0\n"
                + "mcloud_grid_devices{platform=\"IOS\",status=\"ignored\"} 0\n"
                + "mcloud_grid_devices{platform=\"IOS\",status=\"down\"} 0\n"), metrics);
        // the session of the device is not started through the queue of the registry
        Assert.assertTrue(metrics.contains("# TYPE mcloud_grid_sessions gauge\nmcloud_grid_sessions 0\n"), metrics);
        Assert.assertTrue(metrics.contains("mcloud_grid_new_session_requests 0\n"), metrics);
        registry.stop();
    }

    @Test
    public void sessionsAndQueueAreCounted() throws Exception {
        GridRegistry grid = GridFixtures.registry();
        Map<String, Object> requested = new HashMap<>();
        requested.put("platformName", "Android");
        TestSession first = GridFixtures.proxy(grid, "http://node-1:4723", GridFixtures.androidNodeCaps("a-1")).getNewSession(requested);
        TestSession second = GridFixtures.proxy(grid, "http://node-2:4723", GridFixtures.androidNodeCaps("a-2")).getNewSession(requested);
        GridRegistry registry = ServletStubs.stub(GridRegistry.class, (method, args) -> {
            switch (method) {
            case "getAllProxies":
                return new ProxySet(false);
            case "getActiveSessions":
                return Set.of(first, second);
            case "getNewSessionRequestCount":
                return 3;
            default:
                return null;
            }
        });

        ServletStubs.Response response = new ServletStubs.Response();
        new MetricsServlet(registry).doGet(ServletStubs.request(), response.servletResponse);

        Assert.assertEquals(response.body(), "# HELP mcloud_grid_devices Devices registered in the grid by platform and status.\n"
                + "# TYPE mcloud_grid_devices gauge\n"
                + "# HELP mcloud_grid_sessions Sessions running in the grid.\n"
                + "# TYPE mcloud_grid_sessions gauge\n"
                + "mcloud_grid_sessions 2\n"
                + "# HELP mcloud_grid_new_session_requests New session requests waiting for a device.\n"
                + "# TYPE mcloud_grid_new_session_requests gauge\n"
                + "mcloud_grid_new_session_requests 3\n");
        grid.stop();
    }
}
