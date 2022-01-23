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
import com.zebrunner.mcloud.grid.models.stf.User;
import com.zebrunner.mcloud.grid.util.HttpClient;

@SuppressWarnings("rawtypes")
public class STFClient {
    private static Logger LOGGER = Logger.getLogger(STFClient.class.getName());

    private String serviceURL;
    private String authToken;
    private User user;
    private long timeout;
    
    private boolean isConnected = false;
    
    public STFClient(String serviceURL, String authToken, long timeout) {
        this.serviceURL = serviceURL;
        this.authToken = authToken;
        this.timeout = timeout;
        
        if (isEnabled()) {
            LOGGER.fine(String.format("Trying to verify connection to '%s' using '%s' token...", serviceURL, authToken));
            // do an extra verification call to make sure enabled connection might be established
            HttpClient.Response<User> response = HttpClient.uri(Path.STF_USER_PATH, serviceURL)
                    .withAuthorization(buildAuthToken(authToken))
                    .get(User.class);

            int status = response.getStatus();
            if (status == 200) {
                LOGGER.fine("STF connection successfully established.");
                this.user = (User) response.getObject();
                if (this.user.getSuccess()) {
                    String msg = String.format("User (privilege is '%s') %s (%s) was sucessfully logged in.", this.user.getUser()
                            .getPrivilege(),
                            this.user.getUser().getName(), this.user.getUser().getEmail());
                    LOGGER.fine(msg);
                } else {
                    LOGGER.log(Level.SEVERE, String.format("Not authenticated at STF successfully! URL: '%s'; Token: '%s';", serviceURL, authToken));
                    throw new RuntimeException("Not authenticated at STF!");
                }
            } else {
                LOGGER.log(Level.SEVERE, String.format("Required STF connection not established! URL: '%s'; Token: '%s'; Error code: %d",
                        serviceURL, authToken, status));
                throw new RuntimeException("Unable to connect to STF!");
            }
        } else {
            LOGGER.fine("STF integration disabled.");
        }
    }
    
    public boolean isEnabled() {
        return (!StringUtils.isEmpty(this.serviceURL) && !StringUtils.isEmpty(this.authToken));
    }
    
    public boolean isConnected() {
        return this.isConnected;
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
                        LOGGER.log(Level.INFO, "this.user.getUser().getName(): " + this.user.getUser().getName());
                        
                        LOGGER.log(Level.INFO, "device.getPresent(): " + device.getPresent());
                        LOGGER.log(Level.INFO, "device.getReady(): " + device.getReady());
                        LOGGER.log(Level.INFO, "device.getOwner(): " + device.getOwner());
                        
                        boolean isOccupied = device.getOwner() == null;
                        boolean isAccessible = false;
                        if (!isOccupied) {
                            isAccessible = true;
                        } else {
                            // #54 try to check usage ownership by token to allow automation launch over occupied devices
                            LOGGER.log(Level.INFO, "device.getOwner().getName(): " + device.getOwner().getName());
                            // isAccessible should be tru if the same STF user occupied device
                            isAccessible = this.user.getUser().getName().equals(device.getOwner().getName());
                        }

                        available = device.getPresent() && device.getReady() && isAccessible;
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
        
        if (status) {
            this.isConnected = true;
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
        if (!isConnected()) {
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
        Map<String, Object> entity = new HashMap<>();
        entity.put("serial", serial);
        entity.put("timeout", TimeUnit.SECONDS.toMillis(this.timeout)); // 3600 sec by default
        
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
