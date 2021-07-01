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

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;

import com.google.common.collect.ImmutableMap;
import com.zebrunner.mcloud.grid.integration.Appium;
import com.zebrunner.mcloud.grid.integration.client.STFClient;
import com.zebrunner.mcloud.grid.models.appium.LogValue;
import com.zebrunner.mcloud.grid.models.stf.STFDevice;
import com.zebrunner.mcloud.grid.s3.S3Uploader;

/**
 * Mobile proxy that connects/disconnects STF devices.
 * 
 * @author Alex Khursevich (alex@qaprosoft.com)
 */
public class MobileRemoteProxy extends DefaultRemoteProxy {
    private static final Logger LOGGER = Logger.getLogger(MobileRemoteProxy.class.getName());
    private static final Set<String> recordingSessions = new HashSet<>();
    
    private final static Map<String, String> DEFAULT_LOGS_MAPPING_ANDROID = ImmutableMap.of(
            "logcat", "android.log",
            "server", "session.log");

    private final static Map<String, String> DEFAULT_LOGS_MAPPING_IOS = ImmutableMap.of(
            "syslog", "session.log");

    private static final String ENABLE_VIDEO = "enableVideo";
    private static final String ENABLE_LOG = "enableLog";
    
    private final String URL = System.getenv("STF_URL");
    private final String TOKEN = System.getenv("STF_TOKEN");
    
    // Max time is seconds for reserving devices in STF
    private final String TIMEOUT = System.getenv("STF_TIMEOUT");

    private static final String STF_CLIENT = "STF_CLIENT";

    public MobileRemoteProxy(RegistrationRequest request, GridRegistry registry) {
        super(request, registry);
    }

    @Override
    public TestSession getNewSession(Map<String, Object> requestedCapability) {
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

            if (!testslot.getCapabilities().containsKey("udid")) {
                // Appium node must have UDID capability to be identified in STF
                return null;
            }
            
            // Check if device is busy in STF
            if (client.isEnabled() && !client.isDeviceAvailable((String) testslot.getCapabilities().get("udid"))) {
                return null;
            }

            TestSession session = testslot.getNewSession(requestedCapability);
            // remember current STF client in test session object
            session.put(STF_CLIENT, client);

            if (session != null) {
                return session;
            }
        }
        return null;
    }
    
    @Override
    public void beforeSession(TestSession session) {
        String sessionId = getExternalSessionId(session);
        LOGGER.finest("beforeSession sessionId: " + sessionId);

        String udid = String.valueOf(session.getSlot().getCapabilities().get("udid"));
        if (!StringUtils.isEmpty(udid)) {
            Object deviceType = session.getRequestedCapabilities().get("deviceType");
            if (deviceType != null  && "tvos".equalsIgnoreCase(deviceType.toString())) {
                //override platformName for the appium capabilities into tvOS
                LOGGER.finest("beforeSession overriding: '" + session.get("platformName") + "' by 'tvOS' for " + sessionId);
                session.getRequestedCapabilities().put("platformName", "tvOS");
            }
            
            STFClient client = (STFClient) session.get(STF_CLIENT);
            if (client.reserveDevice(udid, session.getRequestedCapabilities())) {
                // this is our slot object for Zebrunner Mobile Farm (Android or iOS)
                session.getRequestedCapabilities().put("slotCapabilities", getSlotCapabilities(session, udid));
            }
        }
    }

    @Override
    public void afterSession(TestSession session) {
        String sessionId = getExternalSessionId(session);
        LOGGER.finest("afterSession sessionId: " + sessionId);

        // unable to start recording after Session due to the:
        // Error running afterSession for ext. key 5e6960c5-b82b-4e68-a24d-508c3d98dc53, the test slot is now dead: null

        STFClient client = (STFClient) session.get(STF_CLIENT);
        String udid = String.valueOf(session.getSlot().getCapabilities().get("udid"));
        client.returnDevice(udid, session.getRequestedCapabilities());
        
    }

    @Override
    public void afterCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        super.afterCommand(session, request, response);

        // ExternalKey is defined ONLY after first valid command so we can start recording
        String sessionId = getExternalSessionId(session);
        LOGGER.finest("afterCommand sessionId: " + sessionId);

        // double check that external key not empty
        if (!isRecording(sessionId) && !sessionId.isEmpty() && !"DELETE".equals(request.getMethod())) {
            if (isCapabilityEnabled(session, ENABLE_VIDEO)) {
                recordingSessions.add(sessionId);
                LOGGER.info("start recording sessionId: " + getExternalSessionId(session));
                startRecording(sessionId, session.getSlot().getRemoteURL().toString(), session);
            }
        }
    }

    @Override
    public void beforeCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        super.beforeCommand(session, request, response);
        String sessionId = getExternalSessionId(session);
        LOGGER.finest("afterCommand sessionId: " + sessionId);

        // TODO: try to add more conditions to make sure it is DELETE session call
        // DELETE /wd/hub/session/5e6960c5-b82b-4e68-a24d-508c3d98dc53
        if ("DELETE".equals(request.getMethod())) {
            // saving of video recording
            boolean isRecording = isRecording(sessionId);
            LOGGER.finest("recording for " + sessionId + ": " + isRecording);
            if (isRecording) {
                recordingSessions.remove(sessionId);

                String appiumUrl = session.getSlot().getRemoteURL().toString();
                // Do stopRecordingScreen call to appium using predefined args for Android and iOS:
                // http://appium.io/docs/en/commands/device/recording-screen/stop-recording-screen/
                String data = Appium.stopRecording(appiumUrl, sessionId);

                if (data != null) {
                    // Convert base64 encoded result string into the mp4 file (use sessionId to make filename unique)
                    String filePath = sessionId + ".mp4";
                    File file = null;

                    try {
                        LOGGER.finest("Saving video artifact: " + filePath);
                        file = new File(filePath);
                        FileUtils.writeByteArrayToFile(file, Base64.getDecoder().decode(data));
                        LOGGER.info("Saved video artifact: " + filePath);
                    } catch (IOException e) {
                        LOGGER.log(Level.SEVERE, "Error has been occurred during video artifact generation: " + filePath, e);
                    }

                    S3Uploader.getInstance().uploadArtifact(sessionId, file, S3Uploader.VIDEO_S3_FILENAME);
                }
                else {
                    LOGGER.log(Level.SEVERE,
                            "Error has been occurred during termination of video session recording. Video is not saved for session: " + sessionId);
                }
            }

            // saving of session logs
            boolean isLogEnabled = isCapabilityEnabled(session, ENABLE_LOG);
            LOGGER.finest("log saving enabled for " + sessionId + ": " + isLogEnabled);
            if (isLogEnabled) {
                String appiumUrl = session.getSlot().getRemoteURL().toString();
                saveSessionLogsForPlatform(appiumUrl, session);
            }
        }
    }

    private boolean isRecording(String sessionId) {
        return recordingSessions.contains(sessionId);
    }

    private void startRecording(String sessionId, String appiumUrl, TestSession session) {
        // TODO: organize overriding video options via capabilitities. Think about putting all options.* one more time if exist
        Map<String, String> options = new HashMap<>();
        // Unable to specify video recording time limit for more then 1800 sec due to the appium failure:
        // [XCUITest] The timeLimit value must be in range [1, 1800] seconds. The value of '3600' has been passed instead.
        options.put("timeLimit", "1800"); // 1 hour as maximal video recording duration
        
        if (Platform.ANDROID.equals(Platform.fromCapabilities(session.getRequestedCapabilities()))) {
            options.put("forceRestart", "true");
            options.put("bitRate", "1000000");
            options.put("bugReport", "true");
        } else if (Platform.IOS.equals(Platform.fromCapabilities(session.getRequestedCapabilities()))) {
            options.put("videoType", "libx264");
            options.put("videoQuality", "medium");
            options.put("videoFps", "10");
            options.put("videoScale", "-2:720");
            options.put("videoType", "libx264");
        }

        // do start_recording_screen call to appium using predefined args for Android and iOS
        // http://appium.io/docs/en/commands/device/recording-screen/start-recording-screen/
        Appium.startRecording(appiumUrl, sessionId, options);
    }

    private Map<String, Object> getSlotCapabilities(TestSession session, String udid) {
        // obligatory create new map as original object is UnmodifiableMap
        Map<String, Object> slotCapabilities = new HashMap<String, Object>();

        // get existing slot capabilities from session
        slotCapabilities.putAll(session.getSlot().getCapabilities());
        
        Object deviceType = session.getSlot().getCapabilities().get("deviceType");
        if (deviceType != null  && "tvos".equalsIgnoreCase(deviceType.toString())) {
            //override platformName in slot to register valid platform in reporting
            session.getSlot().getCapabilities().put("platformName", "tvOS");
        }

        // get remoteURL from STF device and put into custom slotCapabilities map
        String remoteURL = null;
        STFClient client = (STFClient) session.get(STF_CLIENT);
        STFDevice stfDevice = client.getDevice(udid);
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

    private boolean isCapabilityEnabled(TestSession session, String capabilityName) {
        boolean isEnabled = false;
        if (session.getRequestedCapabilities().containsKey(capabilityName)) {
            isEnabled = (session.getRequestedCapabilities().get(capabilityName) instanceof Boolean)
                    ? (Boolean) session.getRequestedCapabilities().get(capabilityName)
                    : Boolean.valueOf((String) session.getRequestedCapabilities().get(capabilityName));
        }

        return isEnabled;
    }
    
    private void saveSessionLogsForPlatform(String appiumUrl, TestSession session) {
        String sessionId = getExternalSessionId(session);
        List<String> logTypes = Appium.getLogTypes(appiumUrl, sessionId);
        if (logTypes != null) {
            if (Platform.ANDROID.equals(Platform.fromCapabilities(session.getRequestedCapabilities()))) {
                DEFAULT_LOGS_MAPPING_ANDROID.forEach((k, v) -> {
                    if(logTypes.contains(k)) {
                        saveSessionLogs(appiumUrl, sessionId, k, v);
                    } else {
                        LOGGER.warning(String.format("Logs of type '%s' are missing for session %s", k.toString(), sessionId));
                    }
                });
            } else if (Platform.IOS.equals(Platform.fromCapabilities(session.getRequestedCapabilities()))) {
                DEFAULT_LOGS_MAPPING_IOS.forEach((k, v) -> {
                    if (logTypes.contains(k)) {
                        saveSessionLogs(appiumUrl, sessionId, k, v);
                    } else {
                        LOGGER.warning(String.format("Logs of type '%s' are missing for session %s", k.toString(), sessionId));
                    }
                });
            }
        }
    }

    private void saveSessionLogs(String appiumUrl, String sessionId, String logType, String fileName) {
        List<LogValue> logs = Appium.getLogs(appiumUrl, sessionId, logType);
        if (logs != null && !logs.isEmpty()) {
            String locFileName = String.format("%s_%s", sessionId, fileName);
            File file = null;
            try {
                LOGGER.finest("Saving log entries to: " + locFileName);
                file = new File(locFileName);
                for (LogValue l : logs) {
                    FileUtils.writeByteArrayToFile(file, l.toString().concat(System.lineSeparator()).getBytes(), true);
                }
                LOGGER.info("Saved log entries to: " + locFileName);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error has been occurred during log entries saving to " + locFileName, e);
            }

            try {
                S3Uploader.getInstance().uploadArtifact(sessionId, file, fileName);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, String.format("Exception during uploading file '%s' to S3", fileName), e);
            }
        }
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