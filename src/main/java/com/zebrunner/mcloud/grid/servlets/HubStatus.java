package com.zebrunner.mcloud.grid.servlets;

import com.google.common.collect.ImmutableMap;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.web.servlet.RegistryBasedServlet;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;
import org.openqa.selenium.BuildInfo;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.ErrorCodes;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The W3C status of the hub, {@code /wd/hub/status} and {@code /status}, as HubW3CStatusServlet of Selenium answers it:
 * {@code ready} while a node that is not down has a free slot. The state of a node is the one of the status polling of the
 * hub (every nodePolling of the node, see DefaultRemoteProxy): the status asks no node itself. HubW3CStatusServlet asked
 * every node in the request, in the shared pool of the parallel streams of the JVM, so a status request waited for the
 * slowest node (up to 7 s each, a few at a time on a host with few CPUs). {@link #install} maps it in place of
 * HubW3CStatusServlet.
 */
public class HubStatus extends RegistryBasedServlet {
    private static final long serialVersionUID = 1L;
    static final String NAME = "HubStatus";
    static final String[] PATHS = {"/status", "/wd/hub/status"};


    public HubStatus(GridRegistry registry) {
        super(registry);
    }

    /**
     * Maps the servlet to {@link #PATHS} of the context of the hub, in place of HubW3CStatusServlet of Selenium; the context
     * may be started.
     */
    public static void install(ServletContextHandler context, GridRegistry registry) {
        HubServlets.map(context, NAME, new HubStatus(registry), PATHS);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<RemoteProxy> alive = getRegistry().getAllProxies().getSorted().stream()
                .filter(proxy -> !(proxy instanceof DefaultRemoteProxy) || !((DefaultRemoteProxy) proxy).isDown())
                .collect(Collectors.toList());
        long busy = alive.stream()
                .filter(proxy -> proxy.getMaxNumberOfConcurrentTestSessions() - proxy.getTotalUsed() <= 0)
                .count();
        boolean ready = alive.size() > busy;

        BuildInfo buildInfo = new BuildInfo();
        Map<String, Object> value = ImmutableMap.<String, Object>builder()
                .put("ready", ready)
                .put("message", ready ? "Hub has capacity" : "No spare hub capacity")
                .put("build", ImmutableMap.of(
                        "revision", buildInfo.getBuildRevision(),
                        "time", buildInfo.getBuildTime(),
                        "version", buildInfo.getReleaseLabel()))
                .put("os", ImmutableMap.of(
                        "arch", System.getProperty("os.arch"),
                        "name", System.getProperty("os.name"),
                        "version", System.getProperty("os.version")))
                .put("java", ImmutableMap.of("version", System.getProperty("java.version")))
                .build();
        byte[] payload = new Json().toJson(ImmutableMap.of("status", ErrorCodes.SUCCESS, "value", value)).getBytes(StandardCharsets.UTF_8);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Content-Type", "application/json; charset=utf-8");
        response.setHeader("Content-Length", String.valueOf(payload.length));
        try (ServletOutputStream out = response.getOutputStream()) {
            out.write(payload);
        }
    }
}
