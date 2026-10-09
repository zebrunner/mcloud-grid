package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.servlets.DevicesServlet;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.internal.utils.HtmlRenderer;
import org.openqa.grid.web.servlet.console.DefaultProxyHtmlRenderer;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Grid console card of a mobile device with an Info tab instead of the empty Browsers tab.
 */
public class ProxyHtmlRenderer implements HtmlRenderer {
    private static final String HEADER_PATCH_SCRIPT = "<script>(function(){"
            + "if(window.__mcloudConsolePatched){return;}"
            + "window.__mcloudConsolePatched=true;"
            + "document.title='MCloud-grid';"
            + "document.addEventListener('DOMContentLoaded',function(){"
            + "var header=document.querySelector('#header h2');"
            + "if(header){header.textContent='MCloud-grid';}"
            + "});"
            + "}());</script>";
    private final Supplier<RemoteProxy> proxySupplier;
    private final String udid;

    public ProxyHtmlRenderer(Supplier<RemoteProxy> proxySupplier, String udid) {
        this.proxySupplier = Objects.requireNonNull(proxySupplier, "proxySupplier must not be null");
        this.udid = Objects.requireNonNull(udid, "udid must not be null");
    }

    @Override
    public String renderSummary() {
        RemoteProxy proxy = proxy();
        StringBuilder builder = new StringBuilder();
        builder.append(HEADER_PATCH_SCRIPT);
        builder.append("<div class='proxy'>");
        builder.append("<p class='proxyname'>");
        builder.append(proxy.getClass().getSimpleName());
        builder.append(getHtmlNodeVersion());
        builder.append("</p>");
        builder.append("<p class='proxyid'>id : ");
        builder.append(proxy.getId());
        builder.append(", OS : ").append(DefaultProxyHtmlRenderer.getPlatform(proxy)).append("</p>");
        builder.append(nodeTabs());
        builder.append("<div class='content'>");
        builder.append(tabInfo());
        builder.append(tabConfig());
        builder.append("</div>");
        builder.append("</div>");
        return builder.toString();
    }

    private String getHtmlNodeVersion() {
        try {
            RemoteProxy proxy = proxy();
            Map<String, Object> object = proxy.getProxyStatus();
            Map<?, ?> value = (Map<?, ?>) object.get("value");
            Map<?, ?> build = (Map<?, ?>) value.get("build");
            String version = (String) build.get("version");
            return " (version : " + version + ")";
        } catch (Exception e) {
            return " unknown version," + e.getMessage();
        }
    }

    private String nodeTabs() {
        return "<div class='tabs'><ul>"
                + "<li class='tab' type='browsers'><a title='device details' href='#'>Info</a></li>"
                + "<li class='tab' type='config'><a title='node configuration' href='#'>Configuration</a></li>"
                + "</ul></div>";
    }

    private String tabInfo() {
        RemoteProxy proxy = proxy();
        StringBuilder builder = new StringBuilder();
        builder.append("<div type='browsers' class='content_detail'>");

        TestSlot slot = proxy.getTestSlots().stream().findFirst().orElse(null);
        if (slot == null) {
            appendInfoLine(builder, "UDID", udid);
            builder.append("<p>Device info is unavailable: the node has no test slots.</p>");
            builder.append("</div>");
            return builder.toString();
        }

        Map<String, Object> device = DevicesServlet.describe(proxy, slot);
        appendInfoLine(builder, "UDID", device.get("udid"));
        appendInfoLine(builder, "Device name", device.get("deviceName"));
        appendInfoLine(builder, "Platform", device.get("platformName"));
        appendInfoLine(builder, "Platform version", device.get("platformVersion"));
        appendInfoLine(builder, "Device type", device.get("deviceType"));
        appendInfoLine(builder, "Node", device.get("node"));
        appendInfoLine(builder, "Status", device.get("status"));

        appendNestedInfo(builder, "Ignored", device.get("ignored"));
        appendNestedInfo(builder, "Session", device.get("session"));

        builder.append("</div>");
        return builder.toString();
    }

    private String tabConfig() {
        RemoteProxy proxy = proxy();
        return "<div type='config' class='content_detail'>"
                + proxy.getConfig().toString("<p>%1$s: %2$s</p>")
                + "</div>";
    }

    private RemoteProxy proxy() {
        return proxySupplier.get();
    }

    @SuppressWarnings("unchecked")
    private void appendNestedInfo(StringBuilder builder, String section, Object value) {
        if (!(value instanceof Map)) {
            return;
        }
        ((Map<String, Object>) value).forEach((key, nestedValue) -> appendInfoLine(builder, section + ' ' + humanize(key), nestedValue));
    }

    private void appendInfoLine(StringBuilder builder, String label, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return;
        }
        builder.append("<p>")
                .append(escape(label))
                .append(" : ")
                .append(escape(text))
                .append("</p>");
    }

    private String humanize(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private static String escape(String value) {
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
