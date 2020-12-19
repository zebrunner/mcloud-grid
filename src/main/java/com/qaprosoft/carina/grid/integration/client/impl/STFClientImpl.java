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

import com.qaprosoft.carina.grid.integration.client.Path;
import com.qaprosoft.carina.grid.integration.client.STFClient;
import com.qaprosoft.carina.grid.models.stf.Device;
import com.qaprosoft.carina.grid.models.stf.Devices;
import com.qaprosoft.carina.grid.models.stf.RemoteConnectUserDevice;
import com.qaprosoft.carina.grid.util.HttpClient;

@SuppressWarnings("rawtypes")
public class STFClientImpl implements STFClient {

    private String serviceURL;
    private String authToken;

    public STFClientImpl(String serviceURL, String authToken) {
        this.serviceURL = serviceURL;
        this.authToken = authToken;
    }
    
    @Override
    public HttpClient.Response<Devices> getAllDevices() {
        return HttpClient.uri(Path.STF_DEVICES_PATH, serviceURL)
                  .withAuthorization(buildAuthToken(authToken))
                  .get(Devices.class);
    }

    @Override
    public HttpClient.Response<Device> getDevice(String udid) {
        return HttpClient.uri(Path.STF_DEVICES_ITEM_PATH, serviceURL, udid)
                         .withAuthorization(buildAuthToken(authToken))
                         .get(Device.class);
    }

    @Override
    public boolean reserveDevice(String serial, long timeout) {
        Map<String, String> entity = new HashMap<>();
        entity.put("serial", serial);
        HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_PATH, serviceURL)
                         .withAuthorization(buildAuthToken(authToken))
                         .post(Void.class, entity);
        return response.getStatus() == 200;
    }

    @Override
    public boolean returnDevice(String serial) {
        HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_BY_ID_PATH, serviceURL, serial)
                                                 .withAuthorization(buildAuthToken(authToken))
                                                 .delete(Void.class);
        return response.getStatus() == 200;
    }

    @Override
    public HttpClient.Response<RemoteConnectUserDevice> remoteConnectDevice(String serial) {
        return HttpClient.uri(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH, serviceURL, serial)
                         .withAuthorization(buildAuthToken(authToken))
                         .post(RemoteConnectUserDevice.class, null);
    }

    @Override
    public boolean remoteDisconnectDevice(String serial) {
        HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH, serviceURL, serial)
                                                 .withAuthorization(buildAuthToken(authToken))
                                                 .post(Void.class, null);
        return response.getStatus() == 200;
    }

    @Override
    public boolean isConnected() {
		HttpClient.Response response = HttpClient.uri(Path.STF_DEVICES_PATH, serviceURL)
                                                 .withAuthorization(buildAuthToken(authToken))
                                                 .get(Devices.class);
        return response.getStatus() == 200;
    }

    private String buildAuthToken(String authToken) {
        return "Bearer " + authToken;
    }

}
