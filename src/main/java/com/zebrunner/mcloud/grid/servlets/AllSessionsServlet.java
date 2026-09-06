package com.zebrunner.mcloud.grid.servlets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.web.servlet.RegistryBasedServlet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

public class AllSessionsServlet extends RegistryBasedServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public AllSessionsServlet() { this(null); }
    public AllSessionsServlet(org.openqa.grid.internal.GridRegistry registry) { super(registry); }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (TestSession session : getRegistry().getActiveSessions()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", session.getExternalKey() != null
                ? session.getExternalKey().getKey()
                : session.getInternalKey());
            entry.put("capabilities", flattenCapabilities(session.getRequestedCapabilities()));
            sessions.add(entry);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("value", sessions);

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(MAPPER.writeValueAsString(result));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenCapabilities(Map<String, Object> requested) {
        Map<String, Object> merged = new LinkedHashMap<>(requested);

        Object slotCapsObj = requested.get("zebrunner:slotCapabilities");
        if (slotCapsObj instanceof Map) {
            Map<String, Object> slotCaps = (Map<String, Object>) slotCapsObj;
            merged.put("platformName", slotCaps.get("platformName"));
            merged.put("platformVersion", slotCaps.get("platformVersion"));
            merged.put("automationName", slotCaps.get("automationName"));
            merged.put("deviceName", slotCaps.get("deviceName"));
            merged.put("udid", slotCaps.get("udid"));
        }

        return merged;
    }
}