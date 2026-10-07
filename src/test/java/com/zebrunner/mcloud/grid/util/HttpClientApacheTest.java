package com.zebrunner.mcloud.grid.util;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.zebrunner.mcloud.grid.integration.client.Path;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

public class HttpClientApacheTest {
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
    public void getWithEntitySendsBody() {
        server.stubFor(get("/wd/hub/status-adb").willReturn(aResponse().withStatus(200).withBody("ok")));

        HttpClient.Response<String> response = HttpClientApache.create()
                .withUri(Path.APPIUM_STATUS_ADB, server.baseUrl() + "/wd/hub")
                .get(new StringEntity("{\"exitCode\": 101}", ContentType.APPLICATION_JSON));

        Assert.assertEquals(response.getStatus(), 200);
        Assert.assertEquals(response.getObject(), "ok");
        server.verify(getRequestedFor(urlEqualTo("/wd/hub/status-adb")).withRequestBody(equalToJson("{\"exitCode\":101}")));
    }

    @Test
    public void errorStatusIsReturnedWithBody() {
        server.stubFor(get("/status").willReturn(aResponse().withStatus(500).withBody("adb is dead")));

        HttpClient.Response<String> response = HttpClientApache.create().withUri(Path.APPIUM_STATUS, server.baseUrl()).get();

        Assert.assertEquals(response.getStatus(), 500);
        Assert.assertEquals(response.getObject(), "adb is dead");
    }

    @Test
    public void connectionErrorReturnsZeroStatus() {
        Assert.assertEquals(HttpClientApache.create().withUri(Path.APPIUM_STATUS, "http://localhost:1").get().getStatus(), 0);
    }

    @Test
    public void missingUrlReturnsNull() {
        Assert.assertNull(HttpClientApache.create().get());
    }
}
