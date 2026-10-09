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
import com.zebrunner.mcloud.grid.models.stf.DeviceResponse;
import com.zebrunner.mcloud.grid.models.stf.Devices;
import com.zebrunner.mcloud.grid.models.stf.RemoteConnectUserDevice;
import com.zebrunner.mcloud.grid.models.stf.STFDevice;
import com.zebrunner.mcloud.grid.models.stf.User;
import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import com.zebrunner.mcloud.grid.util.EnvUtils;
import com.zebrunner.mcloud.grid.util.HttpClient;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Duration USER_CACHE_TIME = Duration.ofMinutes(5);
    private static final Map<String, CachedUser> USERS = new ConcurrentHashMap<>();
    private static final Map<String, Object> DEVICE_LOCKS = new ConcurrentHashMap<>();

    private STFClient() {
        //do nothing
    }

    /**
     * Reserves the device in STF for a session. It runs while the hub matches new session requests to devices, which the hub does
     * one at a time, so it makes as few STF requests as possible: the device itself (not the list of all devices), the reservation
     * and, for Android, remoteConnect. The STF user of the token is requested only when the device already has an owner.
     *
     * @return the reserved device, null if it is not reserved
     */
    public static STFDevice reserveSTFDevice(String deviceUDID, Map<String, Object> requestedCapabilities, String sessionUUID) {
        synchronized (deviceLock(deviceUDID)) {
            return reserve(deviceUDID, requestedCapabilities, sessionUUID);
        }
    }

    private static STFDevice reserve(String deviceUDID, Map<String, Object> requestedCapabilities, String sessionUUID) {
        LOGGER.fine(() -> String.format("[%s][%s] Reserving the device in STF.", deviceUDID, sessionUUID));

        String stfToken = CapabilityUtils.getZebrunnerCapability(requestedCapabilities, "STF_TOKEN")
                .map(String::valueOf)
                .orElse(DEFAULT_STF_TOKEN);
        int stfTimeout = stfTimeoutSeconds(requestedCapabilities, DEFAULT_STF_TIMEOUT);

        HttpClient.Response<DeviceResponse> deviceResponse = getDevice(deviceUDID, stfToken);
        if (deviceResponse.getStatus() == 401) {
            LOGGER.warning(() -> String.format("[%s][%s] STF did not accept the token (HTTP 401), the device is not reserved. URL: '%s', token: '%s'.",
                    deviceUDID, sessionUUID, STF_URL, maskToken(stfToken)));
            return null;
        }
        STFDevice stfDevice = deviceResponse.getObject() == null ? null : deviceResponse.getObject().getDevice();
        if (deviceResponse.getStatus() != 200 || stfDevice == null) {
            // STF answers 500 instead of 404 for an unknown serial
            LOGGER.warning(() -> String.format("[%s][%s] Could not get the device from STF (HTTP %s), it is not reserved.",
                    deviceUDID, sessionUUID, deviceResponse.getStatus()));
            return null;
        }
        LOGGER.fine(() -> String.format("[%s][%s] STF device: %s", deviceUDID, sessionUUID, stfDevice));

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

        boolean available = Boolean.TRUE.equals(stfDevice.getPresent()) && Boolean.TRUE.equals(stfDevice.getReady());
        // true when the device was reserved by this call, so it must be returned if a later step fails
        boolean reservedNow = false;
        if (stfDevice.getOwner() != null) {
            String owner = stfDevice.getOwner().getName();
            Optional<String> user = userName(stfToken);
            if (user.isEmpty()) {
                LOGGER.warning(() -> String.format("[%s][%s] Could not get the STF user of the token '%s', the device is not reserved.",
                        deviceUDID, sessionUUID, maskToken(stfToken)));
                return null;
            }
            if (!StringUtils.equals(owner, user.get())) {
                IgnoredDevices.ignore(deviceUDID, STF_DEVICE_MANUALLY_RESERVED_TIMEOUT, "device is reserved in STF by " + owner);
                LOGGER.warning(() -> String.format("[%s][%s] Device is reserved in STF by %s, it is ignored for %s seconds.",
                        deviceUDID, sessionUUID, owner, STF_DEVICE_MANUALLY_RESERVED_TIMEOUT.toSeconds()));
                return null;
            }
            if (!available) {
                return notReady(deviceUDID, sessionUUID);
            }
            LOGGER.info(() -> String.format("[%s][%s] Device is already reserved in STF by the grid user %s, the reservation is reused.",
                    deviceUDID, sessionUUID, owner));
        } else if (available) {
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
        } else {
            return notReady(deviceUDID, sessionUUID);
        }

        if (Platform.ANDROID.equals(Platform.fromCapabilities(requestedCapabilities))) {
            LOGGER.fine(() -> String.format("[%s][%s] Starting remoteConnect of the device.", deviceUDID, sessionUUID));

            HttpClient.Response<RemoteConnectUserDevice> remoteConnect = HttpClient.uri(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH,
                            STF_URL, deviceUDID)
                    .withAuthorization(buildAuthToken(stfToken))
                    .post(RemoteConnectUserDevice.class, null);

            if (remoteConnect.getStatus() != 200) {
                LOGGER.warning(
                        () -> String.format("[%s][%s] STF could not start remoteConnect (HTTP %s), the device is not reserved. Response: %s",
                                deviceUDID, sessionUUID, remoteConnect.getStatus(), remoteConnect.getObject()));
                if (reservedNow) {
                    returnDevice(deviceUDID, stfToken, false, sessionUUID);
                }
                return null;
            }
            // the remote ADB URL appears only after the reservation, remoteConnect returns it
            if (remoteConnect.getObject() != null && StringUtils.isNotBlank(remoteConnect.getObject().getRemoteConnectUrl())) {
                stfDevice.setRemoteConnectUrl(remoteConnect.getObject().getRemoteConnectUrl());
            }

            boolean enableAdb = CapabilityUtils.getZebrunnerCapability(requestedCapabilities, "enableAdb")
                    .map(String::valueOf)
                    .map(Boolean::parseBoolean)
                    .orElse(false);
            if (enableAdb && StringUtils.isBlank(String.valueOf(Optional.ofNullable(stfDevice.getRemoteConnectUrl()).orElse("")))) {
                LOGGER.warning(() -> String.format("[%s][%s] 'enableAdb' is requested, but STF has no remote ADB URL of the device, it is not reserved.",
                        deviceUDID, sessionUUID));
                if (reservedNow) {
                    returnDevice(deviceUDID, stfToken, true, sessionUUID);
                }
                return null;
            }
        }
        LOGGER.info(
                () -> String.format("[%s][%s] Device is reserved in STF%s.", deviceUDID, sessionUUID,
                        stfDevice.getRemoteConnectUrl() == null ? "" : ", remote ADB: " + stfDevice.getRemoteConnectUrl()));
        return stfDevice;
    }

    private static STFDevice notReady(String deviceUDID, String sessionUUID) {
        IgnoredDevices.ignore(deviceUDID, UNHEALTHY_TIMEOUT, "device is not present or not ready in STF");
        LOGGER.warning(() -> String.format("[%s][%s] Device is not present or not ready in STF, it is ignored for %s seconds.",
                deviceUDID, sessionUUID, UNHEALTHY_TIMEOUT.toSeconds()));
        return null;
    }

    private static HttpClient.Response<DeviceResponse> getDevice(String udid, String stfToken) {
        return HttpClient.uri(Path.STF_DEVICES_ITEM_PATH, STF_URL, udid)
                .withAuthorization(buildAuthToken(stfToken))
                .get(DeviceResponse.class);
    }

    /**
     * Name of the STF user of the token; cached because it is needed for every device that already has an owner.
     */
    private static Optional<String> userName(String stfToken) {
        CachedUser cached = USERS.get(stfToken);
        if (cached != null && Instant.now().isBefore(cached.until)) {
            return Optional.of(cached.name);
        }
        HttpClient.Response<User> user = HttpClient.uri(Path.STF_USER_PATH, STF_URL)
                .withAuthorization(buildAuthToken(stfToken))
                .get(User.class);
        if (user.getStatus() != 200 || user.getObject() == null || user.getObject().getUser() == null) {
            return Optional.empty();
        }
        String name = user.getObject().getUser().getName();
        USERS.put(stfToken, new CachedUser(name, Instant.now().plus(USER_CACHE_TIME)));
        return Optional.of(name);
    }

    private static final class CachedUser {
        private final String name;
        private final Instant until;

        CachedUser(String name, Instant until) {
            this.name = name;
            this.until = until;
        }
    }

    /**
     * Reservation and return of the same device must not interleave; different devices are handled in parallel.
     */
    private static Object deviceLock(String udid) {
        return DEVICE_LOCKS.computeIfAbsent(udid, key -> new Object());
    }

    /**
     * Forgets the cached STF users, for tests.
     */
    public static void clearCache() {
        USERS.clear();
    }

    public static void disconnectSTFDevice(String udid, Platform platform, boolean isReservedManually, String sessionUUID) {
        disconnectSTFDevice(udid, platform, isReservedManually, DEFAULT_STF_TOKEN, sessionUUID);
    }

    /**
     * @param stfToken token the device was reserved with
     */
    public static void disconnectSTFDevice(String udid, Platform platform, boolean isReservedManually, String stfToken,
            String sessionUUID) {
        synchronized (deviceLock(udid)) {
            disconnect(udid, platform, isReservedManually, stfToken, sessionUUID);
        }
    }

    private static void disconnect(String udid, Platform platform, boolean isReservedManually, String stfToken, String sessionUUID) {
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
     * Releases a device in STF with the given token, for the termination of a session through the API.
     * STF releases a device only for its owner or an STF admin, so with a token of another user it answers 403:
     * the device is reserved by the grid user (STF_TOKEN) unless the session used a personal 'STF_TOKEN' capability.
     *
     * @return HTTP status of the release in STF, 0 if STF did not answer
     */
    public static int releaseDevice(String udid, Platform platform, String stfToken, String sessionUUID) {
        HttpClient.Response response = HttpClient.uri(Path.STF_USER_DEVICES_BY_ID_PATH, STF_URL, udid)
                .withAuthorization(buildAuthToken(stfToken))
                .delete(Void.class);
        if (response.getStatus() != 200) {
            LOGGER.warning(() -> String.format("[%s][%s] STF did not release the device with the given key (HTTP %s, key: %s).",
                    udid, sessionUUID, response.getStatus(), maskToken(stfToken)));
            return response.getStatus();
        }
        if (Platform.ANDROID.equals(platform)) {
            HttpClient.Response remoteConnect = HttpClient.uri(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH, STF_URL, udid)
                    .withAuthorization(buildAuthToken(stfToken))
                    .delete(Void.class);
            if (remoteConnect.getStatus() != 200) {
                // STF usually stops remoteConnect itself when the device is released
                LOGGER.fine(() -> String.format("[%s][%s] remoteConnect was not stopped after the release (HTTP %s).",
                        udid, sessionUUID, remoteConnect.getStatus()));
            }
        }
        LOGGER.info(() -> String.format("[%s][%s] Device is released in STF with the key %s.", udid, sessionUUID, maskToken(stfToken)));
        return response.getStatus();
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
        HttpClient.Response<DeviceResponse> device = getDevice(udid, DEFAULT_STF_TOKEN);
        if (device.getStatus() != 200 || device.getObject() == null || device.getObject().getDevice() == null) {
            // STF answers 500 instead of 404 for an unknown serial
            LOGGER.warning(() -> String.format("[NODE REGISTRATION] Could not get the device '%s' from STF (HTTP %s), the node is not registered.",
                    udid, device.getStatus()));
            return false;
        }
        return StringUtils.equals(device.getObject().getDevice().getSerial(), udid);
    }
}
