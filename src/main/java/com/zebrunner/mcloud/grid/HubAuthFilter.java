package com.zebrunner.mcloud.grid;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.Source;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.web.Hub;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lets a request of the hub in for its user only, see {@link HubAuth}: 401 without the user of stf-proxy, 403 for a
 * user on an admin route, 404 for a session of another user (as an unknown session: whether it exists is not told).
 * <p>
 * The request goes on without the headers of {@link HubAuth#HIDDEN_HEADERS}: the hub copies the headers of a command
 * to the node of its session, which must not get the token of the user, the secret of stf-proxy or the cookie.
 * <p>
 * The hub of Selenium 3 has no setting for a filter: {@link #install(Hub)} adds it to the Jetty context of the started
 * hub ({@link HubLauncher}). Until then the registry refuses the new sessions without a user.
 */
public final class HubAuthFilter implements Filter {
    private static final Logger LOGGER = Logger.getLogger(HubAuthFilter.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String NAME = "HubAuthFilter";
    // the WebDriver commands and the Playwright WebSocket of a session
    private static final Pattern SESSION_PATH = Pattern.compile("^(?:/wd/hub/session|/grid/admin/PlaywrightServlet)/([^/]+)(?:/.*)?$");

    private final HubAuth auth;
    private final Function<ServletRequest, GridRegistry> registry;

    HubAuthFilter(HubAuth auth, Function<ServletRequest, GridRegistry> registry) {
        this.auth = auth;
        this.registry = registry;
    }

    /**
     * Adds the filter in front of every servlet of the started hub.
     *
     * @throws ReflectiveOperationException when the hub is not the one of Selenium 3.141.59 (its Jetty server is a private
     *         field)
     */
    static void install(Hub hub, HubAuth auth) throws ReflectiveOperationException {
        Field field = Hub.class.getDeclaredField("server");
        field.setAccessible(true);
        Server server = (Server) field.get(hub);
        if (server == null || !(server.getHandler() instanceof ServletContextHandler)) {
            throw new IllegalStateException("The hub has no started Jetty context");
        }
        install((ServletContextHandler) server.getHandler(), auth);
    }

    /**
     * Adds the filter in front of every servlet of the context, which may be started.
     */
    static void install(ServletContextHandler context, HubAuth auth) {
        ServletHandler handler = context.getServletHandler();
        HubAuthFilter filter = new HubAuthFilter(auth, request -> (GridRegistry) request.getServletContext().getAttribute(GridRegistry.KEY));
        FilterHolder holder = handler.newFilterHolder(Source.EMBEDDED);
        holder.setName(NAME);
        holder.setFilter(filter);
        handler.addFilter(holder);
        FilterMapping mapping = new FilterMapping();
        mapping.setFilterName(NAME);
        mapping.setPathSpec("/*");
        mapping.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        handler.prependFilterMapping(mapping);
        LOGGER.info("[AUTHENTICATION] The hub lets in the users of stf-proxy only.");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String path = StringUtils.defaultString(request.getServletPath()) + StringUtils.defaultString(request.getPathInfo());
        HubAuth.Access access = HubAuth.access(path);
        Optional<HubAuth.Identity> identity = auth.authenticate(request);
        if (access != HubAuth.Access.ANYONE) {
            if (identity.isEmpty()) {
                LOGGER.fine(() -> String.format("[AUTHENTICATION] %s %s is refused: not a user of stf-proxy (from %s).", request.getMethod(), path,
                        request.getRemoteAddr()));
                refuse(response, HttpServletResponse.SC_UNAUTHORIZED, "unknown error",
                        "The hub takes the requests of the users of MCloud: the STF access token in the URL of the hub, "
                                + "https://<email>:<token>@<host>/mcloud/wd/hub");
                return;
            }
            if (access == HubAuth.Access.ADMIN && !identity.get().isAdmin()) {
                LOGGER.info(() -> String.format("[AUTHENTICATION] %s %s is refused to %s: an admin route.", request.getMethod(), path, identity.get()));
                refuse(response, HttpServletResponse.SC_FORBIDDEN, "unknown error", "Only an admin of STF may use this route.");
                return;
            }
            Optional<String> foreign = foreignSession(request, path, identity.get());
            if (foreign.isPresent()) {
                LOGGER.warning(() -> String.format("[AUTHENTICATION] %s %s is refused to %s: the session of another user.", request.getMethod(), path,
                        identity.get()));
                refuse(response, HttpServletResponse.SC_NOT_FOUND, "invalid session id", String.format("No active session with ID %s", foreign.get()));
                return;
            }
        }
        identity.ifPresent(user -> request.setAttribute(HubAuth.IDENTITY_ATTRIBUTE, user));
        HubAuth.setViewer(identity.orElse(null));
        try {
            chain.doFilter(new HiddenHeaders(request), response);
        } finally {
            HubAuth.setViewer(null);
        }
    }

    /**
     * @return the id of the session of the path when it is a session of another user
     */
    private Optional<String> foreignSession(HttpServletRequest request, String path, HubAuth.Identity identity) {
        Matcher matcher = SESSION_PATH.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String id = matcher.group(1);
        GridRegistry grid = registry.apply(request);
        if (grid == null) {
            return Optional.empty();
        }
        for (TestSession session : grid.getActiveSessions()) {
            boolean same = id.equals(session.getInternalKey()) || session.getExternalKey() != null && id.equals(session.getExternalKey().getKey());
            if (same) {
                return HubAuth.mayUse(identity, session) ? Optional.empty() : Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /**
     * Answers with a WebDriver error, which the clients of the hub show.
     */
    private static void refuse(HttpServletResponse response, int status, String error, String message) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("error", error);
        value.put("message", message);
        value.put("stacktrace", "");
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(Map.of("value", value)));
    }

    /**
     * The request without the headers of {@link HubAuth#HIDDEN_HEADERS}.
     */
    static final class HiddenHeaders extends HttpServletRequestWrapper {
        HiddenHeaders(HttpServletRequest request) {
            super(request);
        }

        private static boolean hidden(String name) {
            return name != null && HubAuth.HIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String getHeader(String name) {
            return hidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !hidden(name))
                    .collect(Collectors.toList());
            return Collections.enumeration(names);
        }

        @Override
        public long getDateHeader(String name) {
            return hidden(name) ? -1 : super.getDateHeader(name);
        }

        @Override
        public int getIntHeader(String name) {
            return hidden(name) ? -1 : super.getIntHeader(name);
        }

        @Override
        public javax.servlet.http.Cookie[] getCookies() {
            return null;
        }
    }
}
