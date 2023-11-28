package com.zebrunner.mcloud.grid.validator;

import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Platform;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.remote.CapabilityType;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.logging.Logger;

public class MobilePlatformValidator implements Validator {
    private static final Logger LOGGER = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());

    @Override
    public Boolean apply(Capabilities nodeCapabilities, Capabilities requestedCapabilities) {
        Object requested = requestedCapabilities.getCapability(CapabilityType.PLATFORM_NAME);
        Object provided = nodeCapabilities.getCapability(CapabilityType.PLATFORM_NAME);
        // we cannot safely call toString method for Platform object. ANDROID, IOS and so on do not override this method,
        // so we try to get name as is.
        if (anything(requested instanceof Platform ? ((Platform) requested).name() : (String) requested)) {
            return true;
        }

        Platform requestedPlatform = extractPlatform(requested);
        if (requestedPlatform != null) {
            Platform providedPlatform = extractPlatform(provided);
            return providedPlatform != null && providedPlatform.is(requestedPlatform);
        }

        if (provided == null) {
            LOGGER.warning("No 'platformName' capability specified for node.");
            return false;
        }

        return StringUtils.equalsIgnoreCase(requested.toString(), provided.toString());
    }

    private Platform extractPlatform(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Platform) {
            return (Platform) o;
        }
        try {
            return Platform.fromString(o.toString());
        } catch (WebDriverException ex) {
            return null;
        }
    }
}
