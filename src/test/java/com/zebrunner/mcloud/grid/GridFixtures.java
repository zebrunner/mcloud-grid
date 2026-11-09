package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.integration.client.NodeTokenVerifier;
import com.zebrunner.mcloud.grid.integration.client.StfNodes;
import com.zebrunner.mcloud.grid.util.NodeRegistrationToken;
import com.zebrunner.mcloud.grid.util.NodeToken;
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
 * Builds real (not mocked) Selenium Grid objects for proxy tests. The nodes register with {@link #NODE_TOKEN}, which the
 * hub accepts at any host without STF (see {@link #useTestNodeAuthentication()}), unless a test verifies it with the STF
 * stub ({@link #useStfNodeAuthentication()}).
 */
public final class GridFixtures {
    public static final String NODE_LABEL = "test-node";
    public static final String NODE_TOKEN = "mcn1." + NODE_LABEL + ".AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";

    private static final NodeTokenVerifier TEST_VERIFIER = new NodeTokenVerifier() {
        @Override
        public StfNodes.Verification verify(NodeToken token, String host) {
            return verifyNow(token, host);
        }

        @Override
        public StfNodes.Verification verifyNow(NodeToken token, String host) {
            return new StfNodes.Verification(NODE_TOKEN.equals(token.getValue()) ? StfNodes.Status.VALID : StfNodes.Status.REJECTED,
                    NODE_TOKEN.equals(token.getValue()) ? 200 : 403);
        }
    };

    static {
        useTestNodeAuthentication();
    }

    private GridFixtures() {
        //hide
    }

    /**
     * The hub accepts {@link #NODE_TOKEN} at any host, without STF.
     */
    public static void useTestNodeAuthentication() {
        MobileRemoteProxy.useNodeAuthentication(new NodeAuthentication(TEST_VERIFIER));
    }

    /**
     * The hub verifies the node tokens with STF_URL and STF_TOKEN of the test execution (the STF stub), with fresh caches.
     */
    public static void useStfNodeAuthentication() {
        MobileRemoteProxy.useNodeAuthentication(NodeAuthentication.fromEnv());
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
        return registrationRequest(nodeUrl, capabilities, NODE_TOKEN);
    }

    /**
     * @param token the token in 'custom' of the configuration
     */
    public static RegistrationRequest registrationRequest(String nodeUrl, Map<String, Object> capabilities, String token) {
        GridNodeConfiguration config = new GridNodeConfiguration();
        config.id = nodeUrl;
        config.remoteHost = nodeUrl;
        config.proxy = MobileRemoteProxy.class.getName();
        config.custom = new HashMap<>(Map.of(NodeRegistrationToken.CUSTOM_KEY, token));
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
