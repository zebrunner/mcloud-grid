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

/**
 * DeviceStatusServlet
 *
 * Selenium Grid 3.141.59 utility servlet that:
 * - Serves a Bootstrap UI with live device/session table (no full page reloads).
 * - Exposes JSON data endpoint for devices + queue + hub/node config.
 * - Allows terminating sessions via a red "×" next to Session ID.
 * - Shows Node config when clicking UDID, and Capabilities when clicking Address.
 * - Search matches UDID/Status/Address and capabilities.
 *
 * Mappings to configure (example in web.xml):
 *   <servlet>
 *     <servlet-name>DeviceStatusServlet</servlet-name>
 *     <servlet-class>com.zebrunner.mcloud.grid.servlets.DeviceStatusServlet</servlet-class>
 *   </servlet>
 *   <servlet-mapping>
 *     <servlet-name>DeviceStatusServlet</servlet-name>
 *     <url-pattern>/grid/devices</url-pattern>
 *   </servlet-mapping>
 *
 * Endpoints provided by this single servlet:
 *   GET  /grid/devices           -> HTML UI
 *   GET  /grid/devices?format=json -> JSON payload { devices, queue, hub }
 *   POST /grid/devices?action=terminate&sessionId=XXXX -> terminate session on hub
 */
public class DeviceStatusServlet extends HttpServlet {

    // === Utility DTOs ===

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeMap(Object maybeMap) {
        if (maybeMap instanceof Map) return (Map<String, Object>) maybeMap;
        return Collections.emptyMap();
    }

    // Extract Hub from context if available
    private static Hub getHub(ServletContext ctx) {
        Object hub = ctx.getAttribute("org.openqa.grid.web.Hub");
        return (hub instanceof Hub) ? (Hub) hub : null;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Handle actions (e.g., terminate session)
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

        // Build Hub base URL
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

        // If JSON requested, serve data only
        if ("json".equalsIgnoreCase(req.getParameter("format"))) {
            serveJson(registry, resp);
            return;
        }

        // Otherwise serve the HTML UI
        serveHtml(resp);
    }

    // Build JSON payload and write to response
    private void serveJson(GridRegistry registry, HttpServletResponse resp) throws IOException {
        Json json = new Json();
        Map<String, Object> payload = new LinkedHashMap<>();

        // Devices array
        List<Map<String, Object>> devices = new ArrayList<>();
        for (RemoteProxy proxy : registry.getAllProxies()) {
            Map<String, Object> device = new LinkedHashMap<>();
            String udid = "—";
            String sessionId = "—";
            String sessionStart = "—";
            long sessionStartMillis = 0L;
            boolean isBusy = false;
            String address = proxy.getRemoteHost().toString();

            // Capabilities (first capability set of the proxy)
            Map<String, Object> capsMap;
            if (!proxy.getConfig().capabilities.isEmpty()) {
                MutableCapabilities caps = proxy.getConfig().capabilities.get(0);
                Object udidObj = caps.getCapability("udid");
                if (udidObj != null) udid = String.valueOf(udidObj);
                capsMap = new LinkedHashMap<>(caps.asMap());
            } else {
                capsMap = new LinkedHashMap<>();
            }

            // Active session
            TestSession active = proxy.getTestSlots().stream()
                    .map(s -> s.getSession())
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);

            if (active != null) {
                isBusy = true;
                sessionId = (active.getExternalKey() != null) ? active.getExternalKey().getKey() : "—";
                long inactivity = active.getInactivityTime(); // elapsed since activity (ms)
                sessionStartMillis = System.currentTimeMillis() - inactivity;
                sessionStart = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(sessionStartMillis));
            }

            // Node "config-like" map (best-effort; add raw text too)
            Map<String, Object> nodeConfig = new LinkedHashMap<>();
            try {
                // Best-effort extraction of common fields
                nodeConfig.put("browserTimeout", opt(proxy, "browserTimeout", 0));
                nodeConfig.put("debug", opt(proxy, "debug", false));
                nodeConfig.put("jettyMaxThreads", opt(proxy, "jettyMaxThreads", -1));
                nodeConfig.put("host", hostFrom(address));
                nodeConfig.put("port", portFrom(address));
                nodeConfig.put("role", "node");
                nodeConfig.put("timeout", opt(proxy, "timeout", 150));
                nodeConfig.put("cleanUpCycle", opt(proxy, "cleanUpCycle", 5000));
                nodeConfig.put("maxSession", opt(proxy, "maxSession", 1));
                nodeConfig.put("servlets", Collections.singletonList(
                        "com.zebrunner.mcloud.grid.servlets.DeviceStatusServlet"));
                nodeConfig.put("proxy", proxy.getClass().getName());
                nodeConfig.put("remoteHost", address);
                // Attach capabilities summary
                nodeConfig.put("capabilities", capsMap);
            } catch (Exception ignore) {
                // Keep graceful
            }
            nodeConfig.put("raw", String.valueOf(proxy.getConfig())); // raw toString fallback

            device.put("udid", udid);
            device.put("status", isBusy ? "Busy" : "Free");
            device.put("sessionId", sessionId);
            device.put("sessionStart", sessionStart);
            device.put("sessionStartMillis", sessionStartMillis);
            device.put("address", address);
            device.put("capabilities", capsMap);
            device.put("nodeConfig", nodeConfig);
            devices.add(device);
        }

        // Queue list
        List<Map<String, Object>> queue = StreamSupport
                .stream(registry.getDesiredCapabilities().spliterator(), false)
                .map(dc -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    // Convert DesiredCapabilities to JSON string
                    String jsonCaps = ((DesiredCapabilities) dc).toJson().toString();
                    // Parse JSON string into Map
                    Map<String, Object> capsMap = new Json().toType(jsonCaps, Map.class);
                    m.put("capabilities", capsMap);
                    return m;
                })
                .collect(Collectors.toList());

        // Hub config (best-effort)
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

            // For the collapsible "Final configuration comes from..." block
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
            // ??? \/ \/
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
            out.write(new Json().toJson(payload));
        }
    }

    // Serve Bootstrap HTML page; JS will fetch ?format=json periodically
    private void serveHtml(HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html;charset=UTF-8");
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>📱 Device Status</title>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        html.append("<link href='https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css' rel='stylesheet'>");
        html.append("<script src='https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.bundle.min.js'></script>");
        html.append("<style>")
                .append("body{padding:20px} th{cursor:pointer} .session-cell{white-space:nowrap}")
                .append(".kill-btn{margin-left:8px;text-decoration:none;font-weight:bold;color:#dc3545}")
                .append(".kill-btn:hover{color:#a71d2a} .muted{opacity:.8}")
                .append(".sticky-footer{position:fixed;left:20px;bottom:20px}")
                .append("</style>");
        html.append("</head><body>");

        html.append("<h1>📱 Devices & Sessions</h1>");
        html.append("<div class='d-flex align-items-center gap-3 mb-3'>")
                .append("<div class='form-check'>")
                .append("<input class='form-check-input' type='checkbox' id='autoRefresh'>")
                .append("<label class='form-check-label' for='autoRefresh'>Auto-refresh (5s, live JSON)</label>")
                .append("</div>")
                .append("<input type='text' id='filterInput' class='form-control' style='max-width:360px' placeholder='Search UDID / Status / Address / Capabilities'>")
                .append("</div>");

        html.append("<table class='table table-striped' id='deviceTable'>")
                .append("<thead><tr>")
                .append("<th data-col='udid'>UDID</th>")
                .append("<th data-col='status'>Status</th>")
                .append("<th>Session ID</th>")
                .append("<th data-col='sessionStart'>Session Start</th>")
                .append("<th>Elapsed</th>")
                .append("<th data-col='address'>Address</th>")
                .append("</tr></thead><tbody></tbody></table>");

        html.append("<h2>Pending Requests</h2><div id='queueBlock' class='mb-5'></div>");

        // Hub config toggle (bottom-left)
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

        // Capability modal
        html.append("<div class='modal fade' id='capsModal' tabindex='-1'>")
                .append("<div class='modal-dialog modal-lg'><div class='modal-content'>")
                .append("<div class='modal-header'><h5 class='modal-title' id='capsTitle'>Details</h5><button type='button' class='btn-close' data-bs-dismiss='modal'></button></div>")
                .append("<div class='modal-body'><pre id='capsContent'></pre></div>")
                .append("</div></div></div>");

        // JavaScript: data model, fetch, render, search, sort, kill
        html.append("<script>")
                // App state
                .append("let DATA={devices:[],queue:[],hub:{}}, SORT={col:'udid',asc:true};")
                .append("const TBL=document.querySelector('#deviceTable tbody');")
                .append("const FILTER=document.getElementById('filterInput');")

                // Fetch JSON without reloading
                .append("async function loadData(){")
                .append(" const res=await fetch(window.location.pathname+'?format=json',{cache:'no-store'});")
                .append(" const json=await res.json();")
                .append(" DATA=json; renderAll();")
                .append("}")

                // Render queue + table + hub block
                .append("function renderAll(){ renderTable(); renderQueue(); renderHub(); }")

                // Case-insensitive full-text match across device + capabilities
                .append("function deviceMatches(d,q){")
                .append(" if(!q) return true;")
                .append(" const capStr=JSON.stringify(d.capabilities||{}).toLowerCase();")
                .append(" return (d.udid||'').toLowerCase().includes(q)||")
                .append("        (d.status||'').toLowerCase().includes(q)||")
                .append("        (d.address||'').toLowerCase().includes(q)||")
                .append("        capStr.includes(q);")
                .append("}")

                // Sort helper
                .append("function sortDevices(list){")
                .append(" const col=SORT.col, asc=SORT.asc?1:-1;")
                .append(" return list.slice().sort((a,b)=>{")
                .append("  const va=(a[col]||'').toString();")
                .append("  const vb=(b[col]||'').toString();")
                .append("  return va.localeCompare(vb,undefined,{numeric:true})*asc;")
                .append(" });")
                .append("}")

                // Render table rows
                .append("function renderTable(){")
                .append(" const q=(FILTER.value||'').toLowerCase();")
                .append(" let rows=sortDevices(DATA.devices).filter(d=>deviceMatches(d,q));")
                .append(" TBL.innerHTML='';")
                .append(" rows.forEach(d=>{")
                .append("  const tr=document.createElement('tr');")
                // UDID (click → node config)
                .append("  const udid=`<a href='#' class='link-primary' data-udid='${d.udid}' data-role='node'>${d.udid}</a>`;")
                // Status color
                .append("  const statClass=(d.status==='Free')?'text-success':'text-danger';")
                // Session with kill button
                .append("  const sid=(d.sessionId&&d.sessionId!=='—')?`<span class='session-cell'>${d.sessionId}<a href='#' class='kill-btn' title='Terminate session' data-sid='${d.sessionId}'>&times;</a></span>`:'—';")
                // Address (click → capabilities)
                .append("  const addr=`<a href='#' class='link-secondary' data-udid='${d.udid}' data-role='caps'>${d.address}</a>`;")
                // Elapsed ticker
                .append("  const elapsed=(d.sessionStartMillis>0)?`<span class='elapsed' data-start='${d.sessionStartMillis}'></span>`:'—';")
                .append("  tr.innerHTML=")
                .append("   `<td>${udid}</td>`+")
                .append("   `<td class='${statClass}'>${d.status}</td>`+")
                .append("   `<td>${sid}</td>`+")
                .append("   `<td>${d.sessionStart||'—'}</td>`+")
                .append("   `<td>${elapsed}</td>`+")
                .append("   `<td>${addr}</td>`;")
                .append("  TBL.appendChild(tr);")
                .append(" });")
                .append(" updateElapsed();")
                .append("}")

                // Render queue
                .append("function renderQueue(){")
                .append(" const el=document.getElementById('queueBlock');")
                .append(" const q=DATA.queue||[];")
                .append(" if(!q.length){ el.innerHTML='<p>The queue is empty.</p>'; return; }")
                .append(" el.innerHTML=`<p>${q.length} request(s) waiting for a free slot</p>`+")
                .append("  '<ul class=\"mb-0\">'+q.map(i=>`<li><code>${escapeHtml(JSON.stringify(i.capabilities))}</code></li>`).join('')+'</ul>';")
                .append("}")

                // Render hub block
                .append("function renderHub(){")
                .append(" const hub=DATA.hub||{};")
                .append(" const cfgLines=[];")
                .append(" ['browserTimeout','debug','jettyMaxThreads','host','port','role','timeout','cleanUpCycle','capabilityMatcher','newSessionWaitTimeout','throwOnCapabilityNotPresent','registry']")
                .append("   .forEach(k=>{ if(hub[k]!==undefined) cfgLines.push(k+' : '+hub[k]); });")
                .append(" document.getElementById('hubConfig').textContent=cfgLines.join('\\n');")
                .append(" const finalInfo=hub.finalDescription||{};")
                .append(" let finalTxt='';")
                .append(" if(finalInfo.defaults){")
                .append("  finalTxt+='the default :\\n'+Object.entries(finalInfo.defaults).map(e=>e[0]+' : '+e[1]).join('\\n')+'\\n';")
                .append(" }")
                .append(" if(finalInfo.cli){ finalTxt+='updated with command line options:\\n'+finalInfo.cli+'\\n'; }")
                .append(" document.getElementById('hubFinal').textContent=finalTxt;")
                .append(" document.getElementById('hubConfigJson').textContent=JSON.stringify(hub.configJson||{},null,2);")
                .append("}")

                // Update elapsed every second
                .append("function updateElapsed(){")
                .append(" document.querySelectorAll('.elapsed').forEach(el=>{")
                .append("  const start=parseInt(el.getAttribute('data-start'),10);")
                .append("  const diff=Math.max(0,Math.floor((Date.now()-start)/1000));")
                .append("  const h=Math.floor(diff/3600), m=Math.floor((diff%3600)/60), s=diff%60;")
                .append("  el.textContent=`${h}h ${m}m ${s}s`;")
                .append(" });")
                .append("}")
                .append("setInterval(updateElapsed,1000);")

                // Sorting by clicking table headers
                .append("document.querySelectorAll('#deviceTable thead th[data-col]').forEach(th=>{")
                .append(" th.addEventListener('click',()=>{")
                .append("  const col=th.getAttribute('data-col');")
                .append("  if(SORT.col===col) SORT.asc=!SORT.asc; else {SORT.col=col; SORT.asc=true;}")
                .append("  renderTable();")
                .append(" });")
                .append("});")

                // Filter input event
                .append("FILTER.addEventListener('input',()=>renderTable());")

                // Handle clicks: UDID -> node config modal; Address -> capabilities modal; Kill session
                .append("document.addEventListener('click',async (e)=>{")
                .append(" const a=e.target.closest('a'); if(!a) return;")
                .append(" // Kill button")
                .append(" if(a.classList.contains('kill-btn')){")
                .append("   e.preventDefault(); const sid=a.getAttribute('data-sid'); if(!sid) return;")
                .append("   if(!confirm('Terminate session '+sid+'?')) return;")
                .append("   const res=await fetch(window.location.pathname+'?action=terminate&sessionId='+encodeURIComponent(sid),{method:'POST'});")
                .append("   if(res.ok){ await loadData(); } else { alert('Failed to terminate session'); }")
                .append("   return;")
                .append(" }")
                .append(" // UDID or Address click")
                .append(" const udid=a.getAttribute('data-udid'); const role=a.getAttribute('data-role');")
                .append(" if(!udid||!role) return; e.preventDefault();")
                .append(" const dev=(DATA.devices||[]).find(d=>d.udid===udid); if(!dev) return;")
                .append(" const modalEl=document.getElementById('capsModal'); const title=document.getElementById('capsTitle'); const content=document.getElementById('capsContent');")
                .append(" if(role==='node'){")
                .append("   title.textContent='Node configuration for '+udid;")
                .append("   const nc=dev.nodeConfig||{};")
                .append("   const lines=[];")
                .append("   ['browserTimeout','debug','jettyMaxThreads','host','port','role','timeout','cleanUpCycle','maxSession','proxy','remoteHost']")
                .append("     .forEach(k=>{ if(nc[k]!==undefined) lines.push(k+': '+nc[k]); });")
                .append("   if(nc.capabilities){ lines.push('\\ncapabilities: '+JSON.stringify(nc.capabilities)); }")
                .append("   if(nc.raw){ lines.push('\\nraw: '+nc.raw); }")
                .append("   content.textContent=lines.join('\\n');")
                .append(" } else {")
                .append("   title.textContent='Capabilities for '+udid;")
                .append("   content.textContent=JSON.stringify(dev.capabilities||{},null,2);")
                .append(" }")
                .append(" new bootstrap.Modal(modalEl).show();")
                .append("});")

                // Auto-refresh toggle (preserve state)
                .append("let interval; const auto=document.getElementById('autoRefresh');")
                .append("auto.addEventListener('change',()=>{")
                .append(" localStorage.setItem('autoRefresh',auto.checked?'1':'0');")
                .append(" if(auto.checked){ interval=setInterval(loadData,5000);} else { clearInterval(interval);} ")
                .append("});")
                .append("if(localStorage.getItem('autoRefresh')==='1'){ auto.checked=true; interval=setInterval(loadData,5000);} ")

                // Helpers
                .append("function escapeHtml(s){return s.replace(/[&<>\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]));}")

                // Initial load
                .append("loadData();")
                .append("</script>");

        html.append("</body></html>");

        try (PrintWriter out = resp.getWriter()) {
            out.write(html.toString());
        }
    }

    // === Helpers to infer host/port from remoteHost URL ===
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

    // Best-effort extraction of proxy config numeric/boolean fields (fallbacks used if inaccessible)
    private static Object opt(RemoteProxy proxy, String field, Object fallback) {
        try {
            // try via toString search as generic fallback (keeps compatibility across builds)
            String s = String.valueOf(proxy.getConfig());
            String key = field + "=";
            int i = s.indexOf(key);
            if (i >= 0) {
                int j = s.indexOf(",", i + key.length());
                String val = (j > i ? s.substring(i + key.length(), j) : s.substring(i + key.length())).trim();
                if (fallback instanceof Integer) return Integer.parseInt(val);
                if (fallback instanceof Long)    return Long.parseLong(val);
                if (fallback instanceof Boolean) return Boolean.parseBoolean(val);
                return val;
            }
        } catch (Exception ignore) {}
        return fallback;
    }

    // Defaults block used in the "final configuration comes from" section
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
