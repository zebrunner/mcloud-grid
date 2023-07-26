package com.zebrunner.mcloud.grid.util;

import java.util.Map;
import java.util.Optional;

public final class CapabilityUtils {

    private CapabilityUtils() {
        //hide
    }

    public static Optional<Object> getAppiumCapability(Map<String, Object> capabilities, String capabilityName) {
        Object value = capabilities.get("appium:" + capabilityName);
        if (value == null) {
            // for backward compatibility
            // todo investigate
            value = capabilities.get(capabilityName);
        }
        return Optional.ofNullable(value);
    }

    public static Optional<Object> getZebrunnerCapability(Map<String, Object> capabilities, String capabilityName) {
        Object value = capabilities.get("zebrunner:" + capabilityName);
        if (value == null) {
            // for backward compatibility
            // todo investigate
            value = capabilities.get("appium:" + capabilityName);
        }
        if (value == null) {
            // for backward compatibility
            // todo investigate
            value = capabilities.get(capabilityName);
        }
        return Optional.ofNullable(value);
    }

}
