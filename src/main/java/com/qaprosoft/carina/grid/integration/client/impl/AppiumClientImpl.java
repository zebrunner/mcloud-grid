/*******************************************************************************
 * Copyright 2013-2019 Qaprosoft (http://www.qaprosoft.com).
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
package com.qaprosoft.carina.grid.integration.client.impl;

import java.util.HashMap;
import java.util.Map;

import com.qaprosoft.carina.grid.integration.client.AppiumClient;
import com.qaprosoft.carina.grid.integration.client.Path;
import com.qaprosoft.carina.grid.models.appium.Status;
import com.qaprosoft.carina.grid.util.HttpClient;
import com.qaprosoft.carina.grid.util.HttpClient.Response;

@SuppressWarnings("rawtypes")
public class AppiumClientImpl implements AppiumClient {

    public AppiumClientImpl() {
    }

    @Override
    public void startRecordingScreen(String appiumUrl, String sessionId, Map<String, String> options) {
        HttpClient.uri(Path.APPIUM_START_RECORDING_SCREEN_PATH, appiumUrl, sessionId).post(Void.class, options);
    }

    @Override
    public String stopRecordingScreen(String appiumUrl, String sessionId) {
        Map<String, String> entity = new HashMap<>();
        entity.put("remotePath", "");
        entity.put("username", "");
        entity.put("password", "");
        entity.put("method", "PUT");
        
        HttpClient.Response response = HttpClient.uri(Path.APPIUM_STOP_RECORDING_SCREEN_PATH, appiumUrl, sessionId).post(Void.class, entity);
        
//        18:27:56.544 ERROR [RequestHandler.process] - cannot forward the request null
//        java.lang.NullPointerException
//                at com.qaprosoft.carina.grid.integration.client.impl.AppiumClientImpl.stopRecordingScreen(AppiumClientImpl.java:..)
        //TODO: find a way to retrieve valid result (base64 encoded video string from response)
        return response.getObject().toString();
    }

    @Override
    public boolean isRunning(String appiumUrl) {
        Response<Status> response = HttpClient.uri(Path.APPIUM_STATUS, appiumUrl).get(Status.class);
        return response.getStatus() == 200;
    }

}
