package com.zebrunner.mcloud.grid.validator;

import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import org.apache.commons.lang3.StringUtils;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.logging.Logger;

public class DeviceTypeValidator implements Validator {
    private static final Logger LOGGER = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());
    private static final String ZEBRUNNER_DEVICE_TYPE_CAPABILITY = "deviceType";

    @Override
    public Boolean apply(Map<String, Object> nodeCapabilities, Map<String, Object> requestedCapabilities) {
        String expectedValue = CapabilityUtils.getZebrunnerCapability(requestedCapabilities, ZEBRUNNER_DEVICE_TYPE_CAPABILITY)
                .map(String::valueOf)
                .orElse(null);

        String actualValue = CapabilityUtils.getZebrunnerCapability(nodeCapabilities, ZEBRUNNER_DEVICE_TYPE_CAPABILITY)
                .map(String::valueOf)
                .orElse(null);

        if (anything(expectedValue)) {
            return true;
        }

        if (actualValue == null) {
            LOGGER.warning("No 'deviceType' capability specified for node.");
            return false;
        }
        return StringUtils.equalsIgnoreCase(actualValue, expectedValue);
    }
}
