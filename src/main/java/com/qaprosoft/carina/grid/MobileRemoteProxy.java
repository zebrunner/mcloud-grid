/*******************************************************************************
 * Copyright 2013-2019 QaProSoft (http://www.qaprosoft.com).
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
package com.qaprosoft.carina.grid;

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
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

import com.qaprosoft.carina.grid.integration.Appium;
import com.qaprosoft.carina.grid.integration.STF;
import com.qaprosoft.carina.grid.models.stf.STFDevice;

/**
 * Mobile proxy that connects/disconnects STF devices.
 * 
 * @author Alex Khursevich (alex@qaprosoft.com)
 */
public class MobileRemoteProxy extends DefaultRemoteProxy {
    private static final Logger LOGGER = Logger.getLogger(MobileRemoteProxy.class.getName());
    private static final Set<String> recordingSessions = new HashSet<>();
    
    private static final String ENABLE_VIDEO = "enableVideo";
    
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
            LOGGER.fine("Node " + this + " has no matching capability");
            return null;
        }

        // any slot left at all?
        if (getTotalUsed() >= config.maxSession) {
            LOGGER.fine("Node " + this + " has no free slots");
            return null;
        }

        // any slot left for the given app ?
        for (TestSlot testslot : getTestSlots()) {

			// Check if device is busy in STF
			if (STF.isSTFRequired(testslot.getCapabilities(), requestedCapability)
					&& !STF.isDeviceAvailable((String) testslot.getCapabilities().get("udid"))) {
				return null;
			}
            
            TestSession session = testslot.getNewSession(requestedCapability);

			if (session != null) {
				return session;
			}
        }
        return null;
    }

    @Override
    public void beforeSession(TestSession session) {
        String sessionId = getExternalSessionId(session);
        LOGGER.log(Level.FINEST, "beforeSession sessionId:" + sessionId);
        
        String udid = String.valueOf(session.getSlot().getCapabilities().get("udid"));
        if (STF.isSTFRequired(session.getSlot().getCapabilities(), session.getRequestedCapabilities())) {
        	LOGGER.info("STF reserve device: " + udid);
            STF.reserveDevice(udid, session.getRequestedCapabilities());
        }
        
		if (!StringUtils.isEmpty(udid)) {
			// this is our mobile Android or iOS device
			session.getRequestedCapabilities().put("slotCapabilities", getSlotCapabilities(session, udid));
		}
		
    }
    
    @Override
    public void afterSession(TestSession session) {
        String sessionId = getExternalSessionId(session);
        LOGGER.log(Level.FINEST, "afterSession sessionId:" + sessionId);
        
        // unable to start recording after Session due to the:
        // Error running afterSession for ext. key 5e6960c5-b82b-4e68-a24d-508c3d98dc53, the test slot is now dead: null
        
        if (STF.isSTFRequired(session.getSlot().getCapabilities(), session.getRequestedCapabilities())) {
        	String udid = String.valueOf(session.getSlot().getCapabilities().get("udid"));
        	LOGGER.info("STF return device: " + udid);
            STF.returnDevice(udid, session.getRequestedCapabilities());
        }
        
        
        /*
         * 3. Upload generated video file to S3 compatible storage (asynchronously)
         *      desired location in bucket: 
         * 4. remove local file if upload is ok 
         */

    }
    
    @Override
    public void afterCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        super.afterCommand(session, request, response);

        // ExternalKey is defined ONLY after first valid command so we can start recording
        String sessionId = getExternalSessionId(session);
        LOGGER.log(Level.FINEST, "afterCommand sessionId:" + sessionId);
        
        // double check that external key not empty
        if (!isRecording(sessionId) && !sessionId.isEmpty()) {
            if (isVideoEnabled(session)) {
                recordingSessions.add(sessionId);
                LOGGER.info("start recording sessionId:" + getExternalSessionId(session));
                startRecording(sessionId, session.getSlot().getRemoteURL().toString());
            }
        }
    }
    
    public void beforeCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        super.beforeCommand(session, request, response);
        String sessionId = getExternalSessionId(session);
        LOGGER.log(Level.FINEST, "afterCommand sessionId:" + sessionId);
        
        //TODO: try to add more conditions to make sure it is DELETE session call 
        // DELETE /wd/hub/session/5e6960c5-b82b-4e68-a24d-508c3d98dc53
        if ("DELETE".equals(request.getMethod())) {
            LOGGER.info(sessionId + " is recording? " + isRecording(sessionId));
            if (isRecording(sessionId)) {
                recordingSessions.remove(sessionId);
                
                String appiumUrl = session.getSlot().getRemoteURL().toString();
                // Do stopRecordingScreen call to appium using predefined args for Android and iOS: 
                // http://appium.io/docs/en/commands/device/recording-screen/stop-recording-screen/
                String data = Appium.stopRecording(appiumUrl, sessionId);
                
                // Convert base64 encoded result string into the mp4 file (use sessionId to make filename unique)
                String filePath = sessionId + ".mp4";
                File file = null;
                
                try {
                    LOGGER.info("Saving video artifact: " + filePath);
                    file = new File(filePath);
                    FileUtils.writeByteArrayToFile(file, Base64.getDecoder().decode(data));
                    LOGGER.info("Saved video artifact: " + filePath);
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Error has been occurred during video artifact generation: " + filePath, e);
                }
            }            
        }
        
      }    
    
    private boolean isRecording(String sessionId) {
        return recordingSessions.contains(sessionId);
    }
    
    private void startRecording(String sessionId, String appiumUrl) {
        
        //TODO: organize smart params setup via properties and capabilities
        Map<String, String> options = new HashMap<>();
        options.put("options.forceRestart", "true");
        options.put("options.timeLimit", "1800");
        
        options.put("options.bitRate", "1000000");
        options.put("options.bugReport", "true");

        /*
         * options.videoType string (iOS Only) The format of the screen capture to be recorded. Available formats are the output of ffmpeg -codecs
         * such as libx264 and mpeg4. Defaults to mpeg4.
         * options.videoQuality string (iOS Only) The video encoding quality (low, medium, high, photo - defaults to medium).
         * options.videoFps string (iOS Only) The Frames Per Second rate of the recorded video. Change this value if the resulting video is too slow
         * or too fast. Defaults to 10. This can decrease the resulting file size.
         * options.videoScale string (iOS Only) The scaling value to apply. Read https://trac.ffmpeg.org/wiki/Scaling for possible values. Example
         * value of 720p scaling is '1280:720'. This can decrease/increase the resulting file size. No scale is applied by default.
         * 
         * options.bitRate string (Android Only) The video bit rate for the video, in megabits per second. 4 Mbp/s(4000000) is by default for Android
         * API level below 27. 20 Mb/s(20000000) for API level 27 and above.
         * options.videoSize string (Android Only) The format is widthxheight. The default value is the device's native display resolution (if
         * supported), 1280x720 if not. For best results, use a size supported by your device's Advanced Video Coding (AVC) encoder. For example,
         * "1280x720"
         * options.bugReport string (Android Only) Set it to true in order to display additional information on the video overlay, such as a
         * timestamp, that is helpful in videos captured to illustrate bugs. This option is only supported since API level 27 (Android O).
         */
        
        // do start_recording_screen call to appium using predefined args for Android and iOS
        // http://appium.io/docs/en/commands/device/recording-screen/start-recording-screen/
        Appium.startRecording(appiumUrl, sessionId, options);
    }
    
	private Map<String, Object> getSlotCapabilities(TestSession session, String udid) {
		//obligatory create new map as original object is UnmodifiableMap
		Map<String, Object> slotCapabilities = new HashMap<String, Object>();
		
		// get existing slot capabilities from session
		slotCapabilities.putAll(session.getSlot().getCapabilities());

		if (STF.isSTFRequired(session.getSlot().getCapabilities(), session.getRequestedCapabilities())) {
			// get remoteURL from STF device and add into custom slotCapabilities map
			String remoteURL = null;
			STFDevice stfDevice = STF.getDevice(udid);
			if (stfDevice != null) {
				LOGGER.info("Identified '" + stfDevice.getModel() + "' device by udid: " + udid);
				remoteURL = (String) stfDevice.getRemoteConnectUrl();
				LOGGER.info("Identified remoteURL '" + remoteURL + "' by udid: " + udid);
				slotCapabilities.put("remoteURL", remoteURL);
			}
		}

		return slotCapabilities;
	}

    private String getExternalSessionId(TestSession session) {
        // external key if exists correlates with valid appium sessionId. Internal key is unique uuid value inside hub
        return session.getExternalKey() != null ? session.getExternalKey().getKey() : "";
    }
    
    private boolean isVideoEnabled(TestSession session) {
        boolean isEnabled = false;
        if (session.getRequestedCapabilities().containsKey(ENABLE_VIDEO)) {
            isEnabled = (session.getRequestedCapabilities().get(ENABLE_VIDEO) instanceof Boolean)
                    ? (Boolean) session.getRequestedCapabilities().get(ENABLE_VIDEO)
                    : Boolean.valueOf((String) session.getRequestedCapabilities().get(ENABLE_VIDEO));
        }

        return isEnabled;
    }

}