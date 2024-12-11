package com.zebrunner.mcloud.grid;

import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.web.servlet.console.DefaultProxyHtmlRenderer;

public class ProxyHtmlRenderer extends DefaultProxyHtmlRenderer {
    private final String udid;
    public ProxyHtmlRenderer(RemoteProxy proxy, String udid) {
        super(proxy);
        this.udid = udid;
    }

    @Override
    public String renderSummary() {
        // Override the renderSummary method to customize the HTML output
        String summary = super.renderSummary();
        // Add your custom logic here

        int index = summary.indexOf("<p class='proxyid'>id : ");
        String stringToInsert = "<p class='proxyudid'>UDID : ";
        return summary.substring(0, index) + stringToInsert + udid + "</p>" + summary.substring(index);
    }
}
