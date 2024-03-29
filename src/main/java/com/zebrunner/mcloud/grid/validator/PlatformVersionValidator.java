package com.zebrunner.mcloud.grid.validator;

import com.zebrunner.mcloud.grid.util.CapabilityUtils;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.logging.Logger;

public class PlatformVersionValidator implements Validator {
    private static final Logger LOGGER = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());
    //todo reuse MobileCapabilityType interface
    private static final String PLATFORM_VERSION_CAPABILITY = "platformVersion";

    @Override
    public Boolean apply(Map<String, Object> nodeCapabilities, Map<String, Object> requestedCapabilities) {
        String expectedValue = CapabilityUtils.getAppiumCapability(requestedCapabilities, PLATFORM_VERSION_CAPABILITY)
                .map(String::valueOf)
                .orElse(null);

        String actualValue = CapabilityUtils.getAppiumCapability(nodeCapabilities, PLATFORM_VERSION_CAPABILITY)
                .map(String::valueOf)
                .orElse(null);

        if (anything(expectedValue)) {
            return true;
        }

        if (actualValue == null) {
            LOGGER.warning("No 'platformVersion' capability specified for node.");
            return false;
        }

        // Limited interval: 6.1.1-7.0
        if (expectedValue.matches("(\\d+\\.){0,}(\\d+)-(\\d+\\.){0,}(\\d+)$")) {
            PlatformVersion actPV = new PlatformVersion(actualValue);
            PlatformVersion minPV = new PlatformVersion(expectedValue.split("-")[0]);
            PlatformVersion maxPV = new PlatformVersion(expectedValue.split("-")[1]);

            return !(actPV.compareTo(minPV) < 0 || actPV.compareTo(maxPV) > 0);
        }
        // Unlimited interval: 6.0+
        else if (expectedValue.matches("(\\d+\\.){0,}(\\d+)\\+$")) {
            PlatformVersion actPV = new PlatformVersion(actualValue);
            PlatformVersion minPV = new PlatformVersion(expectedValue.replace("+", ""));

            return actPV.compareTo(minPV) >= 0;
        }
        // Multiple versions: 6.1,7.0
        else if (expectedValue.matches("(\\d+\\.){0,}(\\d+,)+(\\d+\\.){0,}(\\d+)$")) {
            boolean matches = false;
            for (String version : expectedValue.split(",")) {
                if (new PlatformVersion(version).compareTo(new PlatformVersion(actualValue)) == 0) {
                    matches = true;
                    break;
                }
            }
            return matches;
        }
        // Exact version: 7.0
        else if (expectedValue.matches("(\\d+\\.){0,}(\\d+)$")) {
            return new PlatformVersion(expectedValue).compareTo(new PlatformVersion(actualValue)) == 0;
        }
        LOGGER.warning("Cannot find suitable pattern for version: " + expectedValue);
        return false;
    }

    private class PlatformVersion implements Comparable<PlatformVersion> {
        private int[] version;

        public PlatformVersion(String v) {
            if (v != null && v.matches("(\\d+\\.){0,}(\\d+)$")) {
                String[] digits = v.split("\\.");
                this.version = new int[digits.length];
                for (int i = 0; i < digits.length; i++) {
                    this.version[i] = Integer.valueOf(digits[i]);
                }
            }
        }

        public int[] getVersion() {
            return version;
        }

        public void setVersion(int[] version) {
            this.version = version;
        }

        @Override
        public int compareTo(@Nonnull PlatformVersion pv) {
            int result = 0;
            if (pv.getVersion() != null && this.version != null) {
                int minL = Math.min(this.version.length, pv.getVersion().length);
                int maxL = Math.max(this.version.length, pv.getVersion().length);

                for (int i = 0; i < minL; i++) {
                    result = this.version[i] - pv.getVersion()[i];
                    if (result != 0) {
                        break;
                    }
                }

                if (result == 0 && this.version.length == minL && minL != maxL) {
                    result = -1;
                } else if (result == 0 && this.version.length == maxL && minL != maxL) {
                    result = 1;
                }
            }
            return result;
        }
    }
}
