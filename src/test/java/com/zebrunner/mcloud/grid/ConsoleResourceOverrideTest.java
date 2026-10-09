package com.zebrunner.mcloud.grid;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ConsoleResourceOverrideTest {

    @Test
    public void consoleJavascriptDefaultsToInfoAndRenamesHeader() throws Exception {
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("org/openqa/grid/images/consoleservlet.js")) {
            Assert.assertNotNull(stream, "Missing overridden console JS resource");
            String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Assert.assertTrue(script.contains("show($(this), 'info');"), script);
            Assert.assertTrue(script.contains("$('#header h2').text('MCloud-grid');"), script);
            Assert.assertTrue(script.contains("document.title = 'MCloud-grid';"), script);
        }
    }
}

