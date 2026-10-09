package com.zebrunner.mcloud.grid.servlets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.MobileRemoteProxy;
import com.zebrunner.mcloud.grid.integration.client.STFClient;
import org.apache.commons.lang3.StringUtils;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.web.servlet.RegistryBasedServlet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Terminates the session of a device: POST /grid/admin/TerminateSessionServlet?udid=&lt;udid&gt; (or ?sessionId=&lt;Appium or hub
 * session id&gt;) with the header 'Authorization: Bearer &lt;STF access token&gt;'.
 * <p>
 * The key is checked by STF: the device is released with it first, and STF allows that only for the user the device is
 * reserved by or for an STF admin. Devices are reserved by the grid user (STF_TOKEN), so the key of a regular STF user
 * usually does not work (403) unless the session was started with that user's 'STF_TOKEN' capability; an admin key does.
 * Without STF integration there is nothing to check the key against, so the servlet is not available.
 */
public class TerminateSessionServlet extends RegistryBasedServlet {
    private static final long serialVersionUID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TerminateSessionServlet() {
        this(null);
    }

    public TerminateSessionServlet(GridRegistry registry) {
        super(registry);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!STFClient.isSTFEnabled()) {
            respond(resp, HttpServletResponse.SC_NOT_IMPLEMENTED, false,
                    "Terminating sessions needs the STF integration (STF_URL and STF_TOKEN): the key is checked by STF.", null, null);
            return;
        }
        String token = parseToken(req.getHeader("Authorization"));
        if (token.isEmpty()) {
            respond(resp, HttpServletResponse.SC_UNAUTHORIZED, false, "The header 'Authorization: Bearer <STF access token>' is required.", null, null);
            return;
        }
        String udid = req.getParameter("udid");
        String sessionId = req.getParameter("sessionId");
        if (StringUtils.isAllBlank(udid, sessionId)) {
            respond(resp, HttpServletResponse.SC_BAD_REQUEST, false, "The 'udid' or 'sessionId' parameter is required.", null, null);
            return;
        }

        for (RemoteProxy proxy : getRegistry().getAllProxies()) {
            if (!(proxy instanceof MobileRemoteProxy)) {
                continue;
            }
            MobileRemoteProxy mobileProxy = (MobileRemoteProxy) proxy;
            for (TestSlot slot : proxy.getTestSlots()) {
                TestSession session = slot.getSession();
                if (session != null && matches(mobileProxy, session, udid, sessionId)) {
                    terminate(resp, mobileProxy, session, token);
                    return;
                }
            }
        }
        respond(resp, HttpServletResponse.SC_NOT_FOUND, false, "No running session matches the request.", udid, sessionId);
    }

    /**
     * @return the key of 'Bearer &lt;key&gt;' (a bare key is accepted too), empty if there is none
     */
    static String parseToken(String header) {
        String value = StringUtils.trimToEmpty(header);
        if (value.regionMatches(true, 0, "Bearer", 0, "Bearer".length())
                && (value.length() == "Bearer".length() || Character.isWhitespace(value.charAt("Bearer".length())))) {
            return value.substring("Bearer".length()).trim();
        }
        return value;
    }

    private static boolean matches(MobileRemoteProxy proxy, TestSession session, String udid, String sessionId) {
        if (StringUtils.isNotBlank(udid) && !udid.equals(proxy.getUdid())) {
            return false;
        }
        return StringUtils.isBlank(sessionId)
                || sessionId.equals(session.getInternalKey())
                || session.getExternalKey() != null && sessionId.equals(session.getExternalKey().getKey());
    }

    private static void terminate(HttpServletResponse resp, MobileRemoteProxy proxy, TestSession session, String token) throws IOException {
        String sessionId = session.getExternalKey() != null ? session.getExternalKey().getKey() : session.getInternalKey();
        int status = proxy.terminateSession(session, token);
        switch (status) {
        case 200:
            respond(resp, HttpServletResponse.SC_OK, true, "The session is terminated and the device is released.", proxy.getUdid(), sessionId);
            break;
        case 401:
            respond(resp, HttpServletResponse.SC_UNAUTHORIZED, false, "STF did not accept the key.", proxy.getUdid(), sessionId);
            break;
        case 403:
            respond(resp, HttpServletResponse.SC_FORBIDDEN, false, "STF did not allow releasing the device with this key: it must belong to the user "
                    + "the device is reserved by or to an STF admin. The session keeps running.", proxy.getUdid(), sessionId);
            break;
        default:
            respond(resp, HttpServletResponse.SC_BAD_GATEWAY, false, "STF did not release the device (HTTP " + status + "). The session keeps running.",
                    proxy.getUdid(), sessionId);
            break;
        }
    }

    private static void respond(HttpServletResponse resp, int status, boolean success, String message, String udid, String sessionId)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", success);
        body.put("message", message);
        body.put("udid", udid);
        body.put("sessionId", sessionId);
        String json = MAPPER.writeValueAsString(body);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);
        resp.getWriter().write(json);
    }
}
