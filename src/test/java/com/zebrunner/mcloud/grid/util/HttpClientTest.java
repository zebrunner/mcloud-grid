package com.zebrunner.mcloud.grid.util;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.zebrunner.mcloud.grid.integration.client.Path;
import com.zebrunner.mcloud.grid.models.stf.User;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

public class HttpClientTest {
    private WireMockServer server;

    @BeforeClass
    public void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() {
        server.stop();
    }

    @BeforeMethod
    public void reset() {
        server.resetAll();
    }

    @Test
    public void getDeserializesBodyAndSendsAuthorization() {
        server.stubFor(get("/api/v1/user").willReturn(okJson("{\"success\":true,\"user\":{\"name\":\"bot\",\"email\":\"bot@x\"}}")));

        HttpClient.Response<User> response = HttpClient.uri(Path.STF_USER_PATH, server.baseUrl())
                .withAuthorization("Bearer abc")
                .get(User.class);

        Assert.assertEquals(response.getStatus(), 200);
        Assert.assertEquals(response.getObject().getUser().getName(), "bot");
        server.verify(getRequestedFor(urlEqualTo("/api/v1/user")).withHeader("Authorization", equalTo("Bearer abc")));
    }

    @Test
    public void nonOkStatusHasNoBody() {
        server.stubFor(get("/api/v1/user").willReturn(aResponse().withStatus(401).withBody("{\"success\":false}")));

        HttpClient.Response<User> response = HttpClient.uri(Path.STF_USER_PATH, server.baseUrl()).get(User.class);

        Assert.assertEquals(response.getStatus(), 401);
        Assert.assertNull(response.getObject());
    }

    @Test
    public void postSendsJsonEntity() {
        server.stubFor(post("/api/v1/user/devices").willReturn(okJson("{}")));

        HttpClient.Response<Void> response = HttpClient.uri(Path.STF_USER_DEVICES_PATH, server.baseUrl())
                .post(Void.class, Map.of("serial", "X", "timeout", 1000));

        Assert.assertEquals(response.getStatus(), 200);
        server.verify(postRequestedFor(urlEqualTo("/api/v1/user/devices")).withRequestBody(equalToJson("{\"serial\":\"X\",\"timeout\":1000}")));
    }

    @Test
    public void deleteUsesPathParameters() {
        server.stubFor(delete("/api/v1/user/devices/X").willReturn(okJson("{}")));

        Assert.assertEquals(HttpClient.uri(Path.STF_USER_DEVICES_BY_ID_PATH, server.baseUrl(), "X").delete(Void.class).getStatus(), 200);
        server.verify(deleteRequestedFor(urlEqualTo("/api/v1/user/devices/X")));
    }

    @Test
    public void pooledConnectionClosedByServerIsNotReused() {
        server.stubFor(get("/api/v1/user").willReturn(okJson("{\"success\":true}")));
        Assert.assertEquals(HttpClient.uri(Path.STF_USER_PATH, server.baseUrl()).get(User.class).getStatus(), 200);

        // restart closes the keep-alive connection kept in the pool
        int port = server.port();
        server.stop();
        server = new WireMockServer(options().port(port));
        server.start();
        server.stubFor(get("/api/v1/user").willReturn(okJson("{\"success\":true}")));

        Assert.assertEquals(HttpClient.uri(Path.STF_USER_PATH, server.baseUrl()).get(User.class).getStatus(), 200);
    }

    @Test
    public void connectionErrorReturnsZeroStatus() {
        HttpClient.Response<User> response = HttpClient.uri(Path.STF_USER_PATH, "http://localhost:1").get(User.class);
        Assert.assertEquals(response.getStatus(), 0);
        Assert.assertNull(response.getObject());
    }
}
