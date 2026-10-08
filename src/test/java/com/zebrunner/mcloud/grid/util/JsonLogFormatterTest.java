package com.zebrunner.mcloud.grid.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public class JsonLogFormatterTest {
    private final JsonLogFormatter formatter = new JsonLogFormatter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void extractsDeviceContextAndEscapesMessage() throws IOException {
        LogRecord record = new LogRecord(Level.INFO, "[emulator-5554][session-1] Device \"Pixel\"\nstarted");
        record.setLoggerName("com.zebrunner.mcloud.grid.MobileRemoteProxy");
        record.setInstant(Instant.EPOCH);

        String line = formatter.format(record);
        Assert.assertEquals(line.split("\n").length, 1);
        JsonNode json = mapper.readTree(line);
        Assert.assertEquals(json.get("timestamp").asText(), "1970-01-01T00:00:00Z");
        Assert.assertEquals(json.get("category").asText(), "device");
        Assert.assertEquals(json.get("component").asText(), "mcloud-grid");
        Assert.assertEquals(json.get("udid").asText(), "emulator-5554");
        Assert.assertEquals(json.get("sessionId").asText(), "session-1");
        Assert.assertEquals(json.get("message").asText(), "Device \"Pixel\"\nstarted");
    }

    @Test
    public void formatsSeleniumRecordAndExceptionOnOneLine() throws IOException {
        LogRecord record = new LogRecord(Level.WARNING, "Node failed");
        record.setLoggerName("org.openqa.grid.internal.TestSlot");
        record.setThrown(new IllegalStateException("failure\nreason"));

        String line = formatter.format(record);
        Assert.assertEquals(line.split("\n").length, 1);
        JsonNode json = mapper.readTree(line);
        Assert.assertEquals(json.get("component").asText(), "selenium");
        Assert.assertEquals(json.get("category").asText(), "grid");
        Assert.assertFalse(json.has("udid"));
        Assert.assertTrue(json.get("exception").asText().contains("failure\nreason"));
    }

    @Test
    public void extractsDeviceWithoutSessionButKeepsConfigurationAsGrid() throws IOException {
        LogRecord device = new LogRecord(Level.FINE, "[emulator-5554] Device is ignored");
        device.setLoggerName("com.zebrunner.mcloud.grid.MobileRemoteProxy");
        JsonNode deviceJson = mapper.readTree(formatter.format(device));
        Assert.assertEquals(deviceJson.get("category").asText(), "device");
        Assert.assertEquals(deviceJson.get("udid").asText(), "emulator-5554");
        Assert.assertFalse(deviceJson.has("sessionId"));
        Assert.assertEquals(deviceJson.get("message").asText(), "Device is ignored");

        LogRecord configuration = new LogRecord(Level.INFO, "[CONFIGURATION] Grid started");
        configuration.setLoggerName(device.getLoggerName());
        JsonNode configJson = mapper.readTree(formatter.format(configuration));
        Assert.assertEquals(configJson.get("category").asText(), "grid");
        Assert.assertFalse(configJson.has("udid"));
    }
}
