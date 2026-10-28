package com.zebrunner.mcloud.grid;

import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.utils.configuration.GridHubConfiguration;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;
import org.openqa.grid.web.Hub;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The registry of the hub config (generate_config): only MobileRemoteProxy nodes, queued requests safe for the console.
 */
public class MobileGridRegistryTest {
    private static final String NODE_URL = "http://localhost:4723";

    private GridRegistry registry;

    @BeforeMethod
    public void setUp() {
        GridHubConfiguration config = new GridHubConfiguration();
        config.capabilityMatcher = new MobileCapabilityMatcher();
        config.registry = MobileGridRegistry.class.getName();
        config.port = 0;
        registry = new Hub(config).getRegistry();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        registry.stop();
    }

    @Test
    public void hubUsesTheRegistry() {
        Assert.assertTrue(registry instanceof MobileGridRegistry, registry.getClass().getName());
    }

    @Test
    public void mobileProxyIsAdded() {
        MobileRemoteProxy proxy = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps("emulator-5554"));

        registry.add(proxy);

        Assert.assertSame(registry.getProxyById(NODE_URL), proxy);
    }

    @Test
    public void proxyOfAnotherClassIsRejected() {
        RegistrationRequest request = GridFixtures.registrationRequest(NODE_URL, GridFixtures.androidNodeCaps("emulator-5554"));
        request.getConfiguration().proxy = DefaultRemoteProxy.class.getName();
        RemoteProxy proxy = DefaultRemoteProxy.getNewInstance(request, registry);

        registry.add(proxy);

        Assert.assertNull(registry.getProxyById(NODE_URL));
    }

    @Test
    public void proxyOfAnotherClassDoesNotReplaceRegisteredNode() {
        MobileRemoteProxy registered = GridFixtures.proxy(registry, NODE_URL, GridFixtures.androidNodeCaps("emulator-5554"));
        registry.add(registered);
        RegistrationRequest request = GridFixtures.registrationRequest("http://attacker:4723", GridFixtures.androidNodeCaps("emulator-5554"));
        request.getConfiguration().id = NODE_URL;
        request.getConfiguration().proxy = DefaultRemoteProxy.class.getName();

        registry.add(DefaultRemoteProxy.getNewInstance(request, registry));

        Assert.assertSame(registry.getProxyById(NODE_URL), registered);
        Assert.assertEquals(registry.getAllProxies().size(), 1);
    }

    @Test
    public void queuedRequestsOfTheConsoleHaveNoSecretsAndAreEscaped() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("platformName", "Android");
        request.put("zebrunner:STF_TOKEN", "personal-token");
        request.put("appium:deviceName", "<img src=x onerror=alert(1)>");

        String html = new MobileGridRegistry.ConsoleCapabilities(new DesiredCapabilities(request)).toString();

        Assert.assertFalse(html.contains("personal-token"), html);
        Assert.assertTrue(html.contains("*****"), html);
        Assert.assertFalse(html.contains("<img"), html);
        Assert.assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), html);
    }

    @Test
    public void emptyQueue() {
        Assert.assertFalse(registry.getDesiredCapabilities().iterator().hasNext());
    }
}
