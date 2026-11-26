/*******************************************************************************
 * Copyright 2018-2021 Zebrunner (https://zebrunner.com/).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.zebrunner.mcloud.grid.servlets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.servlet.Source;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.web.servlet.RegistryBasedServlet;
import org.openqa.selenium.json.Json;

/**
 * The node API of the hub, {@code GET /grid/api/proxy?id=<node id>}: whether the node of the id is registered. A node asks
 * it every few seconds and registers again when it is not (zebrunner/appium reads {@code success} and {@code msg}), the
 * healthcheck of the image asks it without an id.
 * <p>
 * Anyone who reaches the hub may ask it (the port of the nodes is public), so it answers about the given id only (the
 * exact id of a registered node) and never with the configuration or the capabilities of a node. {@link #install} maps
 * it instead of ProxyStatusServlet of Selenium, which answered with the registration request of the node (its URL, its
 * devices); only GET is answered.
 *
 * @author Alex Khursevich (alex@qaprosoft.com)
 */
public class ProxyInfo extends RegistryBasedServlet {
    private static final long serialVersionUID = 1224921425278259572L;
    private static final Logger LOGGER = Logger.getLogger(ProxyInfo.class.getName());
    static final String NAME = "ProxyInfo";
    static final String PATH = "/grid/api/proxy/*";
    private static final Json JSON = new Json();

    public ProxyInfo() {
        this(null);
    }

    public ProxyInfo(GridRegistry registry) {
        super(registry);
    }

    /**
     * Maps the servlet to {@link #PATH} of the context of the hub, in place of ProxyStatusServlet of Selenium (the hub has
     * no setting for it); the context may be started.
     */
    public static void install(ServletContextHandler context, GridRegistry registry) {
        ServletHandler handler = context.getServletHandler();
        ServletHolder holder = handler.newServletHolder(Source.EMBEDDED);
        holder.setName(NAME);
        holder.setServlet(new ProxyInfo(registry));
        handler.addServlet(holder);
        List<ServletMapping> mappings = new ArrayList<>();
        // the hub maps one path per servlet: the mapping of ProxyStatusServlet is dropped
        for (ServletMapping mapping : handler.getServletMappings()) {
            if (!mapping.containsPathSpec(PATH)) {
                mappings.add(mapping);
            }
        }
        ServletMapping mapping = new ServletMapping(Source.EMBEDDED);
        mapping.setServletName(NAME);
        mapping.setPathSpec(PATH);
        mappings.add(mapping);
        handler.setServletMappings(mappings.toArray(new ServletMapping[0]));
        LOGGER.info("[NODE REGISTRATION] /grid/api/proxy tells a node whether its id is registered, without the configuration of the nodes.");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String id = request.getParameter("id");
        Map<String, Object> answer = new TreeMap<>();
        if (StringUtils.isEmpty(id)) {
            answer.put("success", false);
            answer.put("msg", "The id of the node is missing: /grid/api/proxy?id=<node id>.");
        } else if (getRegistry().getProxyById(id) == null) {
            answer.put("success", false);
            answer.put("msg", "The node is not registered.");
        } else {
            answer.put("success", true);
            answer.put("msg", "The node is registered.");
            answer.put("id", id);
        }
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(HttpStatus.SC_OK);
        response.getWriter().write(JSON.toJson(answer));
        response.getWriter().flush();
    }
}
