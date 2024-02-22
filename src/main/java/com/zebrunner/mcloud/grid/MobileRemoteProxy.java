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

import com.zebrunner.mcloud.grid.integration.client.MitmProxyClient;
import com.zebrunner.mcloud.grid.integration.client.Path;
import com.zebrunner.mcloud.grid.integration.client.STFClient;
import com.zebrunner.mcloud.grid.models.stf.STFDevice;
import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import com.zebrunner.mcloud.grid.util.HttpClient.Response;
import com.zebrunner.mcloud.grid.util.HttpClientApache;
import com.zebrunner.mcloud.grid.validator.ProxyValidator;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.LazyInitializer;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;
import org.openqa.selenium.remote.CapabilityType;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.logging.Logger;

import static com.zebrunner.mcloud.grid.validator.DeviceTypeValidator.ZEBRUNNER_DEVICE_TYPE_CAPABILITY;
import static com.zebrunner.mcloud.grid.validator.ProxyValidator.MITM_TYPE_CAPABILITY;
import static com.zebrunner.mcloud.grid.validator.ProxyValidator.PROXY_PORT_CAPABILITY;
import static com.zebrunner.mcloud.grid.validator.ProxyValidator.SERVER_PROXY_PORT_CAPABILITY;

/**
 * Mobile proxy that connects/disconnects STF devices.
 *
 * @author Alex Khursevich (alex@qaprosoft.com)
 */
public class MobileRemoteProxy extends DefaultRemoteProxy {
    private static final Logger LOGGER = Logger.getLogger(MobileRemoteProxy.class.getName());
    //to operate with RequestedCapabilities where prefix is present
    private static final boolean CHECK_APPIUM_STATUS = Boolean.parseBoolean(System.getenv("CHECK_APPIUM_STATUS"));
    private static final String SESSION_UUID_PARAMETER = "SESSION_UUID_PARAMETER";
    private static final String IS_MANUALLY_RESERVED = "IS_MANUALLY_RESERVED";
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
            if (CHECK_APPIUM_STATUS) {
                LOGGER.warning(() -> "[CONFIGURATION] 'CHECK_APPIUM_STATUS' is enabled so additional Appium health-check will be verified.");
            } else {
                LOGGER.warning(() -> "[CONFIGURATION] 'CHECK_APPIUM_STATUS' is not enabled.");
            }
            return true;
        }
    };

    private final String udid;
    private final String deviceName;
    private final String deviceType;
    private final Platform platform;
    private final boolean isMitmSupported;
    private final BiFunction<URL, String, Boolean> appiumCheck;

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
        TestSlot slot = getTestSlots().stream()
                .findAny()
                .orElseThrow(() -> new GridException("Node should have slot"));
        udid = CapabilityUtils.getAppiumCapability(slot.getCapabilities(), "udid")
                .orElseThrow(() -> new GridException(String.format("Appium node must have 'UDID' capability. Slot capabilities: %s",
                        slot.getCapabilities())))
                .toString();
        deviceName = CapabilityUtils.getAppiumCapability(slot.getCapabilities(), "deviceName")
                .orElseThrow(() -> new GridException(String.format("Appium node must have 'UDID' capability. Slot capabilities: %s",
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
                                String.format("[NODE-%s] Device is not ready for a session. /status-adb error: %s.",
                                        sessionUUID, response.getObject()));
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
                                String.format("[NODE-%s] Device is not ready for a session. /status-wda error: %s.",
                                        sessionUUID, response.getObject()));
                        return false;
                    }
                    return true;
                };
                break;
            default:
                LOGGER.warning(() -> String.format("Could not find suitable appium check for platform %s. Will be used no-op check.", platform));
                appiumCheck = (remoteURL, sessionUUID) -> true;
                throw new GridException("Invalid platform: " + platform);
            }
        } else {
            appiumCheck = (remoteURL, sessionUUID) -> true;
        }

        Integer serverProxyPort = CapabilityUtils.getZebrunnerCapability(slot.getCapabilities(), SERVER_PROXY_PORT_CAPABILITY)
                .map(String::valueOf)
                .filter(NumberUtils::isParsable)
                .map(Integer::parseInt)
                .orElse(null);
        Integer proxyPort = CapabilityUtils.getZebrunnerCapability(slot.getCapabilities(), PROXY_PORT_CAPABILITY)
                .map(String::valueOf)
                .filter(NumberUtils::isParsable)
                .map(Integer::parseInt)
                .orElse(null);
        isMitmSupported = (serverProxyPort != null && serverProxyPort > 0 && proxyPort != null && proxyPort > 0);

        if (STFClient.isSTFEnabled()) {
            if (!STFClient.isDevicePresentInSTF(udid)) {
                throw new GridException(String.format("Could not find device with udid '%s' in STF. Slot capabilities: %s",
                        udid, slot.getCapabilities()));
            }
        }
        if (isMitmSupported) {
            MitmProxyClient.initProxy(getTestSlots());
        }
    }

    @Override
    public TestSession getNewSession(Map<String, Object> requestedCapability) {
        if (isDown()) {
            LOGGER.warning(() -> String.format("Node is down: '[%s]-'%s'", deviceName, udid));
            return null;
        }

        if (!hasCapability(requestedCapability)) {
            return null;
        }

        if (getTotalUsed() >= 1) {
            return null;
        }

        for (TestSlot testslot : getTestSlots()) {
            TestSession session = testslot.getNewSession(requestedCapability);
            if (session == null) {
                return null;
            }
            String sessionUUID = UUID.randomUUID().toString();

            // additional check if device is ready for session with custom Appium's status verification
            if (!appiumCheck.apply(testslot.getRemoteURL(), sessionUUID)) {
                testslot.doFinishRelease();
                return null;
            }

            if (isMitmSupported) {
                // trigger proxy restart with specific capabilities
                // capabilities already validated in ProxyValidator
                Boolean isMitmEnable = CapabilityUtils.getZebrunnerCapability(requestedCapability, ProxyValidator.MITM_CAPABILITY)
                        .map(String::valueOf)
                        .map(Boolean::valueOf)
                        .orElse(false);
                if (isMitmEnable) {
                    if (!MitmProxyClient.isProxyInitialized(udid)) {
                        testslot.doFinishRelease();
                        LOGGER.warning(() -> String.format("[NODE-%s] Proxy enabled for session, but is not initialized.", sessionUUID));
                        return null;
                    }
                    String mitmArgs = CapabilityUtils.getZebrunnerCapability(requestedCapability, ProxyValidator.MITM_ARGS_CAPABILITY)
                            .map(String::valueOf)
                            .orElse(null);
                    String mitmType = CapabilityUtils.getZebrunnerCapability(requestedCapability, MITM_TYPE_CAPABILITY)
                            .map(String::valueOf)
                            .orElse("simple");

                    if (!MitmProxyClient.start(udid, mitmType, mitmArgs, sessionUUID)) {
                        testslot.doFinishRelease();
                        LOGGER.warning(() -> String.format("[NODE-%s] Could not start proxy with args: %s.", sessionUUID, mitmArgs));
                        return null;
                    }
                }
            }
            if (STFClient.isSTFEnabled()) {
                STFDevice device = STFClient.reserveSTFDevice(udid, requestedCapability, sessionUUID);
                if (device == null) {
                    testslot.doFinishRelease();
                    return null;
                }
                session.put(IS_MANUALLY_RESERVED, false);
                CapabilityUtils.getZebrunnerCapability(requestedCapability, "STF_TOKEN").ifPresent(token -> {
                    if (!StringUtils.equals(String.valueOf(token), STFClient.DEFAULT_STF_TOKEN)) {
                        session.put(IS_MANUALLY_RESERVED, true);
                    }
                });

                Map<String, Object> slotCapabilities = getSlotCapabilities(testslot, deviceType, device);
                LOGGER.info(() ->
                        String.format("[NODE-%s] slotCapabilities will be added to the session capabilities: %s.", sessionUUID, slotCapabilities));
                requestedCapability.put("zebrunner:slotCapabilities", slotCapabilities);
                session.put(SESSION_UUID_PARAMETER, sessionUUID);
            }
            LOGGER.warning(() -> String.format("[NODE-%s] Session on [%s] will be started.", sessionUUID, deviceName));
            return session;
        }
        return null;
    }

    @Override
    public void beforeSession(TestSession session) {
        String sessionUUID = (String) session.get(SESSION_UUID_PARAMETER);
        if (StringUtils.equalsIgnoreCase(deviceType, "tvos")) {
            //override platformName for the appium capabilities into tvOS
            LOGGER.info(() -> String.format("[NODE-%s] Detected 'tvOS' 'deviceType' capability, so 'platformName' will be overrided by 'tvOS'.",
                    sessionUUID));
            session.getRequestedCapabilities()
                    .put(CapabilityType.PLATFORM_NAME, "tvOS");
        }
    }

    @Override
    public void afterSession(TestSession session) {
        String sessionUUID = (String) session.get(SESSION_UUID_PARAMETER);
        String sessionId = getExternalSessionId(session);
        LOGGER.warning(() -> String.format("[NODE-%s] Session on [%s]  will be closed. Ext.id: [%s]", sessionUUID, deviceName, sessionId));
        if (STFClient.isSTFEnabled()) {
            STFClient.disconnectSTFDevice(udid, platform, (boolean) session.get(IS_MANUALLY_RESERVED), sessionUUID);
        }
        if (isMitmSupported) {
            if (MitmProxyClient.isProxyInitialized(udid)) {
                if (!MitmProxyClient.start(udid, "simple", null, sessionUUID)) {
                    LOGGER.info(() -> String.format("[NODE-%s] Could not reset proxy.", sessionUUID));
                }
            }
        }
    }

    // for 'as TIMED OUT due to client inactivity and will be released' exception
    @Override
    public void beforeRelease(TestSession session) {
        super.beforeRelease(session);
        LOGGER.warning(() -> String.format("[CRITICAL] [NODE-%s] [%s] (%s) Session [%s] will be released by timeout. ",
                String.valueOf(session.get(SESSION_UUID_PARAMETER)),
                deviceName,
                udid,
                String.valueOf(getExternalSessionId(session))));
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

    private static String getExternalSessionId(TestSession session) {
        // external key if exists correlates with valid appium sessionId. Internal key is unique uuid value inside hub
        return session.getExternalKey() != null ? session.getExternalKey().getKey() : StringUtils.EMPTY;
    }
}
