package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.integration.client.STFClient;
import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import org.openqa.grid.internal.DefaultGridRegistry;
import org.openqa.grid.web.servlet.handler.RequestHandler;

import java.util.logging.Logger;

public class CustomGridRegistry extends DefaultGridRegistry {
    private static final Logger LOGGER = Logger.getLogger(CustomGridRegistry.class.getName());
    private static final String STF_CLIENT = "STF_CLIENT";

    public boolean removeNewSessionRequest(RequestHandler request) {
        LOGGER.info("removeNewSessionRequest call");
        STFClient client = (STFClient) request.getSession().get(STF_CLIENT);
        Object udid = CapabilityUtils.getAppiumCapability(request.getSession().getSlot().getCapabilities(), "udid").orElse(null);

        if (udid == null) {
            LOGGER.warning(String.format("There are no udid in slot capabilities. Device could not be returned to the STF. Capabilities: %s",
                    request.getSession().getSlot().getCapabilities()));
        }

        boolean isReturned = client.returnDevice(String.valueOf(udid), request.getSession().getRequestedCapabilities());
        if (!isReturned) {
            LOGGER.warning(
                    String.format("Device could not be returned to the STF. Capabilities: %s", request.getSession().getSlot().getCapabilities()));
        }

        return super.removeNewSessionRequest(request);
    }
}
