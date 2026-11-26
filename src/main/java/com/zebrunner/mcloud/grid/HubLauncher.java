package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.servlets.ProxyInfo;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.openqa.grid.selenium.GridLauncherV3;
import org.openqa.grid.shared.Stoppable;
import org.openqa.grid.web.Hub;

import java.lang.reflect.Field;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The main class of the image: starts the hub as GridLauncherV3 does, then puts {@link ProxyInfo} in place of the node API
 * of Selenium (/grid/api/proxy), the error pages of {@link HubErrorHandler} in place of the ones of Jetty, and adds
 * {@link HubAuthFilter} when the authentication of the users is on (GRID_AUTH_SECRET). A hub that could not be changed so
 * stops: it must not run without them.
 */
public final class HubLauncher {
    private static final Logger LOGGER = Logger.getLogger(HubLauncher.class.getName());

    private HubLauncher() {
        //hide
    }

    public static void main(String[] args) {
        HubAuth auth = HubAuth.get();
        Stoppable launched = new GridLauncherV3().launch(args);
        if (!(launched instanceof Hub)) {
            return;
        }
        try {
            restrict((Hub) launched);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "[CONFIGURATION] The node API and the error pages of the hub could not be restricted, the hub is stopped.", e);
            launched.stop();
            System.exit(1);
        }
        if (!auth.isEnabled()) {
            return;
        }
        try {
            HubAuthFilter.install((Hub) launched, auth);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "[AUTHENTICATION] The authentication of the users could not be added to the hub, the hub is stopped.", e);
            launched.stop();
            System.exit(1);
        }
    }

    /**
     * Puts {@link ProxyInfo} and {@link HubErrorHandler} into the started hub.
     *
     * @throws ReflectiveOperationException when the hub is not the one of Selenium 3.141.59 (its Jetty server is a private
     *         field)
     */
    static void restrict(Hub hub) throws ReflectiveOperationException {
        Server server = server(hub);
        ServletContextHandler context = (ServletContextHandler) server.getHandler();
        HubErrorHandler.install(server, context);
        ProxyInfo.install(context, hub.getRegistry());
    }

    /**
     * @return the Jetty server of the started hub, with its servlet context as the handler
     * @throws ReflectiveOperationException when the hub is not the one of Selenium 3.141.59 (its Jetty server is a private
     *         field)
     */
    static Server server(Hub hub) throws ReflectiveOperationException {
        Field field = Hub.class.getDeclaredField("server");
        field.setAccessible(true);
        Server server = (Server) field.get(hub);
        if (server == null || !(server.getHandler() instanceof ServletContextHandler)) {
            throw new IllegalStateException("The hub has no started Jetty context");
        }
        return server;
    }
}
