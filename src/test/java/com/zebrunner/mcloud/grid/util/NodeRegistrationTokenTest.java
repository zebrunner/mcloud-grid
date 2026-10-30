package com.zebrunner.mcloud.grid.util;

import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.exception.GridConfigurationException;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NodeRegistrationTokenTest {

    // the request as the hub parses the JSON a node sends (the 'custom' map of Selenium is not necessarily mutable)
    private static RegistrationRequest request(String token) {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("url", "http://10.0.0.5:4723/wd/hub");
        configuration.put("capabilities", List.of(Map.of("platformName", "ANDROID", "udid", "udid-1")));
        if (token != null) {
            configuration.put("custom", Map.of(NodeRegistrationToken.CUSTOM_KEY, token, "other", "kept"));
        }
        return RegistrationRequest.fromJson(Map.of("configuration", configuration));
    }

    @Test
    public void hubWithoutTokenIsAConfigurationError() {
        for (String token : new String[] {null, "", " \n"}) {
            GridConfigurationException e = Assert.expectThrows(GridConfigurationException.class, () -> NodeRegistrationToken.of(token));
            Assert.assertTrue(e.getMessage().startsWith("NODE_REGISTRATION_TOKEN is not set"), e.getMessage());
        }
    }

    @Test
    public void nodeWithTheTokenOfTheHubIsAccepted() {
        Assert.assertTrue(NodeRegistrationToken.of("s3cret").accept(request("s3cret")));
        // the value of an env var may keep a trailing new line
        Assert.assertTrue(NodeRegistrationToken.of(" s3cret\n").accept(request("s3cret ")));
    }

    @Test
    public void nodeWithoutOrWithAnotherTokenIsRejected() {
        NodeRegistrationToken token = NodeRegistrationToken.of("s3cret");

        Assert.assertFalse(token.accept(request(null)));
        Assert.assertFalse(token.accept(request("")));
        Assert.assertFalse(token.accept(request("s3cret2")));
        Assert.assertFalse(token.accept(request("S3CRET")));
    }

    @Test
    public void tokenIsTakenOutOfTheRequest() {
        // also out of a rejected request
        for (String hubToken : new String[] {"another", "s3cret"}) {
            RegistrationRequest request = request("s3cret");

            NodeRegistrationToken.of(hubToken).accept(request);

            Assert.assertEquals(request.getConfiguration().custom, Map.of("other", "kept"), hubToken);
            Assert.assertFalse(request.toJson().toString().contains("s3cret"), hubToken);
        }
    }

    @Test
    public void clientOfTheHubSendsTheTokenToTheNode() throws Exception {
        List<String> sent = new ArrayList<>();
        HttpClient client = request -> {
            sent.add(request.getHeader(NodeRegistrationToken.HEADER));
            return new HttpResponse();
        };

        NodeRegistrationToken.of("s3cret").authenticate(client).execute(new HttpRequest(HttpMethod.GET, "/wd/hub/status"));

        Assert.assertEquals(sent, List.of("s3cret"));
    }

    @Test
    public void requestWithoutCustomSettingsIsHandled() {
        RegistrationRequest request = request(null);
        request.getConfiguration().custom = null;

        Assert.assertFalse(NodeRegistrationToken.of("s3cret").accept(request));
    }
}
