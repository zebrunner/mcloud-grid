package com.zebrunner.mcloud.grid;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;

public class IgnoredDevicesTest {

    @BeforeMethod
    @AfterMethod(alwaysRun = true)
    public void clear() {
        IgnoredDevices.clear();
    }

    @Test
    public void ignoredDeviceHasReasonAndTimeLeft() {
        IgnoredDevices.ignore("udid-1", Duration.ofMinutes(2), "unhealthy in STF");

        IgnoredDevices.Entry entry = IgnoredDevices.get("udid-1").orElseThrow();
        Assert.assertEquals(entry.getReason(), "unhealthy in STF");
        Assert.assertTrue(entry.secondsLeft() > 110 && entry.secondsLeft() <= 120, String.valueOf(entry.secondsLeft()));
        Assert.assertFalse(IgnoredDevices.isIgnored("udid-2"));
    }

    @Test
    public void expiredEntryIsRemoved() {
        IgnoredDevices.ignore("udid-1", Duration.ofSeconds(-1), "old");

        Assert.assertFalse(IgnoredDevices.isIgnored("udid-1"));
        Assert.assertTrue(IgnoredDevices.snapshot().isEmpty());
    }

    @Test
    public void snapshotContainsActiveEntriesOnly() {
        IgnoredDevices.ignore("active", Duration.ofMinutes(1), "a");
        IgnoredDevices.ignore("expired", Duration.ofSeconds(-1), "b");

        Assert.assertEquals(IgnoredDevices.snapshot().keySet(), java.util.Set.of("active"));
    }

    @Test
    public void newerReasonReplacesOlder() {
        IgnoredDevices.ignore("udid-1", Duration.ofMinutes(1), "first");
        IgnoredDevices.ignore("udid-1", Duration.ofMinutes(10), "second");

        Assert.assertEquals(IgnoredDevices.get("udid-1").orElseThrow().getReason(), "second");
    }
}
