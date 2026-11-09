package com.zebrunner.mcloud.grid.integration.client;

import com.zebrunner.mcloud.grid.util.NodeToken;

/**
 * Verifies the node token of a node for the host of its URL: {@link StfNodes}, the node registry of STF.
 */
public interface NodeTokenVerifier {
    /**
     * @return the answer about the token at the host, a cached one when it is recent
     */
    StfNodes.Verification verify(NodeToken token, String host);

    /**
     * @return the answer about the token at the host without the cache
     */
    StfNodes.Verification verifyNow(NodeToken token, String host);
}
