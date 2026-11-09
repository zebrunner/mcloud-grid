package com.zebrunner.mcloud.grid.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The token of one node, issued by STF when the node joins with an invite: {@code mcn1.<label>.<secret>}, the secret is
 * 32 random bytes in base64url. STF keeps only its SHA-256 and verifies it for the hub (POST /api/v1/nodes/verify).
 * The value is never logged nor shown: {@link #toString()} keeps the label only.
 */
public final class NodeToken {
    public static final String PREFIX = "mcn1.";
    private static final Pattern FORMAT = Pattern.compile("mcn1\\.([a-z0-9][a-z0-9-]{0,39})\\.([A-Za-z0-9_-]{43})");

    private final String value;
    private final String label;
    private final String hash;

    private NodeToken(String value, String label) {
        this.value = value;
        this.label = label;
        this.hash = sha256Hex(value);
    }

    /**
     * @return true if the token is meant to be a node token (has its prefix), even a malformed one
     */
    public static boolean hasNodeTokenPrefix(String token) {
        return token != null && token.startsWith(PREFIX);
    }

    /**
     * @return the node token, empty if the value does not have the format of one
     */
    public static Optional<NodeToken> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(token);
        return matcher.matches() ? Optional.of(new NodeToken(token, matcher.group(1))) : Optional.empty();
    }

    /**
     * @return the secret value, for STF and the header of the requests to the node only
     */
    public String getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    /**
     * @return SHA-256 of the token in hex, the key of the caches
     */
    public String getHash() {
        return hash;
    }

    @Override
    public String toString() {
        return PREFIX + label + "." + SecretMasking.MASK;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NodeToken && MessageDigest.isEqual(
                hash.getBytes(StandardCharsets.US_ASCII), ((NodeToken) other).hash.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
