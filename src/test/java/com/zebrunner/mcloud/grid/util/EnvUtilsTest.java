package com.zebrunner.mcloud.grid.util;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Map;

public class EnvUtilsTest {
    private static final Duration DEFAULT = Duration.ofMinutes(1);

    private static Duration read(Map<String, String> env) {
        return EnvUtils.getDurationInSeconds("TIMEOUT", DEFAULT, env::get);
    }

    @Test
    public void readsSeconds() {
        Assert.assertEquals(read(Map.of("TIMEOUT", "90")), Duration.ofSeconds(90));
        Assert.assertEquals(read(Map.of("TIMEOUT", " 5 ")), Duration.ofSeconds(5));
    }

    @Test
    public void missingOrBlankValueIsDefault() {
        Assert.assertEquals(read(Map.of()), DEFAULT);
        Assert.assertEquals(read(Map.of("TIMEOUT", " ")), DEFAULT);
    }

    @Test
    public void invalidValueIsDefault() {
        // used to fail the class initialization of the proxy with NumberFormatException
        Assert.assertEquals(read(Map.of("TIMEOUT", "10m")), DEFAULT);
    }
}
