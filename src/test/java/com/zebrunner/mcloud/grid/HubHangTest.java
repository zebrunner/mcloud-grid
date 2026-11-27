package com.zebrunner.mcloud.grid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.zebrunner.mcloud.grid.integration.client.NodeTokenVerifier;
import com.zebrunner.mcloud.grid.integration.client.StfNodes;
import com.zebrunner.mcloud.grid.util.NodeToken;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * The hub of the image while STF does not answer the verification of the node tokens (e.g. it starts at the same time as
 * the hub): the nodes register again every few seconds, many at once. The registrations hang in the threads of the
 * registrations only, so the hub keeps answering the status, the node API and the healthcheck, every node gets an answer
 * in time, the healthcheck tells the hang, and the nodes register once STF answers. Before, every registration held a
 * thread of Jetty until the threads of the hub were used up and it answered nothing.
 */
@Test(singleThreaded = true)
public class HubHangTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEADLINE = Duration.ofSeconds(1);
    private static final Duration STUCK = Duration.ofSeconds(3);
    // few threads of Jetty: the registrations that held them would use them up at once
    private static final int JETTY_THREADS = 24;
    private static final int REGISTRATIONS = 3 * JETTY_THREADS;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final CountDownLatch stfAnswers = new CountDownLatch(1);
    private final AtomicInteger verifications = new AtomicInteger();
    private WireMockServer node;
    private Hub hub;
    private String url;

    @BeforeClass
    public void startHub() throws Exception {
        MobileRemoteProxy.useNodeAuthentication(new NodeAuthentication(new NodeTokenVerifier() {
            @Override
            public Optional<StfNodes.Verification> cached(NodeToken token, String host) {
                return Optional.empty();
            }

            @Override
            public StfNodes.Verification verifyNow(NodeToken token, String host) {
                verifications.incrementAndGet();
                try {
                    // an STF that accepts the connection and does not answer, longer than the test
                    stfAnswers.await(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new StfNodes.Verification(StfNodes.Status.VALID, 200);
            }
        }));
        node = new WireMockServer(options().dynamicPort());
        node.start();
        node.stubFor(get("/wd/hub/status").willReturn(okJson("{\"value\":{\"ready\":true}}")));

        GridHubConfiguration config = new GridHubConfiguration();
        config.registry = MobileGridRegistry.class.getName();
        config.capabilityMatcher = new MobileCapabilityMatcher();
        config.port = 0;
        config.host = "127.0.0.1";
        config.jettyMaxThreads = JETTY_THREADS;
        hub = new Hub(config);
        hub.start();
        HubLauncher.restrict(hub, new NodeRegistrations(2, 4, DEADLINE, STUCK, Instant::now));
        url = "http://127.0.0.1:" + ((ServerConnector) HubLauncher.server(hub).getConnectors()[0]).getLocalPort();
    }

    @AfterClass(alwaysRun = true)
    public void stopHub() throws Exception {
        stfAnswers.countDown();
        GridFixtures.useTestNodeAuthentication();
        if (hub != null) {
            hub.getRegistry().stop();
            hub.stop();
        }
        if (node != null) {
            node.stop();
        }
    }

    private HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * @return the registration request of zebrunner/appium at the URL of the WireMock node, a request of its own for the key
     */
    private String registration(String key) {
        URI uri = URI.create(node.baseUrl());
        return "{\"capabilities\": [{\"maxInstances\": 1, \"platformName\": \"ANDROID\", \"appium:udid\": \"udid-" + key + "\","
                + " \"appium:deviceName\": \"Pixel\", \"appium:platformVersion\": \"13\"}],"
                + " \"configuration\": {\"proxy\": \"" + MobileRemoteProxy.class.getName() + "\", \"url\": \"" + node.baseUrl() + "/wd/hub\","
                + " \"host\": \"" + uri.getHost() + "\", \"port\": " + uri.getPort() + ", \"maxSession\": 1,"
                + " \"register\": true, \"custom\": {\"registrationToken\": \"" + GridFixtures.NODE_TOKEN + "\"}}}";
    }

    private JsonNode status(String path) throws Exception {
        HttpResponse<String> status = client.send(HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        Assert.assertEquals(status.statusCode(), 200, status.body());
        Assert.assertTrue(status.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), status.headers().toString());
        return MAPPER.readTree(status.body());
    }

    /**
     * @return the time the status took, ms
     */
    private long statusAnswers() throws Exception {
        long start = System.nanoTime();
        JsonNode status = status("/wd/hub/status");
        Assert.assertEquals(status.get("status").asInt(), 0, status.toString());
        Assert.assertFalse(status.get("value").get("ready").asBoolean(), "no node is registered: " + status);
        Assert.assertEquals(status.get("value").get("message").asText(), "No spare hub capacity");
        for (String field : new String[] {"build", "os", "java"}) {
            Assert.assertTrue(status.get("value").has(field), status.toString());
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private JsonNode health() throws Exception {
        HttpResponse<String> health = send("GET", "/grid/api/health", null);
        JsonNode answer = MAPPER.readTree(health.body());
        Assert.assertEquals(health.statusCode(), answer.get("healthy").asBoolean() ? 200 : 503, health.body());
        return answer;
    }

    private boolean registered(String id) throws Exception {
        HttpResponse<String> status = send("GET", "/grid/api/proxy?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8), null);
        return MAPPER.readTree(status.body()).get("success").asBoolean();
    }

    public void theHubKeepsAnsweringWhileTheRegistrationsOfTheNodesHang() throws Exception {
        Assert.assertTrue(health().get("healthy").asBoolean());

        long start = System.nanoTime();
        List<CompletableFuture<HttpResponse<String>>> registrations = new ArrayList<>();
        for (int i = 0; i < REGISTRATIONS; i++) {
            registrations.add(client.sendAsync(request("POST", "/grid/register", registration("node-" + i)),
                    HttpResponse.BodyHandlers.ofString()));
        }
        // the threads of Jetty are free: the status and the node API answer at once
        for (int i = 0; i < 5; i++) {
            Assert.assertTrue(statusAnswers() < 2000);
            Assert.assertFalse(registered(node.baseUrl()));
        }
        // every node is answered in time (the deadline, or at once while the registrations are busy): 503, it registers again
        for (CompletableFuture<HttpResponse<String>> registration : registrations) {
            HttpResponse<String> answer = registration.get(15, TimeUnit.SECONDS);
            Assert.assertEquals(answer.statusCode(), 503, answer.body());
            Assert.assertEquals(answer.headers().firstValue("Retry-After").orElse(null), "5");
            Assert.assertTrue(answer.body().contains("registers again later"), answer.body());
        }
        Assert.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 15_000);
        // the registrations that hang are 2 (the threads of the registrations), not one per request
        Assert.assertEquals(verifications.get(), 2);

        // a node whose registration still runs is answered at once
        start = System.nanoTime();
        HttpResponse<String> again = send("POST", "/grid/register", registration("node-0"));
        Assert.assertEquals(again.statusCode(), 503, again.body());
        Assert.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < DEADLINE.toMillis(), "answered at once");

        // the healthcheck tells the hang
        Instant deadline = Instant.now().plus(STUCK).plusSeconds(10);
        JsonNode health = health();
        while (health.get("healthy").asBoolean() && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
            health = health();
        }
        Assert.assertFalse(health.get("healthy").asBoolean(), health.toString());
        Assert.assertTrue(health.get("message").asText().contains("a registration of a node has been running for"), health.toString());
        Assert.assertTrue(statusAnswers() < 2000);

        // STF answers: the registrations that ran are done (their nodes are added), the others register again
        stfAnswers.countDown();
        deadline = Instant.now().plusSeconds(20);
        while (!health.get("healthy").asBoolean() && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
            health = health();
        }
        Assert.assertTrue(health.get("healthy").asBoolean(), health.toString());
        String later = "node-" + (REGISTRATIONS - 1);
        HttpResponse<String> registered = send("POST", "/grid/register", registration(later));
        Assert.assertEquals(registered.statusCode(), 200, registered.body());
        deadline = Instant.now().plusSeconds(20);
        // the hub names a node by its URL (zebrunner/appium registers one device per URL)
        while (!registered(node.baseUrl()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        Assert.assertTrue(registered(node.baseUrl()));
        // the node answers its status polling: the hub has capacity
        for (String path : new String[] {"/wd/hub/status", "/status"}) {
            JsonNode status = status(path);
            Assert.assertTrue(status.get("value").get("ready").asBoolean(), status.toString());
            Assert.assertEquals(status.get("value").get("message").asText(), "Hub has capacity");
        }
    }

    public void aRejectedNodeGetsTheReasonAsBefore() throws Exception {
        HttpResponse<String> rejected = send("POST", "/grid/register", registration("rejected")
                .replace(GridFixtures.NODE_TOKEN, "not-a-node-token"));
        Assert.assertEquals(rejected.statusCode(), 500, rejected.body());
        Assert.assertTrue(rejected.body().contains("has a token that is not a node token"), rejected.body());
        Assert.assertFalse(rejected.body().contains("Exception"), rejected.body());
    }

    public void aTooLargeRegistrationIsRefused() throws Exception {
        HttpResponse<String> refused = send("POST", "/grid/register", "{\"configuration\": \"" + "x".repeat(2 * 1024 * 1024) + "\"}");
        Assert.assertEquals(refused.statusCode(), 413, refused.body());
    }
}
