package com.zebrunner.mcloud.grid.integration.client;

import com.github.tomakehurst.wiremock.WireMockServer;

import java.util.Arrays;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * WireMock-based STF API stub. Listens on the port STF_URL points to in the 'stf' surefire execution.
 */
public final class StfStub {
    public static final String DEFAULT_TOKEN = "default-token";
    public static final String BOT_USER = "automation-bot";

    private final WireMockServer server;

    public StfStub() {
        server = new WireMockServer(options().port(Integer.getInteger("stf.stub.port", 18089)));
    }

    public WireMockServer server() {
        return server;
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop();
    }

    public void reset() {
        server.resetAll();
    }

    public void user(String token, String name) {
        server.stubFor(get("/api/v1/user")
                .withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(okJson("{\"success\":true,\"user\":{\"name\":\"" + name + "\",\"email\":\"" + name + "@example.com\"}}")));
    }

    public void devices(String... devicesJson) {
        server.stubFor(get("/api/v1/devices")
                .willReturn(okJson("{\"success\":true,\"devices\":[" + Arrays.stream(devicesJson).collect(Collectors.joining(",")) + "]}")));
    }

    public static DeviceJson device(String serial) {
        return new DeviceJson(serial);
    }

    /**
     * Minimal STF device representation.
     */
    public static final class DeviceJson {
        private final String serial;
        private Integer status = 3;
        private boolean present = true;
        private boolean ready = true;
        private String owner;
        private String remoteConnectUrl;

        private DeviceJson(String serial) {
            this.serial = serial;
        }

        public DeviceJson status(Integer status) {
            this.status = status;
            return this;
        }

        public DeviceJson present(boolean present) {
            this.present = present;
            return this;
        }

        public DeviceJson ready(boolean ready) {
            this.ready = ready;
            return this;
        }

        public DeviceJson owner(String owner) {
            this.owner = owner;
            return this;
        }

        public DeviceJson remoteConnectUrl(String remoteConnectUrl) {
            this.remoteConnectUrl = remoteConnectUrl;
            return this;
        }

        @Override
        public String toString() {
            StringBuilder json = new StringBuilder("{\"serial\":\"").append(serial).append('"')
                    .append(",\"present\":").append(present)
                    .append(",\"ready\":").append(ready);
            if (status != null) {
                json.append(",\"status\":").append(status);
            }
            if (owner != null) {
                json.append(",\"owner\":{\"name\":\"").append(owner).append("\",\"email\":\"").append(owner).append("@example.com\"}");
            }
            if (remoteConnectUrl != null) {
                json.append(",\"remoteConnectUrl\":\"").append(remoteConnectUrl).append('"');
            }
            return json.append('}').toString();
        }
    }
}
