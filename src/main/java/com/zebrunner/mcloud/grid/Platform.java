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

import java.util.Locale;
import java.util.Map;

/**
 * Platforms available in Selenium Grid.
 *
 * @author Alex Khursevich (alex@qaprosoft.com)
 */
public enum Platform {
    ANY,
    ANDROID,
    IOS,
    WINDOWS,
    MAC,
    TVOS,
    LINUX;

    /**
     * Retrieves platform type from capabilities.
     *
     * @param cap - desired capabilities
     * @return platform, {@link #ANY} if it is missing or unknown
     */
    public static Platform fromCapabilities(Map<String, Object> cap) {
        if (cap == null || cap.get("platformName") == null) {
            return Platform.ANY;
        }
        String name = cap.get("platformName").toString().trim().toUpperCase(Locale.ROOT);
        for (Platform platform : values()) {
            if (platform.name().equals(name)) {
                return platform;
            }
        }
        // platforms without dedicated handling (Tizen, webOS, ...)
        return Platform.ANY;
    }
}
