package com.zebrunner.mcloud.grid.servlets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.web.servlet.RegistryBasedServlet;
import org.openqa.selenium.remote.CapabilityType;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Active sessions of the grid: GET /grid/admin/AllSessionsServlet returns
 * {"value": [{"id": <session id>, "capabilities": {<requested capabilities + device of the session>}}]}.
 * The id is the Appium session id, or the internal id of the hub while the session is being started.
 */
public class AllSessionsServlet extends RegistryBasedServlet {
    private static final long serialVersionUID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] DEVICE_CAPABILITIES = {"platformVersion", "automationName", "deviceName", "udid"};

    public AllSessionsServlet() {
        this(null);
    }

    public AllSessionsServlet(GridRegistry registry) {
        super(registry);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (TestSession session : getRegistry().getActiveSessions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", session.getExternalKey() != null
                    ? session.getExternalKey().getKey()
                    : session.getInternalKey());
            entry.put("capabilities", flattenCapabilities(session));
            sessions.add(entry);
        }
        String body = MAPPER.writeValueAsString(Map.of("value", sessions));

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(body);
    }

    /**
     * Requested capabilities with the platform and device of the slot the session runs on, without vendor prefixes.
     */
    private static Map<String, Object> flattenCapabilities(TestSession session) {
        Map<String, Object> merged = new LinkedHashMap<>(session.getRequestedCapabilities());
        Map<String, Object> device = session.getSlot().getCapabilities();
        if (device.get(CapabilityType.PLATFORM_NAME) != null) {
            merged.put(CapabilityType.PLATFORM_NAME, device.get(CapabilityType.PLATFORM_NAME));
        }
        for (String name : DEVICE_CAPABILITIES) {
            CapabilityUtils.getAppiumCapability(device, name).ifPresent(value -> merged.put(name, value));
        }
        return merged;
    }
}
