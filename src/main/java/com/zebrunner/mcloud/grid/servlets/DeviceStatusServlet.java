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
import java.util.Map;

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

        // Немного стилей для кружков и таблицы
        html.append("<style>")
                .append("table { border-collapse: collapse; width: 100%; }")
                .append("th, td { border: 1px solid #ccc; padding: 8px; text-align: left; }")
                .append(".status-free { color: green; font-weight: bold; }")
                .append(".status-busy { color: red; font-weight: bold; }")
                .append(".udid-link { cursor: pointer; color: blue; text-decoration: underline; }")
                .append("</style>");

        // Скрипт для показа capabilities в alert
        html.append("<script>")
                .append("function showCapabilities(capsJson) {")
                .append("  alert('Capabilities:\\n' + capsJson);")
                .append("}")
                .append("</script>");

        html.append("</head><body>");
        html.append("<h1>📱 Устройства и Сессии</h1>");
        html.append("<table><tr><th>UDID</th><th>Status</th><th>Session</th></tr>");

        for (RemoteProxy proxy : registry.getAllProxies()) {
            String udid = "—";
            if (!proxy.getConfig().capabilities.isEmpty()) {
                MutableCapabilities caps = proxy.getConfig().capabilities.get(0);
                Object udidObj = caps.getCapability("udid");
                if (udidObj != null) {
                    udid = udidObj.toString();
                }
            }

            TestSession activeSession = proxy.getTestSlots().stream()
                    .map(slot -> slot.getSession())
                    .filter(session -> session != null)
                    .findFirst()
                    .orElse(null);

            String sessionId = activeSession != null
                    ? activeSession.getExternalKey().getKey()
                    : "—";

            boolean isBusy = activeSession != null;
            String status = isBusy
                    ? "<span class='status-busy'>🔴 Занято</span>"
                    : "<span class='status-free'>🟢 Свободно</span>";

            // Преобразуем capabilities в красиво отформатированный JSON или строку
            List<MutableCapabilities> capsList = proxy.getConfig().capabilities;
            String capsJson = capsList.isEmpty() ? "{}" : capsList.get(0).toString();
            capsJson = capsJson.replace("\"", "\\\"").replace("\n", "\\n");
            html.append("<tr>")
                    .append("<td><span class='udid-link' onclick=\"showCapabilities('").append(capsJson).append("')\">")
                    .append(udid)
                    .append("</span></td>")
                    .append("<td>").append(status).append("</td>")
                    .append("<td>").append(sessionId).append("</td>")
                    .append("</tr>");
        }
        html.append("</table>");

        // Очередь ожидания запросов
        int numUnprocessedRequests = registry.getNewSessionRequestCount();
        html.append("<h2>Очередь ожидания</h2>");
        if (numUnprocessedRequests > 0) {
            html.append("<p>").append(numUnprocessedRequests).append(" запрос(ов) ждут свободного слота</p>");
            html.append("<ul>");
            for (DesiredCapabilities caps : registry.getDesiredCapabilities()) {
                html.append("<li>").append(caps.toString()).append("</li>");
            }
            html.append("</ul>");
        } else {
            html.append("<p>Очередь пуста</p>");
        }

        html.append("</body></html>");

        resp.getWriter().write(html.toString());
    }
}
