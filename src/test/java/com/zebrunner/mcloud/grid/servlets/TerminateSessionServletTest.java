package com.zebrunner.mcloud.grid.servlets;

import com.zebrunner.mcloud.grid.GridFixtures;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Map;

/**
 * Without STF there is nothing to check the key against (STF-enabled cases: TerminateSessionServletStfTest).
 */
public class TerminateSessionServletTest {

    @Test
    public void notAvailableWithoutStf() throws Exception {
        ServletStubs.Response response = new ServletStubs.Response();

        new TerminateSessionServlet(GridFixtures.registry())
                .doPost(ServletStubs.request(Map.of("Authorization", "Bearer key"), Map.of("udid", "x")), response.servletResponse);

        Assert.assertEquals(response.calls.get("setStatus"), 501);
        Assert.assertTrue(response.body().contains("needs the STF integration"), response.body());
    }

    @Test
    public void parsesKeyOfAuthorizationHeader() {
        Assert.assertEquals(TerminateSessionServlet.parseToken("Bearer abc"), "abc");
        Assert.assertEquals(TerminateSessionServlet.parseToken("  bearer   abc  "), "abc");
        Assert.assertEquals(TerminateSessionServlet.parseToken("abc"), "abc");
        Assert.assertEquals(TerminateSessionServlet.parseToken("Bearer "), "");
        Assert.assertEquals(TerminateSessionServlet.parseToken("Bearer"), "");
        Assert.assertEquals(TerminateSessionServlet.parseToken(null), "");
    }
}
