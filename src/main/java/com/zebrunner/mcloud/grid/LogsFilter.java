package com.zebrunner.mcloud.grid;

import org.apache.commons.lang3.StringUtils;

import java.util.logging.Filter;
import java.util.logging.LogRecord;

public class LogsFilter implements Filter {

    public boolean isLoggable(LogRecord record) {
        // do not log this exception
        return !StringUtils.containsIgnoreCase(record.getMessage(), "timed out waiting for a node to become available");
    }
}
