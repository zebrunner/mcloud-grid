package com.zebrunner.mcloud.grid.validator;

import com.zebrunner.mcloud.grid.util.CapabilityUtils;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Logger;

public class PlatformVersionValidator implements Validator {
    private static final Logger LOGGER = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());
    //todo reuse MobileCapabilityType interface
    private static final String PLATFORM_VERSION_CAPABILITY = "platformVersion";
    private static final String VERSION_PATTERN = "(\\d+\\.){0,}(\\d+)$";

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

        if (!actualValue.matches(VERSION_PATTERN)) {
            LOGGER.warning("Node 'platformVersion' capability is not a numeric version: " + actualValue);
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

    private static final class PlatformVersion implements Comparable<PlatformVersion> {
        private int[] version;

        PlatformVersion(String v) {
            if (v != null && v.matches(VERSION_PATTERN)) {
                String[] digits = v.split("\\.");
                this.version = new int[digits.length];
                for (int i = 0; i < digits.length; i++) {
                    this.version[i] = Integer.parseInt(digits[i]);
                }
            }
        }

        public int[] getVersion() {
            return version;
        }

        public void setVersion(int[] version) {
            this.version = version;
        }

        /**
         * Compares versions component by component; missing components are zeros, so 7 == 7.0 == 7.0.0.
         */
        @Override
        public int compareTo(@Nonnull PlatformVersion pv) {
            if (pv.getVersion() == null || this.version == null) {
                return 0;
            }
            int length = Math.max(this.version.length, pv.getVersion().length);
            for (int i = 0; i < length; i++) {
                int result = Integer.compare(component(this.version, i), component(pv.getVersion(), i));
                if (result != 0) {
                    return result;
                }
            }
            return 0;
        }

        private static int component(int[] version, int index) {
            return index < version.length ? version[index] : 0;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PlatformVersion && Arrays.equals(normalized(), ((PlatformVersion) o).normalized());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(normalized());
        }

        private int[] normalized() {
            if (version == null) {
                return null;
            }
            int length = version.length;
            while (length > 1 && version[length - 1] == 0) {
                length--;
            }
            return Arrays.copyOf(version, length);
        }
    }
}
