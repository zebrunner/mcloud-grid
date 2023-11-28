package com.zebrunner.mcloud.grid.validator;

import com.zebrunner.mcloud.grid.utils.CapabilityUtils;
import org.openqa.selenium.Capabilities;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.logging.Logger;

public class DeviceNameValidator implements Validator {
    private static final Logger LOGGER = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());
    private static final String DEVICE_NAME_CAPABILITY = "deviceName";

    @Override
    public Boolean apply(Capabilities nodeCapabilities, Capabilities requestedCapabilities) {
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
