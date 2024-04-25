package com.zebrunner.mcloud.grid.validator;

import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.util.Map;

public class ProxyValidator implements Validator {
    public static final String MITM_CAPABILITY = "Mitm";
    public static final String MITM_ARGS_CAPABILITY = "MitmArgs";
    public static final String MITM_TYPE_CAPABILITY = "MitmType";
    public static final String PROXY_PORT_CAPABILITY = "proxy_port";
    public static final String SERVER_PROXY_PORT_CAPABILITY = "server_proxy_port";

    @Override
    public Boolean apply(Map<String, Object> nodeCapabilities, Map<String, Object> requestedCapabilities) {
        boolean expectedValue = CapabilityUtils.getZebrunnerCapability(requestedCapabilities, MITM_CAPABILITY)
                .map(String::valueOf)
                .map(Boolean::parseBoolean)
                .orElse(false);
        if (!expectedValue) {
            return true;
        }

        Integer serverProxyPort = CapabilityUtils.getZebrunnerCapability(nodeCapabilities, SERVER_PROXY_PORT_CAPABILITY)
                .map(String::valueOf)
                .filter(NumberUtils::isParsable)
                .map(Integer::parseInt)
                .orElse(null);
        Integer proxyPort = CapabilityUtils.getZebrunnerCapability(nodeCapabilities, PROXY_PORT_CAPABILITY)
                .map(String::valueOf)
                .filter(NumberUtils::isParsable)
                .map(Integer::parseInt)
                .orElse(null);
        String mitmType = CapabilityUtils.getZebrunnerCapability(requestedCapabilities, MITM_TYPE_CAPABILITY)
                .map(String::valueOf)
                .orElse("simple");

        return (serverProxyPort != null && serverProxyPort > 0 && proxyPort != null && proxyPort > 0) &&
                StringUtils.equalsAny(mitmType, "full", "simple",  null);
    }
}
