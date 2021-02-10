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
package com.zebrunner.mcloud.grid.integration.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.zebrunner.mcloud.grid.models.appium.LogTypes;
import com.zebrunner.mcloud.grid.models.appium.LogValue;
import com.zebrunner.mcloud.grid.models.appium.Logs;
import com.zebrunner.mcloud.grid.models.appium.Recording;
import com.zebrunner.mcloud.grid.models.appium.Status;
import com.zebrunner.mcloud.grid.util.HttpClient;
import com.zebrunner.mcloud.grid.util.HttpClient.Response;

public class AppiumClient {

    private static Logger LOGGER = Logger.getLogger(AppiumClient.class.getName());

    public void startRecordingScreen(String appiumUrl, String sessionId, Map<String, String> options) {
        Map<String, Object> recordOptions = new HashMap<>();
        recordOptions.put("options", options);
        LOGGER.log(Level.FINEST, "video recording options: " + recordOptions);
        HttpClient.uri(Path.APPIUM_START_RECORDING_SCREEN_PATH, appiumUrl, sessionId).post(Void.class, recordOptions);
    }

    public String stopRecordingScreen(String appiumUrl, String sessionId) {
        Response<Recording> response = HttpClient.uri(Path.APPIUM_STOP_RECORDING_SCREEN_PATH, appiumUrl, sessionId).post(Recording.class, null);

        String result = null;
        if (response.getStatus() == 200) {
            result = ((Recording) response.getObject()).getValue();
        } else {
            LOGGER.log(Level.SEVERE, "Appium response is unsuccessful for stop recoding call: " + response.getStatus());
        }
        return result;
    }

    public List<String> getAvailableLogTypes(String appiumUrl, String sessionId) {
        Response<LogTypes> response = HttpClient.uri(Path.APPIUM_GET_LOG_TYPES_PATH, appiumUrl, sessionId)
                .get(LogTypes.class);

        List<String> result = null;
        if (response.getStatus() == 200) {
            result = response.getObject().getValue();
        } else {
            LOGGER.log(Level.SEVERE, "Appium response is unsuccessful for get log types call: " + response.getStatus());
        }
        return result;
    }

    public List<LogValue> getLogs(String appiumUrl, String sessionId, String logType) {
        Map<String, Object> json = new HashMap<>();
        json.put("type", logType);
        Response<Logs> response = HttpClient.uri(Path.APPIUM_GET_LOGS_PATH, appiumUrl, sessionId)
                .post(Logs.class, json);

        List<LogValue> result = null;
        if (response.getStatus() == 200) {
            result = response.getObject().getValue();
        } else {
            LOGGER.log(Level.SEVERE, "Appium response is unsuccessful for get logs call: " + response.getStatus());
        }
        return result;
    }

    public boolean isRunning(String appiumUrl) {
        Response<Status> response = HttpClient.uri(Path.APPIUM_STATUS, appiumUrl).get(Status.class);
        return response.getStatus() == 200;
    }

}
