package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.util.CapabilityUtils;
import org.apache.commons.lang3.StringUtils;
import org.openqa.grid.common.exception.GridException;

import java.util.Map;

/**
 * The Playwright sessions of the grid (README.md#playwright): Appium sessions of the 'Playwright' automation of
 * zebrunner/appium, with the WebSocket of {@link com.zebrunner.mcloud.grid.servlets.PlaywrightServlet}. They are an
 * option of the farm, off by default: PLAYWRIGHT_ENABLED=true.
 */
public final class PlaywrightSessions {
    public static final String AUTOMATION_NAME = "Playwright";
    public static final String DISABLED_MESSAGE = "Playwright sessions are not enabled on this grid: "
            + "set PLAYWRIGHT_ENABLED=true of the hub (variables.env of MCloud) to run them";

    private static volatile boolean enabled = "true".equalsIgnoreCase(StringUtils.trimToEmpty(System.getenv("PLAYWRIGHT_ENABLED")));

    private PlaywrightSessions() {
        //hide
    }

    /**
     * @return PLAYWRIGHT_ENABLED of the hub
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Turns the option on or off instead of PLAYWRIGHT_ENABLED (tests).
     */
    static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * @return true when the capabilities request a Playwright session ('appium:automationName' Playwright)
     */
    public static boolean isRequested(Map<String, Object> capabilities) {
        return capabilities != null && CapabilityUtils.getAppiumCapability(capabilities, "automationName")
                .map(String::valueOf)
                .map(name -> AUTOMATION_NAME.equalsIgnoreCase(name.trim()))
                .orElse(false);
    }

    /**
     * Refuses a Playwright session when the option is off: before the queue, no device is reserved for it.
     *
     * @throws GridException with {@link #DISABLED_MESSAGE}
     */
    public static void verifyAllowed(Map<String, Object> capabilities) {
        if (!enabled && isRequested(capabilities)) {
            throw new GridException(DISABLED_MESSAGE);
        }
    }
}
