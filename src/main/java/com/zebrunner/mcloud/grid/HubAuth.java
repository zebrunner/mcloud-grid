package com.zebrunner.mcloud.grid;

import org.apache.commons.lang3.StringUtils;
import org.openqa.grid.internal.TestSession;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The authentication of the users of the hub (README.md#authentication-of-the-users), on with GRID_AUTH_SECRET.
 * <p>
 * stf-proxy of MCloud asks STF about every request of the grid (its auth_request, GET /api/v1/zebrunner/hub-auth of
 * STF) and passes the user STF answered on in the headers {@link #USER_HEADER}, {@link #ADMIN_HEADER} and
 * {@link #TOKEN_HEADER} (the STF access token of the user), together with GRID_AUTH_SECRET in {@link #SECRET_HEADER}:
 * the hub trusts these headers only with the secret. {@link HubAuthFilter} checks every request against
 * {@link #access(String)}, the sessions belong to their user ({@link #OWNER}) and the devices are reserved in STF with
 * the token of the user. None of these headers, nor the Authorization and the Cookie of the client, reach a node.
 * <p>
 * Without GRID_AUTH_SECRET every request is let in as before (a hub without MCloud): the hub logs it as a warning.
 */
public final class HubAuth {
    private static final Logger LOGGER = Logger.getLogger(HubAuth.class.getName());

    public static final String SECRET_HEADER = "X-MCloud-Auth";
    public static final String USER_HEADER = "X-MCloud-User";
    public static final String ADMIN_HEADER = "X-MCloud-Admin";
    public static final String TOKEN_HEADER = "X-MCloud-Token";
    /** The headers of the client and of stf-proxy the nodes never get. */
    static final Set<String> HIDDEN_HEADERS = Set.of("authorization", "cookie", SECRET_HEADER.toLowerCase(Locale.ROOT),
            USER_HEADER.toLowerCase(Locale.ROOT), ADMIN_HEADER.toLowerCase(Locale.ROOT), TOKEN_HEADER.toLowerCase(Locale.ROOT));

    /** The user of the request, an attribute set by {@link HubAuthFilter}. */
    public static final String IDENTITY_ATTRIBUTE = HubAuth.class.getName() + ".identity";
    /** The user of a session (its email), an object of the session. */
    public static final String OWNER = "MCLOUD_OWNER";
    /** The STF access token of the user of a session, an object of the session. */
    public static final String TOKEN = "MCLOUD_TOKEN";

    // a secret shorter than that is guessed
    static final int MIN_SECRET_LENGTH = 32;

    private static volatile HubAuth current = fromEnv(System.getenv("GRID_AUTH_SECRET"));

    private final byte[] secret;

    private HubAuth(String secret) {
        this.secret = secret == null ? null : secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @return the authentication of the secret, off for an empty one; a secret shorter than {@link #MIN_SECRET_LENGTH}
     *         is refused
     */
    static HubAuth fromEnv(String secret) {
        if (StringUtils.isEmpty(secret)) {
            return new HubAuth(null);
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(String.format("GRID_AUTH_SECRET has %s characters, at least %s are needed.",
                    secret.length(), MIN_SECRET_LENGTH));
        }
        return new HubAuth(secret);
    }

    public static HubAuth get() {
        return current;
    }

    /**
     * Replaces the authentication of GRID_AUTH_SECRET, for tests.
     */
    static void use(HubAuth auth) {
        current = auth;
    }

    public boolean isEnabled() {
        return secret != null;
    }

    /**
     * Logs the authentication of the hub; MobileGridRegistry logs it when the hub starts.
     */
    static void logConfiguration() {
        if (get().isEnabled()) {
            LOGGER.info("[CONFIGURATION] Authentication of the users: on (GRID_AUTH_SECRET), the requests of the hub come through stf-proxy "
                    + "of MCloud only.");
        } else {
            LOGGER.warning("[CONFIGURATION] Authentication of the users: off (GRID_AUTH_SECRET is not set). Anyone who reaches the hub "
                    + "starts sessions on its devices; MCloud sets GRID_AUTH_SECRET.");
        }
    }

    /**
     * @return the user of the headers of stf-proxy, empty without the secret or the user
     */
    Optional<Identity> authenticate(HttpServletRequest request) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        String given = request.getHeader(SECRET_HEADER);
        if (given == null || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        String user = StringUtils.trimToNull(request.getHeader(USER_HEADER));
        if (user == null) {
            return Optional.empty();
        }
        return Optional.of(new Identity(user, "true".equalsIgnoreCase(request.getHeader(ADMIN_HEADER)),
                StringUtils.trimToNull(request.getHeader(TOKEN_HEADER))));
    }

    /**
     * @return the user {@link HubAuthFilter} let the request in for, empty without the authentication
     */
    public static Optional<Identity> identity(ServletRequest request) {
        Object identity = request.getAttribute(IDENTITY_ATTRIBUTE);
        return identity instanceof Identity ? Optional.of((Identity) identity) : Optional.empty();
    }

    /**
     * Who may call a path of the hub (the servlet path and the path info of the request).
     */
    public enum Access {
        /** the nodes (their registration has a node token of its own) and the healthcheck */
        ANYONE,
        /** a user: the hub, the console and the session routes */
        USER,
        /** an admin: the metrics and the APIs of the hub */
        ADMIN
    }

    static Access access(String path) {
        String value = StringUtils.defaultString(path);
        if (value.equals("/wd/hub/status") || value.equals("/status") || value.equals("/grid/register") || value.startsWith("/grid/register/")
                || value.equals("/grid/api/proxy") || value.equals("/grid/api/health")) {
            return Access.ANYONE;
        }
        if (under(value, "/wd/hub") || under(value, "/grid/console") || under(value, "/grid/resources")
                || under(value, "/grid/admin/DevicesServlet") || under(value, "/grid/admin/AllSessionsServlet")
                || under(value, "/grid/admin/TerminateSessionServlet") || under(value, "/grid/admin/PlaywrightServlet")) {
            return Access.USER;
        }
        return Access.ADMIN;
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * Gives the session to the user of its request: {@link #OWNER} and {@link #TOKEN}.
     */
    static void bind(ServletRequest request, TestSession session) {
        identity(request).ifPresent(identity -> {
            session.put(OWNER, identity.getUser());
            if (identity.getToken() != null) {
                session.put(TOKEN, identity.getToken());
            }
        });
    }

    /**
     * @return true if the user may use the session: its owner or an admin; a session without an owner is for an admin
     */
    public static boolean mayUse(Identity identity, TestSession session) {
        return identity.isAdmin() || identity.getUser().equals(session.get(OWNER));
    }

    /**
     * The user of the request the thread answers, for the console (its renderers have no request).
     */
    private static final ThreadLocal<Identity> VIEWER = new ThreadLocal<>();

    static void setViewer(Identity identity) {
        if (identity == null) {
            VIEWER.remove();
        } else {
            VIEWER.set(identity);
        }
    }

    /**
     * @return true if the user of the request the thread answers may see the details of the session (its id, its last
     *         command, its capabilities): its owner or an admin; anyone without the authentication of the users
     */
    public static boolean maySee(TestSession session) {
        Identity viewer = VIEWER.get();
        return viewer == null || mayUse(viewer, session);
    }

    /**
     * @return the user of a session, null without the authentication of the users
     */
    public static String owner(TestSession session) {
        Object owner = session.get(OWNER);
        return owner == null ? null : String.valueOf(owner);
    }

    /**
     * A user of the hub, as STF answered stf-proxy.
     */
    public static final class Identity {
        private final String user;
        private final boolean admin;
        private final String token;

        public Identity(String user, boolean admin, String token) {
            this.user = user;
            this.admin = admin;
            this.token = token;
        }

        /**
         * @return the email of the user in STF
         */
        public String getUser() {
            return user;
        }

        public boolean isAdmin() {
            return admin;
        }

        /**
         * @return the STF access token of the request, null for a request with the session cookie of STF (the console)
         */
        public String getToken() {
            return token;
        }

        @Override
        public String toString() {
            return user + (admin ? " (admin)" : "");
        }
    }
}
