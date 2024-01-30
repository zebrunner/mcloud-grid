package com.zebrunner.mcloud.grid.servlets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpStatus;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.web.servlet.RegistryBasedServlet;

/**
 * Proxy Servlet<br>
 * Contains logic for sending PAC proxy configuration
 */
public class ProxyServlet extends RegistryBasedServlet {
    private static final Logger LOGGER = Logger.getLogger(ProxyServlet.class.getName());
    // URI example: https://<domain>/grid/admin/ProxyServlet/pac/<device_udid>.pac
    private static final Pattern PAC_PATTERN = Pattern.compile("pac\\/(?<udid>.+?)(\\.pac)?$");
    // Contains custom proxy configuration. Key - device udid, value - pac
    private static final Map<String, String> CUSTOM_DEVICE_PAC_PROXY_CONFIGURATION = new ConcurrentHashMap<>();
    private static final String DEFAULT_PAC_CONFIGURATION =
            "function FindProxyForURL(url,host) {"
                    + " \n return \"DIRECT\";\n"
                    + "}";

    public ProxyServlet() {
        this(null);
    }

    public ProxyServlet(GridRegistry registry) {
        super(registry);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        process(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        process(req, resp);
    }

    protected void process(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Matcher matcher = PAC_PATTERN.matcher(request.getRequestURI());
        if (matcher.find()) {
            String requestURI = request.getRequestURI();
            LOGGER.finest("Detected PAC request: " + requestURI);
            String deviceId = matcher.group("udid");
            String pacConfiguration = CUSTOM_DEVICE_PAC_PROXY_CONFIGURATION.getOrDefault(deviceId, DEFAULT_PAC_CONFIGURATION);
            LOGGER.finest("PAC proxy configuration for the ' " + deviceId + "' device: \n" + pacConfiguration);
            response.setStatus(HttpStatus.SC_OK);
            response.setContentType("application/x-ns-proxy-autoconfig");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Cache-Control", "no-cache");
            response.setCharacterEncoding("UTF-8");
            response.getWriter()
                    .write(new String(pacConfiguration.getBytes(StandardCharsets.UTF_8)));
            response.getWriter()
                    .close();
        }
    }

    public static void updatePacConfiguration(String udid, String pac) {
        CUSTOM_DEVICE_PAC_PROXY_CONFIGURATION.put(udid, pac);
    }
}
