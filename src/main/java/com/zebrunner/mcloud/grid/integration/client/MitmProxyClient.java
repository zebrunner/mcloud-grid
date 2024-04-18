package com.zebrunner.mcloud.grid.integration.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zebrunner.mcloud.grid.util.HttpClient;
import com.zebrunner.mcloud.grid.util.HttpClientApache;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.openqa.grid.internal.TestSlot;

import javax.annotation.Nullable;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class MitmProxyClient {
    private static final Logger LOGGER = Logger.getLogger(MitmProxyClient.class.getName());
    private static final Map<String, MitmProxyClient> PROXY_CLIENTS = new ConcurrentHashMap<>();
    private URL serverProxyURL = null;
    private String remoteHost = null;
    private Integer proxyPort = null;
    private Integer serverProxyPort = null;

    /**
     * Start proxy with arguments
     *
     * @param deviceUUID  device uuid
     * @param proxyType   type of proxy ({@code full}, {@code simple})
     * @param sessionUUID session uuid (for logs)
     * @return true if proxy successfully started (if available), false otherwise
     */
    public static boolean start(String deviceUUID, String proxyType, @Nullable String args, String sessionUUID) {
        MitmProxyClient client = PROXY_CLIENTS.getOrDefault(deviceUUID, null);
        if (client == null) {
            return false;
        }
        Map<String, String> proxy = Map.of(
                "proxyType", StringUtils.isBlank(proxyType) ? "simple" : proxyType,
                "proxyArgs", StringUtils.isBlank(args) ? StringUtils.EMPTY : args
        );

        boolean isStarted = false;
        try {
            HttpClient.Response<String> mitmResponse = HttpClientApache.create()
                    .withUri(Path.PROXY_RESTART, client.serverProxyURL.toString())
                    .post(new StringEntity(new ObjectMapper().writeValueAsString(proxy), ContentType.APPLICATION_JSON));
            if (mitmResponse.getStatus() != 200) {
                LOGGER.warning(() -> String.format("[NODE-%s] Could not start proxy. Response code: %s, %s",
                        sessionUUID, mitmResponse.getStatus(), mitmResponse.getObject()));
            } else {
                isStarted = true;
            }
        } catch (Throwable e) {
            LOGGER.warning(() -> String.format("[NODE-%s]  Could not start proxy. Exception: %s, %s", sessionUUID, e.getMessage(), e));
        }
        return isStarted;
    }

    public static void clearConfiguration(String udid) {
        PROXY_CLIENTS.remove(udid);
    }

    public static void clearConfiguration() {
        PROXY_CLIENTS.clear();
    }

    /**
     * Init proxy client for device
     *
     * @param slot {@link TestSlot}
     */
    public static void initConfiguration(TestSlot slot, String udid, Integer proxyPort, Integer serverProxyPort) {
        URL remoteURL = slot.getRemoteURL();
        MitmProxyClient client = new MitmProxyClient();
        client.proxyPort = proxyPort;
        client.serverProxyPort = serverProxyPort;
        try {
            client.serverProxyURL = new URL(remoteURL.getProtocol(), remoteURL.getHost(), serverProxyPort, "");
        } catch (Throwable e) {
            LOGGER.warning(() -> String.format("Could not init url for proxy client. Exception: %s -  %s", e.getClass(), e.getMessage()));
            return;
        }
        client.remoteHost = slot.getProxy()
                .getRemoteHost()
                .getHost();
        PROXY_CLIENTS.putIfAbsent(udid, client);
    }

    public static @Nullable MitmProxyClient getProxyClient(String udid) {
        return PROXY_CLIENTS.getOrDefault(udid, null);
    }

    public URL getServerProxyURL() {
        return serverProxyURL;
    }

    public Integer getProxyPort() {
        return proxyPort;
    }

    public Integer getServerProxyPort() {
        return serverProxyPort;
    }

    public String getRemoteHost() {
        return remoteHost;
    }
}
