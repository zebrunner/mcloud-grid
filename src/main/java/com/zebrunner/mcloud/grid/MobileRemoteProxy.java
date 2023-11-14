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
import java.util.logging.Logger;

import com.zebrunner.mcloud.grid.servlets.ProxyServlet;
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
    private static final String DEVICE_TYPE = "deviceType";
    private static final boolean CHECK_APPIUM_STATUS = Boolean.parseBoolean(System.getenv("CHECK_APPIUM_STATUS"));

    public MobileRemoteProxy(RegistrationRequest request, GridRegistry registry) {
        super(request, registry);
    }

    @Override
    public TestSession getNewSession(Map<String, Object> requestedCapability) {
        String udid = null;
        try {
            LOGGER.fine("Trying to create a new session on node " + this);
            if (isDown()) {
                return null;
            }
            if (!hasCapability(requestedCapability)) {
                LOGGER.fine("Node '" + this + "' has no matching capability!");
                return null;
            }
            // any slot left at all?
            if (getTotalUsed() >= config.maxSession) {
                LOGGER.fine("Node '" + this + "' has no free slots!");
                return null;
            }
            // any slot left for the given app ?
            for (TestSlot testslot : getTestSlots()) {
                // Check if device is busy in STF
                udid = String.valueOf(CapabilityUtils.getAppiumCapability(testslot.getCapabilities(), "udid").orElse(""));
                if (StringUtils.isBlank(udid)) {
                    LOGGER.warning(String.format("Appium node must have UDID capability to be identified in STF. Capabilities: %s",
                            testslot.getCapabilities()));
                    return null;
                }

                if (!STFClient.reserveSTFDevice(udid, requestedCapability)) {
                    return null;
                }
                requestedCapability.put("zebrunner:slotCapabilities", getSlotCapabilities(testslot, udid, STFClient.getSTFDevice(udid)));

                // additional check if device is ready for session with custom Appium's status verification
                if (CHECK_APPIUM_STATUS) {
                    LOGGER.fine("CHECK_APPIUM_STATUS is enabled so additional Appium health-check will be verified");
                    try {
                        Platform platform = Platform.fromCapabilities(testslot.getCapabilities());
                        Response<String> response;
                        switch (platform) {
                        case ANDROID:
                            response = HttpClientApache.create()
                                    .withUri(Path.APPIUM_STATUS_ADB, testslot.getRemoteURL().toString())
                                    .get(new StringEntity("{\"exitCode\": 101}", ContentType.APPLICATION_JSON));
                            if (response.getStatus() != 200) {
                                LOGGER.warning(String.format("%s is not ready for a session. /status-adb error: %s", udid, response.getObject()));
                                return null;
                            }
                            LOGGER.fine(String.format("%s /status-adb successfully passed", udid));
                            LOGGER.fine("/status-adb response content: " + response.getObject());
                            break;
                        case IOS:
                            response = HttpClientApache.create().withUri(Path.APPIUM_STATUS_WDA, testslot.getRemoteURL().toString())
                                    .get(new StringEntity("{\"exitCode\": 101}", ContentType.APPLICATION_JSON));
                            if (response.getStatus() != 200) {
                                LOGGER.warning(
                                        String.format(
                                                "%s is not ready for a session. /status-wda error: %s",
                                                udid, response.getObject()));
                                return null;
                            }
                            LOGGER.fine(String.format("%s /status-wda successfully passed", udid));
                            LOGGER.fine("/status-wda response content: " + response.getObject());
                            break;
                        default:
                            LOGGER.info(String.format("Appium health-check is not supported for '%s'", platform.toString()));
                        }
                    } catch (Exception e) {
                        LOGGER.warning("Appium health-check failed: " + e.getMessage());
                        return null;
                    }
                } else {
                    LOGGER.fine("CHECK_APPIUM_STATUS is not enabled!");
                }

                TestSession session = testslot.getNewSession(requestedCapability);
                // todo still TBD if session might generate exception
                if (session == null) {
                    STFClient.disconnectSTFDevice(udid);
                    return null;
                }

                ProxyServlet.cleanPacConfiguration(udid);
                Optional<String> pacConfiguration = CapabilityUtils.getZebrunnerCapability(requestedCapability, "pac")
                        .map(String::valueOf);
                if (pacConfiguration.isPresent()) {
                    LOGGER.info(String.format("Detected PAC configuration for device '%s': %n%s", udid, pacConfiguration.get()));
                    ProxyServlet.updatePacConfiguration(udid, pacConfiguration.get());
                }
                return session;
            }
            return null;
        } catch (Exception e) {
            LOGGER.warning(String.format("Got error in MobileRemoteProxy.getNewSession: %s", e));
            if (udid != null) {
                STFClient.disconnectSTFDevice(udid);
            }
            return null;
        }
    }

    @Override
    public void beforeSession(TestSession session) {
        try {
            Object deviceType = CapabilityUtils.getZebrunnerCapability(session.getRequestedCapabilities(), DEVICE_TYPE).orElse(null);
            if (deviceType != null && "tvos".equalsIgnoreCase(deviceType.toString())) {
                //override platformName for the appium capabilities into tvOS
                LOGGER.info("beforeSession overriding: '" + session.get(CapabilityType.PLATFORM_NAME) + "' by 'tvOS'");
                session.getRequestedCapabilities().put(CapabilityType.PLATFORM_NAME, "tvOS");
            }
        } catch (Exception e) {
            LOGGER.warning("Error in the beforeSession: " + e.getMessage());
        }
    }

    @Override
    public void afterSession(TestSession session) {
        try {
            String sessionId = getExternalSessionId(session);
            LOGGER.info(String.format("Session [%s] will be closed.", sessionId));

            // unable to start recording after Session due to the:
            // Error running afterSession for ext. key 5e6960c5-b82b-4e68-a24d-508c3d98dc53, the test slot is now dead: null

            Optional<String> udid = CapabilityUtils.getAppiumCapability(session.getSlot().getCapabilities(), "udid")
                    .map(String::valueOf)
                    .filter(StringUtils::isNotBlank);

            if (udid.isEmpty()) {
                LOGGER.warning(String.format("There are no udid in slot capabilities. Device could not be returned to the STF. Capabilities: %s",
                        session.getSlot().getCapabilities()));
                return;
            }

            ProxyServlet.cleanPacConfiguration(udid.get());
            STFClient.disconnectSTFDevice(udid.get());
        } catch (Exception e) {
            LOGGER.warning("Exception in afterSession: " + e.getMessage());
        }
    }

    private static Map<String, Object> getSlotCapabilities(TestSlot slot, String udid, STFDevice stfDevice) {
        Map<String, Object> slotCapabilities = new HashMap<>(slot.getCapabilities());
        Object deviceType = CapabilityUtils.getZebrunnerCapability(slot.getCapabilities(), DEVICE_TYPE).orElse(null);
        if (deviceType != null && "tvos".equalsIgnoreCase(deviceType.toString())) {
            slotCapabilities.put("platformName", "tvOS");
        }
        String remoteURL;
        if (stfDevice != null) {
            LOGGER.info(String.format("Identified '%s' device by udid: %s", stfDevice.getModel(), udid));
            remoteURL = (String) stfDevice.getRemoteConnectUrl();
            LOGGER.info(String.format("Identified remoteURL '%s' by udid: %s", remoteURL, udid));
            slotCapabilities.put("remoteURL", remoteURL);
        }
        return slotCapabilities;
    }

    private String getExternalSessionId(TestSession session) {
        // external key if exists correlates with valid appium sessionId. Internal key is unique uuid value inside hub
        return session.getExternalKey() != null ? session.getExternalKey().getKey() : "";
    }

}