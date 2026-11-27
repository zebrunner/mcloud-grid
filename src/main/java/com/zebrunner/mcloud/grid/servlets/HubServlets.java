package com.zebrunner.mcloud.grid.servlets;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.servlet.Source;

import javax.servlet.Servlet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Maps the servlets of the image in place of the ones of Selenium in the context of the hub, which has no setting for them.
 */
final class HubServlets {

    private HubServlets() {
        //hide
    }

    /**
     * Maps the servlet to the paths, in place of the servlets of Selenium mapped to any of them; the context may be started.
     */
    static void map(ServletContextHandler context, String name, Servlet servlet, String... paths) {
        ServletHandler handler = context.getServletHandler();
        ServletHolder holder = handler.newServletHolder(Source.EMBEDDED);
        holder.setName(name);
        holder.setServlet(servlet);
        handler.addServlet(holder);
        List<ServletMapping> mappings = new ArrayList<>();
        for (ServletMapping mapping : handler.getServletMappings()) {
            if (Arrays.stream(paths).noneMatch(mapping::containsPathSpec)) {
                mappings.add(mapping);
            }
        }
        ServletMapping mapping = new ServletMapping(Source.EMBEDDED);
        mapping.setServletName(name);
        mapping.setPathSpecs(paths);
        mappings.add(mapping);
        handler.setServletMappings(mappings.toArray(new ServletMapping[0]));
    }
}
