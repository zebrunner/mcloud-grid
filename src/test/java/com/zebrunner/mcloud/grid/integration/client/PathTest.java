package com.zebrunner.mcloud.grid.integration.client;

import org.testng.Assert;
import org.testng.annotations.Test;

public class PathTest {

    @Test
    public void buildsUrlWithParameters() {
        Assert.assertEquals(Path.STF_USER_DEVICES_BY_ID_PATH.build("http://stf", "emulator-5554"), "http://stf/api/v1/user/devices/emulator-5554");
        Assert.assertEquals(Path.STF_USER_DEVICES_REMOTE_CONNECT_PATH.build("http://stf", "X"), "http://stf/api/v1/user/devices/X/remoteConnect");
        Assert.assertEquals(Path.APPIUM_STATUS_ADB.build("http://node:4723/wd/hub"), "http://node:4723/wd/hub/status-adb");
        Assert.assertEquals(Path.EMPTY.build("http://stf"), "http://stf");
    }
}
