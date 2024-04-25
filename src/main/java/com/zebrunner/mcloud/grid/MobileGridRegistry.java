package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.servlets.MitmProxyServlet;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.openqa.grid.internal.DefaultGridRegistry;
import org.openqa.grid.web.Hub;

import javax.servlet.Servlet;
import java.util.Map;

public class MobileGridRegistry extends DefaultGridRegistry {

    public MobileGridRegistry() {
        this(null);
    }

    public MobileGridRegistry(Hub hub) {
        super(hub);
    }

    @Override
    public void setHub(Hub hub) {
        super.setHub(hub);
        if (hub != null) {
            try {
                ((Map<String, Class<? extends Servlet>>) FieldUtils.readField(hub, "extraServlet", true))
                        .put("/proxy/*", MitmProxyServlet.class);
            } catch (Throwable e) {
                ExceptionUtils.rethrow(e);
            }
        }
    }

}
