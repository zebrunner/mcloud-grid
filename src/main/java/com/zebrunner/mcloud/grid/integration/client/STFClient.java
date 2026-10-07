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

import com.zebrunner.mcloud.grid.IgnoredDevices;
import com.zebrunner.mcloud.grid.Platform;
import com.zebrunner.mcloud.grid.models.stf.Devices;
import com.zebrunner.mcloud.grid.models.stf.RemoteConnectUserDevice;
import com.zebrunner.mcloud.grid.models.stf.STFDevice;
import com.zebrunner.mcloud.grid.models.stf.User;
import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import com.zebrunner.mcloud.grid.util.EnvUtils;
import com.zebrunner.mcloud.grid.util.HttpClient;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;


@SuppressWarnings("rawtypes")
public final class STFClient {
    private static final Logger LOGGER = Logger.getLogger(STFClient.class.getName());
    private static final String STF_URL = System.getenv("STF_URL");
    public static final String DEFAULT_STF_TOKEN = System.getenv("STF_TOKEN");
    // Max time is seconds for reserving devices in STF
    private static final String DEFAULT_STF_TIMEOUT = System.getenv("STF_TIMEOUT");
    private static final int FALLBACK_STF_TIMEOUT = 3600;
    private static final boolean IS_STF_ENABLED = (!StringUtils.isEmpty(STF_URL) && !StringUtils.isEmpty(DEFAULT_STF_TOKEN));

    private static final Duration INVALID_STF_RESPONSE_TIMEOUT = EnvUtils.getDurationInSeconds("STF_DEVICE_INVALID_RESPONSE_IGNORE_TIMEOUT", Duration.ofMinutes(10));
    private static final Duration UNAUTHORIZED_TIMEOUT = EnvUtils.getDurationInSeconds("STF_DEVICE_UNAUTHORIZED_IGNORE_TIMEOUT", Duration.ofMinutes(10));

    private static final Duration UNHEALTHY_TIMEOUT = EnvUtils.getDurationInSeconds("STF_DEVICE_UNHEALTHY_IGNORE_TIMEOUT", Duration.ofMinutes(1));

    private static final Duration STF_DEVICE_MANUALLY_RESERVED_TIMEOUT = EnvUtils.getDurationInSeconds("STF_DEVICE_MANUALLY_RESERVED_TIMEOUT", Duration.ofMinutes(3));

    private STFClient() {
        //do nothing
    }

    /**
     * Reserve STF device
     */
    public static synchronized STFDevice reserveSTFDevice(String deviceUDID, Map<String, Object> requestedCapabilities, String sessionUUID) {
        LOGGER.fine(() -> String.format("[%s][%s] Reserving the device in STF.", deviceUDID, sessionUUID));

        String stfToken = CapabilityUtils.getZebrunnerCapability(requestedCapabilities, "STF_TOKEN")
                .map(String::valueOf)
                .orElse(DEFAULT_STF_TOKEN);
        int stfTimeout = stfTimeoutSeconds(requestedCapabilities, DEFAULT_STF_TIMEOUT);

        HttpClient.Response<User> user = HttpClient.uri(Path.STF_USER_PATH, STF_URL)
                .withAuthorization(buildAuthToken(stfToken))
                .get(User.class);

        if (user.getStatus() != 200) {
            LOGGER.warning(() ->
                    String.format("[%s][%s] STF did not accept the token (HTTP %s), the device is not reserved. URL: '%s', token: '%s'.", deviceUDID, sessionUUID,
                            user.getStatus(), STF_URL,
                            maskToken(stfToken)));
            return null;
        }

        HttpClient.Response<Devices> devices = HttpClient.uri(Path.STF_DEVICES_PATH, STF_URL)
                .withAuthorization(buildAuthToken(stfToken))
                .get(Devices.class);

        if (devices.getStatus() != 200) {
            LOGGER.warning(() -> String.format("[%s][%s] Could not get the devices from STF (HTTP %s), the device is not reserved.", deviceUDID, sessionUUID, devices.getStatus()));
            return null;
        }

        Optional<STFDevice> optionalSTFDevice = devices.getObject().getDevices()
                .stream().filter(device -> StringUtils.equals(device.getSerial(), deviceUDID))
                .findFirst();

        if (optionalSTFDevice.isEmpty()) {
            LOGGER.warning(() -> String.format("[%s][%s] Device is not found in STF, it is not reserved.", deviceUDID, sessionUUID));
            return null;
        }

        STFDevice stfDevice = optionalSTFDevice.get();
        STFDevice finalStfDevice2 = stfDevice;
        LOGGER.fine(() -> String.format("[%s][%s] STF device: %s", deviceUDID, sessionUUID, finalStfDevice2));

        if (stfDevice.getStatus() == null) {
            IgnoredDevices.ignore(deviceUDID, INVALID_STF_RESPONSE_TIMEOUT, "STF device status is unknown");
            LOGGER.warning(() -> String.format("[%s][%s] STF returned no status of the device, it is ignored for %s seconds.", deviceUDID, sessionUUID,
                    INVALID_STF_RESPONSE_TIMEOUT.toSeconds()));
            return null;
        }

        if (stfDevice.getStatus().intValue() == 2) {
            IgnoredDevices.ignore(deviceUDID, UNAUTHORIZED_TIMEOUT, "device is unauthorized in STF");
            LOGGER.warning(() -> String.format("[%s][%s] Device is unauthorized in STF, it is ignored for %s seconds.", deviceUDID,
                    sessionUUID, UNAUTHORIZED_TIMEOUT.toSeconds()));
            return null;
        }

        if (stfDevice.getStatus() == 7) {
            IgnoredDevices.ignore(deviceUDID, UNHEALTHY_TIMEOUT, "device is unhealthy in STF");
            LOGGER.warning(() -> String.format("[%s][%s] Device is unhealthy in STF, it is ignored for %s seconds.", deviceUDID,
                    sessionUUID, UNHEALTHY_TIMEOUT.toSeconds()));
            return null;
        }

        // true when the device was reserved by this call, so it must be returned if a later step fails
        boolean reservedNow = false;
        if (stfDevice.getOwner() != null && StringUtils.equals(stfDevice.getOwner().getName(), user.getObject().getUser().getName()) &&
                stfDevice.getPresent() &&
                stfDevice.getReady()) {
            STFDevice finalStfDevice1 = stfDevice;
            LOGGER.info(() -> String.format("[%s][%s] Device is already reserved in STF by the grid user %s, the reservation is reused.",
                    deviceUDID, sessionUUID, finalStfDevice1.getOwner().getName()));
        } else if (stfDevice.getOwner() == null && stfDevice.getPresent() && stfDevice.getReady()) {
            Map<String, Object> entity = new HashMap<>();
            entity.put("serial", deviceUDID);
            entity.put("timeout", TimeUnit.SECONDS.toMillis(stfTimeout));
            HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_PATH, STF_URL)
                    .withAuthorization(buildAuthToken(stfToken))
                    .post(Void.class, entity);
            if (response.getStatus() != 200) {
                LOGGER.warning(() -> String.format("[%s][%s] STF did not reserve the device (HTTP %s), it is ignored for %s seconds.",
                        deviceUDID, sessionUUID, response.getStatus(), INVALID_STF_RESPONSE_TIMEOUT.toSeconds()));
                IgnoredDevices.ignore(deviceUDID, INVALID_STF_RESPONSE_TIMEOUT, "STF did not reserve the device");
                if (response.getStatus() == 0) {
                    markUnhealthy(deviceUDID, sessionUUID);
                }
                return null;
            }
            reservedNow = true;
        } else if (stfDevice.getOwner() != null && !StringUtils.equals(stfDevice.getOwner().getName(), user.getObject().getUser().getName())){
            STFDevice finalStfDevice1 = stfDevice;
            IgnoredDevices.ignore(deviceUDID, STF_DEVICE_MANUALLY_RESERVED_TIMEOUT, "device is reserved in STF by " + stfDevice.getOwner().getName());
            LOGGER.warning(() -> String.format("[%s][%s] Device is reserved in STF by %s, it is ignored for %s seconds.",
                    deviceUDID, sessionUUID, finalStfDevice1.getOwner().getName(), STF_DEVICE_MANUALLY_RESERVED_TIMEOUT.toSeconds()));
            return null;
        } else {
            IgnoredDevices.ignore(deviceUDID, UNHEALTHY_TIMEOUT, "device is not present or not ready in STF");
            LOGGER.warning(() -> String.format("[%s][%s] Device is not present or not ready in STF, it is ignored for %s seconds.",
                    deviceUDID, sessionUUID, UNHEALTHY_TIMEOUT.toSeconds()));
            return null;
        }

        if (Platform.ANDROID.equals(Platform.fromCapabilities(requestedCapabilities))) {
            LOGGER.fine(() -> String.format("[%s][%s] Starting remoteConnect of the device.", deviceUDID, sessionUUID));

            HttpClient.Response<RemoteConnectUserDevice> remoteConnectUserDevice = HttpClient.uri(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH,
                            STF_URL, deviceUDID)
                    .withAuthorization(buildAuthToken(stfToken))
                    .post(RemoteConnectUserDevice.class, null);

            if (remoteConnectUserDevice.getStatus() != 200) {
                LOGGER.warning(
                        () -> String.format("[%s][%s] STF could not start remoteConnect (HTTP %s), the device is not reserved. Response: %s",
                                deviceUDID, sessionUUID, remoteConnectUserDevice.getStatus(), remoteConnectUserDevice.getObject()));
                if (reservedNow) {
                    returnDevice(deviceUDID, stfToken, false, sessionUUID);
                }
                return null;
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
                LOGGER.warning(() -> String.format("[%s][%s] Could not get the devices from STF (HTTP %s), the device is not reserved.",
                        deviceUDID, sessionUUID, _devices.getStatus()));
                if (reservedNow) {
                    returnDevice(deviceUDID, stfToken, true, sessionUUID);
                }
                return null;
            }

            Optional<STFDevice> _optionalSTFDevice = _devices.getObject().getDevices()
                    .stream()
                    .filter(device -> StringUtils.equals(device.getSerial(), deviceUDID))
                    .findFirst();

            if (_optionalSTFDevice.isEmpty()) {
                LOGGER.warning(() -> String.format("[%s][%s] Device disappeared from STF after the reservation, it is not reserved.", deviceUDID, sessionUUID));
                if (reservedNow) {
                    returnDevice(deviceUDID, stfToken, true, sessionUUID);
                }
                return null;
            }
            STFDevice _stfDevice = _optionalSTFDevice.get();
            stfDevice = _stfDevice;
            if (StringUtils.isBlank((String) _stfDevice.getRemoteConnectUrl())) {
                LOGGER.warning(() -> String.format("[%s][%s] 'enableAdb' is requested, but STF has no remote ADB URL of the device, it is not reserved.", deviceUDID, sessionUUID));
                if (reservedNow) {
                    returnDevice(deviceUDID, stfToken, true, sessionUUID);
                }
                return null;
            } else {
                LOGGER.fine(() -> String.format("[%s][%s] Remote ADB URL of the device is ready.", deviceUDID, sessionUUID));
            }
        }
        STFDevice finalStfDevice = stfDevice;
        LOGGER.info(
                () -> String.format("[%s][%s] Device is reserved in STF%s.", deviceUDID, sessionUUID,
                        finalStfDevice.getRemoteConnectUrl() == null ? "" : ", remote ADB: " + finalStfDevice.getRemoteConnectUrl()));
        return stfDevice;
    }

    public static void disconnectSTFDevice(String udid, Platform platform, boolean isReservedManually, String sessionUUID) {
        disconnectSTFDevice(udid, platform, isReservedManually, DEFAULT_STF_TOKEN, sessionUUID);
    }

    /**
     * @param stfToken token the device was reserved with
     */
    public static synchronized void disconnectSTFDevice(String udid, Platform platform, boolean isReservedManually, String stfToken,
            String sessionUUID) {
        // it seems like return and remote disconnect guarantee that device becomes free asap
        if (Platform.ANDROID.equals(platform)) {
            LOGGER.fine(() -> String.format("[%s][%s] Stopping remoteConnect of the device.", udid, sessionUUID));
            HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH, STF_URL, udid)
                    .withAuthorization(buildAuthToken(stfToken))
                    .delete(Void.class);
            if (response.getStatus() != 200) {
                LOGGER.warning(() -> String.format("[%s][%s] Could not stop remoteConnect of the device (HTTP %s).", udid, sessionUUID, response.getStatus()));
            }
        }

        if (isReservedManually) {
            LOGGER.info(() -> String.format("[%s][%s] Device stays reserved in STF: it was reserved with a personal STF token.",
                    udid, sessionUUID));
            return;
        }
        LOGGER.fine(() -> String.format("[%s][%s] Returning the device to STF.", udid, sessionUUID));

        HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_BY_ID_PATH, STF_URL, udid)
                .withAuthorization(buildAuthToken(stfToken))
                .delete(Void.class);
        if (response.getStatus() != 200) {
            LOGGER.warning(() -> String.format("[%s][%s] Could not return the device to STF (HTTP %s).", udid, sessionUUID, response.getStatus()));
        } else {
            LOGGER.info(() -> String.format("[%s][%s] Device is returned to STF.", udid, sessionUUID));
        }

    }

    /**
     * Marks the device as unhealthy in STF when it does not answer the reservation request.
     * It is an admin operation of the STF API, so it works only when STF_TOKEN belongs to an STF admin.
     */
    private static void markUnhealthy(String udid, String sessionUUID) {
        LOGGER.warning(() -> String.format("[%s][%s] STF did not respond to the reservation, device will be marked as unhealthy.", udid, sessionUUID));
        HttpClient.Response response = HttpClient.uri(Path.STF_DEVICES_ITEM_PATH, STF_URL, udid)
                .withAuthorization(buildAuthToken(DEFAULT_STF_TOKEN))
                .put(Void.class, Map.of("device", Map.of("status", "unhealthy")));
        if (response.getStatus() == 403) {
            LOGGER.warning(() -> String.format("[%s][%s] Could not mark device as unhealthy: the STF_TOKEN user is not an STF admin.", udid, sessionUUID));
        } else if (response.getStatus() != 200) {
            LOGGER.warning(() -> String.format("[%s][%s] Could not mark device as unhealthy. Status: %s", udid, sessionUUID, response.getStatus()));
        }
    }

    /**
     * Returns a device reserved by {@link #reserveSTFDevice} when the reservation could not be completed.
     */
    private static void returnDevice(String udid, String stfToken, boolean remoteConnected, String sessionUUID) {
        LOGGER.warning(() -> String.format("[%s][%s] Reservation is not completed, the device is returned to STF.", udid, sessionUUID));
        if (remoteConnected) {
            HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH, STF_URL, udid)
                    .withAuthorization(buildAuthToken(stfToken))
                    .delete(Void.class);
            if (response.getStatus() != 200) {
                LOGGER.warning(() -> String.format("[%s][%s] Could not stop remoteConnect of the device (HTTP %s).", udid, sessionUUID, response.getStatus()));
            }
        }
        HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_BY_ID_PATH, STF_URL, udid)
                .withAuthorization(buildAuthToken(stfToken))
                .delete(Void.class);
        if (response.getStatus() != 200) {
            LOGGER.warning(() -> String.format("[%s][%s] Could not return the device to STF (HTTP %s).", udid, sessionUUID, response.getStatus()));
        }
    }

    public static void disconnectAllDevices() {
        if (!STFClient.isSTFEnabled()) {
            return;
        }
        LOGGER.info("[STF] Returning the devices left reserved by the grid user (e.g. by a previous run of the hub).");
        HttpClient.Response<User> user = HttpClient.uri(Path.STF_USER_PATH, STF_URL)
                .withAuthorization(buildAuthToken(DEFAULT_STF_TOKEN))
                .get(User.class);

        if (user.getStatus() != 200) {
            LOGGER.warning(() ->
                    String.format("[STF] STF did not accept the token (HTTP %s), the devices left reserved are not returned. URL: '%s', token: '%s'.",
                            user.getStatus(), STF_URL, maskToken(DEFAULT_STF_TOKEN)));
            return;
        }

        HttpClient.Response<Devices> devices = HttpClient.uri(Path.STF_DEVICES_PATH, STF_URL)
                .withAuthorization(buildAuthToken(DEFAULT_STF_TOKEN))
                .get(Devices.class);

        if (devices.getStatus() != 200) {
            LOGGER.warning(() -> String.format("[STF] Could not get the devices from STF (HTTP %s), the devices left reserved are not returned.", devices.getStatus()));
            return;
        }

        devices.getObject()
                .getDevices()
                .stream()
                .filter(d -> d.getOwner() != null)
                .filter(d -> StringUtils.equals(d.getOwner().getName(), user.getObject().getUser().getName()))
                .map(STFDevice::getSerial)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList())
                .forEach(udid -> {
                    HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_BY_ID_PATH, STF_URL, udid)
                            .withAuthorization(buildAuthToken(DEFAULT_STF_TOKEN))
                            .delete(Void.class);
                    if (response.getStatus() != 200) {
                        LOGGER.warning(() -> String.format("[STF] Could not return the device '%s' to STF (HTTP %s).", udid, response.getStatus()));
                    } else {
                        LOGGER.info(() -> String.format("[STF] Device '%s' left reserved by the grid user is returned to STF.", udid));
                    }
                });
    }

    /**
     * STF reservation timeout in seconds: 'STF_TIMEOUT' capability, then STF_TIMEOUT env var, then 1 hour.
     */
    static int stfTimeoutSeconds(Map<String, Object> requestedCapabilities, String defaultTimeout) {
        return CapabilityUtils.getZebrunnerCapability(requestedCapabilities, "STF_TIMEOUT")
                .map(String::valueOf)
                .map(Integer::parseInt)
                .orElseGet(() -> StringUtils.isBlank(defaultTimeout) ? FALLBACK_STF_TIMEOUT : Integer.parseInt(defaultTimeout));
    }

    /**
     * Keeps only the last 4 characters of a token, so it can be told apart in logs without being leaked.
     */
    static String maskToken(String token) {
        if (token == null) {
            return null;
        }
        return token.length() <= 8 ? "****" : "****" + token.substring(token.length() - 4);
    }

    private static String buildAuthToken(String authToken) {
        return "Bearer " + authToken;
    }

    public static String getStfUrl() {
        return STF_URL;
    }

    public static boolean isSTFEnabled() {
        return IS_STF_ENABLED;
    }

    public static boolean isDevicePresentInSTF(String udid) {
        if (!isSTFEnabled()) {
            return true;
        }
        HttpClient.Response<Devices> devices = HttpClient.uri(Path.STF_DEVICES_PATH, STF_URL)
                .withAuthorization(buildAuthToken(DEFAULT_STF_TOKEN))
                .get(Devices.class);
        if (devices.getStatus() != 200) {
            LOGGER.warning(() -> String.format("[NODE REGISTRATION] Could not get the devices from STF (HTTP %s), the node of '%s' is not registered.", devices.getStatus(), udid));
            return false;
        }
        return devices.getObject()
                .getDevices()
                .stream()
                .anyMatch(device -> StringUtils.equals(device.getSerial(), udid));
    }
}
