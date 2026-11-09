package com.zebrunner.mcloud.grid.integration.client;

import com.github.tomakehurst.wiremock.WireMockServer;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
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

    /**
     * Stubs the list of devices and GET /api/v1/devices/{serial} of each of them; STF answers 500 for an unknown serial.
     */
    /**
     * STF answers 401 to every request with an invalid token.
     */
    public void rejectToken(String token) {
        server.stubFor(any(urlMatching("/api/v1/.*")).atPriority(1)
                .withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(aResponse().withStatus(401).withBody("{\"success\":false,\"description\":\"Bad Credentials\"}")));
    }

    /**
     * The node registry of STF: POST /api/v1/nodes/verify answers 200 with the node for the token at its address and
     * 403 (the generic error of the API) for anything else.
     */
    public void enrolledNode(String token, String label, String address) {
        server.stubFor(post("/api/v1/nodes/verify").atPriority(10)
                .willReturn(aResponse().withStatus(403).withBody("{\"success\":false}")));
        server.stubFor(post("/api/v1/nodes/verify").atPriority(5)
                .withHeader("Authorization", equalTo("Bearer " + DEFAULT_TOKEN))
                .withRequestBody(equalToJson("{\"token\":\"" + token + "\",\"host\":\"" + address + "\"}"))
                .willReturn(okJson("{\"label\":\"" + label + "\",\"address\":\"" + address + "\"}")));
    }

    /**
     * POST /api/v1/nodes/verify answers the status for every token.
     */
    public void verifyAnswers(int status) {
        server.stubFor(post("/api/v1/nodes/verify").atPriority(1).willReturn(aResponse().withStatus(status).withBody("{\"success\":false}")));
    }

    /**
     * GET /api/v1/nodes: the nodes of the registry of STF at these addresses (the first one is revoked: any status counts).
     */
    public void nodes(String... addresses) {
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i < addresses.length; i++) {
            nodes.append(i == 0 ? "" : ",").append(String.format("{\"label\":\"node-%s\",\"address\":\"%s\",\"status\":\"%s\"}",
                    i, addresses[i], i == 0 ? "revoked" : "active"));
        }
        server.stubFor(get("/api/v1/nodes").withHeader("Authorization", equalTo("Bearer " + DEFAULT_TOKEN))
                .willReturn(okJson("{\"nodes\":[" + nodes + "]}")));
    }

    public void devices(DeviceJson... devices) {
        server.stubFor(get("/api/v1/devices")
                .willReturn(okJson("{\"success\":true,\"devices\":[" + Arrays.stream(devices).map(DeviceJson::toString).collect(Collectors.joining(",")) + "]}")));
        server.stubFor(get(urlMatching("/api/v1/devices/[^/]+")).atPriority(10).willReturn(aResponse().withStatus(500)));
        for (DeviceJson device : devices) {
            server.stubFor(get("/api/v1/devices/" + device.serial).willReturn(okJson("{\"success\":true,\"device\":" + device + "}")));
        }
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
        private String battery;

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

        public DeviceJson battery(double level, double scale, String status) {
            this.battery = String.format(Locale.ROOT, "{\"level\":%s,\"scale\":%s,\"status\":\"%s\"}", level, scale, status);
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
            if (battery != null) {
                json.append(",\"battery\":").append(battery);
            }
            if (remoteConnectUrl != null) {
                json.append(",\"remoteConnectUrl\":\"").append(remoteConnectUrl).append('"');
            }
            return json.append('}').toString();
        }
    }
}
