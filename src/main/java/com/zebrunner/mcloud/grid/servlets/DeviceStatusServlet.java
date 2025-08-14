package com.zebrunner.mcloud.grid.servlets;

import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.web.Hub;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.DesiredCapabilities;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletContext;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class DeviceStatusServlet extends HttpServlet {

    // Extract Hub from context if available
    private static Hub getHub(ServletContext ctx) {
        Object hub = ctx.getAttribute("org.openqa.grid.web.Hub");
        return (hub instanceof Hub) ? (Hub) hub : null;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action = req.getParameter("action");
        if ("terminate".equalsIgnoreCase(action)) {
            handleTerminate(req, resp);
            return;
        }
        resp.sendError(400, "Unknown action");
    }

    // Terminates a session on the Hub via HTTP DELETE /wd/hub/session/{id}
    private void handleTerminate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String sessionId = req.getParameter("sessionId");
        if (sessionId == null || sessionId.isEmpty()) {
            resp.sendError(400, "Missing sessionId");
            return;
        }

        Hub hub = getHub(getServletContext());
        if (hub == null) {
            resp.sendError(500, "Hub not found");
            return;
        }

        String host = hub.getConfiguration().host;
        int port = hub.getConfiguration().port;
        if (host == null || host.isBlank()) host = "localhost";
        String base = "http://" + host + ":" + port;

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/wd/hub/session/" + sessionId))
                    .DELETE()
                    .build();
            HttpResponse<String> httpResp = client.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (httpResp.statusCode() >= 200 && httpResp.statusCode() < 300) {
                resp.setStatus(200);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write("{\"ok\":true}");
            } else {
                resp.setStatus(httpResp.statusCode());
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write("{\"ok\":false,\"status\":" + httpResp.statusCode() + "}");
            }
        } catch (Exception e) {
            resp.setStatus(500);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"ok\":false,\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        GridRegistry registry = (GridRegistry) getServletContext()
                .getAttribute("org.openqa.grid.internal.GridRegistry");

        if (registry == null) {
            resp.sendError(500, "GridRegistry not found in servlet context");
            return;
        }

        resp.setContentType("text/html;charset=UTF-8");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>📱 Device Status</title>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        html.append("<link href='https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css' rel='stylesheet'>");
        html.append("<script src='https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.bundle.min.js'></script>");
        html.append("<style>")
                .append("body { padding: 20px; font-family: sans-serif; }")
                .append("table { border-collapse: collapse; width: 100%; margin-top: 10px; }")
                .append("th, td { border: 1px solid #ccc; padding: 8px; text-align: left; }")
                .append(".status-free { color: green; font-weight: bold; }")
                .append(".status-busy { color: red; font-weight: bold; }")
                .append(".udid-link, .address-link { cursor: pointer; color: blue; text-decoration: underline; }")
                .append(".kill-btn { margin-left: 8px; text-decoration: none; font-weight: bold; color: #dc3545; }")
                .append(".kill-btn:hover { color: #a71d2a; }")
                .append("#controls { margin-top: 10px; margin-bottom: 10px; }")
                .append(".sticky-footer { position: fixed; left: 20px; bottom: 20px; }")
                .append(".session-cell { white-space: nowrap; }")
                .append("</style>");

        html.append("<script>")
                // App state
                .append("let DATA = { devices: [], hub: {} };")
                .append("const TBL = document.querySelector('#deviceTable tbody');")
                .append("const FILTER = document.getElementById('filterInput');")

                // Fetch JSON data
                .append("async function loadData() {")
                .append("  const res = await fetch(window.location.pathname + '?format=json', {cache: 'no-store'});")
                .append("  const json = await res.json();")
                .append("  DATA = json; renderTable(); renderHub();")
                .append("}")

                // Case-insensitive search including capabilities
                .append("function deviceMatches(d, q) {")
                .append("  if (!q) return true;")
                .append("  const capStr = JSON.stringify(d.capabilities || {}).toLowerCase();")
                .append("  return (d.udid || '').toLowerCase().includes(q) ||")
                .append("         (d.status || '').toLowerCase().includes(q) ||")
                .append("         (d.address || '').toLowerCase().includes(q) ||")
                .append("         capStr.includes(q);")
                .append("}")

                // Render table
                .append("function renderTable() {")
                .append("  const q = (FILTER ? FILTER.value || '' : '').toLowerCase();")
                .append("  const rows = DATA.devices.filter(d => deviceMatches(d, q));")
                .append("  TBL.innerHTML = '';")
                .append("  if (rows.length === 0) {")
                .append("    TBL.innerHTML = '<tr><td colspan=\"4\" class=\"text-center\">No devices found</td></tr>';")
                .append("  } else {")
                .append("    rows.forEach(d => {")
                .append("      const tr = document.createElement('tr');")
                .append("      const udid = `<a href='#' class='udid-link' data-udid='${d.udid}' data-role='node'>${d.udid}</a>`;")
                .append("      const statClass = (d.status === 'Free') ? 'status-free' : 'status-busy';")
                .append("      const sid = (d.sessionId && d.sessionId !== '—') ? `<span class='session-cell'>${d.sessionId}<a href='#' class='kill-btn' title='Terminate session' data-sid='${d.sessionId}'>&times;</a></span>` : '—';")
                .append("      const addr = `<a href='#' class='address-link' data-udid='${d.udid}' data-role='caps'>${d.address}</a>`;")
                .append("      tr.innerHTML =")
                .append("        `<td>${udid}</td>` +")
                .append("        `<td class='${statClass}'>${d.status === 'Free' ? '🟢 Free' : '🔴 Busy'}</td>` +")
                .append("        `<td>${sid}</td>` +")
                .append("        `<td>${d.sessionStart}</td>`;")
                .append("      TBL.appendChild(tr);")
                .append("    });")
                .append("  }")
                .append("}")

                // Render hub config
                .append("function renderHub() {")
                .append("  const hub = DATA.hub || {};")
                .append("  const cfgLines = [];")
                .append("  ['browserTimeout','debug','jettyMaxThreads','host','port','role','timeout','cleanUpCycle','servlets','capabilityMatcher','newSessionWaitTimeout','throwOnCapabilityNotPresent','registry']")
                .append("    .forEach(k => { if (hub[k] !== undefined) cfgLines.push(k + ' : ' + hub[k]); });")
                .append("  document.getElementById('hubConfig').textContent = cfgLines.join('\\n');")
                .append("  const finalInfo = hub.finalDescription || {};")
                .append("  let finalTxt = '';")
                .append("  if (finalInfo.defaults) {")
                .append("    finalTxt += 'the default :\\n' + Object.entries(finalInfo.defaults).map(e => e[0] + ' : ' + e[1]).join('\\n') + '\\n';")
                .append("  }")
                .append("  if (finalInfo.cli) { finalTxt += 'updated with command line options:\\n' + finalInfo.cli + '\\n'; }")
                .append("  document.getElementById('hubFinal').textContent = finalTxt;")
                .append("  document.getElementById('hubConfigJson').textContent = JSON.stringify(hub.configJson || {}, null, 2);")
                .append("}")

                // Handle clicks: UDID, Address, Kill session
                .append("document.addEventListener('click', async (e) => {")
                .append("  const a = e.target.closest('a'); if (!a) return;")
                .append("  if (a.classList.contains('kill-btn')) {")
                .append("    e.preventDefault(); const sid = a.getAttribute('data-sid'); if (!sid) return;")
                .append("    if (!confirm('Terminate session ' + sid + '?')) return;")
                .append("    const res = await fetch(window.location.pathname + '?action=terminate&sessionId=' + encodeURIComponent(sid), {method: 'POST'});")
                .append("    if (res.ok) { await loadData(); } else { alert('Failed to terminate session'); }")
                .append("    return;")
                .append("  }")
                .append("  const udid = a.getAttribute('data-udid'); const role = a.getAttribute('data-role');")
                .append("  if (!udid || !role) return; e.preventDefault();")
                .append("  const dev = (DATA.devices || []).find(d => d.udid === udid); if (!dev) return;")
                .append("  const modalEl = document.getElementById('capsModal');")
                .append("  const title = document.getElementById('capsTitle');")
                .append("  const content = document.getElementById('capsContent');")
                .append("  if (role === 'node') {")
                .append("    title.textContent = 'Node configuration for ' + udid;")
                .append("    const nc = dev.nodeConfig || {};")
                .append("    const lines = [];")
                .append("    ['browserTimeout','debug','jettyMaxThreads','host','port','role','timeout','cleanUpCycle','maxSession','servlets','proxy','remoteHost','downPollingLimit','hub','hubHost','hubPort','nodePolling','nodeStatusCheckTimeout','register','registerCycle','unregisterIfStillDownAfter']")
                .append("      .forEach(k => { if (nc[k] !== undefined) lines.push(k + ': ' + nc[k]); });")
                .append("    if (nc.capabilities) { lines.push('capabilities: ' + JSON.stringify(nc.capabilities, null, 2)); }")
                .append("    content.textContent = lines.join('\\n');")
                .append("  } else {")
                .append("    title.textContent = 'Capabilities for ' + udid;")
                .append("    content.textContent = JSON.stringify(dev.capabilities || {}, null, 2);")
                .append("  }")
                .append("  new bootstrap.Modal(modalEl).show();")
                .append("});")

                // Auto-refresh toggle
                .append("let interval; const auto = document.getElementById('autoRefresh');")
                .append("if (auto) {")
                .append("  auto.addEventListener('change', () => {")
                .append("    localStorage.setItem('autoRefresh', auto.checked ? '1' : '0');")
                .append("    if (auto.checked) { interval = setInterval(loadData, 5000); } else { clearInterval(interval); }")
                .append("  });")
                .append("  if (localStorage.getItem('autoRefresh') === '1') { auto.checked = true; interval = setInterval(loadData, 5000); }")
                .append("}")

                // Filter input
                .append("if (FILTER) { FILTER.addEventListener('input', () => renderTable()); }")

                // Initial load
                .append("loadData();")
                .append("</script>");

        html.append("</head><body>");
        html.append("<h1>📱 Devices & Sessions</h1>");

        // Controls
        html.append("<div id='controls' class='d-flex align-items-center gap-3 mb-3'>")
                .append("<div class='form-check'>")
                .append("<input class='form-check-input' type='checkbox' id='autoRefresh'>")
                .append("<label class='form-check-label' for='autoRefresh'>Auto-refresh (5s)</label>")
                .append("</div>")
                .append("<input type='text' id='filterInput' class='form-control' style='max-width:360px' placeholder='Search UDID / Status / Address / Capabilities'>")
                .append("</div>");

        // Table
        html.append("<table id='deviceTable' class='table table-striped'><thead><tr>")
                .append("<th>UDID</th><th>Status</th><th>Session ID</th><th>Session Start</th><th>Address</th>")
                .append("</tr></thead><tbody></tbody></table>");

        // Request queue
        html.append("<h2>Pending Requests</h2><div id='queueBlock' class='mb-5'></div>");

        // Hub config toggle
        html.append("<div class='sticky-footer'>")
                .append("<button class='btn btn-outline-secondary btn-sm' type='button' data-bs-toggle='collapse' data-bs-target='#hubConfigCollapse' aria-expanded='false'>Config for the hub</button>")
                .append("</div>")
                .append("<div class='collapse' id='hubConfigCollapse'>")
                .append("<div class='card card-body mt-3'>")
                .append("<h5>Hub configuration</h5>")
                .append("<pre id='hubConfig' class='mb-3'></pre>")
                .append("<h6>The final configuration comes from:</h6>")
                .append("<pre id='hubFinal' class='mb-3'></pre>")
                .append("<h6>configuration loaded (JSON):</h6>")
                .append("<pre id='hubConfigJson' class='mb-0'></pre>")
                .append("</div></div>");

        // Modal for capabilities and node config
        html.append("<div class='modal fade' id='capsModal' tabindex='-1'>")
                .append("<div class='modal-dialog modal-lg'><div class='modal-content'>")
                .append("<div class='modal-header'><h5 class='modal-title' id='capsTitle'>Details</h5><button type='button' class='btn-close' data-bs-dismiss='modal'></button></div>")
                .append("<div class='modal-body'><pre id='capsContent'></pre></div>")
                .append("</div></div></div>");

        html.append("</body></html>");

        resp.getWriter().write(html.toString());
    }

    // Serve JSON data for dynamic updates
    private void serveJson(GridRegistry registry, HttpServletResponse resp) throws IOException {
        Json json = new Json();
        Map<String, Object> payload = new LinkedHashMap<>();

        // Devices array
        List<Map<String, Object>> devices = new ArrayList<>();
        for (RemoteProxy proxy : registry.getAllProxies()) {
            List<MutableCapabilities> capabilitiesList = proxy.getConfig().capabilities;
            if (capabilitiesList == null || capabilitiesList.isEmpty()) {
                Map<String, Object> device = new LinkedHashMap<>();
                device.put("udid", "—");
                device.put("status", "Free");
                device.put("sessionId", "—");
                device.put("sessionStart", "—");
                device.put("sessionStartMillis", 0L);
                device.put("address", proxy.getRemoteHost().toString());
                device.put("capabilities", new LinkedHashMap<>());
                device.put("nodeConfig", buildNodeConfig(proxy));
                devices.add(device);
                continue;
            }

            for (MutableCapabilities caps : capabilitiesList) {
                Map<String, Object> device = new LinkedHashMap<>();
                String udid = "—";
                String sessionId = "—";
                String sessionStart = "—";
                long sessionStartMillis = 0L;
                boolean isBusy = false;
                String address = proxy.getRemoteHost().toString();

                // Извлечение UDID или deviceName
                Object udidObj = caps.getCapability("udid");
                if (udidObj != null) {
                    udid = String.valueOf(udidObj);
                } else if (caps.getCapability("deviceName") != null) {
                    udid = String.valueOf(caps.getCapability("deviceName"));
                }

                // Конвертация capabilities в Map
                Map<String, Object> capsMap = new LinkedHashMap<>(caps.asMap());

                // Поиск активной сессии
                TestSession active = Objects.requireNonNull(proxy.getTestSlots().stream()
                        .filter(slot -> slot.getSession() != null)
                        .filter(slot -> {
                            Map<String, Object> sessionCaps = slot.getSession().getRequestedCapabilities();
                            return (sessionCaps.get("udid") != null && sessionCaps.get("udid").equals(capsMap.get("udid"))) ||
                                    (sessionCaps.get("deviceName") != null && sessionCaps.get("deviceName").equals(capsMap.get("deviceName")));
                        })
                        .findFirst()
                        .orElse(null)).getSession();

                if (active != null) {
                    isBusy = true;
                    sessionId = (active.getExternalKey() != null) ? active.getExternalKey().getKey() : "—";
                    long inactivity = active.getInactivityTime();
                    sessionStartMillis = System.currentTimeMillis() - inactivity;
                    sessionStart = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(sessionStartMillis));
                }

                device.put("udid", udid);
                device.put("status", isBusy ? "Busy" : "Free");
                device.put("sessionId", sessionId);
                device.put("sessionStart", sessionStart);
                device.put("sessionStartMillis", sessionStartMillis);
                device.put("address", address);
                device.put("capabilities", capsMap);
                device.put("nodeConfig", buildNodeConfig(proxy));
                devices.add(device);
            }
        }

        // Queue list
        List<Map<String, Object>> queue = StreamSupport
                .stream(registry.getDesiredCapabilities().spliterator(), false)
                .map(dc -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String jsonCaps = ((DesiredCapabilities) dc).toJson().toString();
                    Map<String, Object> capsMap = new Json().toType(jsonCaps, Map.class);
                    m.put("capabilities", capsMap);
                    return m;
                })
                .collect(Collectors.toList());

        // Hub config
        Map<String, Object> hubInfo = new LinkedHashMap<>();
        Hub hub = getHub(getServletContext());
        if (hub != null) {
            var cfg = hub.getConfiguration();
            hubInfo.put("browserTimeout", cfg.browserTimeout);
            hubInfo.put("debug", cfg.debug);
            hubInfo.put("jettyMaxThreads", cfg.jettyMaxThreads);
            hubInfo.put("host", cfg.host);
            hubInfo.put("port", cfg.port);
            hubInfo.put("role", "hub");
            hubInfo.put("timeout", cfg.timeout);
            hubInfo.put("cleanUpCycle", cfg.cleanUpCycle);
            hubInfo.put("servlets", cfg.servlets);
            hubInfo.put("capabilityMatcher", cfg.capabilityMatcher);
            hubInfo.put("newSessionWaitTimeout", cfg.newSessionWaitTimeout);
            hubInfo.put("throwOnCapabilityNotPresent", cfg.throwOnCapabilityNotPresent);
            hubInfo.put("registry", (cfg.registry != null) ? cfg.registry : "org.openqa.grid.internal.DefaultGridRegistry");

            Map<String, Object> finalCfg = new LinkedHashMap<>();
            finalCfg.put("defaults", defaultHubDefaults());
            finalCfg.put("cli", cfg.role + " " + (cfg.hubConfig != null ? "-hubConfig " + cfg.hubConfig : ""));
            Map<String, Object> configJson = new LinkedHashMap<>();
            configJson.put("host", cfg.host);
            configJson.put("port", cfg.port);
            configJson.put("role", "hub");
            configJson.put("maxSession", cfg.maxSession);
            configJson.put("newSessionWaitTimeout", cfg.newSessionWaitTimeout);
            configJson.put("capabilityMatcher", cfg.capabilityMatcher);
            configJson.put("throwOnCapabilityNotPresent", cfg.throwOnCapabilityNotPresent);
            configJson.put("jettyMaxThreads", cfg.jettyMaxThreads);
            configJson.put("cleanUpCycle", cfg.cleanUpCycle);
            configJson.put("browserTimeout", cfg.browserTimeout);
            configJson.put("timeout", cfg.timeout);
            configJson.put("debug", cfg.debug);
            configJson.put("proxy", cfg.hubConfig);
            configJson.put("servlets", cfg.servlets);
            hubInfo.put("finalDescription", finalCfg);
            hubInfo.put("configJson", configJson);
        } else {
            hubInfo.put("error", "Hub not available");
        }

        payload.put("devices", devices);
        payload.put("queue", queue);
        payload.put("hub", hubInfo);

        resp.setContentType("application/json;charset=UTF-8");
        try (PrintWriter out = resp.getWriter()) {
            out.write(json.toJson(payload));
        }
    }

    // Helper to build node config
    private static Map<String, Object> buildNodeConfig(RemoteProxy proxy) {
        Map<String, Object> nodeConfig = new LinkedHashMap<>();
        try {
            var cfg = proxy.getConfig();
            nodeConfig.put("browserTimeout", opt(proxy, "browserTimeout", 0));
            nodeConfig.put("debug", opt(proxy, "debug", false));
            nodeConfig.put("jettyMaxThreads", opt(proxy, "jettyMaxThreads", -1));
            nodeConfig.put("host", hostFrom(proxy.getRemoteHost().toString()));
            nodeConfig.put("port", portFrom(proxy.getRemoteHost().toString()));
            nodeConfig.put("role", "node");
            nodeConfig.put("timeout", opt(proxy, "timeout", 150));
            nodeConfig.put("cleanUpCycle", opt(proxy, "cleanUpCycle", 5000));
            nodeConfig.put("maxSession", opt(proxy, "maxSession", 1));
            nodeConfig.put("servlets", Collections.singletonList(
                    "com.zebrunner.mcloud.grid.servlets.DeviceStatusServlet"));
            nodeConfig.put("proxy", proxy.getClass().getName());
            nodeConfig.put("remoteHost", proxy.getRemoteHost().toString());
            nodeConfig.put("downPollingLimit", opt(proxy, "downPollingLimit", 3));
            nodeConfig.put("hub", cfg.hubHost + ":" + cfg.hubPort);
            nodeConfig.put("hubHost", cfg.hubHost);
            nodeConfig.put("hubPort", cfg.hubPort);
            nodeConfig.put("nodePolling", opt(proxy, "nodePolling", 5000));
            nodeConfig.put("nodeStatusCheckTimeout", opt(proxy, "nodeStatusCheckTimeout", 5000));
            nodeConfig.put("register", opt(proxy, "register", true));
            nodeConfig.put("registerCycle", opt(proxy, "registerCycle", 5000));
            nodeConfig.put("unregisterIfStillDownAfter", opt(proxy, "unregisterIfStillDownAfter", 3000));
            List<Map<String, Object>> capsList = proxy.getConfig().capabilities.stream()
                    .map(caps -> new LinkedHashMap<>(caps.asMap()))
                    .collect(Collectors.toList());
            nodeConfig.put("capabilities", capsList.isEmpty() ? new LinkedHashMap<>() : capsList.get(0));
        } catch (Exception e) {
            nodeConfig.put("error", "Failed to parse node config: " + e.getMessage());
        }
        return nodeConfig;
    }

    private static String hostFrom(String remoteHostUrl) {
        try {
            URI u = URI.create(remoteHostUrl);
            return (u.getHost() != null) ? u.getHost() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static int portFrom(String remoteHostUrl) {
        try {
            URI u = URI.create(remoteHostUrl);
            return (u.getPort() > 0) ? u.getPort() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static Object opt(RemoteProxy proxy, String field, Object fallback) {
        try {
            String s = String.valueOf(proxy.getConfig());
            String key = field + "=";
            int i = s.indexOf(key);
            if (i >= 0) {
                int j = s.indexOf(",", i + key.length());
                String val = (j > i ? s.substring(i + key.length(), j) : s.substring(i + key.length())).trim();
                if (fallback instanceof Integer) return Integer.parseInt(val);
                if (fallback instanceof Long) return Long.parseLong(val);
                if (fallback instanceof Boolean) return Boolean.parseBoolean(val);
                return val;
            }
        } catch (Exception ignore) {}
        return fallback;
    }

    private static Map<String, Object> defaultHubDefaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("browserTimeout", 0);
        d.put("debug", false);
        d.put("host", "0.0.0.0");
        d.put("port", 4444);
        d.put("role", "hub");
        d.put("timeout", 1800);
        d.put("cleanUpCycle", 5000);
        d.put("capabilityMatcher", "org.openqa.grid.internal.utils.DefaultCapabilityMatcher");
        d.put("newSessionWaitTimeout", -1);
        d.put("throwOnCapabilityNotPresent", true);
        d.put("registry", "org.openqa.grid.internal.DefaultGridRegistry");
        return d;
    }
}