package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.integration.client.NodeTokenVerifier;
import com.zebrunner.mcloud.grid.integration.client.StfNodes;
import com.zebrunner.mcloud.grid.util.NodeRegistrationToken;
import com.zebrunner.mcloud.grid.util.NodeToken;
import org.apache.commons.lang3.StringUtils;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.exception.GridException;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Decides whether a node may register by the node token in 'custom' of its configuration ({@code mcn1.<label>.<secret>},
 * see {@link NodeToken}): it is accepted only when STF verifies it for the host of the node URL
 * (POST /api/v1/nodes/verify answers 200). The token is taken out of the request in any case. The messages of the
 * rejections go to the node (the answer of /grid/register) and to the log of the hub, they never contain a token.
 */
public final class NodeAuthentication {
    private static final Logger LOGGER = Logger.getLogger(NodeAuthentication.class.getName());

    private final NodeTokenVerifier verifier;

    /**
     * @param verifier the node registry of STF, null when STF_URL and STF_TOKEN are not set (then no node is accepted)
     */
    public NodeAuthentication(NodeTokenVerifier verifier) {
        this.verifier = verifier;
    }

    /**
     * @return the authentication of the hub configuration (STF_URL, STF_TOKEN)
     */
    public static NodeAuthentication fromEnv() {
        return new NodeAuthentication(StfNodes.fromEnv().orElse(null));
    }

    /**
     * The node that may register.
     */
    public static final class Accepted {
        private final RegistrationRequest request;
        private final NodeToken nodeToken;
        private final String host;

        Accepted(RegistrationRequest request, NodeToken nodeToken, String host) {
            this.request = request;
            this.nodeToken = nodeToken;
            this.host = host;
        }

        /**
         * @return the request without the token
         */
        public RegistrationRequest getRequest() {
            return request;
        }

        /**
         * @return the token the hub sends to the node in its requests: its node token
         */
        public NodeRegistrationToken getCredential() {
            return NodeRegistrationToken.of(nodeToken.getValue());
        }

        public NodeToken getNodeToken() {
            return nodeToken;
        }

        /**
         * @return the host of the node URL, the one STF verified the node token for
         */
        public String getHost() {
            return host;
        }
    }

    /**
     * Takes the token out of the request and checks it.
     *
     * @throws GridException with the reason when the node may not register
     */
    public Accepted accept(RegistrationRequest request) {
        return accept(request, host -> { });
    }

    /**
     * Takes the token out of the request and checks it.
     *
     * @param hostCheck the check of the host of the node URL (e.g. NODE_ALLOWED_NETWORKS), which throws a GridException
     *                  when the node may not register; it runs before the request to STF, after the checks without it
     * @throws GridException with the reason when the node may not register
     */
    public Accepted accept(RegistrationRequest request, Consumer<String> hostCheck) {
        String sent = NodeRegistrationToken.take(request);
        String node = request.getConfiguration() == null ? "(no configuration)" : String.valueOf(request.getConfiguration().getRemoteHost());
        if (StringUtils.isEmpty(sent)) {
            throw new GridException(String.format("Node %s has no node token, so it is not registered. "
                    + "Join the node to get one (NODE_REGISTRATION_TOKEN of the node is its node token).", node));
        }
        if (!NodeToken.hasNodeTokenPrefix(sent)) {
            throw new GridException(String.format("Node %s has a token that is not a node token (mcn1.<label>.<secret>), "
                    + "so it is not registered. Join the node to get its node token.", node));
        }
        NodeToken token = NodeToken.parse(sent).orElseThrow(() -> new GridException(String.format(
                "Node %s has a malformed node token, so it is not registered. Join the node again to get a valid one.", node)));
        String host = host(request).orElseThrow(() -> new GridException(String.format(
                "Node %s has no valid URL, so it is not registered.", node)));
        if (verifier == null) {
            throw new GridException(String.format("Node %s (%s) has a node token, but STF_URL and STF_TOKEN of the hub are not set, "
                    + "so it is not registered.", node, token.getLabel()));
        }
        hostCheck.accept(host);
        StfNodes.Verification verification = verifier.verify(token, host);
        switch (verification.getStatus()) {
        case VALID:
            LOGGER.fine(() -> String.format("[NODE REGISTRATION] Node %s is the enrolled node '%s', STF verified its node token.",
                    node, token.getLabel()));
            return new Accepted(request, token, host);
        case REJECTED:
            throw new GridException(String.format("Node %s has an unknown or revoked node token, so it is not registered. "
                    + "A node token is valid only for the address the node was enrolled with (%s is the host of the node URL); "
                    + "join the node again with a new invite.", node, host));
        default:
            throw new GridException(String.format("Node %s has a node token, but STF could not verify it (%s), so it is not registered; "
                    + "the node registers again later.", node, describe(verification.getHttpStatus())));
        }
    }

    /**
     * @return the answer of STF about the node token of a registered node, without the cache
     */
    public StfNodes.Verification verifyNow(NodeToken token, String host) {
        if (verifier == null) {
            return new StfNodes.Verification(StfNodes.Status.UNAVAILABLE, 0);
        }
        return verifier.verifyNow(token, host);
    }

    /**
     * @return the host of the node URL, without the brackets of an IPv6 address
     */
    static Optional<String> host(RegistrationRequest request) {
        if (request.getConfiguration() == null) {
            return Optional.empty();
        }
        try {
            String host = new URL(request.getConfiguration().getRemoteHost()).getHost();
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            return StringUtils.isBlank(host) ? Optional.empty() : Optional.of(host);
        } catch (MalformedURLException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static String describe(int httpStatus) {
        return httpStatus == 0 ? "STF did not answer" : "HTTP " + httpStatus;
    }
}
