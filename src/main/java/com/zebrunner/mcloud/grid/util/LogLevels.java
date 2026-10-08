package com.zebrunner.mcloud.grid.util;

import org.apache.commons.lang3.StringUtils;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MCLOUD_LOG_LEVEL sets the grid logger level independently of Selenium's root logger.
 */
public final class LogLevels {
    public static final String ROOT_LOGGER = "com.zebrunner";
    private static final Logger LOGGER = Logger.getLogger(LogLevels.class.getName());
    private static final Object CONFIGURATION_LOCK = new Object();
    private static volatile boolean configured;

    private LogLevels() {
        //hide
    }

    public static void configure(String levelName) {
        synchronized (CONFIGURATION_LOCK) {
            if (configured || StringUtils.isBlank(levelName)) {
                return;
            }
            Level level;
            try {
                level = Level.parse(levelName.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                LOGGER.warning(() -> String.format("[CONFIGURATION] MCLOUD_LOG_LEVEL '%s' is not a log level (SEVERE, WARNING, INFO, FINE, FINER, FINEST).", levelName));
                return;
            }
            configured = true;
            Logger root = Logger.getLogger(ROOT_LOGGER);
            root.setLevel(level);
            LOGGER.info(() -> "[CONFIGURATION] Log level of the grid: " + level);
        }
    }
}
