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
import org.openqa.grid.common.exception.CapabilityNotPresentOnTheGridException;
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
    private static final String DEVICE_TYPE = "appium:deviceType";
    
    private final String URL = System.getenv("STF_URL");
    private final String TOKEN = System.getenv("STF_TOKEN");
    
    // Max time is seconds for reserving devices in STF
    private final String TIMEOUT = System.getenv("STF_TIMEOUT");

    private static final String STF_CLIENT = "STF_CLIENT";
    
    private final boolean CHECK_APPIUM_STATUS = Boolean.parseBoolean(System.getenv("CHECK_APPIUM_STATUS"));

    public MobileRemoteProxy(RegistrationRequest request, GridRegistry registry) {
        super(request, registry);
    }

    @Override
    public TestSession getNewSession(Map<String, Object> requestedCapability) {
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

            // init new STF client per each session request
            STFClient client = getSTFClient(requestedCapability);

            // any slot left for the given app ?
            for (TestSlot testslot : getTestSlots()) {

                if (CapabilityUtils.getAppiumCapability(testslot.getCapabilities(),"udid").isEmpty()) {
                    LOGGER.warning(String.format("Appium node must have UDID capability to be identified in STF. Capabilities: %s", testslot.getCapabilities()));
                    // Appium node must have UDID capability to be identified in STF
                    return null;
                }

                // Check if device is busy in STF
                String udid = String.valueOf(CapabilityUtils.getAppiumCapability(testslot.getCapabilities(),"udid").get());
                if (client.isEnabled() && !client.isDeviceAvailable(udid)) {
                    return null;
                }

                // additional check if device is ready for session with custom Appium's status verification

                if (this.CHECK_APPIUM_STATUS) {
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

                if(!reserveSTFDevice(client, testslot, requestedCapability)) {
                    return null;
                }

                if (!validateSTFRemoteURL(testslot, client, udid, requestedCapability)) {
                    // obligatory return device to the STF ASAP for unsuccessful session
                    boolean isReturned = client.returnDevice(String.valueOf(udid),  requestedCapability);
                    if (!isReturned) {
                        LOGGER.warning(
                                String.format("Device could not be returned to the STF. Slot capabilities: %s", testslot.getCapabilities()));
                    }
                    return null;
                }

                TestSession session = testslot.getNewSession(requestedCapability);
                // todo still TBD if session might generate exception
                if (session == null) {
                    // obligatory return device to the STF ASAP for unsuccessful session
                    boolean isReturned = client.returnDevice(String.valueOf(udid), session.getRequestedCapabilities());
                    if (!isReturned) {
                        LOGGER.warning(
                                String.format("Device could not be returned to the STF. Slot capabilities: %s", session.getSlot().getCapabilities()));
                    }
                    return null;
                }

                ProxyServlet.cleanPacConfiguration(udid);
                Optional<String> pacConfiguration = CapabilityUtils.getZebrunnerCapability(requestedCapability, "pac")
                        .map(String::valueOf);
                if (pacConfiguration.isPresent()) {
                    LOGGER.info(String.format("Detected PAC configuration for device '%s': %n%s", udid, pacConfiguration.get()));
                    ProxyServlet.updatePacConfiguration(udid, pacConfiguration.get());
                }

                // remember current STF client in test session object
                session.put(STF_CLIENT, client);
                return session;
            }
            return null;
        } catch (Exception e) {
            LOGGER.warning(String.format("Got error in MobileRemoteProxy.getNewSession: %s", e.getMessage()));
            return null;
        }
    }

    @Override
    public boolean hasCapability(Map<String, Object> requestedCapability) {
        // verify that required STF connection can be established trying to init STF client 
        try {
            getSTFClient(requestedCapability); // temp STFClient to test authorization
        } catch (Exception e) {
            // as we have enabled GRID_THROW_ON_CAPABILITY_NOT_PRESENT by default we could raise exception without waiting 4 minutes
            // Confirmed by testing that raising CapabilityNotPresentOnTheGridException is applicable only inside hasCapability method!
            throw new CapabilityNotPresentOnTheGridException(e.getMessage());
        }
        
        return super.hasCapability(requestedCapability);
    }

    private boolean validateSTFRemoteURL(TestSlot testslot, STFClient client, String udid, Map<String, Object> requestedCapabilities) {
        Object enableAdb = CapabilityUtils.getZebrunnerCapability(requestedCapabilities, "enableAdb").orElse(null);
        if (Platform.ANDROID.equals(Platform.fromCapabilities(testslot.getCapabilities())) && enableAdb != null &&
                Boolean.parseBoolean(String.valueOf(enableAdb))) {
            if (StringUtils.isBlank((String) client.getDevice(udid).getRemoteConnectUrl())) {
                LOGGER.warning(String.format("Detected 'true' enableAdb capability, but remoteURL is blank or empty. Slot capabilities: %s", testslot.getCapabilities()));
                return false;
            }
        }
        return true;
    }

    public boolean reserveSTFDevice(STFClient client, TestSlot slot, Map<String, Object>  requestedCapabilities) {
        Object udid = CapabilityUtils.getAppiumCapability(slot.getCapabilities(), "udid").orElse(null);
        if(StringUtils.isEmpty((String)udid)) {
            LOGGER.warning(String.format("Udid is null or empty. Capabilities: %s", slot.getCapabilities()));
            return false;
        }

        if (client.reserveDevice(String.valueOf(udid), requestedCapabilities)) {
            // this is our slot object for Zebrunner Mobile Farm (Android or iOS)
            requestedCapabilities.put("zebrunner:slotCapabilities", getSlotCapabilities(slot, String.valueOf(udid), client));
        } else {
            LOGGER.warning(String.format("Cannot reserve device. Slot capabilities: %s", slot.getCapabilities()));
            return false;
        }
        return true;
    }

    @Override
    public void beforeSession(TestSession session) {
        String sessionId = getExternalSessionId(session);
        LOGGER.info("beforeSession sessionId: " + sessionId);

        Object udid = CapabilityUtils.getAppiumCapability(session.getSlot().getCapabilities(), "udid").orElse(null);
        if (StringUtils.isEmpty((String)udid)) {
            LOGGER.warning(String.format("udid is null or empty in beforeSession. Slot capabilities: %s", session.getSlot().getCapabilities()));
            return;
        }

        Object deviceType = CapabilityUtils.getZebrunnerCapability(session.getRequestedCapabilities(), DEVICE_TYPE).orElse(null);
        if (deviceType != null && "tvos".equalsIgnoreCase(deviceType.toString())) {
            //override platformName for the appium capabilities into tvOS
            LOGGER.info("beforeSession overriding: '" + session.get(CapabilityType.PLATFORM_NAME) + "' by 'tvOS' for " + sessionId);
            session.getRequestedCapabilities().put(CapabilityType.PLATFORM_NAME, "tvOS");
        }
    }

    @Override
    public void afterSession(TestSession session) {
        String sessionId = getExternalSessionId(session);
        LOGGER.info("afterSession sessionId: " + sessionId);

        // unable to start recording after Session due to the:
        // Error running afterSession for ext. key 5e6960c5-b82b-4e68-a24d-508c3d98dc53, the test slot is now dead: null

        STFClient client = (STFClient) session.get(STF_CLIENT);
        String udid = CapabilityUtils.getAppiumCapability(session.getSlot().getCapabilities(), "udid")
                .map(String::valueOf)
                .orElse(null);

        if (udid == null) {
            LOGGER.warning(String.format("There are no udid in slot capabilities. Device could not be returned to the STF. Capabilities: %s",
                    session.getSlot().getCapabilities()));
            return;
        }

        boolean isReturned = client.returnDevice(udid, session.getRequestedCapabilities());
        if (!isReturned) {
            LOGGER.warning(String.format("Device could not be returned to the STF. Capabilities: %s", session.getSlot().getCapabilities()));
        }

        ProxyServlet.cleanPacConfiguration(udid);
    }

    private Map<String, Object> getSlotCapabilities(TestSlot slot, String udid, STFClient client) {
        // obligatory create new map as original object is UnmodifiableMap
        Map<String, Object> slotCapabilities = new HashMap<String, Object>();

        // get existing slot capabilities from session
        slotCapabilities.putAll(slot.getCapabilities());
        
        Object deviceType = CapabilityUtils.getZebrunnerCapability(slot.getCapabilities(), DEVICE_TYPE).orElse(null);
        if (deviceType != null  && "tvos".equalsIgnoreCase(deviceType.toString())) {
            //override platformName in slot to register valid platform in reporting
            slotCapabilities.put("platformName", "tvOS");
        }

        // get remoteURL from STF device and put into custom slotCapabilities map
        String remoteURL = null;
        STFDevice stfDevice = client.getDevice(udid);
        //todo add verification of capabilities.enableAdb
        if (stfDevice != null) {
            LOGGER.info("Identified '" + stfDevice.getModel() + "' device by udid: " + udid);
            remoteURL = (String) stfDevice.getRemoteConnectUrl();
            LOGGER.info("Identified remoteURL '" + remoteURL + "' by udid: " + udid);
            slotCapabilities.put("remoteURL", remoteURL);
        }

        return slotCapabilities;
    }

    private String getExternalSessionId(TestSession session) {
        // external key if exists correlates with valid appium sessionId. Internal key is unique uuid value inside hub
        return session.getExternalKey() != null ? session.getExternalKey().getKey() : "";
    }

    private STFClient getSTFClient(Map<String, Object> requestedCapability) {
        String token = this.TOKEN;
        String timeout = this.TIMEOUT;
        
        if (requestedCapability.containsKey("STF_TOKEN")) {
            token = requestedCapability.get("STF_TOKEN").toString();
        }
        
        if (requestedCapability.containsKey("STF_TIMEOUT")) {
            timeout = requestedCapability.get("STF_TIMEOUT").toString();
        }

        // adjust client integration settings using capabilitites if needed
        return new STFClient(URL, token, Long.parseLong(timeout));
    }
    
}