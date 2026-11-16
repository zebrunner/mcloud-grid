package com.zebrunner.mcloud.grid.util;

import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Reads optional settings from environment variables.
 */
public final class EnvUtils {
    private static final Logger LOGGER = Logger.getLogger(EnvUtils.class.getName());

    private EnvUtils() {
        //hide
    }

    /**
     * @return duration in seconds from the env var, or the default value (may be null) when it is not set, is not a number
     * or is negative
     */
    public static Duration getDurationInSeconds(String name, Duration defaultValue) {
        return getDurationInSeconds(name, defaultValue, System::getenv);
    }

    /**
     * @return integer from the env var, or the default value when it is not set or is not a number
     */
    public static int getInt(String name, int defaultValue) {
        return getInt(name, defaultValue, System::getenv);
    }

    static int getInt(String name, int defaultValue, Function<String, String> env) {
        String value = env.apply(name);
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warning(() -> String.format("[CONFIGURATION] '%s' should be a number, but was '%s'. Default value will be used: %s.",
                    name, value, defaultValue));
            return defaultValue;
        }
    }

    static Duration getDurationInSeconds(String name, Duration defaultValue, Function<String, String> env) {
        String value = env.apply(name);
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        long seconds;
        try {
            seconds = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            seconds = -1;
        }
        if (seconds < 0) {
            LOGGER.warning(() -> String.format("[CONFIGURATION] '%s' should be a number of seconds (0 or more), but was '%s'. Default value will be used: %s.",
                    name, value, defaultValue == null ? "not set" : defaultValue.toSeconds() + " seconds"));
            return defaultValue;
        }
        return Duration.ofSeconds(seconds);
    }
}
