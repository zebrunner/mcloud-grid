package com.zebrunner.mcloud.grid.validator;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

public class ValidatorsTest {

    private static Map<String, Object> caps(Object... keyValues) {
        Map<String, Object> caps = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            caps.put((String) keyValues[i], keyValues[i + 1]);
        }
        return caps;
    }

    @DataProvider
    public Object[][] platformVersions() {
        return new Object[][] {
                // requested, node, expected
                {null, "11", true},
                {"ANY", "11", true},
                {"any", null, true},
                {"", "11", true},
                {"11", null, false},
                {"11", "11", true},
                {"11.0", "11.0", true},
                {"11", "12", false},
                {"11.1.2", "11.1.2", true},
                {"11.1.2", "11.1.3", false},
                {"10-12", "11", true},
                {"10-12", "10", true},
                {"10-12", "12", true},
                {"10-12", "9.9", false},
                {"10-12", "12.1", false},
                {"6.1.1-7.0", "6.1.2", true},
                {"10+", "10", true},
                {"10+", "15.4", true},
                {"10+", "9.3", false},
                {"10,12", "12", true},
                {"10,12", "11", false},
                {"10.1,12.4.1", "12.4.1", true},
                {"garbage", "11", false},
                // trailing zeros are not significant
                {"7", "7.0", true},
                {"7.0", "7", true},
                {"7.0.0", "7", true},
                {"10-12", "12.0", true},
                {"10.0+", "10", true},
                {"10,12", "12.0.0", true},
                // node version that cannot be parsed never matches a version constraint
                {"11", "unknown", false},
                {"10-12", "beta", false},
                {"10+", "", false},
                {"10,12", "x", false},
        };
    }

    @Test(dataProvider = "platformVersions")
    public void platformVersion(String requested, String node, boolean expected) {
        Map<String, Object> requestedCaps = requested == null ? caps() : caps("appium:platformVersion", requested);
        Map<String, Object> nodeCaps = node == null ? caps() : caps("appium:platformVersion", node);
        Assert.assertEquals(new PlatformVersionValidator().apply(nodeCaps, requestedCaps), Boolean.valueOf(expected));
    }

    @Test
    public void platformVersionSupportsCapabilitiesWithoutPrefix() {
        Assert.assertTrue(new PlatformVersionValidator().apply(caps("platformVersion", "11"), caps("platformVersion", "11")));
        Assert.assertTrue(new PlatformVersionValidator().apply(caps("appium:platformVersion", "11"), caps("platformVersion", "11")));
    }

    @DataProvider
    public Object[][] listValues() {
        return new Object[][] {
                {null, "a", true},
                {"ANY", "a", true},
                {"a", null, false},
                {"a", "a", true},
                {"a", "b", false},
                {"a,b,c", "b", true},
                {"a,b,c", "d", false},
        };
    }

    @Test(dataProvider = "listValues")
    public void deviceName(String requested, String node, boolean expected) {
        Map<String, Object> requestedCaps = requested == null ? caps() : caps("appium:deviceName", requested);
        Map<String, Object> nodeCaps = node == null ? caps() : caps("appium:deviceName", node);
        Assert.assertEquals(new DeviceNameValidator().apply(nodeCaps, requestedCaps), Boolean.valueOf(expected));
    }

    @Test(dataProvider = "listValues")
    public void udid(String requested, String node, boolean expected) {
        Map<String, Object> requestedCaps = requested == null ? caps() : caps("appium:udid", requested);
        Map<String, Object> nodeCaps = node == null ? caps() : caps("appium:udid", node);
        Assert.assertEquals(new UDIDValidator().apply(nodeCaps, requestedCaps), Boolean.valueOf(expected));
    }

    @DataProvider
    public Object[][] deviceTypes() {
        return new Object[][] {
                {null, "phone", true},
                {"ANY", "phone", true},
                {"phone", null, false},
                {"Phone", "phone", true},
                {"tvos", "TVOS", true},
                {"tablet", "phone", false},
        };
    }

    @Test(dataProvider = "deviceTypes")
    public void deviceType(String requested, String node, boolean expected) {
        Map<String, Object> requestedCaps = requested == null ? caps() : caps("zebrunner:deviceType", requested);
        Map<String, Object> nodeCaps = node == null ? caps() : caps("zebrunner:deviceType", node);
        Assert.assertEquals(new DeviceTypeValidator().apply(nodeCaps, requestedCaps), Boolean.valueOf(expected));
    }

    @Test
    public void deviceTypeFallsBackToAppiumAndPlainCapabilities() {
        Assert.assertTrue(new DeviceTypeValidator().apply(caps("appium:deviceType", "tablet"), caps("deviceType", "tablet")));
        Assert.assertFalse(new DeviceTypeValidator().apply(caps("deviceType", "phone"), caps("zebrunner:deviceType", "tablet")));
    }

    @DataProvider
    public Object[][] platforms() {
        return new Object[][] {
                {null, "ANDROID", true},
                {"ANY", "ANDROID", true},
                {"android", "ANDROID", true},
                {"Android", "android", true},
                {"iOS", "IOS", true},
                {"iOS", "ANDROID", false},
                {"ANDROID", null, false},
                // not a Selenium platform: compared as strings
                {"tvOS", "TVOS", true},
                {"tvOS", "ios", false},
        };
    }

    @Test(dataProvider = "platforms")
    public void mobilePlatform(String requested, String node, boolean expected) {
        Map<String, Object> requestedCaps = requested == null ? caps() : caps("platformName", requested);
        Map<String, Object> nodeCaps = node == null ? caps() : caps("platformName", node);
        Assert.assertEquals(new MobilePlatformValidator().apply(nodeCaps, requestedCaps), Boolean.valueOf(expected));
    }

    @Test
    public void mobilePlatformAcceptsSeleniumPlatformObjects() {
        Assert.assertTrue(new MobilePlatformValidator()
                .apply(caps("platformName", org.openqa.selenium.Platform.ANDROID), caps("platformName", org.openqa.selenium.Platform.ANDROID)));
        Assert.assertTrue(new MobilePlatformValidator()
                .apply(caps("platformName", "ANDROID"), caps("platformName", org.openqa.selenium.Platform.ANY)));
    }
}
