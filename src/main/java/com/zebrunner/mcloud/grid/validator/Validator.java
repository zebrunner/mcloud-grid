package com.zebrunner.mcloud.grid.validator;

import java.util.Map;
import java.util.function.BiFunction;

public interface Validator extends BiFunction<Map<String, Object>, Map<String, Object>, Boolean> {

    default boolean anything(String requested) {
        return requested == null || "ANY".equalsIgnoreCase(requested) || "".equals(requested) || "*".equals(requested);
    }
}
