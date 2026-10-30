package com.zebrunner.mcloud.grid.util;

import org.apache.commons.lang3.StringUtils;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.exception.GridConfigurationException;
import org.openqa.grid.internal.utils.configuration.GridNodeConfiguration;
import org.openqa.selenium.remote.http.HttpClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * The secret shared by the hub and its nodes (NODE_REGISTRATION_TOKEN of both): a node registers with it in 'custom' of
 * its configuration, the hub sends it to the node with every request in {@link #HEADER} (zebrunner/appium answers
 * nothing else but the container itself). The hub does not start without it.
 */
public final class NodeRegistrationToken {
    public static final String ENV = "NODE_REGISTRATION_TOKEN";
    public static final String CUSTOM_KEY = "registrationToken";
    public static final String HEADER = "X-MCloud-Node-Token";

    private final String token;
    private final byte[] digest;

    private NodeRegistrationToken(String token) {
        this.token = token;
        this.digest = sha256(token);
    }

    /**
     * @param token the token of the hub
     * @throws GridConfigurationException when it is empty
     */
    public static NodeRegistrationToken of(String token) {
        String value = StringUtils.trimToEmpty(token);
        if (value.isEmpty()) {
            throw new GridConfigurationException(ENV + " is not set: the hub registers only the nodes with its token, "
                    + "set it for the hub and for every node (NODE_REGISTRATION_TOKEN of mcloud-agent).");
        }
        return new NodeRegistrationToken(value);
    }

    /**
     * Takes the token out of the request, so that it is in no copy of the node configuration the hub keeps and shows
     * (the console, /grid/api/proxy), and compares it with the token of the hub.
     *
     * @return true if the request has the token of the hub
     */
    public boolean accept(RegistrationRequest request) {
        GridNodeConfiguration configuration = request.getConfiguration();
        String sent = null;
        if (configuration != null && configuration.custom != null && configuration.custom.containsKey(CUSTOM_KEY)) {
            // the map of a parsed request may be immutable
            Map<String, String> custom = new HashMap<>(configuration.custom);
            sent = custom.remove(CUSTOM_KEY);
            configuration.custom = custom;
        }
        // the digests have the same length: the comparison takes the same time for any token
        return sent != null && MessageDigest.isEqual(digest, sha256(sent.trim()));
    }

    /**
     * @return the client that sends the token to the node with every request
     */
    public HttpClient authenticate(HttpClient client) {
        return request -> {
            request.setHeader(HEADER, token);
            return client.execute(request);
        };
    }

    /**
     * @return the client of the requests of the hub itself to the node (the Appium status checks) with the token
     */
    public HttpClientApache authenticate(HttpClientApache client) {
        return client.withHeader(HEADER, token);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
