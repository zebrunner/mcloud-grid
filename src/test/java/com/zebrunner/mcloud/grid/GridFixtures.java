package com.zebrunner.mcloud.grid;

import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.DefaultGridRegistry;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.utils.configuration.GridHubConfiguration;
import org.openqa.grid.internal.utils.configuration.GridNodeConfiguration;
import org.openqa.grid.web.Hub;
import org.openqa.selenium.MutableCapabilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds real (not mocked) Selenium Grid objects for proxy tests.
 */
public final class GridFixtures {

    private GridFixtures() {
        //hide
    }

    public static GridRegistry registry() {
        GridHubConfiguration config = new GridHubConfiguration();
        config.capabilityMatcher = new MobileCapabilityMatcher();
        // keep the hub from binding a random port in tests
        config.port = 0;
        return DefaultGridRegistry.newInstance(new Hub(config));
    }

    public static Map<String, Object> androidNodeCaps(String udid) {
        Map<String, Object> caps = new HashMap<>();
        caps.put("platformName", "ANDROID");
        caps.put("appium:platformVersion", "13");
        caps.put("appium:deviceName", "Pixel-" + udid);
        caps.put("appium:udid", udid);
        caps.put("zebrunner:deviceType", "phone");
        return caps;
    }

    public static Map<String, Object> iosNodeCaps(String udid) {
        Map<String, Object> caps = new HashMap<>();
        caps.put("platformName", "iOS");
        caps.put("appium:platformVersion", "17.2");
        caps.put("appium:deviceName", "iPhone-" + udid);
        caps.put("appium:udid", udid);
        caps.put("zebrunner:deviceType", "phone");
        return caps;
    }

    public static RegistrationRequest registrationRequest(String nodeUrl, Map<String, Object> capabilities) {
        GridNodeConfiguration config = new GridNodeConfiguration();
        config.id = nodeUrl;
        config.remoteHost = nodeUrl;
        config.proxy = MobileRemoteProxy.class.getName();
        MutableCapabilities caps = new MutableCapabilities(capabilities);
        caps.setCapability(RegistrationRequest.MAX_INSTANCES, 1);
        List<MutableCapabilities> list = new ArrayList<>();
        list.add(caps);
        config.capabilities = list;
        return new RegistrationRequest(config);
    }

    public static MobileRemoteProxy proxy(GridRegistry registry, String nodeUrl, Map<String, Object> capabilities) {
        return new MobileRemoteProxy(registrationRequest(nodeUrl, capabilities), registry);
    }
}
