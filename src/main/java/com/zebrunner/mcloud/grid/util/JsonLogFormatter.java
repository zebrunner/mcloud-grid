package com.zebrunner.mcloud.grid.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One JSON object per JUL record, including Selenium's records on the root logger. */
public final class JsonLogFormatter extends Formatter {
    private static final JsonFactory JSON = new JsonFactory();
    private static final Pattern DEVICE_CONTEXT = Pattern.compile("^\\[([^]\\r\\n]+)]\\[([^]\\r\\n]+)](?:\\s+|$)");
    private static final Pattern DEVICE_ONLY = Pattern.compile("^\\[([^]\\r\\n]+)](?:\\s+|$)");

    @Override
    public String format(LogRecord record) {
        String message = formatMessage(record);
        if (message == null) {
            message = "";
        }
        String logger = record.getLoggerName() == null ? "" : record.getLoggerName();
        Matcher context = DEVICE_CONTEXT.matcher(message);
        boolean deviceEvent = context.lookingAt();
        Matcher deviceOnly = DEVICE_ONLY.matcher(message);
        boolean deviceWithoutSession = !deviceEvent && logger.startsWith("com.zebrunner.")
                && deviceOnly.lookingAt() && !"CONFIGURATION".equals(deviceOnly.group(1));
        StringWriter output = new StringWriter();
        try (JsonGenerator json = JSON.createGenerator(output)) {
            json.writeStartObject();
            json.writeStringField("timestamp", Instant.ofEpochMilli(record.getMillis()).toString());
            json.writeStringField("level", record.getLevel().getName());
            json.writeStringField("logger", logger);
            json.writeStringField("component", logger.startsWith("com.zebrunner.") ? "mcloud-grid"
                    : logger.startsWith("org.openqa.") ? "selenium" : "dependency");
            json.writeStringField("category", deviceEvent || deviceWithoutSession ? "device" : "grid");
            if (deviceEvent) {
                json.writeStringField("udid", context.group(1));
                json.writeStringField("sessionId", context.group(2));
                message = message.substring(context.end());
            } else if (deviceWithoutSession) {
                json.writeStringField("udid", deviceOnly.group(1));
                message = message.substring(deviceOnly.end());
            }
            json.writeStringField("message", message);
            if (record.getThrown() != null) {
                StringWriter stackTrace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(stackTrace));
                json.writeStringField("exception", stackTrace.toString());
            }
            json.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException("Could not format log record", e);
        }
        return output + System.lineSeparator();
    }
}
