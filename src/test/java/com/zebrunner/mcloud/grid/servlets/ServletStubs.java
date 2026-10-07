package com.zebrunner.mcloud.grid.servlets;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal servlet request/response stubs without a servlet container.
 */
final class ServletStubs {

    private ServletStubs() {
        //hide
    }

    interface Handler {
        Object handle(String method, Object[] args);
    }

    static <T> T stub(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> handler.handle(method.getName(), args)));
    }

    static HttpServletRequest request() {
        return stub(HttpServletRequest.class, (method, args) -> null);
    }

    /**
     * Response recording the body and the single-argument calls (setStatus, setContentType, ...).
     */
    static final class Response {
        final StringWriter body = new StringWriter();
        final Map<String, Object> calls = new HashMap<>();
        private final PrintWriter writer = new PrintWriter(body);
        final HttpServletResponse servletResponse = stub(HttpServletResponse.class, (method, args) -> {
            if ("getWriter".equals(method)) {
                return writer;
            }
            if (args != null && args.length == 1) {
                calls.put(method, args[0]);
            }
            return null;
        });

        String body() {
            writer.flush();
            return body.toString();
        }
    }
}
