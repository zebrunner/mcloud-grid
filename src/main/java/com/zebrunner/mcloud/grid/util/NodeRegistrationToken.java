package com.zebrunner.mcloud.grid.util;

import org.apache.commons.lang3.StringUtils;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.utils.configuration.GridNodeConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The shared secret a node registers with (NODE_REGISTRATION_TOKEN of the hub and of the node): the node sends it in
 * 'custom' of its configuration, see the node config of zebrunner/appium.
 */
public final class NodeRegistrationToken {
    public static final String CUSTOM_KEY = "registrationToken";
    // the token in a log message: the JSON of a registration request (Selenium logs it with -debug) or a map
    private static final Pattern IN_TEXT = Pattern.compile("(" + CUSTOM_KEY + "\"?\\s*[:=]\\s*\"?)[^\",}\\s]+");

    private final byte[] digest;

    private NodeRegistrationToken(String token) {
        this.digest = StringUtils.isEmpty(token) ? null : sha256(token);
    }

    /**
     * @param token the token of the hub, empty = a node registers without a token
     */
    public static NodeRegistrationToken of(String token) {
        return new NodeRegistrationToken(StringUtils.trimToEmpty(token));
    }

    public boolean isEnabled() {
        return digest != null;
    }

    /**
     * Takes the token out of the request, so that it is in no copy of the node configuration the hub keeps and shows
     * (the console, /grid/api/proxy), and compares it with the token of the hub.
     *
     * @return true if the hub has no token or the request has the same one
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
        if (!isEnabled()) {
            return true;
        }
        // the digests have the same length: the comparison takes the same time for any token
        return sent != null && MessageDigest.isEqual(digest, sha256(sent.trim()));
    }

    /**
     * @return the text with the value of every registration token masked
     */
    public static String mask(String text) {
        if (text == null || !text.contains(CUSTOM_KEY)) {
            return text;
        }
        return IN_TEXT.matcher(text).replaceAll("$1" + SecretMasking.MASK);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
