package com.zebrunner.mcloud.grid;

import org.openqa.grid.selenium.GridLauncherV3;
import org.openqa.grid.shared.Stoppable;
import org.openqa.grid.web.Hub;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The main class of the image: starts the hub as GridLauncherV3 does, then adds {@link HubAuthFilter} to it when the
 * authentication of the users is on (GRID_AUTH_SECRET). A hub whose filter could not be added stops: it must not run
 * without the authentication it was configured with.
 */
public final class HubLauncher {
    private static final Logger LOGGER = Logger.getLogger(HubLauncher.class.getName());

    private HubLauncher() {
        //hide
    }

    public static void main(String[] args) {
        HubAuth auth = HubAuth.get();
        Stoppable launched = new GridLauncherV3().launch(args);
        if (!(launched instanceof Hub) || !auth.isEnabled()) {
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
}
