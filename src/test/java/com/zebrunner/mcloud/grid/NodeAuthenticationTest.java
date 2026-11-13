package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.integration.client.StfNodes;
import com.zebrunner.mcloud.grid.integration.client.StfStub;
import com.zebrunner.mcloud.grid.util.NodeRegistrationToken;
import com.zebrunner.mcloud.grid.util.NodeToken;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.exception.GridException;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;

/**
 * The node tokens of the registration of the nodes, verified by the STF stub, and the cache of the answers of STF.
 */
@Test(groups = "stf", singleThreaded = true)
public class NodeAuthenticationTest {
    private static final String NODE_URL = "http://10.0.0.5:7521";
    private static final String HOST = "10.0.0.5";
    private static final String TOKEN = "mcn1.node-1.AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
    private static final String OTHER_TOKEN = "mcn1.node-1.ZbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
    private static final String NOT_A_NODE_TOKEN = "0123456789abcdef0123456789abcdef";

    private final StfStub stf = new StfStub();
    private final AtomicReference<Instant> now = new AtomicReference<>();
    private StfNodes stfNodes;

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
        now.set(Instant.parse("2026-11-09T12:00:00Z"));
        stfNodes = new StfNodes(stf.server().baseUrl(), StfStub.DEFAULT_TOKEN, now::get);
        stf.enrolledNode(TOKEN, "node-1", HOST);
    }

    private static RegistrationRequest request(String nodeUrl, String token) {
        RegistrationRequest request = GridFixtures.registrationRequest(nodeUrl, GridFixtures.androidNodeCaps("udid-1"));
        request.getConfiguration().custom.remove(NodeRegistrationToken.CUSTOM_KEY);
        if (token != null) {
            request.getConfiguration().custom.put(NodeRegistrationToken.CUSTOM_KEY, token);
        }
        request.getConfiguration().custom.put("other", "kept");
        return request;
    }

    private NodeAuthentication authentication() {
        return new NodeAuthentication(stfNodes);
    }

    private static String rejection(NodeAuthentication authentication, RegistrationRequest request) {
        GridException e = Assert.expectThrows(GridException.class, () -> authentication.accept(request));
        Assert.assertFalse(e.getMessage().contains("AbCdEf"), e.getMessage());
        Assert.assertFalse(e.getMessage().contains(NOT_A_NODE_TOKEN), e.getMessage());
        return e.getMessage();
    }

    private static List<String> sentHeaders(NodeRegistrationToken credential) throws Exception {
        List<String> sent = new ArrayList<>();
        HttpClient client = request -> {
            sent.add(request.getHeader(NodeRegistrationToken.HEADER));
            return new HttpResponse();
        };
        credential.authenticate(client).execute(new HttpRequest(HttpMethod.GET, "/wd/hub/status"));
        return sent;
    }

    public void nodeTokenVerifiedByStfIsAccepted() throws Exception {
        RegistrationRequest request = request(NODE_URL, TOKEN);

        NodeAuthentication.Accepted accepted = authentication().accept(request);

        Assert.assertEquals(accepted.getNodeToken().getLabel(), "node-1");
        Assert.assertEquals(accepted.getHost(), HOST);
        Assert.assertEquals(sentHeaders(accepted.getCredential()), List.of(TOKEN));
        Assert.assertEquals(request.getConfiguration().custom, Map.of("other", "kept"));
        Assert.assertFalse(request.toJson().toString().contains("AbCdEf"));
        stf.server().verify(1, postRequestedFor(urlEqualTo("/api/v1/nodes/verify"))
                .withHeader("Authorization", equalTo("Bearer " + StfStub.DEFAULT_TOKEN))
                .withRequestBody(equalToJson("{\"token\":\"" + TOKEN + "\",\"host\":\"" + HOST + "\"}")));
        stf.server().verify(0, getRequestedFor(urlMatching(".*")));
    }

    public void nodeTokenRejectedByStfIsNotAccepted() {
        String message = rejection(authentication(), request(NODE_URL, OTHER_TOKEN));

        Assert.assertTrue(message.startsWith("Node " + NODE_URL + " has an unknown or revoked node token, so it is not registered."), message);
    }

    public void nodeTokenAtAnotherAddressIsNotAccepted() {
        String message = rejection(authentication(), request("http://10.0.0.6:7521", TOKEN));

        Assert.assertTrue(message.startsWith("Node http://10.0.0.6:7521 has an unknown or revoked node token"), message);
        stf.server().verify(postRequestedFor(urlEqualTo("/api/v1/nodes/verify")).withRequestBody(equalToJson("{\"token\":\"" + TOKEN
                + "\",\"host\":\"10.0.0.6\"}")));
    }

    public void nodeTokenIsNotAcceptedWhenStfDoesNotAnswer() {
        for (int status : new int[] {500, 401, 404}) {
            stf.verifyAnswers(status);

            String message = rejection(authentication(), request(NODE_URL, TOKEN));

            Assert.assertTrue(message.startsWith("Node " + NODE_URL + " has a node token, but STF could not verify it (HTTP " + status
                    + "), so it is not registered"), message);
        }
    }

    public void nodeTokenIsNotAcceptedWhenStfIsUnreachable() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        NodeAuthentication authentication = new NodeAuthentication(new StfNodes("http://localhost:" + port, "x", now::get));

        String message = rejection(authentication, request(NODE_URL, TOKEN));

        Assert.assertTrue(message.contains("but STF could not verify it (STF did not answer)"), message);
    }

    public void answersOfStfAreCachedBriefly() {
        NodeAuthentication authentication = authentication();

        authentication.accept(request(NODE_URL, TOKEN));
        rejection(authentication, request(NODE_URL, OTHER_TOKEN));
        now.set(now.get().plus(StfNodes.CACHE_TIME).minusSeconds(1));
        authentication.accept(request(NODE_URL, TOKEN));
        rejection(authentication, request(NODE_URL, OTHER_TOKEN));
        stf.server().verify(2, postRequestedFor(urlEqualTo("/api/v1/nodes/verify")));

        // the cache is keyed by the token and the host
        rejection(authentication, request("http://10.0.0.6:7521", TOKEN));
        stf.server().verify(3, postRequestedFor(urlEqualTo("/api/v1/nodes/verify")));

        now.set(now.get().plusSeconds(1));
        authentication.accept(request(NODE_URL, TOKEN));
        stf.server().verify(4, postRequestedFor(urlEqualTo("/api/v1/nodes/verify")));
        Assert.assertTrue(StfNodes.CACHE_TIME.compareTo(Duration.ofSeconds(60)) <= 0);
    }

    public void failedVerificationIsNotCached() {
        NodeAuthentication authentication = authentication();
        stf.verifyAnswers(503);
        rejection(authentication, request(NODE_URL, TOKEN));

        stf.reset();
        stf.enrolledNode(TOKEN, "node-1", HOST);
        authentication.accept(request(NODE_URL, TOKEN));
    }

    public void verificationWithoutTheCacheSeesARevocation() {
        NodeAuthentication.Accepted accepted = authentication().accept(request(NODE_URL, TOKEN));
        stf.verifyAnswers(403);

        Assert.assertEquals(stfNodes.verify(accepted.getNodeToken(), HOST).getStatus(), StfNodes.Status.VALID);
        Assert.assertEquals(authentication().verifyNow(accepted.getNodeToken(), HOST).getStatus(), StfNodes.Status.REJECTED);
        // the new answer replaces the cached one
        Assert.assertEquals(stfNodes.verify(accepted.getNodeToken(), HOST).getStatus(), StfNodes.Status.REJECTED);
    }

    public void answerAboutAnotherLabelIsNotAccepted() {
        stf.server().stubFor(post("/api/v1/nodes/verify").atPriority(1)
                .willReturn(aResponse().withStatus(200).withBody("{\"label\":\"node-2\",\"address\":\"10.0.0.5\"}")));

        Assert.assertTrue(rejection(authentication(), request(NODE_URL, TOKEN)).contains("has an unknown or revoked node token"));
    }

    public void malformedNodeTokenIsRejectedWithoutStf() {
        String message = rejection(authentication(), request(NODE_URL, "mcn1.node-1.short"));

        Assert.assertTrue(message.startsWith("Node " + NODE_URL + " has a malformed node token, so it is not registered."), message);
        stf.server().verify(0, postRequestedFor(urlMatching(".*")));
    }

    public void nodeTokenIsRejectedWithoutStf() {
        NodeAuthentication authentication = new NodeAuthentication(null);

        String message = rejection(authentication, request(NODE_URL, TOKEN));

        Assert.assertTrue(message.contains("(node-1) has a node token, but STF_URL and STF_TOKEN of the hub are not set"), message);
        Assert.assertEquals(authentication.verifyNow(NodeToken.parse(TOKEN).orElseThrow(), HOST).getStatus(), StfNodes.Status.UNAVAILABLE);
    }

    public void tokenThatIsNotANodeTokenIsRejectedWithoutStf() {
        String message = rejection(authentication(), request(NODE_URL, NOT_A_NODE_TOKEN));

        Assert.assertEquals(message, "Node " + NODE_URL + " has a token that is not a node token (mcn1.<label>.<secret>), "
                + "so it is not registered. Join the node to get its node token.");
        stf.server().verify(0, postRequestedFor(urlMatching(".*")));
    }

    public void nodeWithoutTokenIsRejected() {
        for (String token : new String[] {null, " "}) {
            Assert.assertEquals(rejection(authentication(), request(NODE_URL, token)), "Node " + NODE_URL + " has no node token, "
                    + "so it is not registered. Join the node to get one (NODE_REGISTRATION_TOKEN of the node is its node token).");
        }
    }

    public void hostCheckRunsBeforeTheRequestsToStf() {
        GridException e = Assert.expectThrows(GridException.class, () -> authentication().accept(request(NODE_URL, TOKEN), host -> {
            throw new GridException("not allowed: " + host);
        }));

        Assert.assertEquals(e.getMessage(), "not allowed: " + HOST);
        stf.server().verify(0, getRequestedFor(urlMatching(".*")));
        stf.server().verify(0, postRequestedFor(urlMatching(".*")));
    }

    public void hostOfTheNodeUrl() {
        Assert.assertEquals(NodeAuthentication.host(request("http://Node.Example.com:4723/wd/hub", null)).orElseThrow(), "Node.Example.com");
        Assert.assertEquals(NodeAuthentication.host(request("http://[fd00::5]:4723", null)).orElseThrow(), "fd00::5");
        RegistrationRequest invalid = request("not a url", null);
        Assert.assertTrue(NodeAuthentication.host(invalid).isEmpty());
        Assert.assertTrue(rejection(authentication(), request("not a url", TOKEN)).contains("has no valid URL"));
    }

    private static String randomToken(int i) {
        return "mcn1.node-1." + ("Rnd" + i + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde").substring(0, 43);
    }

    public void verificationsOfNewTokensAreLimitedPerHostAndForAllHosts() {
        NodeAuthentication authentication = new NodeAuthentication(stfNodes, new VerificationLimit(now::get, 3, 5));
        List<String> checkedHosts = new ArrayList<>();

        // a token counted in this period is verified again: the nodes of a host share its token, a failed answer is not cached
        stf.verifyAnswers(503);
        for (int i = 0; i < 5; i++) {
            Assert.assertTrue(rejection(authentication, request(NODE_URL, TOKEN)).contains("STF could not verify it (HTTP 503)"));
        }
        stf.server().verify(5, postRequestedFor(urlEqualTo("/api/v1/nodes/verify")));
        stf.reset();
        stf.enrolledNode(TOKEN, "node-1", HOST);

        authentication.accept(request(NODE_URL, TOKEN), checkedHosts::add);
        rejection(authentication, request(NODE_URL, randomToken(1)));
        rejection(authentication, request(NODE_URL, randomToken(2)));
        stf.server().verify(3, postRequestedFor(urlEqualTo("/api/v1/nodes/verify")));

        // a new token of the host: neither the host check (DNS) nor STF
        GridException e = Assert.expectThrows(GridException.class,
                () -> authentication.accept(request(NODE_URL, randomToken(3)), checkedHosts::add));
        Assert.assertTrue(e.getMessage().contains("is not registered now: the hub verifies with STF at most 3 node tokens of a host "
                + "and 5 of all the hosts in 30s"), e.getMessage());
        Assert.assertEquals(checkedHosts, List.of(HOST));
        stf.server().verify(3, postRequestedFor(urlEqualTo("/api/v1/nodes/verify")));
        // the answers STF gave recently are not limited
        authentication.accept(request(NODE_URL, TOKEN), checkedHosts::add);
        Assert.assertEquals(checkedHosts, List.of(HOST, HOST));

        // another host has its own limit, all the hosts share one
        rejection(authentication, request("http://10.0.0.6:7521", randomToken(4)));
        rejection(authentication, request("http://10.0.0.7:7521", randomToken(5)));
        Assert.assertTrue(rejection(authentication, request("http://10.0.0.8:7521", randomToken(6))).contains("is not registered now"));
        stf.server().verify(5, postRequestedFor(urlEqualTo("/api/v1/nodes/verify")));

        now.set(now.get().plus(VerificationLimit.PERIOD));
        Assert.assertTrue(rejection(authentication, request(NODE_URL, randomToken(7))).contains("has an unknown or revoked node token"));
        stf.server().verify(6, postRequestedFor(urlEqualTo("/api/v1/nodes/verify")));
    }
}
