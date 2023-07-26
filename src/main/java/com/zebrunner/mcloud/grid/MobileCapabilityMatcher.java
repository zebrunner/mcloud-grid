/*******************************************************************************
 * Copyright 2018-2021 Zebrunner (https://zebrunner.com/).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.validator.DeviceNameValidator;
import com.zebrunner.mcloud.grid.validator.DeviceTypeValidator;
import com.zebrunner.mcloud.grid.validator.MobilePlatformValidator;
import com.zebrunner.mcloud.grid.validator.PlatformVersionValidator;
import com.zebrunner.mcloud.grid.validator.UDIDValidator;
import com.zebrunner.mcloud.grid.validator.Validator;
import org.openqa.grid.internal.utils.DefaultCapabilityMatcher;
import org.openqa.selenium.remote.CapabilityType;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static com.zebrunner.mcloud.grid.util.CapabilityUtils.getAppiumCapability;

/**
 * Custom selenium capability matcher for mobile grid.
 * {@link https://nishantverma.gitbooks.io/appium-for-android/understanding_desired_capabilities.html}
 *
 * @author Alex Khursevich (alex@qaprosoft.com)
 */
public class MobileCapabilityMatcher extends DefaultCapabilityMatcher {
    private static final Logger LOGGER = Logger.getLogger(MobileCapabilityMatcher.class.getName());
    private final List<Validator> validators = List.of(
            new MobilePlatformValidator(),
            new PlatformVersionValidator(),
            new DeviceNameValidator(),
            new DeviceTypeValidator(),
            new UDIDValidator());

    @Override
    public boolean matches(Map<String, Object> nodeCapability, Map<String, Object> requestedCapability) {
        LOGGER.finest("Requested capabilities: " + requestedCapability);
        if (requestedCapability.containsKey(CapabilityType.PLATFORM_NAME) ||
                getAppiumCapability(requestedCapability, "platformVersion").isPresent() ||
                getAppiumCapability(requestedCapability, "deviceName").isPresent() ||
                getAppiumCapability(requestedCapability, "udid").isPresent()) {
            // Mobile-based capabilities
            LOGGER.fine("Using extensionCapabilityCheck matcher.");
            return extensionCapabilityCheck(nodeCapability, requestedCapability);
        } else {
            // Browser-based capabilities
            LOGGER.fine("Using default browser-based capabilities matcher.");
            return super.matches(nodeCapability, requestedCapability);
        }
    }

    /**
     * Verifies matching between requested and actual node capabilities.
     *
     * @param nodeCapabilities      - Selenium node capabilities
     * @param requestedCapabilities - capabilities requested by Selenium client
     * @return match results
     */
    private boolean extensionCapabilityCheck(Map<String, Object> nodeCapabilities,
            Map<String, Object> requestedCapabilities) {
        return nodeCapabilities != null &&
                requestedCapabilities != null &&
                validators.stream()
                        .allMatch(v -> v.apply(nodeCapabilities, requestedCapabilities));
    }

}