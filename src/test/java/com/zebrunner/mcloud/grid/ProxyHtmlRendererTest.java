package com.zebrunner.mcloud.grid;

import org.testng.Assert;
import org.testng.annotations.Test;

public class ProxyHtmlRendererTest {

    @Test
    public void consoleCardUsesInfoTabInsteadOfEmptyBrowsersTab() {
        MobileRemoteProxy proxy = GridFixtures.proxy(GridFixtures.registry(), "http://node-1:4723", GridFixtures.androidNodeCaps("emulator-5554"));

        String html = proxy.getHtmlRender().renderSummary();

        Assert.assertTrue(html.contains(">Info</a></li>"), html);
        Assert.assertFalse(html.contains(">Browsers</a></li>"), html);
        Assert.assertTrue(html.contains("<div type='info' class='content_detail'><p>UDID : emulator-5554</p>"), html);
        Assert.assertTrue(html.contains("<p>Device name : Pixel-emulator-5554</p>"), html);
        Assert.assertTrue(html.contains("<p>Status : free</p>"), html);
    }

    @Test
    public void udidIsEscaped() {
        MobileRemoteProxy proxy = GridFixtures.proxy(GridFixtures.registry(), "http://node-1:4723", GridFixtures.androidNodeCaps("<b>x</b>"));

        Assert.assertTrue(proxy.getHtmlRender().renderSummary().contains("UDID : &lt;b&gt;x&lt;/b&gt;"));
    }
}
