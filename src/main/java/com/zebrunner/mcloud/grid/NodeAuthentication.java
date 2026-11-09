package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.integration.client.StfNodes;
import com.zebrunner.mcloud.grid.util.NodeRegistrationToken;
import com.zebrunner.mcloud.grid.util.NodeToken;
import org.apache.commons.lang3.StringUtils;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.exception.GridException;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Decides whether a node may register by the token in 'custom' of its configuration:
 * <ul>
 * <li>a node token ({@code mcn1.<label>.<secret>}, see {@link NodeToken}) is accepted only when STF verifies it for the
 * host of the node URL (POST /api/v1/nodes/verify answers 200);</li>
 * <li>the legacy shared NODE_REGISTRATION_TOKEN is accepted when the hub has one, but not from the address of a node of
 * the registry of STF (any status): an enrolled node registers with its node token only.</li>
 * </ul>
 * The token is taken out of the request in any case. The messages of the rejections go to the node (the answer of
 * /grid/register) and to the log of the hub, they never contain a token.
 */
public final class NodeAuthentication {
    private static final Logger LOGGER = Logger.getLogger(NodeAuthentication.class.getName());

    private final NodeRegistrationToken sharedToken;
    private final StfNodes stfNodes;

    /**
     * @param sharedToken the legacy NODE_REGISTRATION_TOKEN of the hub, null when it is not set
     * @param stfNodes    the node registry of STF, null when the STF integration is off
     */
    public NodeAuthentication(NodeRegistrationToken sharedToken, StfNodes stfNodes) {
        this.sharedToken = sharedToken;
        this.stfNodes = stfNodes;
    }

    /**
     * @return the authentication of the hub configuration (NODE_REGISTRATION_TOKEN, STF_URL, STF_TOKEN)
     */
    public static NodeAuthentication fromEnv() {
        return new NodeAuthentication(NodeRegistrationToken.optional(System.getenv(NodeRegistrationToken.ENV)).orElse(null),
                StfNodes.fromEnv().orElse(null));
    }

    /**
     * @return the tokens the hub accepts, for the log of the configuration
     */
    public String describe() {
        if (stfNodes != null && sharedToken != null) {
            return "node tokens verified by STF and the shared NODE_REGISTRATION_TOKEN (legacy, not from the addresses of enrolled nodes)";
        }
        return stfNodes != null ? "node tokens verified by STF only" : "the shared NODE_REGISTRATION_TOKEN only (STF integration is off)";
    }

    /**
     * Forgets the answers of STF, for tests.
     */
    public void clearCache() {
        if (stfNodes != null) {
            stfNodes.clearCache();
        }
    }

    /**
     * @return the answer of STF about the node token of a registered node, without the cache
     */
    public StfNodes.Verification verifyNow(NodeToken token, String host) {
        if (stfNodes == null) {
            return new StfNodes.Verification(StfNodes.Status.UNAVAILABLE, 0);
        }
        return stfNodes.verifyNow(token, host);
    }

    /**
     * The node that may register.
     */
    public static final class Accepted {
        private final RegistrationRequest request;
        private final NodeRegistrationToken credential;
        private final NodeToken nodeToken;
        private final String host;

        Accepted(RegistrationRequest request, NodeRegistrationToken credential, NodeToken nodeToken, String host) {
            this.request = request;
            this.credential = credential;
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
         * @return the token the hub sends to the node in its requests
         */
        public NodeRegistrationToken getCredential() {
            return credential;
        }

        /**
         * @return the node token of the node, null for a node with the shared token
         */
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
     *                  when the node may not register; it runs before the requests to STF, after the checks without them
     * @throws GridException with the reason when the node may not register
     */
    public Accepted accept(RegistrationRequest request, Consumer<String> hostCheck) {
        String sent = NodeRegistrationToken.take(request);
        String node = request.getConfiguration() == null ? "(no configuration)" : String.valueOf(request.getConfiguration().getRemoteHost());
        if (StringUtils.isEmpty(sent)) {
            throw new GridException(sharedToken == null
                    ? String.format("Node %s has no node token, so it is not registered. Join the node to get one.", node)
                    : String.format("Node %s has no or another NODE_REGISTRATION_TOKEN than the hub, so it is not registered.", node));
        }
        String host = host(request).orElseThrow(() -> new GridException(String.format(
                "Node %s has no valid URL, so it is not registered.", node)));
        if (NodeToken.hasNodeTokenPrefix(sent)) {
            return acceptNodeToken(request, node, host, sent, hostCheck);
        }
        return acceptSharedToken(request, node, host, sent, hostCheck);
    }

    private Accepted acceptNodeToken(RegistrationRequest request, String node, String host, String sent, Consumer<String> hostCheck) {
        NodeToken token = NodeToken.parse(sent).orElseThrow(() -> new GridException(String.format(
                "Node %s has a malformed node token, so it is not registered. Join the node again to get a valid one.", node)));
        if (stfNodes == null) {
            throw new GridException(String.format("Node %s (%s) has a node token, but the STF integration of the hub is off "
                    + "(STF_URL and STF_TOKEN), so it is not registered.", node, token.getLabel()));
        }
        hostCheck.accept(host);
        StfNodes.Verification verification = stfNodes.verify(token, host);
        switch (verification.getStatus()) {
        case VALID:
            LOGGER.fine(() -> String.format("[NODE REGISTRATION] Node %s is the enrolled node '%s', STF verified its node token.",
                    node, token.getLabel()));
            return new Accepted(request, NodeRegistrationToken.of(token.getValue()), token, host);
        case REJECTED:
            throw new GridException(String.format("Node %s has an unknown or revoked node token, so it is not registered. "
                    + "A node token is valid only for the address the node was enrolled with (%s is the host of the node URL); "
                    + "join the node again with a new invite.", node, host));
        default:
            throw new GridException(String.format("Node %s has a node token, but STF could not verify it (%s), so it is not registered; "
                    + "the node registers again later.", node, describe(verification.getHttpStatus())));
        }
    }

    private Accepted acceptSharedToken(RegistrationRequest request, String node, String host, String sent, Consumer<String> hostCheck) {
        if (sharedToken == null) {
            throw new GridException(String.format("Node %s registers with a shared NODE_REGISTRATION_TOKEN, but the hub accepts only "
                    + "node tokens, so it is not registered. Join the node to get its node token.", node));
        }
        if (!sharedToken.matches(sent)) {
            throw new GridException(String.format("Node %s has no or another NODE_REGISTRATION_TOKEN than the hub, so it is not registered.", node));
        }
        hostCheck.accept(host);
        if (stfNodes != null) {
            Optional<Set<String>> enrolled = stfNodes.registeredAddresses();
            if (enrolled.isEmpty()) {
                throw new GridException(String.format("Node %s registers with the shared NODE_REGISTRATION_TOKEN, but STF could not tell "
                        + "whether %s is the address of an enrolled node, so it is not registered; the node registers again later.", node, host));
            }
            if (enrolled.get().contains(host.toLowerCase(Locale.ROOT))) {
                throw new GridException(String.format("Node %s registers with the shared NODE_REGISTRATION_TOKEN, but %s is the address "
                        + "of an enrolled node, which registers with its node token only, so it is not registered.", node, host));
            }
        }
        return new Accepted(request, sharedToken, null, host);
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
