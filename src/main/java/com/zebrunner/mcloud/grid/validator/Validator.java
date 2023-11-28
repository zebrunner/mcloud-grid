package com.zebrunner.mcloud.grid.validator;

import org.openqa.selenium.Capabilities;

import java.util.function.BiFunction;

public interface Validator extends BiFunction<Capabilities, Capabilities, Boolean> {

    default boolean anything(String requested) {
        return requested == null || "ANY".equalsIgnoreCase(requested) || "".equals(requested) || "*".equals(requested);
    }
}
