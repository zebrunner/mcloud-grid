package com.zebrunner.mcloud.grid.util;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

/** Selenium 3 unconditionally replaces ConsoleHandler's formatter; use a distinct handler for JSON. */
public final class JsonConsoleHandler extends StreamHandler {
    public JsonConsoleHandler() {
        super(System.err, new JsonLogFormatter());
        setLevel(Level.ALL);
    }

    @Override
    public synchronized void publish(LogRecord record) {
        super.publish(record);
        flush();
    }

    @Override
    public synchronized void close() {
        flush(); // Do not close stderr, shared with the rest of the process.
    }
}
