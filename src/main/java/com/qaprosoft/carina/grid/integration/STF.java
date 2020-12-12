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
    
    private static final String STF_URL = "STF_URL";
    private static final String STF_TOKEN = "STF_TOKEN";
    private static final String STF_ENABLED = "enableStf";

    private static final Long STF_TIMEOUT = 3600L;

    private static boolean running = false;

    private STFClient client;

    public final static STF INSTANCE = new STF();

    private STF() {
        String serviceURL = System.getProperty(STF_URL);
        String authToken = System.getProperty(STF_TOKEN);
        LOGGER.info("*********************************");
        LOGGER.info("Credentials for STF: " + serviceURL + " / " + authToken);
        if (!StringUtils.isEmpty(serviceURL) && !StringUtils.isEmpty(authToken)) {
            this.client = new STFClientImpl(serviceURL, authToken);
            if (this.client.getAllDevices().getStatus() == 200) {
                running = true;
                LOGGER.info("STF connection established");
            } else {
                LOGGER.info("STF connection error");
            }
        } else {
            LOGGER.info("Set STF_URL and STF_TOKEN to use STF integration");
        }
        LOGGER.info("*********************************");
    }

    public static boolean isRunning() {
        return running;
    }

    /**
     * Checks availability status in STF.
     * 
     * @param udid
     *            - device UDID
     * @return returns availability status
     */
    public static boolean isDeviceAvailable(String udid) {
        boolean available = false;
        if (isRunning()) {
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
    	STFDevice device = null;
        if (isRunning()) {
            try {
                HttpClient.Response<Device> rs = INSTANCE.client.getDevice(udid);
                if (rs.getStatus() == 200) {
                    device = rs.getObject().getDevice();
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unable to get device HTTP status via udid: " + udid, e);
            }
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
        return  status && INSTANCE.client.returnDevice(udid);
    }

    /**
     * Checks STF required status according to capabilities.
     * 
     * @param nodeCapability
     *            - Selenium node capability
     * @param requestedCapability
     *            - requested capabilities
     * @return if STF required
     */
    public static boolean isSTFRequired(Map<String, Object> nodeCapability, Map<String, Object> requestedCapability) {
        boolean status = true;

        // STF integration not established
        if (status && !STF.isRunning()) {
            status = false;
        }

        // User may pass desired capability enableStf=false for local run
        if (status && (requestedCapability.containsKey(STF_ENABLED))) {
            status = (requestedCapability.get(STF_ENABLED) instanceof Boolean)
                    ? (Boolean) requestedCapability.get(STF_ENABLED)
                    : Boolean.valueOf((String) requestedCapability.get(STF_ENABLED));
        }

        // Appium node should contain UDID capability to be identified in STF
        if (status && !nodeCapability.containsKey("udid")) {
            status = false;
        }

        return status;
    }
}