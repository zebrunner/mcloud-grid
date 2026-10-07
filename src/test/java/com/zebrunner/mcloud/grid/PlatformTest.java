package com.zebrunner.mcloud.grid;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

public class PlatformTest {

    @Test
    public void detectsPlatformCaseInsensitively() {
        Assert.assertEquals(Platform.fromCapabilities(Map.of("platformName", "Android")), Platform.ANDROID);
        Assert.assertEquals(Platform.fromCapabilities(Map.of("platformName", "iOS")), Platform.IOS);
        Assert.assertEquals(Platform.fromCapabilities(Map.of("platformName", "tvOS")), Platform.TVOS);
    }

    @Test
    public void unknownPlatformIsAny() {
        Assert.assertEquals(Platform.fromCapabilities(Map.of("platformName", "Tizen")), Platform.ANY);
        Assert.assertEquals(Platform.fromCapabilities(Map.of("platformName", " android ")), Platform.ANDROID);
    }

    @Test
    public void missingPlatformIsAny() {
        Assert.assertEquals(Platform.fromCapabilities(null), Platform.ANY);
        Assert.assertEquals(Platform.fromCapabilities(Map.of()), Platform.ANY);
        Map<String, Object> caps = new HashMap<>();
        caps.put("platformName", null);
        Assert.assertEquals(Platform.fromCapabilities(caps), Platform.ANY);
    }
}
