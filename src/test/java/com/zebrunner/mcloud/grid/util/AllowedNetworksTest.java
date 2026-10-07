package com.zebrunner.mcloud.grid.util;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class AllowedNetworksTest {

    @Test
    public void emptyListAllowsEveryNode() {
        Assert.assertTrue(AllowedNetworks.parse(null).allowsAll());
        Assert.assertTrue(AllowedNetworks.parse(" , ").allows("203.0.113.7"));
        Assert.assertEquals(AllowedNetworks.parse("").toString(), "any");
    }

    @DataProvider
    public Object[][] hosts() {
        return new Object[][] {
                {"10.0.0.0/8", "10.20.30.40", true},
                {"10.0.0.0/8", "11.0.0.1", false},
                {"192.168.1.0/24", "192.168.1.255", true},
                {"192.168.1.0/24", "192.168.2.1", false},
                {"192.168.1.128/25", "192.168.1.200", true},
                {"192.168.1.128/25", "192.168.1.100", false},
                {"172.16.0.0/12", "172.31.255.1", true},
                {"172.16.0.0/12", "172.32.0.1", false},
                {"192.168.1.15", "192.168.1.15", true},
                {"192.168.1.15", "192.168.1.16", false},
                {"10.0.0.0/8, 192.168.1.15", "192.168.1.15", true},
                {"0.0.0.0/0", "8.8.8.8", true},
                {"fd00::/8", "fd12:3456::1", true},
                {"fd00::/8", "fe80::1", false},
                {"10.0.0.0/8", "::1", false},
                {"127.0.0.0/8", "localhost", true},
                {"10.0.0.0/8", "node.invalid", false},
        };
    }

    @Test(dataProvider = "hosts")
    public void allows(String networks, String host, boolean expected) {
        Assert.assertEquals(AllowedNetworks.parse(networks).allows(host), expected, networks + " / " + host);
    }

    @Test
    public void invalidDefinitionFromEnvDeniesEveryNode() {
        AllowedNetworks networks = AllowedNetworks.fromEnv("NODE_ALLOWED_NETWORKS", "10.0.0.0/8, nodes.example.com");

        Assert.assertFalse(networks.allowsAll());
        Assert.assertFalse(networks.allows("10.1.1.1"));
        Assert.assertEquals(networks.toString(), "none: invalid NODE_ALLOWED_NETWORKS");
        Assert.assertTrue(AllowedNetworks.fromEnv("NODE_ALLOWED_NETWORKS", null).allowsAll());
    }

    @Test
    public void invalidEntriesAreRejected() {
        for (String invalid : new String[] {"10.0.0.0/33", "10.0.0.0/x", "nodes.example.com", "10.0.0.0/-1"}) {
            Assert.expectThrows(IllegalArgumentException.class, () -> AllowedNetworks.parse(invalid));
        }
    }
}
