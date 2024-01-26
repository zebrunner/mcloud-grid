package com.zebrunner.mcloud.grid.integration.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.servlets.ProxyServlet;
import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import com.zebrunner.mcloud.grid.util.HttpClient;
import com.zebrunner.mcloud.grid.util.HttpClientApache;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.openqa.grid.internal.TestSlot;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class MitmProxyClient {
    private static final Logger LOGGER = Logger.getLogger(MitmProxyClient.class.getName());
    private static final Map<String, MitmProxyClient> PROXY_CLIENTS = new ConcurrentHashMap<>();
    private static final String PROXY_PORT_CAPABILITY = "proxy_port";
    private static final String SERVER_PROXY_PORT_CAPABILITY = "server_proxy_port";

    private URL proxyURL = null;
    private Integer proxyPort = null;
    private Integer serverProxyPort = null;

    /**
     * Check is proxy initialized for the device
     *
     * @param deviceUUID device uuid
     * @return true if proxy initialized for device, false otherwise
     */
    public static boolean isProxyInitialized(String deviceUUID) {
        return PROXY_CLIENTS.containsKey(deviceUUID);
    }

    /**
     * Start proxy with default arguments
     *
     * @param deviceUUID  device uuid
     * @param proxyType   type of proxy ({@code full}, {@code simple})
     * @param sessionUUID session uuid (for logs)
     * @return true if proxy successfully started (if available), false otherwise
     */
    public static boolean start(String deviceUUID, String proxyType, String sessionUUID) {
        return start(deviceUUID, proxyType, null, sessionUUID);
    }

    /**
     * Start proxy with arguments
     *
     * @param deviceUUID  device uuid
     * @param proxyType   type of proxy ({@code full}, {@code simple})
     * @param sessionUUID session uuid (for logs)
     * @return true if proxy successfully started (if available), false otherwise
     */
    public static boolean start(String deviceUUID, String proxyType, String args, String sessionUUID) {
        MitmProxyClient client = PROXY_CLIENTS.get(deviceUUID);
        if (client == null) {
            return false;
        }
        if (!StringUtils.equalsAny(proxyType, "full", "simple")) {
            LOGGER.warning(
                    () -> String.format("[NODE-%s] Invalid proxy type: '%s'.", sessionUUID, proxyType));
            return false;
        }

        String proxyArguments = StringUtils.isBlank(args) ? StringUtils.EMPTY : args;
        Map<String, String> proxy = Map.of("proxyType", proxyType, "proxyArgs", proxyArguments);
        boolean isStarted = false;
        try {
            HttpClient.Response<String> mitmResponse = HttpClientApache.create()
                    .withUri(Path.PROXY_RESTART, client.proxyURL.toString())
                    .post(new StringEntity(new ObjectMapper().writeValueAsString(proxy), ContentType.APPLICATION_JSON));
            if (mitmResponse.getStatus() != 200) {
                LOGGER.warning(() -> String.format("[NODE-%s] Could not start proxy. Response code: %s, %s",
                        sessionUUID, mitmResponse.getStatus(), mitmResponse.getObject()));
            } else {
                isStarted = true;
            }
        } catch (Exception e) {
            LOGGER.warning(() -> String.format("[NODE-%s]  Could not start proxy. Exception: %s, %s", sessionUUID, e.getMessage(), e));
        }
        return isStarted;
    }

    /**
     * Init proxy client for device
     *
     * @param slots {@link List} of {@link TestSlot}s
     */
    public static void initProxy(List<TestSlot> slots) {
        if (slots.isEmpty()) {
            LOGGER.warning(() -> "Could not init proxy. Could not find any test slot.");
        }
        TestSlot slot = slots.get(0);
        Map<String, Object> slotCapabilities = slot.getCapabilities();
        Integer proxyPort = CapabilityUtils.getZebrunnerCapability(slotCapabilities, PROXY_PORT_CAPABILITY)
                .map(String::valueOf)
                .filter(NumberUtils::isParsable)
                .map(Integer::parseInt)
                .orElse(null);
        Integer serverProxyPort = CapabilityUtils.getZebrunnerCapability(slotCapabilities, SERVER_PROXY_PORT_CAPABILITY)
                .map(String::valueOf)
                .filter(NumberUtils::isParsable)
                .map(Integer::parseInt)
                .orElse(null);

        // validate proxy capabilities
        if ((proxyPort != null && proxyPort > 0) || (serverProxyPort != null && serverProxyPort > 0)) {
            if ((proxyPort == null || proxyPort <= 0)) {
                LOGGER.warning(() -> String.format("Invalid device configuration. 'server_proxy_port' initialized, but 'proxy_port' missed: %s",
                        slotCapabilities));
                return;
            }
            if ((serverProxyPort == null || serverProxyPort <= 0)) {
                LOGGER.warning(() -> String.format("Invalid device configuration. 'proxy_port' initialized, but 'server_proxy_port' missed: %s",
                        slotCapabilities));
                return;
            }
        } else {
            return;
        }

        String udid = String.valueOf(CapabilityUtils.getAppiumCapability(slotCapabilities, "udid").orElse(""));
        if (StringUtils.isBlank(udid)) {
            LOGGER.warning(() -> String.format("Appium node must have 'UDID' capability to be identified in STF: %s", slotCapabilities));
            return;
        }
        LOGGER.info(() -> String.format("Detected '%s' device with 'proxy_port=%s' and 'server_proxy_port=%s' capabilities.",
                CapabilityUtils.getAppiumCapability(slotCapabilities, "deviceName").orElse(""),
                proxyPort,
                serverProxyPort));

        URL remoteURL = slot.getRemoteURL();
        String pac = String.format("function FindProxyForURL(url,host) {"
                + " \n return \"PROXY %s:%s\";\n"
                + "}", remoteURL.getHost(), proxyPort);

        MitmProxyClient client = new MitmProxyClient();
        client.setProxyPort(proxyPort);
        client.setServerProxyPort(serverProxyPort);
        try {
            client.setProxyURL(new URL(remoteURL.getProtocol(), remoteURL.getHost(), serverProxyPort, ""));
        } catch (Exception e) {
            LOGGER.warning(() -> String.format("Could not init url for proxy client. Exception: %s -  %s", e.getClass(), e.getMessage()));
            return;
        }
        ProxyServlet.updatePacConfiguration(udid, pac);
        PROXY_CLIENTS.put(udid, client);
    }

    public URL getProxyURL() {
        return proxyURL;
    }

    public void setProxyURL(URL proxyURL) {
        this.proxyURL = proxyURL;
    }

    public Integer getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(Integer proxyPort) {
        this.proxyPort = proxyPort;
    }

    public Integer getServerProxyPort() {
        return serverProxyPort;
    }

    public void setServerProxyPort(Integer serverProxyPort) {
        this.serverProxyPort = serverProxyPort;
    }
}
