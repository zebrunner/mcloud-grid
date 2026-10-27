package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.util.SecretMasking;
import org.openqa.grid.internal.DefaultGridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.web.Hub;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * The registry of the hub ("registry" of the hub config, see generate_config).
 * A node names the class of its proxy itself, so a node with another one (e.g. DefaultRemoteProxy) would skip the
 * checks of MobileRemoteProxy: NODE_REGISTRATION_TOKEN, NODE_ALLOWED_NETWORKS, the reachability and STF.
 */
public class MobileGridRegistry extends DefaultGridRegistry {
    private static final Logger LOGGER = Logger.getLogger(MobileGridRegistry.class.getName());

    public MobileGridRegistry() {
        super();
    }

    public MobileGridRegistry(Hub hub) {
        super(hub);
    }

    @Override
    public void start() {
        super.start();
        if (!MobileRemoteProxy.isRegistrationTokenEnabled()) {
            LOGGER.warning("[CONFIGURATION] NODE_REGISTRATION_TOKEN is not set: any host that reaches the hub can register a node "
                    + "and get the sessions of its devices.");
        }
    }

    /**
     * Only a MobileRemoteProxy is added: it is checked before (its constructor), the others are rejected before they
     * replace a registered node with the same id.
     */
    @Override
    public void add(RemoteProxy proxy) {
        if (proxy == null) {
            return;
        }
        if (!(proxy instanceof MobileRemoteProxy)) {
            MobileRemoteProxy.warnAboutRejectedNode(String.valueOf(proxy.getRemoteHost()), String.format(
                    "Node %s registers with the proxy %s, only %s is accepted, so it is not registered.",
                    proxy.getRemoteHost(), proxy.getClass().getName(), MobileRemoteProxy.class.getName()));
            // the cleanup thread of the proxy is already started; DefaultRemoteProxy then fails to stop the polling it has not started
            try {
                proxy.teardown();
            } catch (RuntimeException e) {
                LOGGER.finest(() -> "Teardown of the rejected proxy: " + e);
            }
            return;
        }
        super.add(proxy);
    }

    /**
     * The queued session requests: the console of the hub (the only user) prints them as HTML.
     */
    @Override
    public Iterable<DesiredCapabilities> getDesiredCapabilities() {
        List<DesiredCapabilities> requests = new ArrayList<>();
        for (DesiredCapabilities request : super.getDesiredCapabilities()) {
            requests.add(new ConsoleCapabilities(request));
        }
        return requests;
    }

    /**
     * Capabilities of a session request without secrets, escaped as HTML by toString().
     */
    static final class ConsoleCapabilities extends DesiredCapabilities {
        private static final long serialVersionUID = 1L;

        ConsoleCapabilities(DesiredCapabilities request) {
            super(SecretMasking.mask(request.asMap()));
        }

        @Override
        public String toString() {
            return ProxyHtmlRenderer.escape(super.toString());
        }
    }
}
