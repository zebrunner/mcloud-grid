package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.util.NodeToken;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Limits the node tokens the hub verifies with STF when STF did not answer about them recently (the misses of the cache
 * of {@link com.zebrunner.mcloud.grid.integration.client.StfNodes}), per host of the node URL and for all the hosts:
 * anyone who reaches /grid/register could make the hub resolve the host and ask STF with every request, sending a new
 * token each time. The nodes of an enrolled host share its node token, which counts once.
 */
final class VerificationLimit {
    static final Duration PERIOD = Duration.ofSeconds(30);
    static final int PER_HOST = 10;
    static final int ALL_HOSTS = 1000;

    private final Supplier<Instant> clock;
    private final int perHost;
    private final int allHosts;
    private final Map<String, Set<String>> tokensByHost = new HashMap<>();
    private Instant periodEnd;
    private int total;

    VerificationLimit(Supplier<Instant> clock) {
        this(clock, PER_HOST, ALL_HOSTS);
    }

    VerificationLimit(Supplier<Instant> clock, int perHost, int allHosts) {
        this.clock = clock;
        this.perHost = perHost;
        this.allHosts = allHosts;
    }

    /**
     * @return true when the token may be verified for the host in this period (a new one is counted)
     */
    synchronized boolean tryAcquire(NodeToken token, String host) {
        Instant now = clock.get();
        if (periodEnd == null || !now.isBefore(periodEnd)) {
            periodEnd = now.plus(PERIOD);
            tokensByHost.clear();
            total = 0;
        }
        String key = StringUtils.defaultString(host).toLowerCase(Locale.ROOT);
        Set<String> tokens = tokensByHost.getOrDefault(key, Collections.emptySet());
        if (tokens.contains(token.getHash())) {
            return true;
        }
        if (tokens.size() >= perHost || total >= allHosts) {
            return false;
        }
        tokensByHost.computeIfAbsent(key, k -> new HashSet<>()).add(token.getHash());
        total++;
        return true;
    }

    @Override
    public String toString() {
        return String.format("%s node tokens of a host and %s of all the hosts in %ss", perHost, allHosts, PERIOD.toSeconds());
    }
}
