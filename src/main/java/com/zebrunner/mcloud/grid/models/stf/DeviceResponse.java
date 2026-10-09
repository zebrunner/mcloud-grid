package com.zebrunner.mcloud.grid.models.stf;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response of GET /api/v1/devices/{serial}.
 */
@Getter
@Setter
@NoArgsConstructor
public class DeviceResponse {
    private Boolean success;
    private STFDevice device;
}
