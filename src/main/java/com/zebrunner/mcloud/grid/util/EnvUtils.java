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
     * @return duration in seconds from the env var, or the default value (may be null) when it is not set or is not a number
     */
    public static Duration getDurationInSeconds(String name, Duration defaultValue) {
        return getDurationInSeconds(name, defaultValue, System::getenv);
    }

    static Duration getDurationInSeconds(String name, Duration defaultValue, Function<String, String> env) {
        String value = env.apply(name);
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            LOGGER.warning(() -> String.format("[CONFIGURATION] '%s' should be a number of seconds, but was '%s'. Default value will be used: %s.",
                    name, value, defaultValue == null ? "not set" : defaultValue.toSeconds() + " seconds"));
            return defaultValue;
        }
    }
}
