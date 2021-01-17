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
package com.zebrunner.carina.grid.integration;

import java.util.Map;
import java.util.logging.Logger;

import com.zebrunner.carina.grid.integration.client.AppiumClient;

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
        
        String message = videoContent;
        if (message.length() > 128) {
            message = message.substring(0, 128);
        }
        LOGGER.finest("video base64 string: " + message + "...");
        return videoContent;
    }

}