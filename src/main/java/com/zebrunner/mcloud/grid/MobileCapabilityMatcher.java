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
import com.zebrunner.mcloud.grid.validator.ProxyValidator;
import com.zebrunner.mcloud.grid.validator.UDIDValidator;
import com.zebrunner.mcloud.grid.validator.Validator;
import org.openqa.grid.internal.utils.DefaultCapabilityMatcher;

import java.util.List;
import java.util.Map;

/**
 * Custom selenium capability matcher for mobile grid.
 *
 * @author Alex Khursevich (alex@qaprosoft.com)
 */
public class MobileCapabilityMatcher extends DefaultCapabilityMatcher {
    private static final List<Validator> VALIDATORS = List.of(
            new MobilePlatformValidator(),
            new DeviceNameValidator(),
            new DeviceTypeValidator(),
            new PlatformVersionValidator(),
            new UDIDValidator(),
            new ProxyValidator());

    @Override
    public boolean matches(Map<String, Object> nodeCapabilities, Map<String, Object> requestedCapabilities) {
        return nodeCapabilities != null &&
                requestedCapabilities != null &&
                VALIDATORS.stream()
                        .allMatch(v -> v.apply(nodeCapabilities, requestedCapabilities));
    }
}
