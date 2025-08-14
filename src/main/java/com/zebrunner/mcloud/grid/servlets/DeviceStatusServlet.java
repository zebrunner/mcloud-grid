package com.zebrunner.mcloud.grid.servlets;

import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.remote.DesiredCapabilities;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

public class DeviceStatusServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        GridRegistry registry = (GridRegistry) getServletContext()
                .getAttribute("org.openqa.grid.internal.GridRegistry");

        if (registry == null) {
            resp.sendError(500, "GridRegistry not found in servlet context");
            return;
        }

        resp.setContentType("text/html;charset=UTF-8");

        StringBuilder html = new StringBuilder();
        html.append("<html><head><title>📱 Device Status</title>");

        // Styles
        html.append("<style>")
                .append("body { font-family: sans-serif; }")
                .append("table { border-collapse: collapse; width: 100%; margin-top: 10px; }")
                .append("th, td { border: 1px solid #ccc; padding: 8px; text-align: left; }")
                .append(".status-free { color: green; font-weight: bold; }")
                .append(".status-busy { color: red; font-weight: bold; }")
                .append(".udid-link { cursor: pointer; color: blue; text-decoration: underline; }")
                .append("#controls { margin-top: 10px; margin-bottom: 10px; }")
                .append("</style>");

        // Scripts: auto-refresh, show caps, filtering
        html.append("<script>")
                // Auto-refresh logic
                .append("let refreshInterval;")
                .append("function toggleAutoRefresh(checkbox) {")
                .append("  if (checkbox.checked) {")
                .append("    refreshInterval = setInterval(() => window.location.reload(), 5000);")
                .append("  } else {")
                .append("    clearInterval(refreshInterval);")
                .append("  }")
                .append("}")

                // Show capabilities
                .append("function showCapabilities(capsJson) {")
                .append("  alert('Capabilities:\\n' + capsJson);")
                .append("}")

                // Filter table
                .append("function filterTable() {")
                .append("  const filter = document.getElementById('filterInput').value.toLowerCase();")
                .append("  const rows = document.querySelectorAll('#deviceTable tbody tr');")
                .append("  rows.forEach(row => {")
                .append("    const text = row.innerText.toLowerCase();")
                .append("    row.style.display = text.includes(filter) ? '' : 'none';")
                .append("  });")
                .append("}")
                .append("</script>");

        html.append("</head><body>");
        html.append("<h1>📱 Devices & Sessions</h1>");

        // Controls
        html.append("<div id='controls'>")
                .append("<label><input type='checkbox' onchange='toggleAutoRefresh(this)'> Auto-refresh (5s)</label>")
                .append("&nbsp;&nbsp;&nbsp;")
                .append("<label>Filter: <input type='text' id='filterInput' onkeyup='filterTable()' placeholder='Enter UDID or Status'></label>")
                .append("</div>");

        html.append("<table id='deviceTable'><thead><tr><th>UDID</th><th>Status</th><th>Session ID</th><th>Session Start</th></tr></thead><tbody>");

        for (RemoteProxy proxy : registry.getAllProxies()) {
            String udid = "—";
            String sessionId = "—";
            String sessionStart = "—";
            boolean isBusy = false;

            // Extract UDID
            if (!proxy.getConfig().capabilities.isEmpty()) {
                MutableCapabilities caps = proxy.getConfig().capabilities.get(0);
                Object udidObj = caps.getCapability("udid");
                if (udidObj != null) {
                    udid = udidObj.toString();
                }
            }

            // Check for active session
            TestSession activeSession = proxy.getTestSlots().stream()
                    .map(slot -> slot.getSession())
                    .filter(session -> session != null)
                    .findFirst()
                    .orElse(null);

            if (activeSession != null) {
                isBusy = true;
                sessionId = activeSession.getExternalKey() != null
                        ? activeSession.getExternalKey().getKey()
                        : "—";
                long startTime = activeSession.getInactivityTime(); // ms
                sessionStart = startTime > 0 ? (System.currentTimeMillis() - startTime) / 1000 + "s ago" : "—";
            }

            String status = isBusy
                    ? "<span class='status-busy'>🔴 Busy</span>"
                    : "<span class='status-free'>🟢 Free</span>";

            // Capabilities JSON
            List<MutableCapabilities> capsList = proxy.getConfig().capabilities;
            String capsJson = capsList.isEmpty() ? "{}" : capsList.get(0).toString();
            capsJson = capsJson.replace("\"", "\\\"").replace("\n", "\\n");

            html.append("<tr>")
                    .append("<td><span class='udid-link' onclick=\"showCapabilities('").append(capsJson).append("')\">")
                    .append(udid)
                    .append("</span></td>")
                    .append("<td>").append(status).append("</td>")
                    .append("<td>").append(sessionId).append("</td>")
                    .append("<td>").append(sessionStart).append("</td>")
                    .append("</tr>");
        }
        html.append("</tbody></table>");

        // Request queue
        int numUnprocessedRequests = registry.getNewSessionRequestCount();
        html.append("<h2>Pending Requests</h2>");
        if (numUnprocessedRequests > 0) {
            html.append("<p>").append(numUnprocessedRequests).append(" request(s) waiting for a free slot</p>");
            html.append("<ul>");
            for (DesiredCapabilities caps : registry.getDesiredCapabilities()) {
                html.append("<li>").append(caps.toString()).append("</li>");
            }
            html.append("</ul>");
        } else {
            html.append("<p>The queue is empty.</p>");
        }

        html.append("</body></html>");

        resp.getWriter().write(html.toString());
    }
}
