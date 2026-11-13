package com.zebrunner.mcloud.grid.util;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MCLOUD_LOG_LEVEL sets the grid logger level independently of Selenium's root logger.
 */
public final class LogLevels {
    public static final String ROOT_LOGGER = "com.zebrunner";
    private static final Logger LOGGER = Logger.getLogger(LogLevels.class.getName());
    // the LogManager keeps its loggers by weak references: a logger without a reference may lose its level
    private static final Logger ROOT = Logger.getLogger(ROOT_LOGGER);

    private LogLevels() {
        //hide
    }

    /**
     * Sets the level of the grid loggers, MCLOUD_LOG_LEVEL of the hub; a blank or invalid level changes nothing.
     */
    public static void configure(String levelName) {
        if (StringUtils.isBlank(levelName)) {
            return;
        }
        Level level;
        try {
            level = Level.parse(levelName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> String.format("[CONFIGURATION] MCLOUD_LOG_LEVEL '%s' is not a log level (SEVERE, WARNING, INFO, FINE, FINER, FINEST).", levelName));
            return;
        }
        ROOT.setLevel(level);
        LOGGER.info(() -> "[CONFIGURATION] Log level of the grid: " + level);
    }
}
