package com.zebrunner.mcloud.grid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.BaseRemoteProxy;
import org.openqa.grid.internal.DefaultGridRegistry;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.SessionTerminationReason;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.internal.listeners.TestSessionListener;
import org.openqa.grid.internal.utils.configuration.GridNodeConfiguration;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MobileGridRegistryTest {

    static final class Proxy extends BaseRemoteProxy implements TestSessionListener {
        volatile boolean failAfterSession;
        int afterSessions;

        Proxy(GridRegistry registry) {
            super(request(), registry);
        }

        private static RegistrationRequest request() {
            GridNodeConfiguration configuration = new GridNodeConfiguration();
            configuration.id = "http://node:4723";
            configuration.timeout = 0;
            Map<String, Object> capabilities = new HashMap<>();
            capabilities.put(CapabilityType.PLATFORM_NAME, "ANDROID");
            capabilities.put(RegistrationRequest.MAX_INSTANCES, 1);
            configuration.capabilities = new ArrayList<>(List.of(new DesiredCapabilities(capabilities)));
            return new RegistrationRequest(configuration);
        }

        @Override
        public void beforeSession(TestSession session) {
        }

        @Override
        public void afterSession(TestSession session) {
            afterSessions++;
            if (failAfterSession) {
                throw new IllegalStateException("afterSession failed");
            }
        }
    }

    private static TestSession newSession(TestSlot slot) {
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put(CapabilityType.PLATFORM_NAME, "ANDROID");
        TestSession session = slot.getNewSession(capabilities);
        Assert.assertNotNull(session, "the slot is expected to be free");
        return session;
    }

    @Test
    public void lateTerminationDoesNotReleaseTheNextSession() {
        MobileGridRegistry registry = new MobileGridRegistry();
        Proxy proxy = new Proxy(registry);
        TestSlot slot = proxy.getTestSlots().get(0);

        TestSession first = newSession(slot);
        registry.release(first, SessionTerminationReason.CLIENT_STOPPED_SESSION);
        Assert.assertNull(slot.getSession());

        TestSession second = newSession(slot);
        // e.g. a command of the client after the end of the first session
        registry.release(first, SessionTerminationReason.CLIENT_STOPPED_SESSION);

        Assert.assertSame(slot.getSession(), second);
        Assert.assertEquals(proxy.afterSessions, 1);
        registry.release(second, SessionTerminationReason.CLIENT_STOPPED_SESSION);
        Assert.assertNull(slot.getSession(), "the next session must stay releasable");
        Assert.assertEquals(proxy.afterSessions, 2);
    }

    @Test
    public void slotIsReleasedWhenAfterSessionFails() {
        MobileGridRegistry registry = new MobileGridRegistry();
        Proxy proxy = new Proxy(registry);
        TestSlot slot = proxy.getTestSlots().get(0);
        proxy.failAfterSession = true;

        TestSession session = newSession(slot);
        registry.release(session, SessionTerminationReason.TIMEOUT);

        Assert.assertNull(slot.getSession());
        newSession(slot);
    }

    @Test
    public void defaultRegistryReleasesTheNextSessionOnLateTermination() throws InterruptedException {
        // the behavior that is fixed: DefaultGridRegistry releases whatever session the slot holds
        DefaultGridRegistry registry = new DefaultGridRegistry();
        Proxy proxy = new Proxy(registry);
        TestSlot slot = proxy.getTestSlots().get(0);

        TestSession first = newSession(slot);
        registry.forceRelease(slot, SessionTerminationReason.CLIENT_STOPPED_SESSION);
        TestSession second = newSession(slot);
        registry.terminate(first, SessionTerminationReason.CLIENT_STOPPED_SESSION);
        for (int i = 0; i < 50 && slot.getSession() != null; i++) {
            Thread.sleep(20);
        }
        Assert.assertNull(slot.getSession(), "DefaultGridRegistry ended " + second);
    }
}
