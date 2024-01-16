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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import com.zebrunner.mcloud.grid.integration.client.MitmProxyClient;
import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;

import com.zebrunner.mcloud.grid.integration.client.Path;
import com.zebrunner.mcloud.grid.integration.client.STFClient;
import com.zebrunner.mcloud.grid.models.stf.STFDevice;
import com.zebrunner.mcloud.grid.util.HttpClient.Response;
import com.zebrunner.mcloud.grid.util.HttpClientApache;
import org.openqa.selenium.remote.CapabilityType;

/**
 * Mobile proxy that connects/disconnects STF devices.
 *
 * @author Alex Khursevich (alex@qaprosoft.com)
 */
public class MobileRemoteProxy extends DefaultRemoteProxy {
    private static final Logger LOGGER = Logger.getLogger(MobileRemoteProxy.class.getName());
    //to operate with RequestedCapabilities where prefix is present
    private static final String DEVICE_TYPE_CAPABILITY = "deviceType";
    private static final boolean CHECK_APPIUM_STATUS = Boolean.parseBoolean(System.getenv("CHECK_APPIUM_STATUS"));
    private static final String SESSION_UUID_PARAMETER = "SESSION_UUID_PARAMETER";
    private final AtomicBoolean lock = new AtomicBoolean(false);

    public MobileRemoteProxy(RegistrationRequest request, GridRegistry registry) {
        super(request, registry);
        MitmProxyClient.initProxy(getTestSlots().get(0));
    }

    @Override
    public TestSession getNewSession(Map<String, Object> requestedCapability) {
        String sessionUUID = UUID.randomUUID().toString();
        String udid = null;
        TestSession testSession = null;

        try {
            if (!lock.compareAndSet(false, true)) {
                return null;
            }
            if (isDown()) {
                LOGGER.info(() -> "Node is down.");
                return null;
            }
            if (!hasCapability(requestedCapability)) {
                return null;
            }
            if (getTotalUsed() >= config.maxSession) {
                return null;
            }

            for (TestSlot testslot : getTestSlots()) {
                LOGGER.info(() -> String.format("[NODE-%s] Occupy the slot. Requested capabilities: %n%s. %nSlotCapabilities: %n%s",
                        sessionUUID, requestedCapability, testslot.getCapabilities()));
                udid = String.valueOf(CapabilityUtils.getAppiumCapability(testslot.getCapabilities(), "udid").orElse(""));
                if (StringUtils.isBlank(udid)) {
                    LOGGER.warning(() -> String.format("[NODE-%s] Appium node must have 'UDID' capability to be identified in STF.", sessionUUID));
                    return null;
                }

                // additional check if device is ready for session with custom Appium's status verification
                if (CHECK_APPIUM_STATUS) {
                    LOGGER.info(() -> String.format("[NODE-%s] 'CHECK_APPIUM_STATUS' is enabled so additional Appium health-check will be verified.",
                            sessionUUID));
                    try {
                        Platform platform = Platform.fromCapabilities(testslot.getCapabilities());
                        Response<String> response;
                        switch (platform) {
                        case ANDROID:
                            response = HttpClientApache.create()
                                    .withUri(Path.APPIUM_STATUS_ADB, testslot.getRemoteURL().toString())
                                    .get(new StringEntity("{\"exitCode\": 101}", ContentType.APPLICATION_JSON));
                            if (response.getStatus() != 200) {
                                LOGGER.warning(() ->
                                        String.format("[NODE-%s] Device is not ready for a session. /status-adb error: %s.",
                                                sessionUUID, response.getObject()));
                                return null;
                            }
                            LOGGER.info(() -> String.format("[NODE-%s] /status-adb successfully passed.", sessionUUID));
                            break;
                        case IOS:
                            response = HttpClientApache.create()
                                    .withUri(Path.APPIUM_STATUS_WDA, testslot.getRemoteURL().toString())
                                    .get(new StringEntity("{\"exitCode\": 101}", ContentType.APPLICATION_JSON));
                            if (response.getStatus() != 200) {
                                LOGGER.warning(() ->
                                        String.format("[NODE-%s] Device is not ready for a session. /status-wda error: %s.",
                                                sessionUUID, response.getObject()));
                                return null;
                            }
                            LOGGER.info(() -> String.format("[NODE-%s] /status-wda successfully passed.", sessionUUID));
                            break;
                        default:
                            LOGGER.info(() -> String.format("[NODE-%s] Appium health-check is not supported for '%s'.", sessionUUID,
                                    platform.toString()));
                        }
                    } catch (Exception e) {
                        LOGGER.warning(() -> String.format("[NODE-%s] Appium health-check failed: %s.", sessionUUID, e));
                        return null;
                    }
                } else {
                    LOGGER.info(() -> String.format("[NODE-%s] 'CHECK_APPIUM_STATUS' is not enabled.", sessionUUID));
                }

                // trigger proxy restart with specific capabilities
                Boolean isMitmEnable = CapabilityUtils.getZebrunnerCapability(requestedCapability, "Mitm")
                        .map(String::valueOf)
                        .map(Boolean::valueOf)
                        .orElse(false);
                if (isMitmEnable) {
                    String mitmArgs = CapabilityUtils.getZebrunnerCapability(requestedCapability, "MitmArgs")
                            .map(String::valueOf)
                            .orElse("");
                    if (!MitmProxyClient.restartProxy(udid, mitmArgs)) {
                        LOGGER.info(() -> String.format("[NODE-%s] Could not start proxy with args: %s.", sessionUUID, mitmArgs));
                        return null;
                    }
                }

                if (!STFClient.reserveSTFDevice(udid, requestedCapability, sessionUUID)) {
                    return null;
                }

                Map<String, Object> slotCapabilities = getSlotCapabilities(testslot, udid, STFClient.getSTFDevice(udid));
                LOGGER.info(() ->
                        String.format("[NODE-%s] slotCapabilities will be added to the session capabilities: %s.", sessionUUID, slotCapabilities));
                requestedCapability.put("zebrunner:slotCapabilities", slotCapabilities);

                TestSession session = testslot.getNewSession(requestedCapability);
                if (session == null) {
                    LOGGER.warning(() ->
                            String.format(
                                    "[NODE-%s] Somehow we got null when we try to call getNewSession from free slot. Device will be disconnected.",
                                    sessionUUID));
                    STFClient.disconnectSTFDevice(udid);
                    if (!MitmProxyClient.restartProxy(udid, "")) {
                        LOGGER.info(() -> String.format("[NODE-%s] Could not reset proxy.", sessionUUID));
                        return null;
                    }
                    return null;
                }
                testSession = session;
                session.put(SESSION_UUID_PARAMETER, sessionUUID);
                return session;
            }
            return null;
        } catch (Exception e) {
            LOGGER.warning(() -> String.format("[NODE-%s] Exception in MobileRemoteProxy.getNewSession: %s.", sessionUUID, e));
            if (udid != null) {
                STFClient.disconnectSTFDevice(udid);
                if (!MitmProxyClient.restartProxy(udid, "")) {
                    LOGGER.info(() -> String.format("[NODE-%s] Could not reset proxy.", sessionUUID));
                    return null;
                }
            }
            return null;
        } finally {
            if (testSession == null) {
                lock.set(false);
            }
        }
    }

    @Override
    public void beforeSession(TestSession session) {
        String sessionUUID = (String) session.get(SESSION_UUID_PARAMETER);
        try {
            String deviceType = CapabilityUtils.getZebrunnerCapability(session.getRequestedCapabilities(), DEVICE_TYPE_CAPABILITY)
                    .map(String::valueOf)
                    .orElse("");

            if (StringUtils.equalsIgnoreCase(deviceType, "tvos")) {
                //override platformName for the appium capabilities into tvOS
                LOGGER.info(() -> String.format("[NODE-%s] Detected 'tvOS' 'deviceType' capability, so 'platformName' will be overrided by 'tvOS'.",
                        sessionUUID));
                session.getRequestedCapabilities()
                        .put(CapabilityType.PLATFORM_NAME, "tvOS");
            }
        } catch (Exception e) {
            LOGGER.warning(() -> String.format("[NODE-%s] Exception in 'beforeSession' method: %s.", sessionUUID, e));
        }
    }

    @Override
    public void afterSession(TestSession session) {
        String sessionUUID = (String) session.get(SESSION_UUID_PARAMETER);
        try {
            String sessionId = getExternalSessionId(session);
            LOGGER.info(() -> String.format("[NODE-%s] Session [%s] will be closed.", sessionUUID, sessionId));

            Optional<String> udid = CapabilityUtils.getAppiumCapability(session.getSlot().getCapabilities(), "udid")
                    .map(String::valueOf);

            if (udid.isEmpty()) {
                LOGGER.warning(() ->
                        String.format(
                                "[NODE-%s] Could not detect udid in slot capabilities. Device could not be returned to the STF. Capabilities: %s.",
                                sessionUUID, session.getSlot().getCapabilities()));
                return;
            }

            STFClient.disconnectSTFDevice(udid.get());
            if (!MitmProxyClient.restartProxy(udid.get(), "")) {
                LOGGER.info(() -> String.format("[NODE-%s] Could not reset proxy.", sessionUUID));
            }
        } catch (Exception e) {
            LOGGER.warning(String.format("[NODE-%s] Exception in afterSession: %s.", sessionUUID, e));
        } finally {
            LOGGER.warning(() -> String.format("[NODE-%s] Lock removed.", sessionUUID));
            lock.set(false);
        }
    }

    private static Map<String, Object> getSlotCapabilities(TestSlot slot, String udid, STFDevice stfDevice) {
        Map<String, Object> slotCapabilities = new HashMap<>(slot.getCapabilities());
        String deviceType = CapabilityUtils.getZebrunnerCapability(slot.getCapabilities(), DEVICE_TYPE_CAPABILITY)
                .map(String::valueOf)
                .orElse("");
        if (StringUtils.equalsIgnoreCase("tvos", deviceType)) {
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
        return session.getExternalKey() != null ? session.getExternalKey().getKey() : "";
    }
}
