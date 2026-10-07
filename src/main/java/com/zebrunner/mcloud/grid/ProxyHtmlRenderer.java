package com.zebrunner.mcloud.grid;

import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.web.servlet.console.DefaultProxyHtmlRenderer;

/**
 * Grid console card of a device with its UDID above the node id.
 */
public class ProxyHtmlRenderer extends DefaultProxyHtmlRenderer {
    private static final String PROXY_ID_MARKER = "<p class='proxyid'>";
    private final String udid;

    public ProxyHtmlRenderer(RemoteProxy proxy, String udid) {
        super(proxy);
        this.udid = udid;
    }

    @Override
    public String renderSummary() {
        String summary = super.renderSummary();
        String udidLine = "<p class='proxyudid'>UDID : " + escape(udid) + "</p>";
        int index = summary.indexOf(PROXY_ID_MARKER);
        return index < 0 ? udidLine + summary : summary.substring(0, index) + udidLine + summary.substring(index);
    }

    private static String escape(String value) {
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
