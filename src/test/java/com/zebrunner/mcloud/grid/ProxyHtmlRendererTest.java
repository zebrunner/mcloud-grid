package com.zebrunner.mcloud.grid;

import org.testng.Assert;
import org.testng.annotations.Test;

public class ProxyHtmlRendererTest {

    @Test
    public void consoleCardShowsUdidBeforeNodeId() {
        MobileRemoteProxy proxy = GridFixtures.proxy(GridFixtures.registry(), "http://node-1:4723", GridFixtures.androidNodeCaps("emulator-5554"));

        String html = proxy.getHtmlRender().renderSummary();

        int udid = html.indexOf("<p class='proxyudid'>UDID : emulator-5554</p>");
        Assert.assertTrue(udid >= 0, html);
        Assert.assertTrue(udid < html.indexOf("<p class='proxyid'>"), html);
    }

    @Test
    public void udidIsEscaped() {
        MobileRemoteProxy proxy = GridFixtures.proxy(GridFixtures.registry(), "http://node-1:4723", GridFixtures.androidNodeCaps("<b>x</b>"));

        Assert.assertTrue(proxy.getHtmlRender().renderSummary().contains("UDID : &lt;b&gt;x&lt;/b&gt;"));
    }
}
