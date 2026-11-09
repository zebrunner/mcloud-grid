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
 * The tokens of the registration of the nodes: the node tokens verified by the STF stub, the shared token refused from
 * the addresses of enrolled nodes, the caches of the answers of STF.
 */
@Test(groups = "stf", singleThreaded = true)
public class NodeAuthenticationTest {
    private static final String NODE_URL = "http://10.0.0.5:7521";
    private static final String HOST = "10.0.0.5";
    private static final String TOKEN = "mcn1.node-1.AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
    private static final String OTHER_TOKEN = "mcn1.node-1.ZbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
    private static final String SHARED = "shared-secret";

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
        stf.nodes("10.0.0.9", "Node-A.Example.com");
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

    private NodeAuthentication withSharedToken() {
        return new NodeAuthentication(NodeRegistrationToken.of(SHARED), stfNodes);
    }

    private NodeAuthentication nodeTokensOnly() {
        return new NodeAuthentication(null, stfNodes);
    }

    private static String rejection(NodeAuthentication authentication, RegistrationRequest request) {
        GridException e = Assert.expectThrows(GridException.class, () -> authentication.accept(request));
        Assert.assertFalse(e.getMessage().contains("AbCdEf"), e.getMessage());
        Assert.assertFalse(e.getMessage().contains(SHARED), e.getMessage());
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

        NodeAuthentication.Accepted accepted = nodeTokensOnly().accept(request);

        Assert.assertEquals(accepted.getNodeToken().getLabel(), "node-1");
        Assert.assertEquals(accepted.getHost(), HOST);
        Assert.assertEquals(sentHeaders(accepted.getCredential()), List.of(TOKEN));
        Assert.assertEquals(request.getConfiguration().custom, Map.of("other", "kept"));
        Assert.assertFalse(request.toJson().toString().contains("AbCdEf"));
        stf.server().verify(1, postRequestedFor(urlEqualTo("/api/v1/nodes/verify"))
                .withHeader("Authorization", equalTo("Bearer " + StfStub.DEFAULT_TOKEN))
                .withRequestBody(equalToJson("{\"token\":\"" + TOKEN + "\",\"host\":\"" + HOST + "\"}")));
        stf.server().verify(0, getRequestedFor(urlEqualTo("/api/v1/nodes")));
    }

    public void nodeTokenRejectedByStfIsNotAccepted() {
        String message = rejection(withSharedToken(), request(NODE_URL, OTHER_TOKEN));

        Assert.assertTrue(message.startsWith("Node " + NODE_URL + " has an unknown or revoked node token, so it is not registered."), message);
    }

    public void nodeTokenAtAnotherAddressIsNotAccepted() {
        String message = rejection(nodeTokensOnly(), request("http://10.0.0.6:7521", TOKEN));

        Assert.assertTrue(message.startsWith("Node http://10.0.0.6:7521 has an unknown or revoked node token"), message);
        stf.server().verify(postRequestedFor(urlEqualTo("/api/v1/nodes/verify")).withRequestBody(equalToJson("{\"token\":\"" + TOKEN
                + "\",\"host\":\"10.0.0.6\"}")));
    }

    public void nodeTokenIsNotAcceptedWhenStfDoesNotAnswer() {
        for (int status : new int[] {500, 401, 404}) {
            stf.verifyAnswers(status);

            String message = rejection(nodeTokensOnly(), request(NODE_URL, TOKEN));

            Assert.assertTrue(message.startsWith("Node " + NODE_URL + " has a node token, but STF could not verify it (HTTP " + status
                    + "), so it is not registered"), message);
        }
    }

    public void nodeTokenIsNotAcceptedWhenStfIsUnreachable() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        NodeAuthentication authentication = new NodeAuthentication(null, new StfNodes("http://localhost:" + port, "x", now::get));

        String message = rejection(authentication, request(NODE_URL, TOKEN));

        Assert.assertTrue(message.contains("but STF could not verify it (STF did not answer)"), message);
    }

    public void answersOfStfAreCachedBriefly() {
        NodeAuthentication authentication = nodeTokensOnly();

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
        NodeAuthentication authentication = nodeTokensOnly();
        stf.verifyAnswers(503);
        rejection(authentication, request(NODE_URL, TOKEN));

        stf.reset();
        stf.enrolledNode(TOKEN, "node-1", HOST);
        authentication.accept(request(NODE_URL, TOKEN));
    }

    public void verificationWithoutTheCacheSeesARevocation() {
        NodeAuthentication.Accepted accepted = nodeTokensOnly().accept(request(NODE_URL, TOKEN));
        stf.verifyAnswers(403);

        Assert.assertEquals(stfNodes.verify(accepted.getNodeToken(), HOST).getStatus(), StfNodes.Status.VALID);
        Assert.assertEquals(nodeTokensOnly().verifyNow(accepted.getNodeToken(), HOST).getStatus(), StfNodes.Status.REJECTED);
        // the new answer replaces the cached one
        Assert.assertEquals(stfNodes.verify(accepted.getNodeToken(), HOST).getStatus(), StfNodes.Status.REJECTED);
    }

    public void answerAboutAnotherLabelIsNotAccepted() {
        stf.server().stubFor(post("/api/v1/nodes/verify").atPriority(1)
                .willReturn(aResponse().withStatus(200).withBody("{\"label\":\"node-2\",\"address\":\"10.0.0.5\"}")));

        Assert.assertTrue(rejection(nodeTokensOnly(), request(NODE_URL, TOKEN)).contains("has an unknown or revoked node token"));
    }

    public void malformedNodeTokenIsRejectedWithoutStf() {
        String message = rejection(withSharedToken(), request(NODE_URL, "mcn1.node-1.short"));

        Assert.assertTrue(message.startsWith("Node " + NODE_URL + " has a malformed node token, so it is not registered."), message);
        stf.server().verify(0, postRequestedFor(urlMatching(".*")));
    }

    public void nodeTokenIsRejectedWithoutStfIntegration() {
        NodeAuthentication authentication = new NodeAuthentication(NodeRegistrationToken.of(SHARED), null);

        String message = rejection(authentication, request(NODE_URL, TOKEN));

        Assert.assertTrue(message.contains("(node-1) has a node token, but the STF integration of the hub is off"), message);
        Assert.assertEquals(authentication.verifyNow(NodeToken.parse(TOKEN).orElseThrow(), HOST).getStatus(),
                StfNodes.Status.UNAVAILABLE);
    }

    public void sharedTokenIsAcceptedFromANodeThatIsNotEnrolled() throws Exception {
        RegistrationRequest request = request("http://10.0.0.7:4723", SHARED);

        NodeAuthentication.Accepted accepted = withSharedToken().accept(request);

        Assert.assertNull(accepted.getNodeToken());
        Assert.assertEquals(sentHeaders(accepted.getCredential()), List.of(SHARED));
        Assert.assertFalse(request.toJson().toString().contains(SHARED));
        stf.server().verify(getRequestedFor(urlEqualTo("/api/v1/nodes")).withHeader("Authorization", equalTo("Bearer " + StfStub.DEFAULT_TOKEN)));
        stf.server().verify(0, postRequestedFor(urlEqualTo("/api/v1/nodes/verify")));
    }

    public void sharedTokenIsRefusedFromTheAddressOfAnEnrolledNode() {
        // the first node of the stub is revoked: any status counts; the addresses are compared ignoring the case
        for (String nodeUrl : new String[] {"http://10.0.0.9:4723", "http://node-a.example.com:4723"}) {
            String message = rejection(withSharedToken(), request(nodeUrl, SHARED));

            Assert.assertTrue(message.startsWith("Node " + nodeUrl + " registers with the shared NODE_REGISTRATION_TOKEN, but "
                    + nodeUrl.substring(7, nodeUrl.lastIndexOf(':')) + " is the address of an enrolled node"), message);
        }
        // the addresses are cached
        stf.server().verify(1, getRequestedFor(urlEqualTo("/api/v1/nodes")));
    }

    public void sharedTokenIsAcceptedWhenStfHasNoNodeRegistry() {
        stf.server().stubFor(get("/api/v1/nodes").atPriority(1).willReturn(aResponse().withStatus(404)));

        withSharedToken().accept(request(NODE_URL, SHARED));
    }

    public void sharedTokenIsRefusedWhenTheEnrolledNodesAreUnknown() {
        stf.server().stubFor(get("/api/v1/nodes").atPriority(1).willReturn(aResponse().withStatus(500)));

        String message = rejection(withSharedToken(), request("http://10.0.0.7:4723", SHARED));

        Assert.assertTrue(message.contains("but STF could not tell whether 10.0.0.7 is the address of an enrolled node"), message);
    }

    public void lastKnownEnrolledNodesAreUsedWhenStfDoesNotAnswer() {
        NodeAuthentication authentication = withSharedToken();
        authentication.accept(request("http://10.0.0.7:4723", SHARED));
        stf.server().stubFor(get("/api/v1/nodes").atPriority(1).willReturn(aResponse().withStatus(500)));
        now.set(now.get().plus(StfNodes.CACHE_TIME));

        authentication.accept(request("http://10.0.0.7:4723", SHARED));
        Assert.assertTrue(rejection(authentication, request("http://node-a.example.com:4723", SHARED)).contains("is the address of an enrolled node"));
        stf.server().verify(3, getRequestedFor(urlEqualTo("/api/v1/nodes")));
    }

    public void sharedTokenWithoutStfIntegrationIsAccepted() {
        new NodeAuthentication(NodeRegistrationToken.of(SHARED), null).accept(request(NODE_URL, SHARED));

        stf.server().verify(0, getRequestedFor(urlMatching(".*")));
    }

    public void anotherSharedTokenIsRejectedWithoutStf() {
        String message = rejection(withSharedToken(), request(NODE_URL, "another"));

        Assert.assertEquals(message, "Node " + NODE_URL + " has no or another NODE_REGISTRATION_TOKEN than the hub, so it is not registered.");
        stf.server().verify(0, getRequestedFor(urlMatching(".*")));
    }

    public void sharedTokenIsRejectedByAHubOfNodeTokensOnly() {
        String message = rejection(nodeTokensOnly(), request(NODE_URL, SHARED));

        Assert.assertTrue(message.startsWith("Node " + NODE_URL + " registers with a shared NODE_REGISTRATION_TOKEN, but the hub accepts "
                + "only node tokens, so it is not registered."), message);
        stf.server().verify(0, getRequestedFor(urlMatching(".*")));
    }

    public void nodeWithoutTokenIsRejected() {
        Assert.assertEquals(rejection(nodeTokensOnly(), request(NODE_URL, null)),
                "Node " + NODE_URL + " has no node token, so it is not registered. Join the node to get one.");
        Assert.assertEquals(rejection(withSharedToken(), request(NODE_URL, " ")),
                "Node " + NODE_URL + " has no or another NODE_REGISTRATION_TOKEN than the hub, so it is not registered.");
    }

    public void hostCheckRunsBeforeTheRequestsToStf() {
        for (String token : new String[] {TOKEN, SHARED}) {
            GridException e = Assert.expectThrows(GridException.class, () -> withSharedToken().accept(request(NODE_URL, token), host -> {
                throw new GridException("not allowed: " + host);
            }));

            Assert.assertEquals(e.getMessage(), "not allowed: " + HOST);
        }
        stf.server().verify(0, getRequestedFor(urlMatching(".*")));
        stf.server().verify(0, postRequestedFor(urlMatching(".*")));
    }

    public void hostOfTheNodeUrl() {
        Assert.assertEquals(NodeAuthentication.host(request("http://Node.Example.com:4723/wd/hub", null)).orElseThrow(), "Node.Example.com");
        Assert.assertEquals(NodeAuthentication.host(request("http://[fd00::5]:4723", null)).orElseThrow(), "fd00::5");
        RegistrationRequest invalid = request("not a url", null);
        Assert.assertTrue(NodeAuthentication.host(invalid).isEmpty());
        Assert.assertTrue(rejection(withSharedToken(), request("not a url", SHARED)).contains("has no valid URL"));
    }

    public void configurationIsDescribed() {
        Assert.assertEquals(nodeTokensOnly().describe(), "node tokens verified by STF only");
        Assert.assertTrue(withSharedToken().describe().startsWith("node tokens verified by STF and the shared NODE_REGISTRATION_TOKEN"));
        Assert.assertTrue(new NodeAuthentication(NodeRegistrationToken.of(SHARED), null).describe().startsWith("the shared"));
    }
}
