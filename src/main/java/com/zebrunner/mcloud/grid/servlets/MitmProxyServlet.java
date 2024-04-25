package com.zebrunner.mcloud.grid.servlets;

import com.zebrunner.mcloud.grid.MobileRemoteProxy;
import com.zebrunner.mcloud.grid.integration.client.MitmProxyClient;
import com.zebrunner.mcloud.grid.integration.client.Path;
import com.zebrunner.mcloud.grid.util.HttpClient;
import com.zebrunner.mcloud.grid.util.HttpClientApache;
import org.apache.commons.lang3.StringUtils;
import org.openqa.grid.internal.ExternalSessionKey;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.web.servlet.RegistryBasedServlet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MitmProxyServlet extends RegistryBasedServlet {
    private static final Logger LOGGER = Logger.getLogger(MitmProxyServlet.class.getName());
    private static final String PROXY_PAC_CONFIGURATION = "function FindProxyForURL(url, host) { return 'PROXY %s:%s'; }";
    private static final String SESSION_ID_GROUP = "sessionId";
    private static final String FLOW_GROUP = "flow";
    private static final String DEVICE_UDID = "deviceUdid";
    private static final Pattern PAC_PATTERN = Pattern.compile(
            "^\\/proxy\\/(?<" + DEVICE_UDID + ">[a-zA-Z-0-9][^\\/]*)\\.pac.?");
    private static final Pattern HAR_DOWNLOAD_PATTERN = Pattern.compile(
            "^\\/proxy\\/(?<" + SESSION_ID_GROUP + ">[a-zA-Z-0-9][^\\/]*)\\/download\\/har\\/(?<" + FLOW_GROUP + ">[a-zA-Z-0-9@][^\\/]*)$");
    private static final Pattern DUMP_DOWNLOAD_PATTERN = Pattern.compile(
            "^\\/proxy\\/(?<" + SESSION_ID_GROUP + ">[a-zA-Z-0-9][^\\/]*)\\/download\\/dump\\/(?<" + FLOW_GROUP + ">[a-zA-Z-0-9@][^\\/]*)$");
    private static final Pattern CLEAR_FLOW_PATTERN = Pattern.compile(
            "^\\/proxy\\/(?<" + SESSION_ID_GROUP + ">[a-zA-Z-0-9][^\\/]*)\\/clear-flows$");
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile(
            "^\\/proxy\\/(?<" + SESSION_ID_GROUP + ">[a-zA-Z-0-9][^\\/]*)\\/.+$");

    public MitmProxyServlet() {
        this(null);
    }

    public MitmProxyServlet(GridRegistry registry) {
        super(registry);
    }

    @Override
    @SuppressWarnings("squid:S1989")
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        process(request, response);
    }

    @Override
    @SuppressWarnings("squid:S1989")
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        process(req, resp);
    }

    @Override
    @SuppressWarnings("squid:S1989")
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        process(req, resp);
    }

    protected void process(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Matcher pacMatcher = PAC_PATTERN.matcher(request.getRequestURI());
        if (pacMatcher.matches()) {
            try {
                String udid = pacMatcher.group(DEVICE_UDID);
                LOGGER.warning(() -> String.format("[PAC] Request for '%s' device.", udid));
                MitmProxyClient client = MitmProxyClient.getProxyClient(udid);
                if (client == null) {
                    LOGGER.warning(() -> String.format("[PAC] The '%s' device is not registered in the grid or proxy is not enabled for it.", udid));
                    response.sendError(404, String.format("The '%s' device is not registered in the grid or proxy is not enabled for it,"
                            + "so we cannot provide pac proxy configuration.", udid));
                    return;
                }
                response.setStatus(200);
                response.setContentType("application/x-ns-proxy-autoconfig");
                String pac = String.format(PROXY_PAC_CONFIGURATION, client.getRemoteHost(), client.getProxyPort());
                response.setContentLengthLong(StringUtils.length(pac));
                try (PrintWriter writer = response.getWriter()) {
                    writer.write(pac);
                }
            } catch (IOException e) {
                throw e;
            } catch (Throwable e) {
                LOGGER.warning(() -> String.format("[CRITICAL] [PAC] cannot send for '%s' request: %s", request.getRequestURI(), e.getMessage()));
            }
            return;
        }

        try {
            Matcher harMatcher = HAR_DOWNLOAD_PATTERN.matcher(request.getRequestURI());
            Matcher dumpMatcher = DUMP_DOWNLOAD_PATTERN.matcher(request.getRequestURI());
            Matcher clearMatcher = CLEAR_FLOW_PATTERN.matcher(request.getRequestURI());
            if (!(harMatcher.matches() || dumpMatcher.matches() || clearMatcher.matches())) {
                response.sendError(500, "Invalid proxy URI.");
                return;
            }
            Matcher sessionIdMatcher = SESSION_ID_PATTERN.matcher(request.getRequestURI());
            sessionIdMatcher.matches();
            String sessionId = sessionIdMatcher.group(SESSION_ID_GROUP);
            TestSession session = getRegistry().getExistingSession(new ExternalSessionKey(sessionId));
            if (session == null) {
                response.sendError(500);
                return;
            }

            String udid = ((MobileRemoteProxy) session.getSlot().getProxy()).getUdid();
            MitmProxyClient client = MitmProxyClient.getProxyClient(udid);
            if (client == null) {
                response.sendError(500);
                return;
            }

            if (harMatcher.matches()) {
                HttpClient.Response<String> mitmResponse = HttpClientApache.create()
                        .withUri(Path.PROXY_DOWNLOAD_HAR,
                                client.getServerProxyURL().toString(),
                                harMatcher.group(FLOW_GROUP))
                        .get();
                if (mitmResponse.getStatus() != 200) {
                    response.sendError(500, String.format("Error downloading har: %s", mitmResponse.getStatus()));
                    return;
                }
                try (PrintWriter writer = response.getWriter()) {
                    writer.write(mitmResponse.getObject());
                }
            } else if (dumpMatcher.matches()) {
                HttpClient.Response<String> mitmResponse = HttpClientApache.create()
                        .withUri(Path.PROXY_DOWNLOAD_DUMP,
                                client.getServerProxyURL().toString(),
                                dumpMatcher.group(FLOW_GROUP))
                        .get();
                if (mitmResponse.getStatus() != 200) {
                    response.sendError(500,
                            String.format("Error downloading dump: %s", mitmResponse.getStatus()));
                    return;
                }
                try (PrintWriter writer = response.getWriter()) {
                    writer.write(mitmResponse.getObject());
                }
            } else if (clearMatcher.matches()) {
                HttpClient.Response<String> mitmResponse = HttpClientApache.create()
                        .withUri(Path.PROXY_CLEAR_FLOWS,
                                client.getServerProxyURL().toString())
                        .delete();
                if (mitmResponse.getStatus() != 200) {
                    response.sendError(500,
                            String.format("Error clear proxy flows: %s", mitmResponse.getStatus()));
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.warning(() -> String.format("[CRITICAL] [PROXY] %s:  %s", e.getClass().getSimpleName(), e.getMessage()));
            response.sendError(500);
        }
    }
}
