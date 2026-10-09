package com.zebrunner.mcloud.grid.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;

/**
 * Checks that the hub can open a connection to a node: a node registered with an address the hub cannot reach
 * (e.g. an internal IP of another network) gets sessions forwarded that hang until timeouts, see #144.
 */
public final class NodeReachability {

    private NodeReachability() {
        //hide
    }

    /**
     * @return empty if a TCP connection to the host and port of the node can be opened in time, otherwise the reason
     */
    public static Optional<String> check(URL node, Duration timeout) {
        int port = node.getPort() == -1 ? node.getDefaultPort() : node.getPort();
        InetSocketAddress address = new InetSocketAddress(node.getHost(), port);
        if (address.isUnresolved()) {
            return Optional.of("host '" + node.getHost() + "' cannot be resolved");
        }
        try (Socket socket = new Socket()) {
            socket.connect(address, (int) timeout.toMillis());
            return Optional.empty();
        } catch (IOException e) {
            return Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
