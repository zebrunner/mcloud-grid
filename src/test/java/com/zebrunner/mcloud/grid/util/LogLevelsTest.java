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
}
