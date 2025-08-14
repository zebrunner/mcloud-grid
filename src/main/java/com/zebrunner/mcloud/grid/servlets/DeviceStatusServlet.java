package com.zebrunner.mcloud.grid.servlets;

import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

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
        html.append("<html><head><title>📱 Device Status</title></head><body>");
        html.append("<h1>📱 Устройства и Сессии</h1>");
        html.append("<table border='1'><tr><th>UDID</th><th>Status</th><th>Session</th></tr>");

        for (RemoteProxy proxy : registry.getAllProxies()) {
            proxy.getTestSlots().forEach(slot -> {
                String udid = "—";
                if (slot.getCapabilities().containsKey("udid")) {
                    Object udidObj = slot.getCapabilities().get("udid");
                    if (udidObj != null) {
                        udid = udidObj.toString();
                    }
                }

                TestSession activeSession = slot.getSession();
                String sessionId = activeSession != null ? activeSession.getExternalKey().getKey() : "—";
                String status = activeSession != null ? "🟢 Занят" : "⚪ Свободен";

                html.append("<tr>")
                        .append("<td>").append(udid).append("</td>")
                        .append("<td>").append(status).append("</td>")
                        .append("<td>").append(sessionId).append("</td>")
                        .append("</tr>");
            });
        }

        html.append("</table>");
        html.append("</body></html>");
        resp.getWriter().write(html.toString());
    }
}