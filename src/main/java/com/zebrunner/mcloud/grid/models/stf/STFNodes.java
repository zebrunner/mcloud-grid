package com.zebrunner.mcloud.grid.models.stf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * The answer of GET /api/v1/nodes of STF.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class STFNodes {
    private List<STFNode> nodes;
}
