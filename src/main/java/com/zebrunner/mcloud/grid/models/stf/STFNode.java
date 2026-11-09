package com.zebrunner.mcloud.grid.models.stf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A node of the STF node registry (GET /api/v1/nodes) or the answer of POST /api/v1/nodes/verify.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class STFNode {
    private String label;
    private String address;
    private String status;
}
