package com.zebrunner.mcloud.grid.validator;

import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.util.Map;

public class ProxyValidator implements Validator {
    private static final String MITM_CAPABILITY = "Mitm";
    private static final String PROXY_PORT_CAPABILITY = "proxy_port";
    private static final String SERVER_PROXY_PORT_CAPABILITY = "server_proxy_port";

    @Override
    public Boolean apply(Map<String, Object> nodeCapabilities, Map<String, Object> requestedCapabilities) {
        Boolean expectedValue = CapabilityUtils.getZebrunnerCapability(requestedCapabilities, MITM_CAPABILITY)
                .map(String::valueOf)
                .map(Boolean::parseBoolean)
                .orElse(false);

        Integer serverProxyPortNode = CapabilityUtils.getZebrunnerCapability(nodeCapabilities, SERVER_PROXY_PORT_CAPABILITY)
                .map(String::valueOf)
                .filter(NumberUtils::isParsable)
                .map(Integer::parseInt)
                .orElse(null);

        Integer proxyPortNode = CapabilityUtils.getZebrunnerCapability(nodeCapabilities, PROXY_PORT_CAPABILITY)
                .map(String::valueOf)
                .filter(NumberUtils::isParsable)
                .map(Integer::parseInt)
                .orElse(null);

        if (!expectedValue) {
            return true;
        }

        return serverProxyPortNode != null && serverProxyPortNode > 0 && proxyPortNode != null && proxyPortNode > 0;
    }
}
