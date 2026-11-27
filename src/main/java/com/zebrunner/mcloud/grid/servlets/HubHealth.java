package com.zebrunner.mcloud.grid.servlets;

import com.zebrunner.mcloud.grid.MobileGridRegistry;
import com.zebrunner.mcloud.grid.NodeRegistrations;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.web.servlet.RegistryBasedServlet;
import org.openqa.selenium.json.Json;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * The health of the hub for the healthcheck of the image, {@code GET /grid/api/health}: 200 while the hub registers the
 * nodes (no registration hangs, see {@link NodeRegistrations#problem()}) and its registry works (its lock is free within
 * {@link #REGISTRY_TIMEOUT} and its matcher runs), 503 with the reason otherwise. The request is answered by a thread of
 * Jetty like every other one, so a hub whose threads are all busy does not answer it either. It asks no node and no STF.
 */
public class HubHealth extends RegistryBasedServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(HubHealth.class.getName());
    static final String NAME = "HubHealth";
    public static final String PATH = "/grid/api/health";
    static final Duration REGISTRY_TIMEOUT = Duration.ofSeconds(2);
    private static final Json JSON = new Json();

    private final transient NodeRegistrations registrations;
    private final AtomicBoolean unhealthy = new AtomicBoolean();

    public HubHealth(GridRegistry registry, NodeRegistrations registrations) {
        super(registry);
        this.registrations = registrations;
    }

    /**
     * Maps the servlet to {@link #PATH} of the context of the hub; the context may be started.
     */
    public static void install(ServletContextHandler context, GridRegistry registry, NodeRegistrations registrations) {
        HubServlets.map(context, NAME, new HubHealth(registry, registrations), PATH);
    }

    /**
     * @return why the hub is not healthy, empty when it is
     */
    Optional<String> problem() {
        Optional<String> registration = registrations.problem();
        if (registration.isPresent()) {
            return registration;
        }
        GridRegistry registry = getRegistry();
        if (registry instanceof MobileGridRegistry && !((MobileGridRegistry) registry).respondsWithin(REGISTRY_TIMEOUT)) {
            return Optional.of(String.format("the registry of the hub is locked for more than %ss or its matcher stopped: "
                    + "the hub does not match the session requests nor registers the nodes", REGISTRY_TIMEOUT.toSeconds()));
        }
        return Optional.empty();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<String> problem = problem();
        Map<String, Object> answer = new TreeMap<>();
        answer.put("healthy", problem.isEmpty());
        problem.ifPresent(reason -> answer.put("message", "The hub is not healthy: " + reason + ". Restart it: docker restart mcloud-grid."));
        if (problem.isPresent() && unhealthy.compareAndSet(false, true)) {
            LOGGER.severe(() -> "[HEALTH] The hub is not healthy: " + problem.get() + ". Docker does not restart an unhealthy container: "
                    + "restart it (docker restart mcloud-grid).");
        } else if (problem.isEmpty() && unhealthy.compareAndSet(true, false)) {
            LOGGER.info("[HEALTH] The hub is healthy again.");
        }
        response.setStatus(problem.isPresent() ? HttpServletResponse.SC_SERVICE_UNAVAILABLE : HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.toJson(answer));
    }
}
