package com.zebrunner.mcloud.grid.util;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

public class LogLevelsTest {

    @Test
    public void invalidOrMissingLevelChangesNothing() {
        Logger root = Logger.getLogger(LogLevels.ROOT_LOGGER);
        Level before = root.getLevel();

        LogLevels.configure(null);
        LogLevels.configure("  ");
        LogLevels.configure("LOUD");

        Assert.assertEquals(root.getLevel(), before);
        Assert.assertTrue(root.getUseParentHandlers());
    }

    @Test
    public void validLevelSetsTheLevelOfTheGridLoggers() {
        Logger root = Logger.getLogger(LogLevels.ROOT_LOGGER);
        Level before = root.getLevel();
        try {
            LogLevels.configure(" fine ");

            Assert.assertEquals(root.getLevel(), Level.FINE);
            Assert.assertTrue(Logger.getLogger("com.zebrunner.mcloud.grid.MobileRemoteProxy").isLoggable(Level.FINE));
            Assert.assertTrue(root.getUseParentHandlers());

            // an invalid level keeps the configured one
            LogLevels.configure("LOUD");
            Assert.assertEquals(root.getLevel(), Level.FINE);
        } finally {
            root.setLevel(before);
        }
    }
}
