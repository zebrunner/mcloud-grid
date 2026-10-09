/*******************************************************************************
 * Copyright 2018-2021 Zebrunner (https://zebrunner.com/).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.integration.client.Path;
import com.zebrunner.mcloud.grid.integration.client.STFClient;
import com.zebrunner.mcloud.grid.models.stf.STFDevice;
import com.zebrunner.mcloud.grid.util.AllowedNetworks;
import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import com.zebrunner.mcloud.grid.util.EnvUtils;
import com.zebrunner.mcloud.grid.util.HttpClient.Response;
import com.zebrunner.mcloud.grid.util.HttpClientApache;
import com.zebrunner.mcloud.grid.util.LogLevels;
import com.zebrunner.mcloud.grid.util.NodeReachability;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.LazyInitializer;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.SessionTerminationReason;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.internal.utils.HtmlRenderer;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;
import org.openqa.selenium.remote.CapabilityType;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.logging.Logger;

import static com.zebrunner.mcloud.grid.validator.DeviceTypeValidator.ZEBRUNNER_DEVICE_TYPE_CAPABILITY;

/**
 * Mobile proxy that connects/disconnects STF devices.
 *
 * @author Alex Khursevich (alex@qaprosoft.com)
 */
public class MobileRemoteProxy extends DefaultRemoteProxy {
    private static final Logger LOGGER = Logger.getLogger(MobileRemoteProxy.class.getName());
    //to operate with RequestedCapabilities where prefix is present
    private static final boolean CHECK_APPIUM_STATUS = Boolean.parseBoolean(System.getenv("CHECK_APPIUM_STATUS"));
    private static final String IS_MANUALLY_RESERVED = "IS_MANUALLY_RESERVED";
    private static final String STF_DISCONNECTED = "STF_DISCONNECTED";
    private static final String STF_TOKEN = "STF_TOKEN";
    private static final String STARTED_LOGGED = "STARTED_LOGGED";
    private static final LazyInitializer<Object> DISCONNECT_ALL_DEVICES = new LazyInitializer<>() {
        @Override
        protected Object initialize() throws ConcurrentException {
            STFClient.disconnectAllDevices();
            return true;
        }
    };
    private static final LazyInitializer<Boolean> INITIAL_GRID_CONFIGURATION_LOGS = new LazyInitializer<Boolean>() {
        @Override
        protected Boolean initialize() throws ConcurrentException {
            LogLevels.configure(System.getenv("MCLOUD_LOG_LEVEL"));
            LOGGER.info(() -> String.format("[CONFIGURATION] STF integration: %s; Appium status check (CHECK_APPIUM_STATUS): %s; "
                            + "node reachability check: %s; nodes allowed from: %s; newCommandTimeout limit: %s; device is ignored after a failed Appium check for %ss, "
                            + "after a session timed out before start for %ss.",
                    STFClient.isSTFEnabled() ? "enabled (" + STFClient.getStfUrl() + ")" : "disabled (STF_URL and STF_TOKEN are not set)",
                    CHECK_APPIUM_STATUS ? "on" : "off",
                    CHECK_NODE_REACHABILITY ? "on (" + NODE_REACHABILITY_TIMEOUT.toSeconds() + "s)" : "off",
                    NODE_ALLOWED_NETWORKS,
                    MAX_NEW_COMMAND_TIMEOUT == null ? "none" : MAX_NEW_COMMAND_TIMEOUT.toSeconds() + "s",
                    UNHEALTHY_MOBILE_TIMEOUT.toSeconds(), INACTIVITY_RELEASE_TIMEOUT.toSeconds()));
            return true;
        }
    };

    // adb/wda timeout
    private static final Duration UNHEALTHY_MOBILE_TIMEOUT = EnvUtils.getDurationInSeconds("UNHEALTHY_MOBILE_TIMEOUT", Duration.ofMinutes(1));

    // optional upper limit of the 'appium:newCommandTimeout' capability, not set = no limit
    private static final Duration MAX_NEW_COMMAND_TIMEOUT = EnvUtils.getDurationInSeconds("MAX_NEW_COMMAND_TIMEOUT", null);

    // a node registered with an address the hub cannot reach is rejected, see #144
    private static final boolean CHECK_NODE_REACHABILITY = !"false".equalsIgnoreCase(System.getenv("CHECK_NODE_REACHABILITY"));
    private static final Duration REJECTED_NODE_WARNING_PERIOD = Duration.ofMinutes(10);
    private static final Map<String, Instant> REJECTED_NODE_WARNINGS = new ConcurrentHashMap<>();
    // nodes may register only from these networks, empty = from anywhere
    private static final AllowedNetworks NODE_ALLOWED_NETWORKS = AllowedNetworks.fromEnv("NODE_ALLOWED_NETWORKS", System.getenv("NODE_ALLOWED_NETWORKS"));
    private static final Duration NODE_REACHABILITY_TIMEOUT = EnvUtils.getDurationInSeconds("NODE_REACHABILITY_TIMEOUT", Duration.ofSeconds(2));

    private static final Duration INACTIVITY_RELEASE_TIMEOUT = EnvUtils.getDurationInSeconds("INACTIVITY_RELEASE_TIMEOUT", Duration.ofMinutes(1));

    private final String udid;
    private final String deviceName;
    private final String deviceType;
    private final Platform platform;
    private final BiFunction<URL, String, Boolean> appiumCheck;
    private final HtmlRenderer htmlRenderer;

    public MobileRemoteProxy(RegistrationRequest request, GridRegistry registry) {
        super(request, registry);
        try {
            INITIAL_GRID_CONFIGURATION_LOGS.get();
        } catch (Exception e) {
            LOGGER.warning(() -> String.format("Could not provide grid configuration logs. Error message: %s", e.getMessage()));
        }
        try {
            DISCONNECT_ALL_DEVICES.get();
        } catch (Exception e) {
            LOGGER.warning(() -> String.format("Could not disconnect STF devices. Error message: %s", e.getMessage()));
        }
        // a node repeats its registration every few seconds: a node the hub already has was checked when it was added
        boolean registered = registry.getProxyById(getId()) != null;
        if (!registered && !NODE_ALLOWED_NETWORKS.allows(getRemoteHost().getHost())) {
            String message = String.format("Node %s is not in NODE_ALLOWED_NETWORKS (%s), so it is not registered.",
                    getRemoteHost(), NODE_ALLOWED_NETWORKS);
            warnAboutRejectedNode(getRemoteHost(), message);
            throw new GridException(message);
        }
        if (CHECK_NODE_REACHABILITY && !registered) {
            URL nodeUrl = getRemoteHost();
            NodeReachability.check(nodeUrl, NODE_REACHABILITY_TIMEOUT).ifPresent(reason -> {
                String message = String.format("Node %s is not reachable from the hub (%s), so it is not registered. "
                        + "Check the address the node registers with: it must be accessible from the hub.", nodeUrl, reason);
                warnAboutRejectedNode(nodeUrl, message);
                throw new GridException(message);
            });
        }
        TestSlot slot = getTestSlots().stream()
                .findAny()
                .orElseThrow(() -> new GridException("Node should have slot"));
        udid = CapabilityUtils.getAppiumCapability(slot.getCapabilities(), "udid")
                .orElseThrow(() -> new GridException(String.format("Appium node must have 'UDID' capability. Slot capabilities: %s",
                        slot.getCapabilities())))
                .toString();
        deviceName = CapabilityUtils.getAppiumCapability(slot.getCapabilities(), "deviceName")
                .orElseThrow(() -> new GridException(String.format("Appium node must have 'deviceName' capability. Slot capabilities: %s",
                        slot.getCapabilities())))
                .toString();
        deviceType = CapabilityUtils.getZebrunnerCapability(slot.getCapabilities(), ZEBRUNNER_DEVICE_TYPE_CAPABILITY)
                .map(String::valueOf)
                .orElse(null);
        platform = Platform.fromCapabilities(slot.getCapabilities());
        if (CHECK_APPIUM_STATUS) {
            switch (platform) {
            case ANDROID:
                appiumCheck = (remoteURL, sessionUUID) -> {
                    Response<String> response = HttpClientApache.create()
                            .withUri(Path.APPIUM_STATUS_ADB, remoteURL.toString())
                            .get(new StringEntity("{\"exitCode\": 101}", ContentType.APPLICATION_JSON));
                    if (response.getStatus() != 200) {
                        LOGGER.warning(() ->
                                String.format("[%s][%s] Appium /status-adb check failed (HTTP %s): %s",
                                        udid, sessionUUID, response.getStatus(), response.getObject()));
                        return false;
                    }
                    return true;
                };
                break;
            case IOS:
                appiumCheck = (remoteURL, sessionUUID) -> {
                    Response<String> response = HttpClientApache.create()
                            .withUri(Path.APPIUM_STATUS_WDA, remoteURL.toString())
                            .get(new StringEntity("{\"exitCode\": 101}", ContentType.APPLICATION_JSON));
                    if (response.getStatus() != 200) {
                        LOGGER.warning(() ->
                                String.format("[%s][%s] Appium /status-wda check failed (HTTP %s): %s",
                                        udid, sessionUUID, response.getStatus(), response.getObject()));
                        return false;
                    }
                    return true;
                };
                break;
            default:
                LOGGER.fine(() -> String.format("[%s] No Appium status check for platform %s, the device is not checked.", udid, platform));
                appiumCheck = (remoteURL, sessionUUID) -> true;
                break;
            }
        } else {
            appiumCheck = (remoteURL, sessionUUID) -> true;
        }

        htmlRenderer = new ProxyHtmlRenderer(() -> this, udid);

        if (STFClient.isSTFEnabled() && !registered) {
            if (!STFClient.isDevicePresentInSTF(udid)) {
                throw new GridException(String.format("Could not find device with udid '%s' in STF. Slot capabilities: %s",
                        udid, slot.getCapabilities()));
            }
        }
    }

    public void beforeCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        super.beforeCommand(session, request, response);
        LOGGER.finest(() -> String.format("[%s][%s] before command: %s", udid, session.getInternalKey(), request.getRequestURI()));
    }

    public void afterCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        super.afterCommand(session, request, response);
        if (session.getExternalKey() != null && session.get(STARTED_LOGGED) == null) {
            session.put(STARTED_LOGGED, true);
            LOGGER.info(() -> String.format("[%s][%s] Appium session '%s' is started on '%s'.",
                    udid, session.getInternalKey(), session.getExternalKey().getKey(), deviceName));
        }
        LOGGER.finest(() -> String.format("[%s][%s] after command: %s", udid, session.getInternalKey(), request.getRequestURI()));
    }

    @Override
    public TestSession getNewSession(Map<String, Object> requestedCapability) {

        if (isDown()) {
            LOGGER.fine(() -> String.format("[%s] Node %s is down, the device is skipped.", udid, getRemoteHost()));
            return null;
        }

        if (!hasCapability(requestedCapability)) {
            return null;
        }

        if (getTotalUsed() >= 1) {
            return null;
        }

        Optional<IgnoredDevices.Entry> ignored = IgnoredDevices.get(udid);
        if (ignored.isPresent()) {
            LOGGER.fine(() -> String.format("[%s] Device is ignored for %ss more (%s), it is skipped.",
                    udid, ignored.get().secondsLeft(), ignored.get().getReason()));
            return null;
        }

        for (TestSlot testslot : getTestSlots()) {
            TestSession session = testslot.getNewSession(requestedCapability);
            if (session == null) {
                LOGGER.fine(() -> String.format("[%s] Test slot did not create a session (it is busy or does not match).", udid));
                return null;
            }

            String internalKey = session.getInternalKey();

            // additional check if device is ready for session with custom Appium's status verification
            if (!appiumCheck.apply(testslot.getRemoteURL(), internalKey)) {
                IgnoredDevices.ignore(udid, UNHEALTHY_MOBILE_TIMEOUT, "Appium status check failed");
                LOGGER.warning(() -> String.format("[%s][%s] Device '%s' is not ready for a session, it is ignored for %s seconds.",
                        udid, internalKey, deviceName, UNHEALTHY_MOBILE_TIMEOUT.toSeconds()));
                testslot.doFinishRelease();
                return null;
            }

            if (STFClient.isSTFEnabled()) {
                STFDevice device = STFClient.reserveSTFDevice(udid, requestedCapability, internalKey);
                if (device == null) {
                    testslot.doFinishRelease();
                    return null;
                }
                String stfToken = CapabilityUtils.getZebrunnerCapability(requestedCapability, "STF_TOKEN")
                        .map(String::valueOf)
                        .orElse(STFClient.DEFAULT_STF_TOKEN);
                session.put(STF_TOKEN, stfToken);
                session.put(IS_MANUALLY_RESERVED, !StringUtils.equals(stfToken, STFClient.DEFAULT_STF_TOKEN));

                Map<String, Object> slotCapabilities = getSlotCapabilities(testslot, deviceType, device);
                LOGGER.fine(() -> String.format("[%s][%s] 'zebrunner:slotCapabilities' of the session: %s", udid, internalKey, slotCapabilities));
                requestedCapability.put("zebrunner:slotCapabilities", slotCapabilities);
            }
            LOGGER.info(() -> String.format("[%s][%s] Device '%s' (%s %s) is selected for the session, starting the Appium session.",
                    udid, internalKey, deviceName, platform, CapabilityUtils.getAppiumCapability(testslot.getCapabilities(), "platformVersion").orElse("")));
            return session;
        }
        return null;
    }

    @Override
    public void beforeSession(TestSession session) {
        String internalKey = session.getInternalKey();
        if (StringUtils.equalsIgnoreCase(deviceType, "tvos")) {
            //override platformName for the appium capabilities into tvOS
            LOGGER.fine(() -> String.format("[%s][%s] tvOS device: 'platformName' of the session is tvOS.", udid, internalKey));
            session.getRequestedCapabilities()
                    .put(CapabilityType.PLATFORM_NAME, "tvOS");
        }
        limitNewCommandTimeout(session.getRequestedCapabilities(), internalKey);
    }

    /**
     * A big 'newCommandTimeout' keeps a device busy long after its client is gone, see #110.
     */
    private void limitNewCommandTimeout(Map<String, Object> capabilities, String internalKey) {
        if (MAX_NEW_COMMAND_TIMEOUT == null) {
            return;
        }
        for (String name : new String[] {"appium:newCommandTimeout", "newCommandTimeout"}) {
            Object value = capabilities.get(name);
            if (value == null) {
                continue;
            }
            long requested;
            try {
                requested = Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            // 0 disables the timeout in Appium
            if (requested <= 0 || requested > MAX_NEW_COMMAND_TIMEOUT.toSeconds()) {
                capabilities.put(name, MAX_NEW_COMMAND_TIMEOUT.toSeconds());
                LOGGER.info(() -> String.format("[%s][%s] '%s' %s is limited to MAX_NEW_COMMAND_TIMEOUT %s seconds.",
                        udid, internalKey, name, requested, MAX_NEW_COMMAND_TIMEOUT.toSeconds()));
            }
        }
    }

    @Override
    public void afterSession(TestSession session) {
        String internalKey = session.getInternalKey();
        LOGGER.info(() -> String.format("[%s][%s] Session %s is finished after %s. Last command: %s",
                udid, internalKey, describeExternalSession(session), sessionDuration(), session.get("lastCommand")));
        disconnectSTFDevice(session);
    }

    // for 'as TIMED OUT due to client inactivity and will be released' exception
    @Override
    public void beforeRelease(TestSession session) {
        super.beforeRelease(session);
        String internalKey = session.getInternalKey();
        LOGGER.warning(() -> String.format("[%s][%s] Session %s timed out: no commands from the client for %ss, the device is released. Last command: %s",
                udid, internalKey, describeExternalSession(session), session.getInactivityTime() / 1000, session.get("lastCommand")));
        if (session.getExternalKey() == null) {
            LOGGER.warning(() -> String.format("[%s][%s] The Appium session was not started, the device is ignored for %s seconds.",
                    udid, internalKey, INACTIVITY_RELEASE_TIMEOUT.toSeconds()));
            IgnoredDevices.ignore(udid, INACTIVITY_RELEASE_TIMEOUT, "session timed out before it was started on the device");
        }
        disconnectSTFDevice(session);
    }

    /**
     * Returns the device to STF once per session: on inactivity timeout the grid calls both beforeRelease and afterSession.
     */
    private void disconnectSTFDevice(TestSession session) {
        if (!STFClient.isSTFEnabled() || session.get(STF_DISCONNECTED) != null) {
            return;
        }
        session.put(STF_DISCONNECTED, true);
        String stfToken = Optional.ofNullable(session.get(STF_TOKEN))
                .map(String::valueOf)
                .orElse(STFClient.DEFAULT_STF_TOKEN);
        STFClient.disconnectSTFDevice(udid, platform, Boolean.TRUE.equals(session.get(IS_MANUALLY_RESERVED)), stfToken, session.getInternalKey());
    }

    /**
     * Terminates the session running on the device, e.g. when automation is stopped while the client is still alive (#65).
     * The device is released in STF with the key of the caller first: STF allows it only for the user the device is reserved by
     * or for an STF admin, so a key of any other user is refused (403) and the session keeps running. Whether the key belongs
     * to an admin is not checked here, STF decides.
     *
     * @return HTTP status of the STF release: 200 when the session is terminated
     */
    public int terminateSession(TestSession session, String stfToken) {
        String internalKey = session.getInternalKey();
        int status = STFClient.releaseDevice(udid, platform, stfToken, internalKey);
        if (status != 200) {
            return status;
        }
        // the device is already released in STF, afterSession must not return it again with the grid token
        session.put(STF_DISCONNECTED, true);
        if (session.getExternalKey() != null && !session.sendDeleteSessionRequest()) {
            LOGGER.warning(() -> String.format("[%s][%s] Node did not delete the Appium session %s, the device is released anyway.",
                    udid, internalKey, describeExternalSession(session)));
        }
        LOGGER.info(() -> String.format("[%s][%s] Session %s is terminated through the API.", udid, internalKey, describeExternalSession(session)));
        getRegistry().terminate(session, SessionTerminationReason.CLIENT_STOPPED_SESSION);
        return status;
    }

    /**
     * A rejected node retries the registration every few seconds: it is warned about once in a while.
     */
    private static void warnAboutRejectedNode(URL nodeUrl, String message) {
        Instant lastWarning = REJECTED_NODE_WARNINGS.get(nodeUrl.toString());
        if (lastWarning == null || lastWarning.plus(REJECTED_NODE_WARNING_PERIOD).isBefore(Instant.now())) {
            REJECTED_NODE_WARNINGS.put(nodeUrl.toString(), Instant.now());
            LOGGER.warning(() -> "[NODE REGISTRATION] " + message);
        } else {
            LOGGER.fine(() -> "[NODE REGISTRATION] " + message);
        }
    }

    @Override
    public HtmlRenderer getHtmlRender() {
        return htmlRenderer;
    }

    public String getUdid() {
        return udid;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getDeviceType() {
        return deviceType;
    }

    private static Map<String, Object> getSlotCapabilities(TestSlot slot, String deviceType, STFDevice stfDevice) {
        Map<String, Object> slotCapabilities = new HashMap<>(slot.getCapabilities());
        if (deviceType != null && StringUtils.equalsIgnoreCase("tvos", deviceType)) {
            slotCapabilities.put(CapabilityType.PLATFORM_NAME, "tvOS");
        }

        if (stfDevice != null) {
            String remoteURL = (String) stfDevice.getRemoteConnectUrl();
            slotCapabilities.put("remoteURL", remoteURL);
        }
        return slotCapabilities;
    }

    /**
     * The external key is the Appium session id, the internal key is the id of the session inside the hub.
     */
    private static String describeExternalSession(TestSession session) {
        return session.getExternalKey() != null ? "'" + session.getExternalKey().getKey() + "'" : "(no Appium session)";
    }

    private String sessionDuration() {
        long started = getTestSlots().get(0).getLastSessionStart();
        return started > 0 ? (System.currentTimeMillis() - started) / 1000 + "s" : "unknown time";
    }
}
