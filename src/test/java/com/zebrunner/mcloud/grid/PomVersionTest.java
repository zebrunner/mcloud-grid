package com.zebrunner.mcloud.grid;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version of pom.xml is the release in work of the file VERSION (CONVENTIONS.md#versions); maven runs the tests in
 * the directory of the project.
 */
public class PomVersionTest {
    private static final Pattern PROJECT_VERSION = Pattern.compile("<artifactId>mcloud-grid</artifactId>\\s*(?:<!--.*?-->\\s*)?<version>([^<]+)</version>");

    @Test
    public void pomVersionIsTheReleaseInWork() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher matcher = PROJECT_VERSION.matcher(pom);
        Assert.assertTrue(matcher.find(), "the version of the project in pom.xml");

        Assert.assertEquals(matcher.group(1), Files.readString(Path.of("VERSION"), StandardCharsets.UTF_8).trim());
    }
}
