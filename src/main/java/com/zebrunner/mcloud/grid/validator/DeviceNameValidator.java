package com.zebrunner.mcloud.grid.validator;

import com.zebrunner.mcloud.grid.util.CapabilityUtils;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Logger;

public class DeviceNameValidator implements Validator {
    private static final Logger LOGGER = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());
    //todo reuse MobileCapabilityType interface
    private static final String DEVICE_NAME_CAPABILITY = "deviceName";

    @Override
    public Boolean apply(Map<String, Object> nodeCapabilities, Map<String, Object> requestedCapabilities) {
        String expectedValue = CapabilityUtils.getAppiumCapability(requestedCapabilities, DEVICE_NAME_CAPABILITY)
                .map(String::valueOf)
                .orElse(null);

        String actualValue = CapabilityUtils.getAppiumCapability(nodeCapabilities, DEVICE_NAME_CAPABILITY)
                .map(String::valueOf)
                .orElse(null);

        if (anything(expectedValue)) {
            return true;
        }

        if (actualValue == null) {
            LOGGER.warning("No 'deviceName' capability specified for node.");
            return false;
        }

        //todo add trim
        return Arrays.asList(expectedValue.split(",")).contains(actualValue);
    }
}
