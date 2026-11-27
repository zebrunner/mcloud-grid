package com.zebrunner.mcloud.grid.servlets;

import com.zebrunner.mcloud.grid.NodeRegistrations;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.web.servlet.RegistrationServlet;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * The registration of the nodes, {@code /grid/register}: the registration of Selenium (RegistrationServlet: the request,
 * the proxy of the node with its checks, the node added in the background) in a thread of {@link NodeRegistrations}
 * instead of the thread of Jetty, which waits for it {@link NodeRegistrations#DEADLINE} at most. A registration that hangs
 * (e.g. a request to STF or to the node) is answered 503 and holds one thread of the registrations, not the threads of
 * the hub: the hub keeps answering the users, the status and the healthcheck. {@link #install} maps it in place of
 * RegistrationServlet of Selenium.
 */
public class NodeRegistration extends RegistrationServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(NodeRegistration.class.getName());
    static final String NAME = "NodeRegistration";
    static final String PATH = "/grid/register/*";
    // a registration request of zebrunner/appium has 1-2 KB
    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int RETRY_AFTER_SECONDS = 5;

    private final transient NodeRegistrations registrations;

    public NodeRegistration(GridRegistry registry, NodeRegistrations registrations) {
        super(registry);
        this.registrations = registrations;
    }

    /**
     * Maps the servlet to {@link #PATH} of the context of the hub, in place of RegistrationServlet of Selenium; the context
     * may be started.
     */
    public static void install(ServletContextHandler context, GridRegistry registry, NodeRegistrations registrations) {
        HubServlets.map(context, NAME, new NodeRegistration(registry, registrations), PATH);
        LOGGER.info("[NODE REGISTRATION] /grid/register checks the nodes in threads of their own, a node waits for its registration "
                + "up to " + registrations.deadline().toSeconds() + "s.");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        register(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        register(request, response);
    }

    private void register(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "The registration request is too large.");
            return;
        }
        HttpServletRequest detached = new Body(request, body);
        try {
            // the response is answered here: the registration may go on after the request is answered
            registrations.register(key(body), () -> process(detached, null));
        } catch (NodeRegistrations.Unavailable e) {
            LOGGER.fine(() -> "[NODE REGISTRATION] A node is answered 503: " + e.getMessage());
            response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getMessage());
            return;
        }
        super.reply(response, "ok");
    }

    /**
     * The registration of Selenium answers "ok" with the response it gets: the registration in the thread of the
     * registrations gets none, the request is answered by {@link #register}.
     */
    @Override
    protected void reply(HttpServletResponse response, String content) throws IOException {
        if (response != null) {
            super.reply(response, content);
        }
    }

    /**
     * @return the key of the registration request: a node sends the same one again and again
     */
    static String key(byte[] body) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * The request with its body read already: the registration of Selenium reads the body only, also after the request
     * of Jetty is answered.
     */
    static final class Body extends HttpServletRequestWrapper {
        private final byte[] body;

        Body(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            InputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() throws IOException {
                    return input.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    return input.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    try {
                        return input.available() == 0;
                    } catch (IOException e) {
                        return true;
                    }
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("The body is read already");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
    }
}
