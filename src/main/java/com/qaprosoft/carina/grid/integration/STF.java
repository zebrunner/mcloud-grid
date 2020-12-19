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
package com.qaprosoft.carina.grid.integration;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;

import com.qaprosoft.carina.grid.Platform;
import com.qaprosoft.carina.grid.integration.client.STFClient;
import com.qaprosoft.carina.grid.integration.client.impl.STFClientImpl;
import com.qaprosoft.carina.grid.models.stf.Device;
import com.qaprosoft.carina.grid.models.stf.Devices;
import com.qaprosoft.carina.grid.models.stf.STFDevice;
import com.qaprosoft.carina.grid.util.HttpClient;

/**
 * Singleton for STF client.
 * 
 * @author Alex Khursevich (alex@qaprosoft.com)
 */
public class STF {
    private static Logger LOGGER = Logger.getLogger(STF.class.getName());

    private static final String STF_URL = System.getProperty("STF_URL");
    private static final String STF_TOKEN = System.getProperty("STF_TOKEN");
    
    // Max time is seconds for reserving devices in STF
    private static final long STF_TIMEOUT = Long.parseLong(System.getProperty("STF_TIMEOUT"), 10);
    
    private static final String ENABLE_STF = "enableStf";
    
    private static boolean isConnected = false;

    private STFClient client;

    public final static STF INSTANCE = new STF();

    private STF() {
        LOGGER.info("*********************************");
        if (!StringUtils.isEmpty(STF_URL) && !StringUtils.isEmpty(STF_TOKEN)) {
            LOGGER.info("Credentials for STF: " + STF_URL + "/" + STF_TOKEN);
            this.client = new STFClientImpl(STF_URL, STF_TOKEN);
            int status = this.client.getAllDevices().getStatus();
            if (status == 200) {
                isConnected = true;
                LOGGER.info("STF connection established.");
            } else {
                throw new RuntimeException("Unable to start hub due to the STF connection error! Code: " + status);
            }
        } else {
            LOGGER.warning("Set STF_URL and STF_TOKEN to use STF integration!");
        }
        LOGGER.info("*********************************");
    }

    public static boolean isConnected() {
        return isConnected;
    }

    /**
     * Checks availability status in STF.
     * 
     * @param udid
     *            - device UDID
     * @return returns availability status
     */
    public static boolean isDeviceAvailable(String udid) {
        if (!isConnected()) {
            return false;
        }

        boolean available = false;

        try {
            HttpClient.Response<Devices> rs = INSTANCE.client.getAllDevices();
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
    public static STFDevice getDevice(String udid) {
        if (!isConnected()) {
            return null;
        }

        STFDevice device = null;
        try {
            HttpClient.Response<Device> rs = INSTANCE.client.getDevice(udid);
            if (rs.getStatus() == 200) {
                device = rs.getObject().getDevice();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to get device HTTP status via udid: " + udid, e);
        }

        return device;
    }

    /**
     * Connects to remote device.
     * 
     * @param udid
     *            - device UDID
     * @return status of connected device
     */
    public static boolean reserveDevice(String udid, Map<String, Object> requestedCapability) {
        boolean status = INSTANCE.client.reserveDevice(udid, TimeUnit.SECONDS.toMillis(STF_TIMEOUT));
        if (status && Platform.ANDROID.equals(Platform.fromCapabilities(requestedCapability))) {
            status = INSTANCE.client.remoteConnectDevice(udid).getStatus() == 200;
        }
        return status;
    }

    /**
     * Disconnects STF device.
     * 
     * @param udid
     *            - device UDID
     * @return status of returned device
     */
    public static boolean returnDevice(String udid, Map<String, Object> requestedCapability) {
        // it seems like return and remote disconnect guarantee that device becomes free
        // asap
        boolean status = true;
        if (Platform.ANDROID.equals(Platform.fromCapabilities(requestedCapability))) {
            status = INSTANCE.client.remoteDisconnectDevice(udid);
        }
        return status && INSTANCE.client.returnDevice(udid);
    }

    /**
     * Checks if STF integration enabled according to isConnected() result and capabilities.
     * 
     * @param nodeCapability
     *            - Selenium node capability
     * @param requestedCapability
     *            - requested capabilities
     * @return if STF required
     */
    public static boolean isEnabled(Map<String, Object> nodeCapability, Map<String, Object> requestedCapability) {
        boolean status = isConnected();

        // User may pass desired capability "enableStf=false" to disable integration
        if (status && (requestedCapability.containsKey(ENABLE_STF))) {
            status = (requestedCapability.get(ENABLE_STF) instanceof Boolean)
                    ? (Boolean) requestedCapability.get(ENABLE_STF)
                    : Boolean.valueOf((String) requestedCapability.get(ENABLE_STF));
        }

        // Appium node must have UDID capability to be identified in STF
        if (status && !nodeCapability.containsKey("udid")) {
            status = false;
        }

        return status;
    }
}