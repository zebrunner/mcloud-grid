package com.zebrunner.mcloud.grid.util;

import org.apache.commons.lang3.StringUtils;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.utils.configuration.GridNodeConfiguration;
import org.openqa.selenium.remote.http.HttpClient;

import java.util.HashMap;
import java.util.Map;

/**
 * The node token a node registers with in 'custom' of its configuration ({@link #CUSTOM_KEY}, NODE_REGISTRATION_TOKEN of
 * zebrunner/appium), see {@link NodeToken}, and the hub sends back to that node with every request in {@link #HEADER}
 * (zebrunner/appium answers nothing else but the container itself).
 */
public final class NodeRegistrationToken {
    public static final String CUSTOM_KEY = "registrationToken";
    public static final String HEADER = "X-MCloud-Node-Token";

    private final String token;

    private NodeRegistrationToken(String token) {
        this.token = token;
    }

    /**
     * @throws IllegalArgumentException when the token is empty
     */
    public static NodeRegistrationToken of(String token) {
        String value = StringUtils.trimToEmpty(token);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("The token is empty");
        }
        return new NodeRegistrationToken(value);
    }

    /**
     * Takes the token out of the request, so that it is in no copy of the node configuration the hub keeps and shows
     * (the console, /grid/api/proxy).
     *
     * @return the token the node sent, trimmed; null if it sent none
     */
    public static String take(RegistrationRequest request) {
        GridNodeConfiguration configuration = request.getConfiguration();
        String sent = null;
        if (configuration != null && configuration.custom != null && configuration.custom.containsKey(CUSTOM_KEY)) {
            // the map of a parsed request may be immutable
            Map<String, String> custom = new HashMap<>(configuration.custom);
            sent = custom.remove(CUSTOM_KEY);
            configuration.custom = custom;
        }
        return sent == null ? null : sent.trim();
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

    @Override
    public String toString() {
        return SecretMasking.MASK;
    }
}
