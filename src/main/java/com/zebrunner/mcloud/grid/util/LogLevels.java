package com.zebrunner.mcloud.grid.util;

import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.remote.server.log.TerseFormatter;

import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MCLOUD_LOG_LEVEL (e.g. FINE) sets the log level of the grid code only: the handler of Selenium prints INFO and above
 * unless the whole hub runs with -debug, so the grid loggers get their own handler with the Selenium format.
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
            if (level.intValue() < Level.INFO.intValue()) {
                Handler handler = new ConsoleHandler();
                handler.setLevel(level);
                handler.setFormatter(new TerseFormatter());
                root.addHandler(handler);
                root.setUseParentHandlers(false);
            }
            LOGGER.info(() -> "[CONFIGURATION] Log level of the grid: " + level);
        }
    }
}
