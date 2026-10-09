package com.zebrunner.mcloud.grid.servlets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.IgnoredDevices;
import com.zebrunner.mcloud.grid.MobileRemoteProxy;
import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.web.servlet.RegistryBasedServlet;
import org.openqa.selenium.remote.CapabilityType;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Devices of the grid and what they are doing: GET /grid/admin/DevicesServlet returns
 * {"value": [{"udid", "deviceName", "platformName", "platformVersion", "deviceType", "node",
 * "status": "free|busy|ignored|down", "ignored": {"reason", "until", "secondsLeft"},
 * "session": {"id", "internalId", "startedAt", "inactivitySeconds", "lastCommand"}}]}.
 * "ignored" is set while the device is excluded from automation, "session" while it runs one.
 */
public class DevicesServlet extends RegistryBasedServlet {
    private static final long serialVersionUID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public DevicesServlet() {
        this(null);
    }

    public DevicesServlet(GridRegistry registry) {
        super(registry);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<Map<String, Object>> devices = new ArrayList<>();
        for (RemoteProxy proxy : getRegistry().getAllProxies()) {
            for (TestSlot slot : proxy.getTestSlots()) {
                devices.add(describe(proxy, slot));
            }
        }
        devices.sort(Comparator.comparing(device -> String.valueOf(device.get("deviceName"))));
        String body = MAPPER.writeValueAsString(Map.of("value", devices));

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(body);
    }

    public static Map<String, Object> describe(RemoteProxy proxy, TestSlot slot) {
        Map<String, Object> capabilities = slot.getCapabilities();
        String udid = proxy instanceof MobileRemoteProxy
                ? ((MobileRemoteProxy) proxy).getUdid()
                : CapabilityUtils.getAppiumCapability(capabilities, "udid").map(String::valueOf).orElse(null);

        Map<String, Object> device = new LinkedHashMap<>();
        device.put("udid", udid);
        device.put("deviceName", CapabilityUtils.getAppiumCapability(capabilities, "deviceName").orElse(null));
        device.put("platformName", capabilities.get(CapabilityType.PLATFORM_NAME));
        device.put("platformVersion", CapabilityUtils.getAppiumCapability(capabilities, "platformVersion").orElse(null));
        device.put("deviceType", CapabilityUtils.getZebrunnerCapability(capabilities, "deviceType")
                .map(String::valueOf)
                .map(DevicesServlet::normalizeDeviceType)
                .orElse(null));
        device.put("node", String.valueOf(proxy.getRemoteHost()));

        Optional<IgnoredDevices.Entry> ignored = udid == null ? Optional.empty() : IgnoredDevices.get(udid);
        TestSession session = slot.getSession();
        String status;
        if (proxy instanceof MobileRemoteProxy && ((MobileRemoteProxy) proxy).isDown()) {
            status = "down";
        } else if (session != null) {
            status = "busy";
        } else if (ignored.isPresent()) {
            status = "ignored";
        } else {
            status = "free";
        }
        device.put("status", status);

        device.put("ignored", ignored.map(entry -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("reason", entry.getReason());
            info.put("until", entry.getUntil().toString());
            info.put("secondsLeft", entry.secondsLeft());
            return info;
        }).orElse(null));

        if (session != null) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", session.getExternalKey() == null ? null : session.getExternalKey().getKey());
            info.put("internalId", session.getInternalKey());
            info.put("startedAt", slot.getLastSessionStart() > 0 ? Instant.ofEpochMilli(slot.getLastSessionStart()).toString() : null);
            info.put("inactivitySeconds", session.getInactivityTime() / 1000);
            info.put("lastCommand", session.get("lastCommand"));
            device.put("session", info);
        } else {
            device.put("session", null);
        }
        return device;
    }

    private static String normalizeDeviceType(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
