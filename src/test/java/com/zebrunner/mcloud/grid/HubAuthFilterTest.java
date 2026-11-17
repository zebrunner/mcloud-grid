package com.zebrunner.mcloud.grid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.zebrunner.mcloud.grid.servlets.AllSessionsServlet;
import com.zebrunner.mcloud.grid.servlets.DevicesServlet;
import com.zebrunner.mcloud.grid.servlets.MetricsServlet;
import com.zebrunner.mcloud.grid.servlets.PlaywrightServlet;
import com.zebrunner.mcloud.grid.servlets.TerminateSessionServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.utils.configuration.GridHubConfiguration;
import org.openqa.grid.web.Hub;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * The hub of Selenium with {@link HubAuthFilter} installed as {@link HubLauncher} does, and a node played by WireMock: the
 * requests of the users through the filter, the sessions of their users and the headers the node gets.
 */
@Test(singleThreaded = true)
public class HubAuthFilterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UDID = "emulator-5554";
    private static final String NODE_SESSION = "node-session-1";
    private static final String JOHN = "john@example.com";
    private static final String MARY = "mary@example.com";
    private static final String ADMIN = "admin@example.com";

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private WireMockServer node;
    private Hub hub;
    private String url;

    @BeforeClass
    public void startHub() throws Exception {
        GridFixtures.useHubAuth(GridFixtures.HUB_SECRET);
        node = new WireMockServer(options().dynamicPort());
        node.start();
        node.stubFor(get("/wd/hub/status").willReturn(okJson("{\"value\":{\"ready\":true}}")));
        node.stubFor(post("/wd/hub/session").willReturn(okJson("{\"value\":{\"sessionId\":\"" + NODE_SESSION
                + "\",\"capabilities\":{\"platformName\":\"Android\"}}}")));
        node.stubFor(get(urlMatching("/wd/hub/session/" + NODE_SESSION + "/.*")).willReturn(okJson("{\"value\":\"ok\"}")));

        GridHubConfiguration config = new GridHubConfiguration();
        config.registry = MobileGridRegistry.class.getName();
        config.capabilityMatcher = new MobileCapabilityMatcher();
        config.port = 0;
        config.host = "127.0.0.1";
        config.newSessionWaitTimeout = 10000;
        // the servlets of generate_config
        config.servlets = List.of(AllSessionsServlet.class.getName(), DevicesServlet.class.getName(), MetricsServlet.class.getName(),
                PlaywrightServlet.class.getName(), TerminateSessionServlet.class.getName());
        hub = new Hub(config);
        hub.start();
        HubAuthFilter.install(hub, HubAuth.get());
        Field field = Hub.class.getDeclaredField("server");
        field.setAccessible(true);
        int port = ((ServerConnector) ((Server) field.get(hub)).getConnectors()[0]).getLocalPort();
        url = "http://127.0.0.1:" + port;

        MobileRemoteProxy proxy = GridFixtures.proxy(hub.getRegistry(), node.baseUrl(), GridFixtures.androidNodeCaps(UDID));
        hub.getRegistry().add(proxy);
    }

    @AfterClass(alwaysRun = true)
    public void stopHub() throws Exception {
        try {
            if (hub != null) {
                hub.getRegistry().stop();
                hub.stop();
            }
            if (node != null) {
                node.stop();
            }
        } finally {
            GridFixtures.useHubAuth(null);
        }
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String[] user(String email, boolean admin, String token) {
        return new String[] {HubAuth.SECRET_HEADER, GridFixtures.HUB_SECRET, HubAuth.USER_HEADER, email, HubAuth.ADMIN_HEADER, String.valueOf(admin),
                HubAuth.TOKEN_HEADER, token};
    }

    private static String[] concat(String[] first, String... second) {
        String[] all = new String[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    @Test
    public void letsTheHealthcheckInWithoutAUser() throws Exception {
        Assert.assertEquals(send("GET", "/wd/hub/status", null).statusCode(), 200);
    }

    @Test
    public void refusesARequestWithoutTheUserOfStfProxy() throws Exception {
        HttpResponse<String> anonymous = send("POST", "/wd/hub/session", "{\"capabilities\":{\"alwaysMatch\":{\"platformName\":\"Android\"}}}");
        Assert.assertEquals(anonymous.statusCode(), 401);
        Assert.assertTrue(anonymous.body().contains("https://<email>:<token>@<host>/mcloud/wd/hub"), anonymous.body());

        // the headers of a user without the secret: a client that reached the hub without stf-proxy
        String[] forged = {HubAuth.SECRET_HEADER, "not-the-secret-not-the-secret-not-the", HubAuth.USER_HEADER, ADMIN, HubAuth.ADMIN_HEADER, "true"};
        Assert.assertEquals(send("GET", "/grid/console", null, forged).statusCode(), 401);
        Assert.assertEquals(send("GET", "/grid/api/hub", null, forged).statusCode(), 401);
        Assert.assertEquals(send("GET", "/grid/admin/DevicesServlet", null).statusCode(), 401);
    }

    @Test
    public void letsTheAdminRoutesInForAnAdminOnly() throws Exception {
        Assert.assertEquals(send("GET", "/grid/api/hub", null, user(JOHN, false, "john-token")).statusCode(), 403);
        Assert.assertEquals(send("GET", "/grid/admin/MetricsServlet", null, user(JOHN, false, "john-token")).statusCode(), 403);
        Assert.assertEquals(send("GET", "/grid/api/hub", null, user(ADMIN, true, "admin-token")).statusCode(), 200);
        Assert.assertEquals(send("GET", "/grid/admin/MetricsServlet", null, user(ADMIN, true, "admin-token")).statusCode(), 200);
        Assert.assertEquals(send("GET", "/grid/console", null, user(JOHN, false, "john-token")).statusCode(), 200);
    }

    @Test(dependsOnMethods = {"letsTheHealthcheckInWithoutAUser", "refusesARequestWithoutTheUserOfStfProxy", "letsTheAdminRoutesInForAnAdminOnly"})
    public void sessionBelongsToItsUserAndTheNodeGetsNoneOfTheSecrets() throws Exception {
        String[] john = concat(user(JOHN, false, "john-token"), "Authorization", "Basic am9objpqb2huLXRva2Vu", "Cookie", "ssid=secret");
        HttpResponse<String> created = send("POST", "/wd/hub/session", "{\"capabilities\":{\"alwaysMatch\":{\"platformName\":\"Android\"}}}", john);
        Assert.assertEquals(created.statusCode(), 200, created.body());
        Assert.assertEquals(MAPPER.readTree(created.body()).path("value").path("sessionId").asText(), NODE_SESSION);

        List<LoggedRequest> forwarded = node.findAll(postRequestedFor(urlEqualTo("/wd/hub/session")));
        Assert.assertEquals(forwarded.size(), 1);
        for (String header : new String[] {"Authorization", "Cookie", HubAuth.SECRET_HEADER, HubAuth.USER_HEADER, HubAuth.ADMIN_HEADER,
                HubAuth.TOKEN_HEADER}) {
            Assert.assertFalse(forwarded.get(0).containsHeader(header), header + " reached the node: " + forwarded.get(0).getHeaders());
        }

        TestSession session = hub.getRegistry().getActiveSessions().iterator().next();
        Assert.assertEquals(session.get(HubAuth.OWNER), JOHN);
        Assert.assertEquals(session.get(HubAuth.TOKEN), "john-token");

        // another user gets the answer of an unknown session, the node gets no command
        HttpResponse<String> foreign = send("GET", "/wd/hub/session/" + NODE_SESSION + "/url", null, user(MARY, false, "mary-token"));
        Assert.assertEquals(foreign.statusCode(), 404);
        Assert.assertEquals(MAPPER.readTree(foreign.body()).path("value").path("error").asText(), "invalid session id");
        node.verify(0, getRequestedFor(urlEqualTo("/wd/hub/session/" + NODE_SESSION + "/url")));
        HttpResponse<String> playwright = send("GET", "/grid/admin/PlaywrightServlet/" + NODE_SESSION, null, user(MARY, false, "mary-token"));
        Assert.assertEquals(playwright.statusCode(), 404);

        Assert.assertEquals(send("GET", "/wd/hub/session/" + NODE_SESSION + "/url", null, user(JOHN, false, "john-token")).statusCode(), 200);
        Assert.assertEquals(send("GET", "/wd/hub/session/" + NODE_SESSION + "/url", null, user(ADMIN, true, "admin-token")).statusCode(), 200);
        node.verify(2, getRequestedFor(urlEqualTo("/wd/hub/session/" + NODE_SESSION + "/url")));

        // the sessions and the devices of the others are not shown to a user
        JsonNode marySessions = MAPPER.readTree(send("GET", "/grid/admin/AllSessionsServlet", null, user(MARY, false, "mary-token")).body());
        Assert.assertEquals(marySessions.path("value").size(), 0, marySessions.toString());
        JsonNode johnSessions = MAPPER.readTree(send("GET", "/grid/admin/AllSessionsServlet", null, user(JOHN, false, "john-token")).body());
        Assert.assertEquals(johnSessions.path("value").size(), 1, johnSessions.toString());

        JsonNode maryDevices = MAPPER.readTree(send("GET", "/grid/admin/DevicesServlet", null, user(MARY, false, "mary-token")).body());
        Assert.assertEquals(maryDevices.path("value").get(0).path("session"), MAPPER.readTree("{\"user\":\"" + JOHN + "\"}"), maryDevices.toString());
        JsonNode johnDevices = MAPPER.readTree(send("GET", "/grid/admin/DevicesServlet", null, user(JOHN, false, "john-token")).body());
        Assert.assertEquals(johnDevices.path("value").get(0).path("session").path("id").asText(), NODE_SESSION, johnDevices.toString());

        String console = send("GET", "/grid/console", null, user(MARY, false, "mary-token")).body();
        Assert.assertFalse(console.contains(NODE_SESSION), console);

        hub.getRegistry().terminate(session, org.openqa.grid.internal.SessionTerminationReason.CLIENT_STOPPED_SESSION);
    }

    @Test
    public void installsIntoAStartedHubOnly() {
        GridHubConfiguration config = new GridHubConfiguration();
        config.port = 0;
        Hub notStarted = new Hub(config);
        try {
            Assert.assertThrows(IllegalStateException.class, () -> HubAuthFilter.install(notStarted, HubAuth.get()));
        } finally {
            notStarted.getRegistry().stop();
        }
    }

    @Test
    public void hidesTheSecretsFromTheRequestOfTheServlets() {
        Map<String, String> headers = Map.of("Authorization", "Basic x", "Cookie", "c", HubAuth.SECRET_HEADER, "s", HubAuth.TOKEN_HEADER, "t",
                "User-Agent", "client");
        javax.servlet.http.HttpServletRequest request = (javax.servlet.http.HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {javax.servlet.http.HttpServletRequest.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "getHeader":
                        return headers.get((String) args[0]);
                    case "getHeaders":
                        return java.util.Collections.enumeration(headers.containsKey((String) args[0]) ? List.of(headers.get((String) args[0]))
                                : List.of());
                    case "getHeaderNames":
                        return java.util.Collections.enumeration(headers.keySet());
                    default:
                        return null;
                    }
                });
        HubAuthFilter.HiddenHeaders hidden = new HubAuthFilter.HiddenHeaders(request);
        Assert.assertEquals(java.util.Collections.list(hidden.getHeaderNames()), List.of("User-Agent"));
        Assert.assertNull(hidden.getHeader("authorization"));
        Assert.assertNull(hidden.getHeader("X-MCLOUD-TOKEN"));
        Assert.assertFalse(hidden.getHeaders("Cookie").hasMoreElements());
        Assert.assertEquals(hidden.getHeader("User-Agent"), "client");
        Assert.assertNull(hidden.getCookies());
    }
}
