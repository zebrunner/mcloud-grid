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
package com.zebrunner.mcloud.grid.integration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.zebrunner.mcloud.grid.integration.client.AppiumClient;
import com.zebrunner.mcloud.grid.models.appium.LogTypes.LogType;
import com.zebrunner.mcloud.grid.models.appium.LogValue;

/**
 * Singleton for Appium client.
 * 
 */
public class Appium {
    private static Logger LOGGER = Logger.getLogger(Appium.class.getName());
    
    private AppiumClient client;

    public final static Appium INSTANCE = new Appium();

    private Appium() {
        this.client = new AppiumClient();
    }

    public static boolean isRunning(String appiumUrl) {
        return INSTANCE.client.isRunning(appiumUrl);
    }

    /**
     * Start Video recording.
     * 
     * @param appiumUrl
     * @param sessionId
     * @return boolean if video recording started
     */
    public static void startRecording(String appiumUrl, String sessionId, Map<String, String> options) {
        INSTANCE.client.startRecordingScreen(appiumUrl, sessionId, options);
    }

    /**
     * Stop Video recording.
     *
     * @param appiumUrl
     * @param sessionId
     * @return Base64 encoded string
     */
    public static String stopRecording(String appiumUrl, String sessionId) {
        String videoContent = INSTANCE.client.stopRecordingScreen(appiumUrl, sessionId);

        if (videoContent != null) {
            LOGGER.finest(
                    String.format("video base64 string: %s", videoContent.length() > 128 ? videoContent.substring(0, 128) + "..." : videoContent));
        }

        return videoContent;
    }

    /**
     * Returns logs of chosen type for the sessionId
     * 
     * @param appiumUrl
     * @param sessionId
     * @param logType
     * @return List of found log entries
     */
    public static List<LogValue> getLogs(String appiumUrl, String sessionId, LogType logType) {
        List<LogValue> logs = INSTANCE.client.getLogs(appiumUrl, sessionId, logType);
        if (logs != null) {
            LOGGER.finest(String.format("Found %d log entries of type '%s' for session %s", logs.size(), logType.toString(), sessionId));
        }
        return logs;
    }

    /**
     * Returns list of available log types for the sessionId
     * 
     * @param appiumUrl
     * @param sessionId
     * @return List of found log types
     */
    public static List<LogType> getLogTypes(String appiumUrl, String sessionId) {
        List<LogType> logTypes = INSTANCE.client.getAvailableLogTypes(appiumUrl, sessionId);
        if (logTypes != null) {
            LOGGER.finest(String.format("Log types available for session %s: %s", sessionId, Arrays.toString(logTypes.toArray())));
        }
        return logTypes;
    }

}