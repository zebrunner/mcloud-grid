package com.zebrunner.mcloud.grid.integration.client;

import com.zebrunner.mcloud.grid.MobileRemoteProxy;
import com.zebrunner.mcloud.grid.Platform;
import com.zebrunner.mcloud.grid.models.stf.STFDevice;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.zebrunner.mcloud.grid.integration.client.StfStub.BOT_USER;
import static com.zebrunner.mcloud.grid.integration.client.StfStub.DEFAULT_TOKEN;
import static com.zebrunner.mcloud.grid.integration.client.StfStub.device;

@Test(groups = "stf", singleThreaded = true)
public class STFClientTest {
    private static final String UDID = "emulator-5554";
    private static final String SESSION = "session-1";

    private final StfStub stf = new StfStub();

    @BeforeClass(alwaysRun = true)
    public void startStf() {
        stf.start();
    }

    @AfterClass(alwaysRun = true)
    public void stopStf() {
        stf.stop();
    }

    @BeforeMethod(alwaysRun = true)
    public void reset() {
        stf.reset();
        MobileRemoteProxy.DEVICE_IGNORE_AUTOMATION_TIMERS.clear();
        stf.user(DEFAULT_TOKEN, BOT_USER);
        stf.server().stubFor(post("/api/v1/user/devices").willReturn(okJson("{\"success\":true}")));
        stf.server().stubFor(post(urlMatching("/api/v1/user/devices/.*/remoteConnect"))
                .willReturn(okJson("{\"success\":true,\"remoteConnectUrl\":\"10.0.0.1:7401\",\"serial\":\"" + UDID + "\"}")));
        stf.server().stubFor(delete(urlMatching("/api/v1/user/devices/.*")).willReturn(okJson("{\"success\":true}")));
    }

    private static Map<String, Object> caps(String platform, Object... keyValues) {
        Map<String, Object> caps = new HashMap<>();
        caps.put("platformName", platform);
        for (int i = 0; i < keyValues.length; i += 2) {
            caps.put((String) keyValues[i], keyValues[i + 1]);
        }
        return caps;
    }

    private void assertIgnoredFor(Duration expected) {
        Duration until = MobileRemoteProxy.DEVICE_IGNORE_AUTOMATION_TIMERS.get(UDID);
        Assert.assertNotNull(until, "device should be ignored");
        long left = until.toMillis() - System.currentTimeMillis();
        Assert.assertTrue(left > expected.toMillis() - 5000 && left <= expected.toMillis(), "ignored for " + left + "ms, expected ~" + expected);
    }

    private void assertNoReservationRequest() {
        stf.server().verify(0, postRequestedFor(urlEqualTo("/api/v1/user/devices")));
    }

    public void stfIsEnabledFromEnvironment() {
        Assert.assertTrue(STFClient.isSTFEnabled());
    }

    public void reservesFreeIosDevice() {
        stf.devices(device(UDID).toString());

        STFDevice device = STFClient.reserveSTFDevice(UDID, caps("iOS"), SESSION);

        Assert.assertNotNull(device);
        Assert.assertEquals(device.getSerial(), UDID);
        stf.server().verify(postRequestedFor(urlEqualTo("/api/v1/user/devices"))
                .withHeader("Authorization", equalTo("Bearer " + DEFAULT_TOKEN))
                .withRequestBody(equalToJson("{\"serial\":\"" + UDID + "\",\"timeout\":3600000}")));
        stf.server().verify(0, postRequestedFor(urlMatching(".*/remoteConnect")));
    }

    public void reservesFreeAndroidDeviceAndCallsRemoteConnect() {
        stf.devices(device(UDID).toString());

        Assert.assertNotNull(STFClient.reserveSTFDevice(UDID, caps("Android"), SESSION));
        stf.server().verify(postRequestedFor(urlEqualTo("/api/v1/user/devices/" + UDID + "/remoteConnect")));
    }

    public void usesTokenAndTimeoutFromCapabilities() {
        stf.user("personal-token", "john");
        stf.devices(device(UDID).toString());

        Assert.assertNotNull(STFClient.reserveSTFDevice(UDID,
                caps("iOS", "zebrunner:STF_TOKEN", "personal-token", "zebrunner:STF_TIMEOUT", "60"), SESSION));
        stf.server().verify(postRequestedFor(urlEqualTo("/api/v1/user/devices"))
                .withHeader("Authorization", equalTo("Bearer personal-token"))
                .withRequestBody(equalToJson("{\"serial\":\"" + UDID + "\",\"timeout\":60000}")));
    }

    public void unauthenticatedUserCannotReserve() {
        stf.devices(device(UDID).toString());

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("iOS", "zebrunner:STF_TOKEN", "wrong"), SESSION));
        assertNoReservationRequest();
    }

    public void tokensAreNotLogged() {
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(STFClient.class.getName());
        Level level = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            stf.devices(device(UDID).toString());
            STFClient.reserveSTFDevice(UDID, caps("iOS", "zebrunner:STF_TOKEN", "secret-personal-token"), SESSION);
            stf.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/api/v1/user").willReturn(aResponse().withStatus(401)));
            STFClient.disconnectAllDevices();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
        Assert.assertFalse(messages.isEmpty());
        messages.forEach(message -> {
            Assert.assertFalse(message.contains("secret-personal-token"), message);
            Assert.assertFalse(message.contains(DEFAULT_TOKEN), message);
        });
    }

    public void maskTokenKeepsOnlyTail() {
        Assert.assertNull(STFClient.maskToken(null));
        Assert.assertEquals(STFClient.maskToken("short"), "****");
        Assert.assertEquals(STFClient.maskToken("0123456789abcdef"), "****cdef");
    }

    public void stfTimeoutResolution() {
        Assert.assertEquals(STFClient.stfTimeoutSeconds(caps("iOS", "zebrunner:STF_TIMEOUT", "60"), null), 60);
        Assert.assertEquals(STFClient.stfTimeoutSeconds(caps("iOS", "zebrunner:STF_TIMEOUT", 120), "3600"), 120);
        Assert.assertEquals(STFClient.stfTimeoutSeconds(caps("iOS"), "1800"), 1800);
        Assert.assertEquals(STFClient.stfTimeoutSeconds(caps("iOS"), null), 3600);
        Assert.assertEquals(STFClient.stfTimeoutSeconds(caps("iOS"), ""), 3600);
    }

    public void unknownDeviceIsNotReserved() {
        stf.devices(device("another").toString());

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("iOS"), SESSION));
        assertNoReservationRequest();
    }

    public void devicesEndpointFailureIsNotReserved() {
        stf.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/api/v1/devices").willReturn(aResponse().withStatus(500)));

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("iOS"), SESSION));
        assertNoReservationRequest();
    }

    public void deviceWithoutStatusIsIgnored() {
        stf.devices(device(UDID).status(null).toString());

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("iOS"), SESSION));
        assertIgnoredFor(Duration.ofMinutes(10));
        assertNoReservationRequest();
    }

    public void unauthorizedDeviceIsIgnored() {
        stf.devices(device(UDID).status(2).toString());

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("iOS"), SESSION));
        assertIgnoredFor(Duration.ofMinutes(10));
    }

    public void unhealthyDeviceIsIgnored() {
        stf.devices(device(UDID).status(7).toString());

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("iOS"), SESSION));
        assertIgnoredFor(Duration.ofMinutes(1));
    }

    public void deviceReservedByAnotherUserIsIgnored() {
        stf.devices(device(UDID).owner("someone-else").toString());

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("iOS"), SESSION));
        assertIgnoredFor(Duration.ofMinutes(3));
        assertNoReservationRequest();
    }

    public void deviceAlreadyReservedBySameUserIsReused() {
        stf.devices(device(UDID).owner(BOT_USER).toString());

        Assert.assertNotNull(STFClient.reserveSTFDevice(UDID, caps("iOS"), SESSION));
        assertNoReservationRequest();
    }

    public void notReadyDeviceIsIgnored() {
        stf.devices(device(UDID).ready(false).toString());

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("iOS"), SESSION));
        assertIgnoredFor(Duration.ofMinutes(1));
    }

    public void absentDeviceIsIgnored() {
        stf.devices(device(UDID).present(false).toString());

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("iOS"), SESSION));
        assertIgnoredFor(Duration.ofMinutes(1));
    }

    public void failedReservationIgnoresDevice() {
        stf.devices(device(UDID).toString());
        stf.server().stubFor(post("/api/v1/user/devices").willReturn(aResponse().withStatus(403)));

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("iOS"), SESSION));
        assertIgnoredFor(Duration.ofMinutes(10));
    }

    public void failedRemoteConnectFailsReservationAndReturnsDevice() {
        stf.devices(device(UDID).toString());
        stf.server().stubFor(post(urlMatching("/api/v1/user/devices/.*/remoteConnect")).willReturn(aResponse().withStatus(500)));

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("Android"), SESSION));
        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + UDID)));
    }

    public void enableAdbRequiresRemoteConnectUrlAndReturnsDevice() {
        stf.devices(device(UDID).toString());

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("Android", "zebrunner:enableAdb", true), SESSION));
        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + UDID + "/remoteConnect")));
        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + UDID)));
    }

    public void failedPostReservationStepDoesNotReturnDeviceReservedBefore() {
        stf.devices(device(UDID).owner(BOT_USER).toString());
        stf.server().stubFor(post(urlMatching("/api/v1/user/devices/.*/remoteConnect")).willReturn(aResponse().withStatus(500)));

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("Android"), SESSION));
        stf.server().verify(0, deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + UDID)));
    }

    public void returnAfterFailureUsesTokenOfReservation() {
        stf.user("personal-token", "john");
        stf.devices(device(UDID).toString());
        stf.server().stubFor(post(urlMatching("/api/v1/user/devices/.*/remoteConnect")).willReturn(aResponse().withStatus(500)));

        Assert.assertNull(STFClient.reserveSTFDevice(UDID, caps("Android", "zebrunner:STF_TOKEN", "personal-token"), SESSION));
        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + UDID))
                .withHeader("Authorization", equalTo("Bearer personal-token")));
    }

    public void enableAdbReturnsDeviceWithRemoteConnectUrl() {
        stf.devices(device(UDID).remoteConnectUrl("10.0.0.1:7401").toString());

        STFDevice device = STFClient.reserveSTFDevice(UDID, caps("Android", "zebrunner:enableAdb", "true"), SESSION);

        Assert.assertNotNull(device);
        Assert.assertEquals(device.getRemoteConnectUrl(), "10.0.0.1:7401");
    }

    public void disconnectAndroidDeviceStopsRemoteConnectAndReturnsDevice() {
        STFClient.disconnectSTFDevice(UDID, Platform.ANDROID, false, SESSION);

        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + UDID + "/remoteConnect")));
        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + UDID)));
    }

    public void disconnectIosDeviceOnlyReturnsDevice() {
        STFClient.disconnectSTFDevice(UDID, Platform.IOS, false, SESSION);

        stf.server().verify(0, deleteRequestedFor(urlMatching(".*/remoteConnect")));
        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + UDID)));
    }

    public void manuallyReservedDeviceIsNotReturned() {
        STFClient.disconnectSTFDevice(UDID, Platform.ANDROID, true, SESSION);

        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + UDID + "/remoteConnect")));
        stf.server().verify(0, deleteRequestedFor(urlEqualTo("/api/v1/user/devices/" + UDID)));
    }

    public void disconnectAllReturnsOnlyDevicesOfAutomationUser() {
        stf.devices(device("mine-1").owner(BOT_USER).toString(), device("mine-2").owner(BOT_USER).toString(),
                device("foreign").owner("someone-else").toString(), device("free").toString());

        STFClient.disconnectAllDevices();

        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/mine-1")));
        stf.server().verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/mine-2")));
        stf.server().verify(0, deleteRequestedFor(urlEqualTo("/api/v1/user/devices/foreign")));
        stf.server().verify(0, deleteRequestedFor(urlEqualTo("/api/v1/user/devices/free")));
    }

    public void devicePresenceCheck() {
        stf.devices(device(UDID).toString());

        Assert.assertTrue(STFClient.isDevicePresentInSTF(UDID));
        Assert.assertFalse(STFClient.isDevicePresentInSTF("unknown"));
    }
}
