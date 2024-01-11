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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import org.apache.commons.lang3.StringUtils;

import com.zebrunner.mcloud.grid.Platform;
import com.zebrunner.mcloud.grid.models.stf.Devices;
import com.zebrunner.mcloud.grid.models.stf.RemoteConnectUserDevice;
import com.zebrunner.mcloud.grid.models.stf.STFDevice;
import com.zebrunner.mcloud.grid.models.stf.User;
import com.zebrunner.mcloud.grid.util.HttpClient;

@SuppressWarnings("rawtypes")
public final class STFClient {
    private static final Logger LOGGER = Logger.getLogger(STFClient.class.getName());
    private static final Map<String, STFClient> STF_CLIENTS = new ConcurrentHashMap<>();
    private static final Map<String, Duration> STF_DEVICE_IGNORE_AUTOMATION_TIMERS = new ConcurrentHashMap<>();
    private static final String STF_URL = System.getenv("STF_URL");
    private static final String DEFAULT_STF_TOKEN = System.getenv("STF_TOKEN");
    // Max time is seconds for reserving devices in STF
    private static final String DEFAULT_STF_TIMEOUT = System.getenv("STF_TIMEOUT");

    private static final Duration UNAUTHORIZED_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration UNHEALTHY_TIMEOUT = Duration.ofMinutes(5);

    private Platform platform;
    private String token;
    private boolean isReservedManually = false;
    private STFDevice device;
    private String sessionUUID;

    private STFClient() {
        //do nothing
    }

    /**
     * Get STF device
     *
     * @param udid udid of the device
     * @return {@link STFDevice} if STF client registered, null otherwise
     */
    public static STFDevice getSTFDevice(String udid) {
        if (STF_CLIENTS.get(udid) != null) {
            return STF_CLIENTS.get(udid).getDevice();
        }
        return null;
    }

    /**
     * Reserve STF device
     */
    public static boolean reserveSTFDevice(String deviceUDID, Map<String, Object> requestedCapabilities, String sessionUUID) {
        LOGGER.info(() -> String.format("[STF-%s] Reserve STF Device.", sessionUUID));
        if (!isSTFEnabled()) {
            return true;
        }

        if (STF_DEVICE_IGNORE_AUTOMATION_TIMERS.get(deviceUDID) != null) {
            Duration timeout = STF_DEVICE_IGNORE_AUTOMATION_TIMERS.get(deviceUDID);
            if (Duration.ofMillis(System.currentTimeMillis()).compareTo(timeout) < 0) {
                return false;
            } else {
                STF_DEVICE_IGNORE_AUTOMATION_TIMERS.remove(deviceUDID);
            }
        }


        if (STF_CLIENTS.get(deviceUDID) != null) {
            LOGGER.warning(() -> String.format("Device '%s' already busy (in the local pool). Info: %s", deviceUDID, STF_CLIENTS.get(deviceUDID)));
        }

        STFClient stfClient = new STFClient();
        String stfToken = CapabilityUtils.getZebrunnerCapability(requestedCapabilities, "STF_TOKEN")
                .map(String::valueOf)
                .orElse(DEFAULT_STF_TOKEN);
        Integer stfTimeout = CapabilityUtils.getZebrunnerCapability(requestedCapabilities, "STF_TIMEOUT")
                .map(String::valueOf)
                .map(Integer::parseInt)
                .orElse(Integer.parseInt(DEFAULT_STF_TIMEOUT));

        stfClient.setToken(stfToken);

        HttpClient.Response<User> user = HttpClient.uri(Path.STF_USER_PATH, STF_URL)
                .withAuthorization(buildAuthToken(stfToken))
                .get(User.class);

        if (user.getStatus() != 200) {
            LOGGER.warning(() ->
                    String.format("[STF-%s] Not authenticated at STF successfully! URL: '%s'; Token: '%s';", sessionUUID, STF_URL, stfToken));
            return false;
        }

        HttpClient.Response<Devices> devices = HttpClient.uri(Path.STF_DEVICES_PATH, STF_URL)
                .withAuthorization(buildAuthToken(stfToken))
                .get(Devices.class);

        if (devices.getStatus() != 200) {
            LOGGER.warning(() -> String.format("[STF-%s] Unable to get devices status. HTTP status: %s", sessionUUID, devices.getStatus()));
            return false;
        }

        Optional<STFDevice> optionalSTFDevice = devices.getObject().getDevices()
                .stream().filter(device -> StringUtils.equals(device.getSerial(), deviceUDID))
                .findFirst();

        if (optionalSTFDevice.isEmpty()) {
            LOGGER.warning(() -> String.format("[STF-%s] Could not find STF device with udid: %s", sessionUUID, deviceUDID));
            return false;
        }

        STFDevice stfDevice = optionalSTFDevice.get();
        LOGGER.info(() -> String.format("[STF-%s] STF device info: %s", sessionUUID, stfDevice));

        stfClient.setDevice(stfDevice);

        if(stfDevice.getStatus() == null) {
            LOGGER.warning(() -> String.format("[STF-%s] STF device status is null. It will be ignored.", sessionUUID));
            return false;
        }

        if(stfDevice.getStatus().intValue() == 2) {
            LOGGER.warning(() -> String.format("[STF-%s] STF device status 'UNAUTHORIZED'. It will be ignored: %s.", sessionUUID,
                    UNAUTHORIZED_TIMEOUT));
           STF_DEVICE_IGNORE_AUTOMATION_TIMERS.put(deviceUDID, Duration.ofMillis(System.currentTimeMillis()).plus(UNAUTHORIZED_TIMEOUT));
           return false;
        }

        if(stfDevice.getStatus() == 7) {
            LOGGER.warning(() -> String.format("[STF-%s] STF device status 'UNHEALTHY'. It will be ignored: %s.", sessionUUID, UNHEALTHY_TIMEOUT));
            STF_DEVICE_IGNORE_AUTOMATION_TIMERS.put(deviceUDID, Duration.ofMillis(System.currentTimeMillis()).plus(UNHEALTHY_TIMEOUT));
            return false;
        }

        if (stfDevice.getOwner() != null && StringUtils.equals(stfDevice.getOwner().getName(), user.getObject().getUser().getName()) &&
                stfDevice.getPresent() &&
                stfDevice.getReady()) {
            LOGGER.info(() -> String.format("[STF-%s] Device [%s] already reserved manually by the same user: %s.",
                    sessionUUID, deviceUDID, stfDevice.getOwner().getName()));
            stfClient.reservedManually(true);
        } else if (stfDevice.getOwner() == null && stfDevice.getPresent() && stfDevice.getReady()) {

            Map<String, Object> entity = new HashMap<>();
            entity.put("serial", deviceUDID);
            entity.put("timeout", TimeUnit.SECONDS.toMillis(stfTimeout));
            HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_PATH, STF_URL)
                    .withAuthorization(buildAuthToken(stfToken))
                    .post(Void.class, entity);
            if (response.getStatus() != 200) {
                LOGGER.warning(() -> String.format("[STF-%s] Could not reserve STF device with udid: %s. Status: %s. Response: %s",
                        sessionUUID, deviceUDID, response.getStatus(), response.getObject()));
                return false;
            }
        } else {
            return false;
        }

        if (Platform.ANDROID.equals(Platform.fromCapabilities(requestedCapabilities))) {
            LOGGER.info(() -> String.format("[STF-%s] Additionally call 'remoteConnect'.", sessionUUID));

            HttpClient.Response<RemoteConnectUserDevice> remoteConnectUserDevice = HttpClient.uri(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH,
                            STF_URL, deviceUDID)
                    .withAuthorization(buildAuthToken(stfToken))
                    .post(RemoteConnectUserDevice.class, null);

            if (remoteConnectUserDevice.getStatus() != 200) {
                LOGGER.warning(
                        () -> String.format("[STF-%s] Unsuccessful remoteConnect. Status: %s. Response: %s",
                                sessionUUID, remoteConnectUserDevice.getStatus(), remoteConnectUserDevice.getObject()));

                if (!stfClient.reservedManually()) {
                    if (HttpClient.uri(Path.STF_USER_DEVICES_BY_ID_PATH, STF_URL, deviceUDID)
                            .withAuthorization(buildAuthToken(stfToken))
                            .delete(Void.class).getStatus() != 200) {
                        LOGGER.warning(() -> String.format("[STF-%s] Could not return device to the STF after unsuccessful Android remoteConnect.",
                                sessionUUID));
                    }
                }
                return false;
            }
        }

        //RemoteURL appears only after reservation
        if (Platform.ANDROID.equals(Platform.fromCapabilities(requestedCapabilities)) &&
                CapabilityUtils.getZebrunnerCapability(requestedCapabilities, "enableAdb")
                        .map(String::valueOf)
                        .map(Boolean::parseBoolean)
                        .orElse(false)) {
            // get again device info
            HttpClient.Response<Devices> _devices = HttpClient.uri(Path.STF_DEVICES_PATH, STF_URL)
                    .withAuthorization(buildAuthToken(stfToken))
                    .get(Devices.class);

            if (_devices.getStatus() != 200) {
                LOGGER.warning(() -> String.format("[STF-%s] Unable to get devices status. HTTP status: %s", sessionUUID, _devices.getStatus()));
                return false;
            }

            Optional<STFDevice> _optionalSTFDevice = _devices.getObject().getDevices()
                    .stream()
                    .filter(device -> StringUtils.equals(device.getSerial(), deviceUDID))
                    .findFirst();

            if (_optionalSTFDevice.isEmpty()) {
                LOGGER.warning(() -> String.format("[STF-%s] Could not find STF device with udid: %s", sessionUUID, deviceUDID));
                return false;
            }
            STFDevice _stfDevice = _optionalSTFDevice.get();
            stfClient.setDevice(_stfDevice);

            if (StringUtils.isBlank((String) _stfDevice.getRemoteConnectUrl())) {
                LOGGER.warning(() -> String.format("[STF-%s] Detected 'true' enableAdb capability, but remoteURL is blank or empty.", sessionUUID));
                if (!stfClient.reservedManually()) {
                    if (HttpClient.uri(Path.STF_USER_DEVICES_BY_ID_PATH, STF_URL, deviceUDID)
                            .withAuthorization(buildAuthToken(stfToken))
                            .delete(Void.class).getStatus() != 200) {
                        LOGGER.warning(() -> String.format("[STF-%s] Could not return device to the STF after unsuccessful remoteURL check.",
                                sessionUUID));
                    }
                }
                return false;
            } else {
                LOGGER.info(() -> String.format("[STF-%s] Detected 'true' enableAdb capability, and remoteURL is present.", sessionUUID));
            }
        }

        stfClient.setPlatform(Platform.fromCapabilities(requestedCapabilities));
        stfClient.setSTFSessionUUID(sessionUUID);
        LOGGER.info(
                () -> String.format("[STF-%s] Device '%s' successfully reserved.", sessionUUID, stfClient.getDevice().getSerial()));
        STF_CLIENTS.put(deviceUDID, stfClient);
        return true;
    }

    public static void disconnectSTFDevice(String udid) {

        STFClient client = STF_CLIENTS.get(udid);
        if (client == null) {
            return;
        }
        String sessionUUID = client.getSTFSessionUUID();
        try {
            // it seems like return and remote disconnect guarantee that device becomes free asap
            if (Platform.ANDROID.equals(client.getPlatform())) {
                LOGGER.info(() -> String.format("[STF-%s] Additionally disconnect 'remoteConnect'.", sessionUUID));
                HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH, STF_URL, udid)
                        .withAuthorization(buildAuthToken(client.getToken()))
                        .delete(Void.class);
                if (response.getStatus() != 200) {
                    LOGGER.warning(() -> String.format("[STF-%s] Could not disconnect 'remoteConnect'.", sessionUUID));
                }
            }

            if (client.reservedManually()) {
                LOGGER.info(() -> String.format("[STF-%s] Device '%s' will not be returned as it was reserved manually.",
                        sessionUUID, client.getDevice().getSerial()));
                return;
            }
            LOGGER.info(() -> String.format("[STF-%s] Return STF Device.", sessionUUID));

            HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_BY_ID_PATH, STF_URL, udid)
                    .withAuthorization(buildAuthToken(client.getToken()))
                    .delete(Void.class);
            if (response.getStatus() != 200) {
                LOGGER.warning(() -> String.format("[STF-%s] Could not return device to the STF. Status: %s", sessionUUID, response.getStatus()));
            } else {
                LOGGER.warning(() -> String.format("[STF-%s] Device '%s' successfully returned to the STF.",
                        sessionUUID, client.getDevice().getSerial()));
            }
        } catch (Exception e) {
            LOGGER.warning(() -> String.format("[STF-%s] Error when return device to the STF: %s", sessionUUID, e));
        } finally {
            STF_CLIENTS.remove(udid);
        }

    }

    private static String buildAuthToken(String authToken) {
        return "Bearer " + authToken;
    }

    public STFDevice getDevice() {
        return device;
    }

    public void setDevice(STFDevice device) {
        this.device = device;
    }

    public Platform getPlatform() {
        return platform;
    }

    public void setPlatform(Platform platform) {
        this.platform = platform;
    }

    public String getToken() {
        return token;
    }

    public String getSTFSessionUUID() {
        return sessionUUID;
    }

    public void setSTFSessionUUID(String uuid) {
        this.sessionUUID = uuid;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public boolean reservedManually() {
        return isReservedManually;
    }

    public void reservedManually(boolean owned) {
        isReservedManually = owned;
    }

    private static boolean isSTFEnabled() {
        return (!StringUtils.isEmpty(STF_URL) && !StringUtils.isEmpty(DEFAULT_STF_TOKEN));
    }

    @Override public String toString() {
        return "STFClient{" +
                "platform=" + platform +
                ", token='" + token + '\'' +
                ", isReservedManually=" + isReservedManually +
                ", device=" + device +
                ", sessionUUID='" + sessionUUID + '\'' +
                '}';
    }
}
