package com.zebrunner.mcloud.grid.servlets;

import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.web.servlet.RegistryBasedServlet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Grid metrics in the Prometheus text format: GET /grid/admin/MetricsServlet.
 */
public class MetricsServlet extends RegistryBasedServlet {
    private static final long serialVersionUID = 1L;
    private static final String[] STATUSES = {"free", "busy", "ignored", "down"};

    public MetricsServlet() {
        this(null);
    }

    public MetricsServlet(GridRegistry registry) {
        super(registry);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // platform -> status -> count
        Map<String, Map<String, Integer>> devices = new TreeMap<>();
        for (RemoteProxy proxy : getRegistry().getAllProxies()) {
            for (TestSlot slot : proxy.getTestSlots()) {
                Map<String, Object> device = DevicesServlet.describe(proxy, slot);
                String platform = String.valueOf(device.get("platformName")).toUpperCase();
                devices.computeIfAbsent(platform, p -> new TreeMap<>()).merge(String.valueOf(device.get("status")), 1, Integer::sum);
            }
        }

        StringBuilder metrics = new StringBuilder();
        metrics.append("# HELP mcloud_grid_devices Devices registered in the grid by platform and status.\n")
                .append("# TYPE mcloud_grid_devices gauge\n");
        devices.forEach((platform, byStatus) -> {
            for (String status : STATUSES) {
                metrics.append("mcloud_grid_devices{platform=\"").append(escape(platform)).append("\",status=\"").append(status).append("\"} ")
                        .append(byStatus.getOrDefault(status, 0)).append('\n');
            }
        });
        metrics.append("# HELP mcloud_grid_sessions Sessions running in the grid.\n")
                .append("# TYPE mcloud_grid_sessions gauge\n")
                .append("mcloud_grid_sessions ").append(getRegistry().getActiveSessions().size()).append('\n')
                .append("# HELP mcloud_grid_new_session_requests New session requests waiting for a device.\n")
                .append("# TYPE mcloud_grid_new_session_requests gauge\n")
                .append("mcloud_grid_new_session_requests ").append(getRegistry().getNewSessionRequestCount()).append('\n');

        resp.setContentType("text/plain; version=0.0.4");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(metrics.toString());
    }

    private static String escape(String label) {
        return label.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
