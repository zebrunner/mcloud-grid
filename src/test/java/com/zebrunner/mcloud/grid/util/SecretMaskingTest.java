package com.zebrunner.mcloud.grid.util;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SecretMaskingTest {

    @Test
    public void secretNames() {
        for (String name : new String[] {"zebrunner:STF_TOKEN", "STF_TOKEN", "appium:keystorePassword", "appium:keyPassword",
                "x:clientSecret", "remoteURL", "X-Amz-Signature", "X-Amz-Credential", "sig", "apiKey", "accessKey", "Authorization"}) {
            Assert.assertTrue(SecretMasking.isSecretName(name), name);
        }
        for (String name : new String[] {"platformName", "appium:udid", "appium:unicodeKeyboard", "appium:app", "deviceName",
                "appium:noReset", "zebrunner:slotCapabilities", "signal", null}) {
            Assert.assertFalse(SecretMasking.isSecretName(name), String.valueOf(name));
        }
    }

    @Test
    public void secretsOfCapabilitiesAreMasked() {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("udid", "emulator-5554");
        slot.put("remoteURL", "10.0.0.5:7401");
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("platformName", "Android");
        capabilities.put("zebrunner:STF_TOKEN", "personal-token");
        capabilities.put("zebrunner:slotCapabilities", slot);
        capabilities.put("appium:app", "https://user:pass@apps.example.com/app.apk?X-Amz-Signature=abc&version=2");
        capabilities.put("appium:otherApps", List.of("https://apps.example.com/a.apk?token=t1", "/local/b.apk"));
        capabilities.put("appium:newCommandTimeout", 300);
        capabilities.put("appium:keyPassword", null);

        Map<String, Object> masked = SecretMasking.mask(capabilities);

        Assert.assertEquals(masked.get("platformName"), "Android");
        Assert.assertEquals(masked.get("zebrunner:STF_TOKEN"), "*****");
        Assert.assertEquals(masked.get("zebrunner:slotCapabilities"), Map.of("udid", "emulator-5554", "remoteURL", "*****"));
        Assert.assertEquals(masked.get("appium:app"), "https://*****@apps.example.com/app.apk?X-Amz-Signature=*****&version=2");
        Assert.assertEquals(masked.get("appium:otherApps"), List.of("https://apps.example.com/a.apk?token=*****", "/local/b.apk"));
        Assert.assertEquals(masked.get("appium:newCommandTimeout"), 300);
        Assert.assertTrue(masked.containsKey("appium:keyPassword"));
        Assert.assertNull(masked.get("appium:keyPassword"));
        // the capabilities of the session are not changed
        Assert.assertEquals(capabilities.get("zebrunner:STF_TOKEN"), "personal-token");
        Assert.assertEquals(slot.get("remoteURL"), "10.0.0.5:7401");
    }

    @Test
    public void textWithoutUrlsIsKept() {
        Assert.assertEquals(SecretMasking.maskUrl("a?token=b"), "a?token=b");
        Assert.assertEquals(SecretMasking.maskUrl("http://hub:4444/wd/hub"), "http://hub:4444/wd/hub");
    }
}
