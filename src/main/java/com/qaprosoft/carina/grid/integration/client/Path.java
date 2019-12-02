/*******************************************************************************
 * Copyright 2013-2019 Qaprosoft (http://www.qaprosoft.com).
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
package com.qaprosoft.carina.grid.integration.client;

public enum Path {

    DEVICES_PATH("/api/v1/devices"),
    DEVICES_ITEM_PATH("/api/v1/devices/%s"),
    USER_DEVICES_PATH("/api/v1/user/devices"),
    USER_DEVICES_BY_ID_PATH("/api/v1/user/devices/%s"),
    USER_DEVICES_REMOTE_CONNECT_PATH("/api/v1/user/devices/%s/remoteConnect");
    
    private final String relativePath;

    Path(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String build(String serviceUrl, Object... parameters) {
        return serviceUrl + String.format(relativePath, parameters);
    }
    
}
