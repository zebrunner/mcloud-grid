package com.zebrunner.mcloud.grid.servlets;

import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.utils.configuration.GridHubConfiguration;
import org.openqa.grid.internal.utils.configuration.GridNodeConfiguration;
import org.openqa.grid.web.Hub;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.json.Json;

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

    // Terminates a session via HTTP DELETE /wd/hub/session/{sessionId}
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
                resp.getWriter().write("{\"ok\":false,\"status\":" + httpResp.statusCode() + ",\"body\":\"" + httpResp.body().replace("\"", "\\\"") + "\"}");
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

        // ===== Collect device data =====
        List<Map<String, Object>> devices = new ArrayList<>();
        for (RemoteProxy proxy : registry.getAllProxies()) {
            Map<String, Object> device = new HashMap<>();
            String udid = "—";
            String sessionId = "—";
            String sessionStart = "—";
            long sessionStartMillis = 0;
            boolean isBusy = false;
            String address = proxy.getRemoteHost().toString();

            // Extract UDID and capabilities
            if (!proxy.getConfig().capabilities.isEmpty()) {
                MutableCapabilities caps = proxy.getConfig().capabilities.get(0);
                Object udidObj = caps.getCapability("udid");
                if (udidObj != null) udid = udidObj.toString();
                device.put("capabilities", caps.asMap());
            } else {
                device.put("capabilities", Collections.emptyMap());
            }

            // Check active session
            TestSession activeSession = proxy.getTestSlots().stream()
                    .map(slot -> slot.getSession())
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            if (activeSession != null) {
                isBusy = true;
                sessionId = activeSession.getExternalKey() != null
                        ? activeSession.getExternalKey().getKey()
                        : "—";
                long inactivity = activeSession.getInactivityTime();
                sessionStartMillis = System.currentTimeMillis() - inactivity;
                sessionStart = new SimpleDateFormat("HH:mm:ss")
                        .format(new Date(sessionStartMillis));
            }

            device.put("udid", udid);
            device.put("status", isBusy ? "Busy" : "Free");
            device.put("sessionId", sessionId);
            device.put("sessionStart", sessionStart);
            device.put("sessionStartMillis", sessionStartMillis);
            device.put("address", address);
            device.put("slotInfo", getSlotInfo(proxy));
            devices.add(device);
        }

        // ===== Collect queue data =====
        List<Map<String, Object>> queue = new ArrayList<>();
        for (DesiredCapabilities caps : registry.getDesiredCapabilities()) {
            Map<String, Object> q = new HashMap<>();
            q.put("capabilities", caps.asMap());
            queue.add(q);
        }

        // ===== Collect hub info =====
        Map<String, Object> hubInfo = new HashMap<>();
        Hub hub = getHub(getServletContext());
        if (hub != null) {
            GridHubConfiguration cfg = hub.getConfiguration();
            hubInfo.put("host", cfg.host);
            hubInfo.put("port", cfg.port);
            hubInfo.put("timeout", cfg.timeout);
            hubInfo.put("browserTimeout", cfg.browserTimeout);
            hubInfo.put("cleanUpCycle", cfg.cleanUpCycle);
            hubInfo.put("newSessionWaitTimeout", cfg.newSessionWaitTimeout);
            hubInfo.put("servlets", cfg.servlets);
            hubInfo.put("capabilityMatcher", cfg.capabilityMatcher != null ? cfg.capabilityMatcher.toString() : "default");
            hubInfo.put("throwOnCapabilityNotPresent", cfg.throwOnCapabilityNotPresent);
            hubInfo.put("registry", cfg.registry != null ? cfg.registry.getClass().getName() : "default");
            hubInfo.put("jettyMaxThreads", cfg.jettyMaxThreads);
            hubInfo.put("debug", cfg.debug);
        } else {
            hubInfo.put("error", "Hub not available");
        }

        Json json = new Json(); // Selenium's built-in JSON serializer

        // ===== HTML with Bootstrap & JS rendering =====
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>📱 Device Status</title>");
        html.append("<link href='https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css' rel='stylesheet'>");
        html.append("<script src='https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js'></script>");
        html.append("<style>")
                .append("body { padding: 20px; }")
                .append("th { cursor: pointer; }")
                .append(".kill-btn { margin-left: 12px; text-decoration: none; font-weight: bold; color: #dc3545; }")
                .append(".kill-btn:hover { color: #a71d2a; }")
                .append(".udid-cell, .session-cell { white-space: nowrap; }")
                .append(".sticky-footer { position: fixed; left: 20px; bottom: 20px; z-index: 1000; }")
                .append("</style>");
        html.append("</head><body>");

        html.append("<h1>📱 Devices & Sessions</h1>");
        html.append("<div class='mb-3'>")
                .append("<label class='form-check-label me-3'><input type='checkbox' id='autoRefresh' class='form-check-input'> Auto-refresh (5s)</label>")
                .append("<input type='text' id='filterInput' class='form-control d-inline-block' style='width:250px' placeholder='Filter by UDID or Status'>")
                .append("</div>");

        html.append("<table class='table table-striped' id='deviceTable'>")
                .append("<thead><tr>")
                .append("<th onclick='sortTable(0)'>UDID</th>")
                .append("<th onclick='sortTable(1)'>Status</th>")
                .append("<th>Session ID</th>")
                .append("<th onclick='sortTable(3)'>Session Start</th>")
                .append("<th>Elapsed</th>")
                .append("<th>Address</th>")
                .append("</tr></thead><tbody></tbody></table>");

        html.append("<h2>Pending Requests</h2><div id='queueBlock'></div>");

        // Capabilities modal
        html.append("<div class='modal fade' id='capsModal' tabindex='-1'>")
                .append("<div class='modal-dialog modal-lg'><div class='modal-content'>")
                .append("<div class='modal-header'><h5 class='modal-title'>Capabilities</h5><button type='button' class='btn-close' data-bs-dismiss='modal'></button></div>")
                .append("<div class='modal-body'><pre id='capsContent'></pre></div>")
                .append("</div></div></div>");

        // Slot info modal
        html.append("<div class='modal fade' id='slotModal' tabindex='-1'>")
                .append("<div class='modal-dialog modal-lg'><div class='modal-content'>")
                .append("<div class='modal-header'><h5 class='modal-title'>Slot Info</h5><button type='button' class='btn-close' data-bs-dismiss='modal'></button></div>")
                .append("<div class='modal-body'><pre id='slotContent'></pre></div>")
                .append("</div></div></div>");

        // Hub info collapse
        html.append("<div class='sticky-footer'>")
                .append("<button class='btn btn-outline-secondary' type='button' data-bs-toggle='collapse' data-bs-target='#hubInfoCollapse' aria-expanded='false' aria-controls='hubInfoCollapse'>Config for the hub</button>")
                .append("</div>")
                .append("<div class='collapse' id='hubInfoCollapse'>")
                .append("<div class='card card-body mt-3'>")
                .append("<pre>").append(json.toJson(hubInfo)).append("</pre>")
                .append("</div></div>");

        // ===== JavaScript section =====
        html.append("<script>")
                .append("const devices = ").append(json.toJson(devices)).append(";")
                .append("const queue = ").append(json.toJson(queue)).append(";")

                // Render table
                .append("function renderTable(){")
                .append("let tbody=document.querySelector('#deviceTable tbody');tbody.innerHTML='';")
                .append("let filter=document.getElementById('filterInput').value.toLowerCase();")
                .append("devices.forEach(d=>{")
                .append(" if(!d.udid.toLowerCase().includes(filter) && !d.status.toLowerCase().includes(filter)) return;")
                .append(" let elapsedCell = d.sessionStartMillis>0 ? `<span data-start='${d.sessionStartMillis}' class='elapsed'></span>` : '—';")
                .append(" let killBtn = d.sessionId && d.sessionId !== '—' ? `<a href='#' class='kill-btn' title='Terminate session' data-sid='${d.sessionId}'>&times;</a>` : '';")
                .append(" let tr=document.createElement('tr');")
                .append(" tr.innerHTML = `<td class='udid-cell'><a href='#' onclick='showCaps(\"${d.udid}\")'>${d.udid}</a></td>`")
                .append(" + `<td class='${d.status==='Free'?'text-success':'text-danger'}'>${d.status}</td>`")
                .append(" + `<td class='session-cell'>${d.sessionId}${killBtn}</td>`")
                .append(" + `<td>${d.sessionStart}</td>`")
                .append(" + `<td>${elapsedCell}</td>`")
                .append(" + `<td><a href='#' onclick='showSlot(\"${d.udid}\")'>${d.address}</a></td>`;")
                .append(" tbody.appendChild(tr);")
                .append("});updateElapsed();")
                .append("}")

                // Show capabilities modal
                .append("function showCaps(udid){")
                .append(" let dev=devices.find(x=>x.udid===udid);")
                .append(" if(!dev) return;")
                .append(" localStorage.setItem('openModal', 'caps');")
                .append(" localStorage.setItem('openModalUdid', udid);")
                .append(" document.getElementById('capsContent').textContent=JSON.stringify(dev.capabilities,null,2);")
                .append(" new bootstrap.Modal(document.getElementById('capsModal')).show();")
                .append("}")

                // Show slot info modal
                .append("function showSlot(udid){")
                .append(" let dev=devices.find(x=>x.udid===udid);")
                .append(" if(!dev) return;")
                .append(" localStorage.setItem('openModal', 'slot');")
                .append(" localStorage.setItem('openModalUdid', udid);")
                .append(" document.getElementById('slotContent').textContent=JSON.stringify(dev.slotInfo,null,2);")
                .append(" new bootstrap.Modal(document.getElementById('slotModal')).show();")
                .append("}")

                // Render queue
                .append("function renderQueue(){")
                .append("let q=document.getElementById('queueBlock');")
                .append("if(queue.length===0){q.innerHTML='<p>The queue is empty.</p>';return;}")
                .append("q.innerHTML=`<p>${queue.length} request(s) waiting for a free slot</p>`+'<ul>'+queue.map(c=>`<li>${JSON.stringify(c.capabilities)}</li>`).join('')+'</ul>';")
                .append("}")

                // Sorting
                .append("function sortTable(n){")
                .append("let table=document.getElementById('deviceTable'),rows=Array.from(table.rows).slice(1);")
                .append("let asc=table.getAttribute('data-sort-dir')!=='asc';")
                .append("rows.sort((a,b)=>a.cells[n].innerText.localeCompare(b.cells[n].innerText,undefined,{numeric:true})*(asc?1:-1));")
                .append("rows.forEach(r=>table.tBodies[0].appendChild(r));")
                .append("table.setAttribute('data-sort-dir',asc?'asc':'desc');")
                .append("}")

                // Real-time elapsed time update
                .append("function updateElapsed(){")
                .append("document.querySelectorAll('.elapsed').forEach(el=>{")
                .append(" let start=parseInt(el.getAttribute('data-start'));")
                .append(" let diff=Math.floor((Date.now()-start)/1000);")
                .append(" let h=Math.floor(diff/3600), m=Math.floor((diff%3600)/60), s=diff%60;")
                .append(" el.textContent=`${h}h ${m}m ${s}s`;")
                .append("});")
                .append("}")
                .append("setInterval(updateElapsed,1000);")

                // Terminate session
                .append("document.addEventListener('click', async (e) => {")
                .append(" if (e.target.classList.contains('kill-btn')) {")
                .append("   e.preventDefault();")
                .append("   const sid = e.target.getAttribute('data-sid');")
                .append("   if (!sid || !confirm('Terminate session ' + sid + '?')) return;")
                .append("   const res = await fetch(window.location.pathname + '?action=terminate&sessionId=' + encodeURIComponent(sid), { method: 'POST' });")
                .append("   if (res.ok) { location.reload(); } else {")
                .append("     const json = await res.json();")
                .append("     alert('Failed to terminate session: ' + (json.error || json.status || 'Unknown error'));")
                .append("   }")
                .append(" }")
                .append("});")

                // Auto-refresh checkbox with persistence, default on
                .append("let interval;")
                .append("const autoRefresh = document.getElementById('autoRefresh');")
                .append("autoRefresh.addEventListener('change',function(){")
                .append("localStorage.setItem('autoRefresh',this.checked);")
                .append("if(this.checked) interval=setInterval(()=>location.reload(),5000); else clearInterval(interval);")
                .append("});")
                .append("if(localStorage.getItem('autoRefresh') !== 'false'){autoRefresh.checked=true;interval=setInterval(()=>location.reload(),5000);}")

                // Modal persistence
                .append("const capsModalEl = document.getElementById('capsModal');")
                .append("const slotModalEl = document.getElementById('slotModal');")
                .append("capsModalEl.addEventListener('hidden.bs.modal', () => { if(localStorage.getItem('openModal') === 'caps') localStorage.removeItem('openModal'); localStorage.removeItem('openModalUdid'); });")
                .append("slotModalEl.addEventListener('hidden.bs.modal', () => { if(localStorage.getItem('openModal') === 'slot') localStorage.removeItem('openModal'); localStorage.removeItem('openModalUdid'); });")
                .append("const openModal = localStorage.getItem('openModal');")
                .append("const openUdid = localStorage.getItem('openModalUdid');")
                .append("if(openModal && openUdid) {")
                .append(" if(openModal === 'caps') showCaps(openUdid);")
                .append(" else if(openModal === 'slot') showSlot(openUdid);")
                .append("}")

                // Filter input
                .append("document.getElementById('filterInput').addEventListener('input',renderTable);")

                // Initial render
                .append("renderTable();renderQueue();")
                .append("</script>");

        html.append("</body></html>");

        resp.getWriter().write(html.toString());
    }

    // Helper to get slot info
    private static Map<String, Object> getSlotInfo(RemoteProxy proxy) {
        Map<String, Object> slotInfo = new HashMap<>();
        GridNodeConfiguration cfg = proxy.getConfig();
        slotInfo.put("host", cfg.host);
        slotInfo.put("port", cfg.port);
        slotInfo.put("maxSession", cfg.maxSession);
        slotInfo.put("timeout", cfg.timeout);
        slotInfo.put("cleanUpCycle", cfg.cleanUpCycle);
        slotInfo.put("servlets", cfg.servlets);
        slotInfo.put("proxy", proxy.getClass().getName());
        slotInfo.put("remoteHost", proxy.getRemoteHost().toString());
        slotInfo.put("capabilities", proxy.getConfig().capabilities.stream()
                .map(MutableCapabilities::asMap)
                .collect(Collectors.toList()));
        return slotInfo;
    }
}