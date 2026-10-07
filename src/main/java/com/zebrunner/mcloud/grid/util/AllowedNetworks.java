package com.zebrunner.mcloud.grid.util;

import org.apache.commons.lang3.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Networks nodes may register from, as a comma separated list of CIDR ranges or single addresses
 * (e.g. "10.0.0.0/8, 192.168.1.15, fd00::/8"). An empty list allows every node.
 * The address the node registers with is checked: the hub forwards the sessions there.
 */
public final class AllowedNetworks {
    private static final Logger LOGGER = Logger.getLogger(AllowedNetworks.class.getName());
    private final List<Network> networks;
    private final String definition;
    private final boolean denyAll;

    private AllowedNetworks(List<Network> networks, String definition, boolean denyAll) {
        this.networks = networks;
        this.definition = definition;
        this.denyAll = denyAll;
    }

    /**
     * Like {@link #parse}, but an invalid definition denies every node instead of failing: it is a security setting.
     */
    public static AllowedNetworks fromEnv(String name, String definition) {
        try {
            return parse(definition);
        } catch (IllegalArgumentException e) {
            LOGGER.severe(() -> String.format("[CONFIGURATION] %s is invalid (%s), no node is registered until it is fixed.", name, e.getMessage()));
            return new AllowedNetworks(Collections.emptyList(), "none: invalid " + name, true);
        }
    }

    /**
     * @throws IllegalArgumentException for an entry that is not an IP address or a CIDR range
     */
    public static AllowedNetworks parse(String definition) {
        List<Network> networks = new ArrayList<>();
        for (String entry : StringUtils.split(StringUtils.defaultString(definition), ',')) {
            if (StringUtils.isNotBlank(entry)) {
                networks.add(Network.parse(entry.trim()));
            }
        }
        return new AllowedNetworks(Collections.unmodifiableList(networks), StringUtils.trimToEmpty(definition), false);
    }

    public boolean allowsAll() {
        return networks.isEmpty() && !denyAll;
    }

    /**
     * @return true if any address of the host is in an allowed network
     */
    public boolean allows(String host) {
        if (allowsAll()) {
            return true;
        }
        if (denyAll) {
            return false;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return false;
        }
        for (InetAddress address : addresses) {
            for (Network network : networks) {
                if (network.contains(address)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return allowsAll() ? "any" : definition;
    }

    private static final class Network {
        private final byte[] address;
        private final int prefix;

        private Network(byte[] address, int prefix) {
            this.address = address;
            this.prefix = prefix;
        }

        static Network parse(String entry) {
            String[] parts = entry.split("/", 2);
            byte[] address = parseAddress(parts[0], entry);
            int prefix = address.length * 8;
            if (parts.length == 2) {
                try {
                    prefix = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid network prefix: " + entry, e);
                }
                if (prefix < 0 || prefix > address.length * 8) {
                    throw new IllegalArgumentException("Invalid network prefix: " + entry);
                }
            }
            return new Network(address, prefix);
        }

        private static byte[] parseAddress(String value, String entry) {
            // only literal addresses: a host name would make the check depend on DNS of the moment
            if (!value.matches("[0-9.]+") && !value.contains(":")) {
                throw new IllegalArgumentException("Not an IP address or CIDR range: " + entry);
            }
            try {
                return InetAddress.getByName(value).getAddress();
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Not an IP address or CIDR range: " + entry, e);
            }
        }

        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != address.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != address[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (bytes[fullBytes] & mask) == (address[fullBytes] & mask);
        }
    }
}
