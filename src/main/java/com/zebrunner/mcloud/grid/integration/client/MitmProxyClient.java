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
import org.openqa.selenium.json.Json;

import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class MitmProxyClient {

    private static final Logger LOGGER = Logger.getLogger(MitmProxyClient.class.getName());
    private static final Map<String, MitmProxyClient> MITM_PROXY_URLS = new ConcurrentHashMap<>();
    private static final String PROXY_PORT = "proxy_port";
    private static final String SERVER_PROXY_PORT = "server_proxy_port";

    private URL proxyURL = null;
    private Integer proxyPort = null;
    private Integer serverProxyPort = null;

    public static boolean isProxyAvailable(TestSlot slot) {
        String udid = String.valueOf(CapabilityUtils.getAppiumCapability(slot.getCapabilities(), "udid").orElse(""));
        if (StringUtils.isBlank(udid)) {
            LOGGER.warning(() -> String.format("Appium node must have 'UDID' capability to be identified in STF: %s", slot.getCapabilities()));
            return false;
        }
        return isProxyAvailable(udid);
    }

    public static boolean isProxyAvailable(String deviceUDID) {
        return MITM_PROXY_URLS.containsKey(deviceUDID);
    }

    public static boolean restartProxy(TestSlot slot, String args) {
        String udid = String.valueOf(CapabilityUtils.getAppiumCapability(slot.getCapabilities(), "udid").orElse(""));
        if (StringUtils.isBlank(udid)) {
            LOGGER.warning(() -> String.format("Appium node must have 'UDID' capability to be identified in STF: %s", slot.getCapabilities()));
            return false;
        }
        return restartProxy(udid, args);
    }

    public static boolean restartProxy(String deviceUDID, String args) {
        MitmProxyClient client = MITM_PROXY_URLS.get(deviceUDID);
        if (client == null) {
            return false;
        }

        Map<String, String> proxy = Map.of("proxyType", "simple", "proxyArgs", String.format(" %s ", args));

        try {
            HttpClient.Response<String> mitmResponse = HttpClientApache.create()
                    .withUri(Path.MITM_RESTART, client.proxyURL.toString())
                    .post(new StringEntity(new ObjectMapper().writeValueAsString(proxy),
                            ContentType.APPLICATION_JSON));
            if (mitmResponse.getStatus() != 200) {
                LOGGER.warning(
                        () -> String.format(" Could not start proxy. Response code: %s, %s", mitmResponse.getStatus(), mitmResponse.getObject()));
                return false;
            }
        } catch (Exception e) {
            LOGGER.warning(
                    () -> String.format(" Could not start proxy. Exception: %s, %s", e.getMessage(), e));
            return false;
        }
        return true;
    }

    public static void initProxy(TestSlot slot) {
        Map<String, Object> slotCapabilities = slot.getCapabilities();
        Integer proxyPort = CapabilityUtils.getZebrunnerCapability(slotCapabilities, PROXY_PORT)
                .map(String::valueOf)
                .filter(NumberUtils::isParsable)
                .map(Integer::parseInt)
                .orElse(null);
        if (proxyPort == null || proxyPort <= 0) {
            return;
        }

        URL remoteURL = slot.getRemoteURL();
        String pac = String.format("function FindProxyForURL(url,host) {"
                + " \n return \"PROXY %s:%s\";\n"
                + "}", remoteURL.getHost(), proxyPort);

        String udid = String.valueOf(CapabilityUtils.getAppiumCapability(slotCapabilities, "udid").orElse(""));
        if (StringUtils.isBlank(udid)) {
            LOGGER.warning(() -> String.format("Appium node must have 'UDID' capability to be identified in STF: %s", slotCapabilities));
            return;
        }

        Integer serverProxyPort = CapabilityUtils.getZebrunnerCapability(slotCapabilities, SERVER_PROXY_PORT)
                .map(String::valueOf)
                .filter(NumberUtils::isParsable)
                .map(Integer::parseInt)
                .orElse(null);
        if (serverProxyPort == null || serverProxyPort <= 0) {
            return;
        }

        LOGGER.info(() -> String.format("Detected '%s' device with 'proxy_port' and 'server_proxy_port' capabilities.",
                CapabilityUtils.getAppiumCapability(slotCapabilities, "deviceName").orElse("")));

        MitmProxyClient client = new MitmProxyClient();
        client.proxyPort = proxyPort;
        client.serverProxyPort = serverProxyPort;

        try {
            client.proxyURL = new URL(remoteURL.getProtocol(), remoteURL.getHost(), serverProxyPort, "");
        } catch (Exception e) {
            LOGGER.warning(() -> String.format("Could not create proxy client. Exception: %s -  %s", e.getClass(), e.getMessage()));
            return;
        }
        ProxyServlet.updatePacConfiguration(udid, pac);
        MITM_PROXY_URLS.put(udid, client);
    }

}
