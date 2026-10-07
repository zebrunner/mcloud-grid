package com.zebrunner.mcloud.grid.util;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Optional;

public class CapabilityUtilsTest {

    @Test
    public void appiumCapabilityPrefersPrefixedValue() {
        Map<String, Object> caps = Map.of("appium:udid", "prefixed", "udid", "plain");
        Assert.assertEquals(CapabilityUtils.getAppiumCapability(caps, "udid"), Optional.of("prefixed"));
    }

    @Test
    public void appiumCapabilityFallsBackToPlainName() {
        Assert.assertEquals(CapabilityUtils.getAppiumCapability(Map.of("udid", "plain"), "udid"), Optional.of("plain"));
        Assert.assertEquals(CapabilityUtils.getAppiumCapability(Map.of(), "udid"), Optional.empty());
    }

    @Test
    public void appiumCapabilityIgnoresZebrunnerPrefix() {
        Assert.assertEquals(CapabilityUtils.getAppiumCapability(Map.of("zebrunner:udid", "z"), "udid"), Optional.empty());
    }

    @Test
    public void zebrunnerCapabilityLookupOrder() {
        Assert.assertEquals(CapabilityUtils.getZebrunnerCapability(
                Map.of("zebrunner:STF_TOKEN", "z", "appium:STF_TOKEN", "a", "STF_TOKEN", "p"), "STF_TOKEN"), Optional.of("z"));
        Assert.assertEquals(CapabilityUtils.getZebrunnerCapability(
                Map.of("appium:STF_TOKEN", "a", "STF_TOKEN", "p"), "STF_TOKEN"), Optional.of("a"));
        Assert.assertEquals(CapabilityUtils.getZebrunnerCapability(Map.of("STF_TOKEN", "p"), "STF_TOKEN"), Optional.of("p"));
        Assert.assertEquals(CapabilityUtils.getZebrunnerCapability(Map.of(), "STF_TOKEN"), Optional.empty());
    }
}
