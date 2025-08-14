package com.zebrunner.mcloud.grid.servlets;

import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.json.Json;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * DeviceStatusServlet
 *
 * Displays device and session status for Selenium Grid 3.141.59.
 * Uses Bootstrap for UI, JSON data for rendering, sorting, filtering, auto-refresh,
 * and real-time elapsed session time updates.
 */
public class DeviceStatusServlet extends HttpServlet {

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
                sessionStart = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new Date(sessionStartMillis));
            }

            device.put("udid", udid);
            device.put("status", isBusy ? "Busy" : "Free");
            device.put("sessionId", sessionId);
            device.put("sessionStart", sessionStart);
            device.put("sessionStartMillis", sessionStartMillis);
            device.put("address", address);
            devices.add(device);
        }

        // ===== Collect queue data =====
        List<Map<String, Object>> queue = new ArrayList<>();
        for (DesiredCapabilities caps : registry.getDesiredCapabilities()) {
            Map<String, Object> q = new HashMap<>();
            q.put("capabilities", caps.asMap());
            queue.add(q);
        }

        Json json = new Json(); // Selenium's built-in JSON serializer

        // ===== HTML with Bootstrap & JS rendering =====
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>📱 Device Status</title>");
        html.append("<link href='https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css' rel='stylesheet'>");
        html.append("<script src='https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js'></script>");
        html.append("<style>body { padding: 20px; } th { cursor: pointer; }</style>");
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
                .append(" let tr=document.createElement('tr');")
                .append(" tr.innerHTML = `<td><a href='#' onclick='showCaps(\"${d.udid}\")'>${d.udid}</a></td>`")
                .append(" + `<td class='${d.status==='Free'?'text-success':'text-danger'}'>${d.status}</td>`")
                .append(" + `<td>${d.sessionId}</td>`")
                .append(" + `<td>${d.sessionStart}</td>`")
                .append(" + `<td>${elapsedCell}</td>`")
                .append(" + `<td>${d.address}</td>`;")
                .append(" tbody.appendChild(tr);")
                .append("});updateElapsed();")
                .append("}")

                // Show capabilities modal
                .append("function showCaps(udid){")
                .append(" let dev=devices.find(x=>x.udid===udid);")
                .append(" if(!dev) return;")
                .append(" document.getElementById('capsContent').textContent=JSON.stringify(dev.capabilities,null,2);")
                .append(" new bootstrap.Modal(document.getElementById('capsModal')).show();")
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

                // Auto-refresh checkbox with persistence
                .append("let interval;")
                .append("document.getElementById('autoRefresh').addEventListener('change',function(){")
                .append("localStorage.setItem('autoRefresh',this.checked);")
                .append("if(this.checked) interval=setInterval(()=>location.reload(),5000); else clearInterval(interval);")
                .append("});")
                .append("if(localStorage.getItem('autoRefresh')==='true'){document.getElementById('autoRefresh').checked=true;interval=setInterval(()=>location.reload(),5000);}")

                // Filter input
                .append("document.getElementById('filterInput').addEventListener('input',renderTable);")

                // Initial render
                .append("renderTable();renderQueue();")
                .append("</script>");

        html.append("</body></html>");

        resp.getWriter().write(html.toString());
    }
}
