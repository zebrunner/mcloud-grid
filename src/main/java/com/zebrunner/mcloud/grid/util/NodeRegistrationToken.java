package com.zebrunner.mcloud.grid.util;

import org.apache.commons.lang3.StringUtils;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.utils.configuration.GridNodeConfiguration;
import org.openqa.selenium.remote.http.HttpClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A token a node registers with in 'custom' of its configuration ({@link #CUSTOM_KEY}) and the hub sends back to the
 * node with every request in {@link #HEADER} (zebrunner/appium answers nothing else but the container itself): the
 * node token of the node (see {@link NodeToken}) or the legacy secret shared by the hub and all its nodes
 * (NODE_REGISTRATION_TOKEN of both).
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
     * @throws IllegalArgumentException when the token is empty
     */
    public static NodeRegistrationToken of(String token) {
        return optional(token).orElseThrow(() -> new IllegalArgumentException("The token is empty"));
    }

    /**
     * @return the token, empty when it is not set (e.g. NODE_REGISTRATION_TOKEN of the hub)
     */
    public static Optional<NodeRegistrationToken> optional(String token) {
        String value = StringUtils.trimToEmpty(token);
        return value.isEmpty() ? Optional.empty() : Optional.of(new NodeRegistrationToken(value));
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
     * @return true if the sent token is this one
     */
    public boolean matches(String sent) {
        // the digests have the same length: the comparison takes the same time for any token
        return sent != null && MessageDigest.isEqual(digest, sha256(sent.trim()));
    }

    /**
     * Takes the token out of the request (see {@link #take}) and compares it with this one.
     *
     * @return true if the request has this token
     */
    public boolean accept(RegistrationRequest request) {
        return matches(take(request));
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

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
