package com.zebrunner.mcloud.grid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.eclipse.jetty.server.ServerConnector;
import org.openqa.grid.internal.utils.configuration.GridHubConfiguration;
import org.openqa.grid.web.Hub;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * The hub of Selenium changed as {@link HubLauncher} does: the node API /grid/api/proxy of {@link
 * com.zebrunner.mcloud.grid.servlets.ProxyInfo} and the error pages of {@link HubErrorHandler}, with the registration of a
 * node through /grid/register as zebrunner/appium does it.
 */
@Test(singleThreaded = true)
public class HubLauncherTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UDID = "udid-of-the-launcher-test";

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private WireMockServer node;
    private Hub hub;
    private String url;

    @BeforeClass
    public void startHub() throws Exception {
        GridFixtures.useTestNodeAuthentication();
        node = new WireMockServer(options().dynamicPort());
        node.start();
        node.stubFor(get("/wd/hub/status").willReturn(okJson("{\"value\":{\"ready\":true}}")));

        GridHubConfiguration config = new GridHubConfiguration();
        config.registry = MobileGridRegistry.class.getName();
        config.capabilityMatcher = new MobileCapabilityMatcher();
        config.port = 0;
        config.host = "127.0.0.1";
        hub = new Hub(config);
        hub.start();
        HubLauncher.restrict(hub);
        url = "http://127.0.0.1:" + ((ServerConnector) HubLauncher.server(hub).getConnectors()[0]).getLocalPort();
    }

    @AfterClass(alwaysRun = true)
    public void stopHub() throws Exception {
        if (hub != null) {
            hub.getRegistry().stop();
            hub.stop();
        }
        if (node != null) {
            node.stop();
        }
    }

    private HttpResponse<String> send(String method, String path, String body, String accept) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (accept != null) {
            request.header("Accept", accept);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * @return the registration request of zebrunner/appium for the node URL (its id) with the token
     */
    private static String registration(String nodeUrl, String token) {
        URI uri = URI.create(nodeUrl);
        return "{\"capabilities\": [{\"maxInstances\": 1, \"platformName\": \"ANDROID\", \"appium:udid\": \"" + UDID + "\","
                + " \"appium:deviceName\": \"Pixel\", \"appium:platformVersion\": \"13\"}],"
                + " \"configuration\": {\"proxy\": \"" + MobileRemoteProxy.class.getName() + "\", \"url\": \"" + nodeUrl + "/wd/hub\","
                + " \"host\": \"" + uri.getHost() + "\", \"port\": " + uri.getPort() + ", \"id\": \"" + nodeUrl + "\", \"maxSession\": 1,"
                + " \"register\": true, \"custom\": {\"registrationToken\": \"" + token + "\"}}}";
    }

    private JsonNode proxyStatus(String id) throws Exception {
        HttpResponse<String> response = send("GET", "/grid/api/proxy?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8), null, null);
        Assert.assertEquals(response.statusCode(), 200, response.body());
        return MAPPER.readTree(response.body());
    }

    @Test
    public void aNodeRegistersAndLearnsThatItIsRegistered() throws Exception {
        String nodeUrl = node.baseUrl();
        Assert.assertFalse(proxyStatus(nodeUrl).get("success").asBoolean(), "not registered yet");

        HttpResponse<String> registered = send("POST", "/grid/register", registration(nodeUrl, GridFixtures.NODE_TOKEN), null);
        Assert.assertEquals(registered.statusCode(), 200, registered.body());

        // the hub adds a node in the background
        JsonNode status = proxyStatus(nodeUrl);
        Instant deadline = Instant.now().plusSeconds(20);
        while (!status.get("success").asBoolean() && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
            status = proxyStatus(nodeUrl);
        }
        Assert.assertTrue(status.get("success").asBoolean(), status.toString());
        Assert.assertEquals(status.get("id").asText(), nodeUrl);
        // zebrunner/appium logs msg when the node is not registered
        Assert.assertTrue(status.hasNonNull("msg"), status.toString());
        Assert.assertEquals(status.size(), 3, "success, msg and id only: " + status);
        String body = send("GET", "/grid/api/proxy?id=" + nodeUrl, null, null).body();
        Assert.assertTrue(body.contains("\"success\": true"), "the format of Selenium the nodes and the tests of the image look for: " + body);
        Assert.assertFalse(body.contains(UDID), body);
        Assert.assertFalse(body.contains(GridFixtures.NODE_TOKEN), body);

        // another id, also the URL of the node with a path, is not the node
        Assert.assertFalse(proxyStatus(nodeUrl + "/wd/hub").get("success").asBoolean());
        Assert.assertFalse(proxyStatus("http://192.0.2.1:4723").get("success").asBoolean());
    }

    @Test
    public void theHealthcheckAndAnUnknownIdAreAnswered() throws Exception {
        // the healthcheck of the image (no id) and the doctor of mcloud (?id=none)
        HttpResponse<String> healthcheck = send("GET", "/grid/api/proxy", null, null);
        Assert.assertEquals(healthcheck.statusCode(), 200);
        Assert.assertFalse(MAPPER.readTree(healthcheck.body()).get("success").asBoolean(), healthcheck.body());
        Assert.assertEquals(send("GET", "/grid/api/proxy/", null, null).statusCode(), 200);
        JsonNode unknown = proxyStatus("none");
        Assert.assertFalse(unknown.get("success").asBoolean());
        Assert.assertFalse(unknown.has("id"), unknown.toString());
    }

    @Test
    public void theNodeApiAnswersGetOnly() throws Exception {
        HttpResponse<String> post = send("POST", "/grid/api/proxy", "{\"id\": \"" + node.baseUrl() + "\", \"teardown\": true}", null);
        Assert.assertNotEquals(post.statusCode(), 200, post.body());
    }

    @Test
    public void aRejectedRegistrationIsAnsweredWithItsReasonOnly() throws Exception {
        for (String accept : new String[] {null, "text/html", "application/json, text/plain, */*", "text/plain"}) {
            HttpResponse<String> rejected = send("POST", "/grid/register", registration("http://127.0.0.1:1", "not-a-node-token"), accept);
            String body = rejected.body();
            Assert.assertEquals(rejected.statusCode(), 500, body);
            Assert.assertTrue(body.contains("has a token that is not a node token"), accept + ": " + body);
            for (String internal : new String[] {"\tat ", "at org.", "Exception", "CAUSED BY", "cause0", "SERVLET", "servlet", "Jetty", "jetty"}) {
                Assert.assertFalse(body.contains(internal), accept + ": '" + internal + "' in " + body);
            }
            Assert.assertTrue(rejected.headers().firstValue("Server").isEmpty(), rejected.headers().toString());
        }
    }

    @Test
    public void theReasonHasNoClassNamesNorSystemInfo() {
        Assert.assertEquals(HubErrorHandler.reason(new javax.servlet.ServletException(new IllegalStateException("Node x is rejected."))),
                "Node x is rejected.");
        Assert.assertEquals(HubErrorHandler.reason(new java.security.InvalidParameterException("Error: Node x is rejected.")), "Node x is rejected.");
        Assert.assertEquals(HubErrorHandler.reason(new IllegalStateException("Node x is rejected.\nSystem info: host: 'hub', ip: '10.0.0.1'")),
                "Node x is rejected.");
        Assert.assertNull(HubErrorHandler.reason(new NullPointerException()));
    }
}
