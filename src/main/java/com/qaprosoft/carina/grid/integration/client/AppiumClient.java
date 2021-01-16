/*******************************************************************************
 * Copyright 2013-2021 Qaprosoft (http://www.qaprosoft.com).
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
package com.qaprosoft.carina.grid.integration.client;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.qaprosoft.carina.grid.models.appium.Recording;
import com.qaprosoft.carina.grid.models.appium.Status;
import com.qaprosoft.carina.grid.util.HttpClient;
import com.qaprosoft.carina.grid.util.HttpClient.Response;

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

    public boolean isRunning(String appiumUrl) {
        Response<Status> response = HttpClient.uri(Path.APPIUM_STATUS, appiumUrl).get(Status.class);
        return response.getStatus() == 200;
    }

}
