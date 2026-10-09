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
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpStatus;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.web.servlet.RegistryBasedServlet;
import org.openqa.selenium.Capabilities;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Servlet that retrieves information about connected nodes.
 * 
 * @author Alex Khursevich (alex@qaprosoft.com)
 */
public class ProxyInfo extends RegistryBasedServlet {
    private static final long serialVersionUID = 1224921425278259572L;
    private static final Logger LOGGER = Logger.getLogger(ProxyInfo.class.getName());

    // capabilities are serialized as their values map: bean serialization of MutableCapabilities exposes only the names
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new SimpleModule().addSerializer(Capabilities.class, new JsonSerializer<Capabilities>() {
                @Override
                public void serialize(Capabilities value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
                    generator.writeObject(value.asMap());
                }
            }));

    public ProxyInfo() {
        this(null);
    }

    public ProxyInfo(GridRegistry registry) {
        super(registry);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        process(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        process(req, resp);
    }

    protected void process(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<RegistrationRequest> proxies = new ArrayList<>();
        for (RemoteProxy proxy : getRegistry().getAllProxies()) {
            proxies.add(proxy.getOriginalRegistrationRequest());
        }
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String body;
        try {
            body = MAPPER.writeValueAsString(proxies);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Could not serialize proxies info", e);
            response.sendError(HttpStatus.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            return;
        }
        // the status must be set before the body is written, otherwise it is ignored
        response.setStatus(HttpStatus.SC_OK);
        response.getWriter().write(body);
        response.getWriter().flush();
    }
}
