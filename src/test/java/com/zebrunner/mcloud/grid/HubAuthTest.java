package com.zebrunner.mcloud.grid;

import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.internal.TestSession;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class HubAuthTest {
    private static final String SECRET = GridFixtures.HUB_SECRET;

    @AfterMethod(alwaysRun = true)
    public void turnOff() {
        GridFixtures.useHubAuth(null);
    }

    static HttpServletRequest request(Map<String, String> headers, Map<String, Object> attributes) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "getHeader":
                        return headers.get((String) args[0]);
                    case "getAttribute":
                        return attributes.get((String) args[0]);
                    case "setAttribute":
                        attributes.put((String) args[0], args[1]);
                        return null;
                    default:
                        return null;
                    }
                });
    }

    private static Map<String, String> headers(String secret, String user, String admin, String token) {
        Map<String, String> headers = new HashMap<>();
        if (secret != null) {
            headers.put(HubAuth.SECRET_HEADER, secret);
        }
        if (user != null) {
            headers.put(HubAuth.USER_HEADER, user);
        }
        if (admin != null) {
            headers.put(HubAuth.ADMIN_HEADER, admin);
        }
        if (token != null) {
            headers.put(HubAuth.TOKEN_HEADER, token);
        }
        return headers;
    }

    @Test
    public void isOffWithoutASecretAndRefusesAShortOne() {
        Assert.assertFalse(HubAuth.fromEnv(null).isEnabled());
        Assert.assertFalse(HubAuth.fromEnv("").isEnabled());
        Assert.assertTrue(HubAuth.fromEnv(SECRET).isEnabled());
        Assert.assertThrows(IllegalArgumentException.class, () -> HubAuth.fromEnv("short-secret"));
    }

    @Test
    public void takesTheUserOfTheHeadersWithTheSecretOnly() {
        HubAuth auth = HubAuth.fromEnv(SECRET);
        HubAuth.Identity user = auth.authenticate(request(headers(SECRET, "john@example.com", "false", "token"), new HashMap<>())).orElseThrow();
        Assert.assertEquals(user.getUser(), "john@example.com");
        Assert.assertFalse(user.isAdmin());
        Assert.assertEquals(user.getToken(), "token");

        HubAuth.Identity admin = auth.authenticate(request(headers(SECRET, "admin@example.com", "true", null), new HashMap<>())).orElseThrow();
        Assert.assertTrue(admin.isAdmin());
        Assert.assertNull(admin.getToken());

        Assert.assertTrue(auth.authenticate(request(headers(null, "john@example.com", "true", "token"), new HashMap<>())).isEmpty());
        Assert.assertTrue(auth.authenticate(request(headers(SECRET + "x", "john@example.com", "true", "token"), new HashMap<>())).isEmpty());
        Assert.assertTrue(auth.authenticate(request(headers(SECRET, " ", "true", "token"), new HashMap<>())).isEmpty());
        Assert.assertTrue(HubAuth.fromEnv(null).authenticate(request(headers(SECRET, "john@example.com", "true", "token"), new HashMap<>()))
                .isEmpty());
    }

    @Test
    public void letsTheNodesAndTheHealthcheckInTheUsersToTheHubAndTheAdminsToTheRest() {
        for (String path : new String[] {"/wd/hub/status", "/status", "/grid/register", "/grid/register/", "/grid/api/proxy", "/grid/api/health"}) {
            Assert.assertEquals(HubAuth.access(path), HubAuth.Access.ANYONE, path);
        }
        for (String path : new String[] {"/wd/hub/session", "/wd/hub/session/abc/url", "/wd/hub", "/grid/console", "/grid/console/x",
                "/grid/resources/org/openqa/grid/images/a.png", "/grid/admin/DevicesServlet", "/grid/admin/AllSessionsServlet",
                "/grid/admin/TerminateSessionServlet", "/grid/admin/PlaywrightServlet/abc"}) {
            Assert.assertEquals(HubAuth.access(path), HubAuth.Access.USER, path);
        }
        for (String path : new String[] {"/", "", "/grid/api/hub", "/grid/api/proxy/x", "/grid/api/sessions", "/grid/api/testsession",
                "/grid/admin/MetricsServlet", "/lifecycle-manager", "/selenium-server/driver", "/heartbeat", "/wd/hubx",
                "/grid/consolex", "/grid/admin/DevicesServletX", "/grid/registerx"}) {
            Assert.assertEquals(HubAuth.access(path), HubAuth.Access.ADMIN, path);
        }
    }

    @Test
    public void givesTheSessionToTheUserOfItsRequest() {
        Map<String, Object> attributes = new HashMap<>();
        ServletRequest request = request(Map.of(), attributes);
        attributes.put(HubAuth.IDENTITY_ATTRIBUTE, new HubAuth.Identity("john@example.com", false, "token"));
        TestSession session = new TestSession(null, new HashMap<>(), Clock.systemUTC());

        HubAuth.bind(request, session);

        Assert.assertEquals(session.get(HubAuth.OWNER), "john@example.com");
        Assert.assertEquals(session.get(HubAuth.TOKEN), "token");
        Assert.assertEquals(HubAuth.owner(session), "john@example.com");
        Assert.assertTrue(HubAuth.mayUse(new HubAuth.Identity("john@example.com", false, null), session));
        Assert.assertFalse(HubAuth.mayUse(new HubAuth.Identity("mary@example.com", false, null), session));
        Assert.assertTrue(HubAuth.mayUse(new HubAuth.Identity("admin@example.com", true, null), session));

        TestSession other = new TestSession(null, new HashMap<>(), Clock.systemUTC());
        HubAuth.bind(request(Map.of(), new HashMap<>()), other);
        Assert.assertNull(other.get(HubAuth.OWNER));
        Assert.assertFalse(HubAuth.mayUse(new HubAuth.Identity("john@example.com", false, null), other));
    }

    @Test
    public void showsTheSessionsOfTheViewerOnly() {
        TestSession john = new TestSession(null, new HashMap<>(), Clock.systemUTC());
        john.put(HubAuth.OWNER, "john@example.com");
        Assert.assertTrue(HubAuth.maySee(john));
        try {
            HubAuth.setViewer(new HubAuth.Identity("mary@example.com", false, null));
            Assert.assertFalse(HubAuth.maySee(john));
            HubAuth.setViewer(new HubAuth.Identity("john@example.com", false, null));
            Assert.assertTrue(HubAuth.maySee(john));
        } finally {
            HubAuth.setViewer(null);
        }
        Assert.assertTrue(HubAuth.maySee(john));
    }

    @Test
    public void registryRefusesASessionWithoutItsUser() {
        Map<String, Object> attributes = new HashMap<>();
        ServletRequest request = request(Map.of(), attributes);
        Map<String, Object> capabilities = new HashMap<>(Map.of("platformName", "Android"));

        // without the authentication every request is taken
        MobileGridRegistry.verifyUser(request, capabilities, false, true);

        GridException none = Assert.expectThrows(GridException.class, () -> MobileGridRegistry.verifyUser(request, capabilities, true, true));
        Assert.assertTrue(none.getMessage().contains("users of MCloud"), none.getMessage());

        attributes.put(HubAuth.IDENTITY_ATTRIBUTE, new HubAuth.Identity("john@example.com", false, null));
        GridException noToken = Assert.expectThrows(GridException.class, () -> MobileGridRegistry.verifyUser(request, capabilities, true, true));
        Assert.assertTrue(noToken.getMessage().contains("access token"), noToken.getMessage());
        // without STF no token is needed
        MobileGridRegistry.verifyUser(request, capabilities, true, false);

        attributes.put(HubAuth.IDENTITY_ATTRIBUTE, new HubAuth.Identity("john@example.com", false, "token"));
        MobileGridRegistry.verifyUser(request, capabilities, true, true);
        capabilities.put("zebrunner:STF_TOKEN", "token");
        MobileGridRegistry.verifyUser(request, capabilities, true, true);
        capabilities.put("zebrunner:STF_TOKEN", "other");
        GridException other = Assert.expectThrows(GridException.class, () -> MobileGridRegistry.verifyUser(request, capabilities, true, true));
        Assert.assertTrue(other.getMessage().contains("zebrunner:STF_TOKEN"), other.getMessage());
        Assert.assertFalse(other.getMessage().contains("other"), other.getMessage());
        Assert.assertEquals(HubAuth.identity(request), Optional.of(attributes.get(HubAuth.IDENTITY_ATTRIBUTE)));
    }
}
