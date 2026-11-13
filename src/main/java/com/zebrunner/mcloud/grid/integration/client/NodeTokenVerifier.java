package com.zebrunner.mcloud.grid.integration.client;

import com.zebrunner.mcloud.grid.util.NodeToken;

import java.util.Optional;

/**
 * Verifies the node token of a node for the host of its URL: {@link StfNodes}, the node registry of STF.
 */
public interface NodeTokenVerifier {
    /**
     * @return the recent answer about the token at the host, empty when there is none
     */
    Optional<StfNodes.Verification> cached(NodeToken token, String host);

    /**
     * @return the answer about the token at the host without the cache
     */
    StfNodes.Verification verifyNow(NodeToken token, String host);

    /**
     * @return the answer about the token at the host, a cached one when it is recent
     */
    default StfNodes.Verification verify(NodeToken token, String host) {
        return cached(token, host).orElseGet(() -> verifyNow(token, host));
    }
}
