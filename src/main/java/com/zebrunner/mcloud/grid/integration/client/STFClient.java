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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;

import com.zebrunner.mcloud.grid.Platform;
import com.zebrunner.mcloud.grid.models.stf.Device;
import com.zebrunner.mcloud.grid.models.stf.Devices;
import com.zebrunner.mcloud.grid.models.stf.RemoteConnectUserDevice;
import com.zebrunner.mcloud.grid.models.stf.STFDevice;
import com.zebrunner.mcloud.grid.util.HttpClient;

@SuppressWarnings("rawtypes")
public class STFClient {
    private static Logger LOGGER = Logger.getLogger(STFClient.class.getName());

    private String serviceURL;
    private String authToken;
    private long timeout;
    
    public STFClient(String serviceURL, String authToken, long timeout) {
        this.serviceURL = serviceURL;
        this.authToken = authToken;
        this.timeout = timeout;
        
        if (isEnabled()) {
            //TODO: get User object from response and put into the log the name and maybe extra useful info like "lastUsedDevice"
            LOGGER.fine(String.format("Trying to verify connection to '%s' using '%s' token...", serviceURL, authToken));
            HttpClient.Response response = HttpClient.uri(Path.STF_USER_PATH, serviceURL)
                    .withAuthorization(buildAuthToken(authToken))
                    .get(Void.class);
    
            int status = response.getStatus();
            if (status == 200) {
                LOGGER.fine("STF connection successfully established.");
            } else {
                String error = String.format("STF connection not established! URL: '%s'; Token: '%s'; Error code: %d",
                        serviceURL, authToken, status);
                LOGGER.log(Level.SEVERE, error);
                throw new RuntimeException(error);
            }
        } else {
            LOGGER.fine("STF integration disabled.");
        }
    }
    
    public boolean isEnabled() {
        return (!StringUtils.isEmpty(this.serviceURL) && !StringUtils.isEmpty(this.authToken));
    }
    
    /**
     * Checks availability status in STF.
     * 
     * @param udid
     *            - device UDID
     * @return returns availability status
     */
    public boolean isDeviceAvailable(String udid) {
        boolean available = false;

        try {
            HttpClient.Response<Devices> rs = getAllDevices();
            if (rs.getStatus() == 200) {
                for (STFDevice device : rs.getObject().getDevices()) {
                    if (udid.equals(device.getSerial())) {
                        available = device.getPresent() && device.getReady() && !device.getUsing()
                                && device.getOwner() == null;
                        break;
                    }
                }
            } else {
                LOGGER.log(Level.SEVERE, "Unable to get devices status HTTP status: " + rs.getStatus());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to get devices status HTTP status via udid: " + udid, e);
        }

        return available;
    }
    
    /**
     * Gets STF device info.
     * 
     * @param udid
     *            - device UDID
     * @return STF device
     */
    public STFDevice getDevice(String udid) {
        if (!isEnabled()) {
            return null;
        }
        
        STFDevice device = null;
        try {
            HttpClient.Response<Device> rs = HttpClient.uri(Path.STF_DEVICES_ITEM_PATH, serviceURL, udid)
                    .withAuthorization(buildAuthToken(authToken))
                    .get(Device.class);
            if (rs.getStatus() == 200) {
                device = rs.getObject().getDevice();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to get device HTTP status via udid: " + udid, e);
        }

        return device;
    }
    
    /**
     * Connect to remote device.
     * 
     * @param udid
     *            - device UDID
     * @return status of connected device
     */
    public boolean reserveDevice(String udid, Map<String, Object> requestedCapability) {
        if (!isEnabled()) {
            return false;
        }
        
        boolean status = reserveDevice(udid);
        if (status && Platform.ANDROID.equals(Platform.fromCapabilities(requestedCapability))) {
            status = remoteConnectDevice(udid).getStatus() == 200;
        }
        return status;
    }
    
    /**
     * Disconnect STF device.
     * 
     * @param udid
     *            - device UDID
     * @return status of returned device
     */
    public boolean returnDevice(String udid, Map<String, Object> requestedCapability) {
        if (!isEnabled()) {
            return false;
        }
        
        // it seems like return and remote disconnect guarantee that device becomes free asap
        boolean status = true;
        if (Platform.ANDROID.equals(Platform.fromCapabilities(requestedCapability))) {
            status = remoteDisconnectDevice(udid);
        }
        return status && returnDevice(udid);
    }
    
    private HttpClient.Response<Devices> getAllDevices() {
        return HttpClient.uri(Path.STF_DEVICES_PATH, serviceURL)
                  .withAuthorization(buildAuthToken(authToken))
                  .get(Devices.class);
    }

    private boolean reserveDevice(String serial) {
        Map<String, String> entity = new HashMap<>();
        entity.put("serial", serial);
        entity.put("timeout", String.valueOf(TimeUnit.SECONDS.toMillis(this.timeout))); // 3600 sec by default
        HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_PATH, serviceURL)
                         .withAuthorization(buildAuthToken(authToken))
                         .post(Void.class, entity);
        return response.getStatus() == 200;
    }

    private boolean returnDevice(String serial) {
        HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_BY_ID_PATH, serviceURL, serial)
                                                 .withAuthorization(buildAuthToken(authToken))
                                                 .delete(Void.class);
        return response.getStatus() == 200;
    }

    private HttpClient.Response<RemoteConnectUserDevice> remoteConnectDevice(String serial) {
        LOGGER.fine("STF reserve device: " + serial);
        return HttpClient.uri(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH, serviceURL, serial)
                         .withAuthorization(buildAuthToken(authToken))
                         .post(RemoteConnectUserDevice.class, null);
    }

    private boolean remoteDisconnectDevice(String serial) {
        LOGGER.fine("STF return device: " + serial);
        HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH, serviceURL, serial)
                                                 .withAuthorization(buildAuthToken(authToken))
                                                 .delete(Void.class);
        return response.getStatus() == 200;
    }

    private String buildAuthToken(String authToken) {
        return "Bearer " + authToken;
    }

}
