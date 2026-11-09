package com.zebrunner.mcloud.grid.integration.client;

import com.zebrunner.mcloud.grid.models.stf.STFNode;
import com.zebrunner.mcloud.grid.models.stf.STFNodes;
import com.zebrunner.mcloud.grid.util.HttpClient;
import com.zebrunner.mcloud.grid.util.NodeToken;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The node registry of STF for the registration of the nodes: the verification of the node tokens
 * (POST /api/v1/nodes/verify) and the addresses of the enrolled nodes (GET /api/v1/nodes), both with the admin token of
 * the hub (STF_TOKEN). The answers are cached for {@link #CACHE_TIME}: the nodes register again every few seconds while
 * they are rejected.
 */
public final class StfNodes {
    public static final Duration CACHE_TIME = Duration.ofSeconds(30);
    private static final Logger LOGGER = Logger.getLogger(StfNodes.class.getName());
    // the tokens and the hosts come from the registration requests: the cache does not grow without a limit
    private static final int MAX_CACHED_VERIFICATIONS = 1000;
    private static final Duration CONFIGURATION_WARNING_PERIOD = Duration.ofMinutes(10);

    private final String stfUrl;
    private final String adminToken;
    private final Supplier<Instant> clock;
    private final Map<String, CachedVerification> verifications = new ConcurrentHashMap<>();
    private final Object addressesLock = new Object();
    private final Object warningLock = new Object();
    private Set<String> addresses;
    private Instant addressesUntil = Instant.MIN;
    private Instant lastConfigurationWarning;

    public StfNodes(String stfUrl, String adminToken, Supplier<Instant> clock) {
        this.stfUrl = stfUrl;
        this.adminToken = adminToken;
        this.clock = clock;
    }

    /**
     * @return the node registry of the STF of the hub (STF_URL, STF_TOKEN), empty when the STF integration is off
     */
    public static Optional<StfNodes> fromEnv() {
        return STFClient.isSTFEnabled()
                ? Optional.of(new StfNodes(STFClient.getStfUrl(), STFClient.DEFAULT_STF_TOKEN, Instant::now))
                : Optional.empty();
    }

    public enum Status {
        /** STF answered 200: the node is active and the host is its address. */
        VALID,
        /** STF answered 403: the token is unknown, revoked, replaced by a re-enrollment, or the host is another one. */
        REJECTED,
        /** STF did not answer or answered something else. */
        UNAVAILABLE
    }

    /**
     * The answer of STF about a node token at a host.
     */
    public static final class Verification {
        private final Status status;
        private final int httpStatus;

        public Verification(Status status, int httpStatus) {
            this.status = status;
            this.httpStatus = httpStatus;
        }

        public Status getStatus() {
            return status;
        }

        /**
         * @return the HTTP status of STF, 0 when STF did not answer
         */
        public int getHttpStatus() {
            return httpStatus;
        }
    }

    /**
     * @return the answer of STF about the token at the host, a cached one when STF answered it recently
     */
    public Verification verify(NodeToken token, String host) {
        CachedVerification cached = verifications.get(cacheKey(token, host));
        if (cached != null && clock.get().isBefore(cached.until)) {
            return cached.verification;
        }
        return verifyNow(token, host);
    }

    /**
     * @return the answer of STF about the token at the host without the cache, which keeps a definite answer
     */
    public Verification verifyNow(NodeToken token, String host) {
        Map<String, String> body = new HashMap<>();
        body.put("token", token.getValue());
        body.put("host", host);
        HttpClient.Response<STFNode> response = HttpClient.uri(Path.STF_NODES_VERIFY_PATH, stfUrl)
                .withAuthorization("Bearer " + adminToken)
                .post(STFNode.class, body);
        Verification verification;
        if (response.getStatus() == 200) {
            STFNode node = response.getObject();
            // the label is a part of the token: STF answers about another node only if it is broken
            if (node != null && node.getLabel() != null && !node.getLabel().equals(token.getLabel())) {
                LOGGER.warning(() -> String.format("[NODE TOKEN] STF verified the node token of '%s' as the one of '%s', it is not accepted.",
                        token.getLabel(), node.getLabel()));
                verification = new Verification(Status.REJECTED, response.getStatus());
            } else {
                verification = new Verification(Status.VALID, response.getStatus());
            }
        } else if (response.getStatus() == 403) {
            verification = new Verification(Status.REJECTED, response.getStatus());
        } else {
            if (response.getStatus() == 401) {
                warnAboutConfiguration("[NODE TOKEN] STF did not accept STF_TOKEN of the hub (HTTP 401): the node tokens are not verified.");
            } else if (response.getStatus() == 404) {
                warnAboutConfiguration("[NODE TOKEN] STF has no node registry (HTTP 404 of /api/v1/nodes/verify): the node tokens are not "
                        + "verified, update STF.");
            }
            return new Verification(Status.UNAVAILABLE, response.getStatus());
        }
        if (verifications.size() >= MAX_CACHED_VERIFICATIONS) {
            Instant now = clock.get();
            verifications.values().removeIf(entry -> !now.isBefore(entry.until));
            if (verifications.size() >= MAX_CACHED_VERIFICATIONS) {
                verifications.clear();
            }
        }
        verifications.put(cacheKey(token, host), new CachedVerification(verification, clock.get().plus(CACHE_TIME)));
        return verification;
    }

    /**
     * The addresses of the nodes in the registry of STF, any status: a shared token is not accepted from them. An STF
     * without the registry (HTTP 404) has none; when STF does not answer, the last known addresses are used.
     *
     * @return the addresses in lower case, empty if STF did not answer and the addresses are not known yet
     */
    public Optional<Set<String>> registeredAddresses() {
        synchronized (addressesLock) {
            Instant now = clock.get();
            if (addresses != null && now.isBefore(addressesUntil)) {
                return Optional.of(addresses);
            }
            HttpClient.Response<STFNodes> response = HttpClient.uri(Path.STF_NODES_PATH, stfUrl)
                    .withAuthorization("Bearer " + adminToken)
                    .get(STFNodes.class);
            if (response.getStatus() == 200 && response.getObject() != null) {
                Set<String> known = new HashSet<>();
                if (response.getObject().getNodes() != null) {
                    for (STFNode node : response.getObject().getNodes()) {
                        if (node != null && StringUtils.isNotBlank(node.getAddress())) {
                            known.add(node.getAddress().trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
                addresses = Collections.unmodifiableSet(known);
                addressesUntil = now.plus(CACHE_TIME);
            } else if (response.getStatus() == 404) {
                LOGGER.fine("[NODE REGISTRATION] STF has no node registry (HTTP 404 of /api/v1/nodes): no node is enrolled.");
                addresses = Collections.emptySet();
                addressesUntil = now.plus(CACHE_TIME);
            } else {
                LOGGER.warning(() -> String.format("[NODE REGISTRATION] Could not get the enrolled nodes from STF (HTTP %s)%s.",
                        response.getStatus(), addresses == null ? "" : ", the last known ones are used"));
            }
            return Optional.ofNullable(addresses);
        }
    }

    /**
     * Forgets the answers of STF, for tests.
     */
    public void clearCache() {
        verifications.clear();
        synchronized (addressesLock) {
            addresses = null;
            addressesUntil = Instant.MIN;
        }
    }

    /**
     * A problem of the configuration fails every registration: it is logged once in a while.
     */
    private void warnAboutConfiguration(String message) {
        Instant now = clock.get();
        synchronized (warningLock) {
            if (lastConfigurationWarning != null && now.isBefore(lastConfigurationWarning.plus(CONFIGURATION_WARNING_PERIOD))) {
                LOGGER.fine(message);
                return;
            }
            lastConfigurationWarning = now;
        }
        LOGGER.warning(message);
    }

    private static String cacheKey(NodeToken token, String host) {
        return token.getHash() + "|" + StringUtils.defaultString(host).toLowerCase(Locale.ROOT);
    }

    private static final class CachedVerification {
        private final Verification verification;
        private final Instant until;

        CachedVerification(Verification verification, Instant until) {
            this.verification = verification;
            this.until = until;
        }
    }
}
