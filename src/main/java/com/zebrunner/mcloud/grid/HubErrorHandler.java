package com.zebrunner.mcloud.grid;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * The error pages of the hub (e.g. the answer of a rejected registration of a node): the status and the message of the
 * error only. The error page of Jetty adds the stack trace, the exception class and its causes, the servlet and the
 * version of Jetty, which tell whoever reaches the hub (its port of the nodes is public) about its internals. The hub logs
 * the error with its stack trace as before.
 */
public final class HubErrorHandler extends ErrorHandler {
    // the class names before a message, e.g. "javax.servlet.ServletException: java.lang.IllegalStateException: "
    private static final Pattern CLASS_PREFIX = Pattern.compile("^(?:[\\w$]+\\.)*[\\w$]*(?:Exception|Error|Throwable): ");

    HubErrorHandler() {
        setShowStacks(false);
        setShowServlet(false);
    }

    /**
     * Uses the error pages of the hub for the server and the context of the hub, and turns the version of Jetty off in
     * its answers (the Server header, the error pages).
     */
    static void install(Server server, ServletContextHandler context) {
        HubErrorHandler handler = new HubErrorHandler();
        server.setErrorHandler(handler);
        context.setErrorHandler(handler);
        for (Connector connector : server.getConnectors()) {
            HttpConnectionFactory http = connector.getConnectionFactory(HttpConnectionFactory.class);
            if (http != null) {
                http.getHttpConfiguration().setSendServerVersion(false);
            }
        }
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        Object failure = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (failure instanceof Throwable) {
            // the page shows the causes of the exception of the request whatever showStacks is
            request.setAttribute(RequestDispatcher.ERROR_MESSAGE, reason((Throwable) failure));
            request.removeAttribute(RequestDispatcher.ERROR_EXCEPTION);
            request.removeAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE);
        }
        super.handle(target, baseRequest, request, response);
    }

    /**
     * @return the first line of the message of the failure without the names of the exception classes, null without a
     *         message (the page shows the reason of the status then)
     */
    static String reason(Throwable failure) {
        String message = StringUtils.defaultString(failure.getMessage()).trim();
        // e.g. WebDriverException adds the build and the system info (the host, its address, its OS) on the next lines
        message = message.split("\\R", 2)[0].trim();
        String previous;
        do {
            previous = message;
            message = CLASS_PREFIX.matcher(message).replaceFirst("");
        } while (!message.equals(previous));
        return StringUtils.trimToNull(message);
    }
}
