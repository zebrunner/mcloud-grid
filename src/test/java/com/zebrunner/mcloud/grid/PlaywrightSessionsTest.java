package com.zebrunner.mcloud.grid;

import org.openqa.grid.common.exception.GridException;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

public class PlaywrightSessionsTest {

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        PlaywrightSessions.setEnabled(false);
    }

    private static Map<String, Object> caps(String key, Object automationName) {
        Map<String, Object> caps = new HashMap<>();
        caps.put("platformName", "Android");
        caps.put(key, automationName);
        return caps;
    }

    @Test
    public void offByDefault() {
        // PLAYWRIGHT_ENABLED is not set in the tests
        Assert.assertFalse(PlaywrightSessions.isEnabled());
    }

    @Test
    public void playwrightSessionIsTheAutomationNameOfTheDriver() {
        Assert.assertTrue(PlaywrightSessions.isRequested(caps("appium:automationName", "Playwright")));
        Assert.assertTrue(PlaywrightSessions.isRequested(caps("appium:automationName", " playwright ")));
        Assert.assertTrue(PlaywrightSessions.isRequested(caps("automationName", "PLAYWRIGHT")));
        Assert.assertFalse(PlaywrightSessions.isRequested(caps("appium:automationName", "UiAutomator2")));
        Assert.assertFalse(PlaywrightSessions.isRequested(caps("appium:deviceName", "Playwright")));
        Assert.assertFalse(PlaywrightSessions.isRequested(null));
    }

    @Test
    public void playwrightSessionIsRefusedWhileTheOptionIsOff() {
        GridException error = Assert.expectThrows(GridException.class,
                () -> PlaywrightSessions.verifyAllowed(caps("appium:automationName", "Playwright")));
        Assert.assertTrue(error.getMessage().contains("PLAYWRIGHT_ENABLED=true"), error.getMessage());
        // the other sessions are not affected
        PlaywrightSessions.verifyAllowed(caps("appium:automationName", "UiAutomator2"));
    }

    @Test
    public void playwrightSessionIsAllowedWithTheOption() {
        PlaywrightSessions.setEnabled(true);
        PlaywrightSessions.verifyAllowed(caps("appium:automationName", "Playwright"));
    }
}
