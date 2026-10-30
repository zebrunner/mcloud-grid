// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// Based on org.openqa.grid.internal.DefaultGridRegistry of Selenium 3.141.59, changed by Zebrunner: the matcher
// prepares the devices outside of the lock of the registry, a queue of its own, only MobileRemoteProxy nodes, the
// timeouts of the hub, the devices of lost nodes are returned in STF, the queued requests are safe for the console.
package com.zebrunner.mcloud.grid;

import com.zebrunner.mcloud.grid.util.NodeRegistrationToken;
import com.zebrunner.mcloud.grid.util.SecretMasking;
import org.openqa.grid.common.exception.GridConfigurationException;
import org.openqa.grid.internal.ActiveTestSessions;
import org.openqa.grid.internal.BaseGridRegistry;
import org.openqa.grid.internal.ExternalSessionKey;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.ProxySet;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.SessionTerminationReason;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.internal.listeners.Prioritizer;
import org.openqa.grid.internal.listeners.RegistrationListener;
import org.openqa.grid.internal.listeners.SelfHealingProxy;
import org.openqa.grid.internal.utils.configuration.GridHubConfiguration;
import org.openqa.grid.web.Hub;
import org.openqa.grid.web.servlet.handler.RequestHandler;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The registry of the hub ("registry" of the hub config, see generate_config).
 * <ul>
 * <li>The matcher claims a free device for a queued request under the lock (no I/O), then the device is prepared
 * (the Appium status check, the reservation in STF) in a thread of its own without the lock: a slow STF or node no
 * longer stops the queueing, the end of the sessions and the registration of the nodes, and the devices are
 * prepared in parallel.</li>
 * <li>The queue removes a request by identity: RequestHandler equals any other request without a session, so the
 * queue of Selenium removes the first request without a session instead (e.g. the cleanup of a timed out request
 * removed another one).</li>
 * <li>A node names the class of its proxy itself: only MobileRemoteProxy nodes are added, the others would skip
 * its checks (NODE_REGISTRATION_TOKEN, NODE_ALLOWED_NETWORKS, the reachability, STF).</li>
 * <li>The timeouts of the hub (GRID_TIMEOUT, GRID_BROWSER_TIMEOUT) replace the ones of the nodes.</li>
 * <li>The device of a session of a lost node is returned in STF.</li>
 * </ul>
 */
public class MobileGridRegistry extends BaseGridRegistry implements GridRegistry {
    private static final Logger LOGGER = Logger.getLogger(MobileGridRegistry.class.getName());
    // the devices prepared at once: every one is a device of its own
    private static final int PREPARATION_THREADS = 32;
    // a request does not claim the device that failed it for a while, the other requests may
    static final Duration FAILED_CLAIM_BACKOFF = Duration.ofSeconds(10);
    private static final long MATCHER_PERIOD_SECONDS = 5;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition stateChanged = lock.newCondition();
    private final ProxySet proxies;
    private final ActiveTestSessions activeTestSessions = new ActiveTestSessions();
    // under the lock: the new session requests in the order of their arrival
    private final List<RequestHandler> queue = new ArrayList<>();
    private final Set<RemoteProxy> registeringProxies = ConcurrentHashMap.newKeySet();
    // under the lock: the requests whose device is being prepared, with the claimed session
    private final Map<RequestHandler, TestSession> preparing = new IdentityHashMap<>();
    // under the lock: the devices that failed a request recently, until when they are skipped for it
    private final Map<RequestHandler, Map<RemoteProxy, Instant>> failedClaims = new IdentityHashMap<>();
    private final Thread matcher = new Thread(this::match, "Matcher thread");
    private final ExecutorService preparation = Executors.newFixedThreadPool(PREPARATION_THREADS, new DaemonThreads("Device preparation"));
    // under the lock: a change of the queue, the devices or the sessions the matcher has not seen yet
    private boolean changed;
    private volatile boolean stop;

    public MobileGridRegistry() {
        this(null);
    }

    public MobileGridRegistry(Hub hub) {
        super(hub);
        proxies = new ProxySet(hub == null || hub.getConfiguration().throwOnCapabilityNotPresent);
        matcher.setDaemon(true);
        matcher.setUncaughtExceptionHandler((thread, e) -> LOGGER.log(Level.SEVERE, "Matcher thread stopped", e));
    }

    /**
     * Hub starts its registry before its server: the hub does not start without NODE_REGISTRATION_TOKEN.
     */
    @Override
    public void start() {
        checkRegistrationToken(System.getenv(NodeRegistrationToken.ENV));
        matcher.start();
    }

    static void checkRegistrationToken(String token) {
        try {
            NodeRegistrationToken.of(token);
        } catch (GridConfigurationException e) {
            LOGGER.severe("[CONFIGURATION] " + e.getMessage() + " The hub does not start.");
            throw e;
        }
    }

    @Override
    public void stop() {
        stop = true;
        matcher.interrupt();
        preparation.shutdownNow();
        lock.lock();
        try {
            queue.forEach(RequestHandler::stop);
        } finally {
            lock.unlock();
        }
        proxies.teardown();
    }

    /**
     * Only a MobileRemoteProxy is added: it is checked before (its constructor), the others are rejected before they
     * replace a registered node with the same id. The timeouts of the hub replace the ones of the node.
     */
    @Override
    public void add(RemoteProxy proxy) {
        if (proxy == null) {
            return;
        }
        if (!(proxy instanceof MobileRemoteProxy)) {
            MobileRemoteProxy.warnAboutRejectedNode(String.valueOf(proxy.getRemoteHost()), String.format(
                    "Node %s registers with the proxy %s, only %s is accepted, so it is not registered.",
                    proxy.getRemoteHost(), proxy.getClass().getName(), MobileRemoteProxy.class.getName()));
            // the cleanup thread of the proxy is already started; DefaultRemoteProxy then fails to stop the polling it has not started
            try {
                proxy.teardown();
            } catch (RuntimeException e) {
                LOGGER.finest(() -> "Teardown of the rejected proxy: " + e);
            }
            return;
        }
        LOGGER.info("Registered a node " + proxy);
        lock.lock();
        try {
            removeIfPresent(proxy);
            if (registeringProxies.contains(proxy)) {
                LOGGER.warning(String.format("Proxy '%s' is already queued for registration.", proxy));
                return;
            }
            applyHubTimeouts(proxy);
            registeringProxies.add(proxy);
            fireStateChanged();
        } finally {
            lock.unlock();
        }

        boolean listenerOk = true;
        try {
            if (proxy instanceof RegistrationListener) {
                ((RegistrationListener) proxy).beforeRegistration();
            }
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Error running the registration listener on " + proxy, t);
            listenerOk = false;
        }

        lock.lock();
        try {
            registeringProxies.remove(proxy);
            if (listenerOk) {
                if (proxy instanceof SelfHealingProxy) {
                    ((SelfHealingProxy) proxy).startPolling();
                }
                proxies.add(proxy);
                fireStateChanged();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * A node of zebrunner/appium sends 'timeout' 150 and 'browserTimeout' 0 (a command without a time limit, a hung
     * one keeps its device forever): the values of the hub config (GRID_TIMEOUT, GRID_BROWSER_TIMEOUT) are the ones of the sessions.
     * A 'timeout' 0 of the hub keeps the one of the node: the cleanup thread of the node is started by its own value, it
     * would end every session at once.
     */
    private void applyHubTimeouts(RemoteProxy proxy) {
        GridHubConfiguration hubConfiguration = getHub() == null ? null : getHub().getConfiguration();
        if (hubConfiguration != null && hubConfiguration.timeout != null && hubConfiguration.timeout > 0) {
            proxy.getConfig().timeout = hubConfiguration.timeout;
        }
        if (hubConfiguration != null && hubConfiguration.browserTimeout != null) {
            proxy.getConfig().browserTimeout = hubConfiguration.browserTimeout;
        }
    }

    @Override
    public void removeIfPresent(RemoteProxy proxy) {
        lock.lock();
        try {
            // the registered proxy, the given one may be a new object with the same id
            if (proxies.contains(proxy)) {
                LOGGER.warning(String.format("Cleaning up stale test sessions on the unregistered node %s", proxy));
                RemoteProxy registered = proxies.remove(proxy);
                registered.getTestSlots().forEach(testSlot -> forceRelease(testSlot, SessionTerminationReason.PROXY_REREGISTRATION));
                registered.teardown();
                fireStateChanged();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Selenium releases the slot without the events of the proxy: the device of the session would stay reserved in STF.
     */
    @Override
    public void forceRelease(TestSlot testSlot, SessionTerminationReason reason) {
        lock.lock();
        try {
            TestSession session = testSlot.getSession();
            if (session == null) {
                return;
            }
            // a session being prepared is returned in STF by its preparation
            boolean prepared = !preparing.containsValue(session);
            release(testSlot.getInternalKey(), reason);
            testSlot.doFinishRelease();
            if (prepared && testSlot.getProxy() instanceof MobileRemoteProxy) {
                MobileRemoteProxy proxy = (MobileRemoteProxy) testSlot.getProxy();
                runQuietly(() -> proxy.releaseLostSession(session, reason));
            }
            fireStateChanged();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void terminate(TestSession session, SessionTerminationReason reason) {
        // the release (afterSession: STF) may take long, the caller does not wait
        new Thread(() -> release(session.getSlot(), reason)).start();
    }

    private void release(TestSlot testSlot, SessionTerminationReason reason) {
        if (!testSlot.startReleaseProcess() || !testSlot.performAfterSessionEvent()) {
            return;
        }
        String internalKey = testSlot.getInternalKey();
        lock.lock();
        try {
            testSlot.finishReleaseProcess();
            release(internalKey, reason);
        } finally {
            lock.unlock();
        }
    }

    private void release(String internalKey, SessionTerminationReason reason) {
        if (internalKey == null) {
            return;
        }
        lock.lock();
        try {
            TestSession session = activeTestSessions.findSessionByInternalKey(internalKey);
            if (session == null) {
                LOGGER.fine(() -> "Tried to release session with internal key " + internalKey + " but couldn't find it.");
                return;
            }
            if (activeTestSessions.remove(session, reason)) {
                fireStateChanged();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addNewSessionRequest(RequestHandler handler) {
        lock.lock();
        try {
            proxies.verifyAbilityToHandleDesiredCapabilities(handler.getRequest().getDesiredCapabilities());
            queue.add(handler);
            fireStateChanged();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean removeNewSessionRequest(RequestHandler handler) {
        lock.lock();
        try {
            failedClaims.remove(handler);
            return queue.removeIf(queued -> queued == handler);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clearNewSessionRequests() {
        lock.lock();
        try {
            failedClaims.clear();
            queue.clear();
        } finally {
            lock.unlock();
        }
    }

    private void fireStateChanged() {
        changed = true;
        stateChanged.signalAll();
    }

    private void match() {
        while (!stop) {
            List<Claim> claims;
            lock.lock();
            try {
                // the period also ends the backoff of the failed claims
                if (!changed && !stateChanged.await(MATCHER_PERIOD_SECONDS, TimeUnit.SECONDS)) {
                    LOGGER.finest("The queue is matched after the period of the matcher.");
                }
                changed = false;
                claims = claimDevices();
            } catch (InterruptedException e) {
                continue;
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Unhandled exception in Matcher thread.", e);
                continue;
            } finally {
                lock.unlock();
            }
            for (Claim claim : claims) {
                try {
                    preparation.execute(() -> prepare(claim));
                } catch (RuntimeException e) {
                    // the registry is stopped
                    finish(claim, false);
                }
            }
        }
    }

    /**
     * Under the lock: a free device for every queued request without one, in the order of the prioritizer of the hub.
     * The requests stay in the queue until their devices are prepared.
     */
    private List<Claim> claimDevices() {
        Instant now = Instant.now();
        failedClaims.values().forEach(failed -> failed.values().removeIf(until -> until.isBefore(now)));
        failedClaims.values().removeIf(Map::isEmpty);

        List<Claim> claims = new ArrayList<>();
        Prioritizer prioritizer = getHub() == null ? null : getHub().getConfiguration().prioritizer;
        // the capabilities of a request being prepared are changed without the lock (zebrunner:slotCapabilities)
        List<RequestHandler> ordered = new ArrayList<>();
        for (RequestHandler handler : queue) {
            if (!preparing.containsKey(handler)) {
                ordered.add(handler);
            }
        }
        if (prioritizer != null) {
            // stable: the requests of the same priority in the order of their arrival
            ordered.sort(Comparator.comparing(handler -> handler.getRequest().getDesiredCapabilities(), prioritizer::compareTo));
        }
        for (RequestHandler handler : ordered) {
            Map<String, Object> capabilities = handler.getRequest().getDesiredCapabilities();
            Map<RemoteProxy, Instant> failed = failedClaims.getOrDefault(handler, Map.of());
            for (RemoteProxy proxy : proxies.getSorted()) {
                if (failed.containsKey(proxy)) {
                    continue;
                }
                TestSession session;
                try {
                    session = proxy instanceof MobileRemoteProxy
                            ? ((MobileRemoteProxy) proxy).claim(capabilities)
                            : proxy.getNewSession(capabilities);
                } catch (RuntimeException e) {
                    // the claims of the other requests are kept
                    LOGGER.log(Level.WARNING, String.format("The device of %s could not be claimed for a session.", proxy), e);
                    failed = new HashMap<>(failed);
                    failed.put(proxy, now.plus(FAILED_CLAIM_BACKOFF));
                    failedClaims.put(handler, failed);
                    continue;
                }
                if (session != null) {
                    preparing.put(handler, session);
                    claims.add(new Claim(handler, proxy, session));
                    break;
                }
            }
        }
        return claims;
    }

    /**
     * Without the lock: prepares the device (MobileRemoteProxy.prepare), then binds the session to its request.
     */
    private void prepare(Claim claim) {
        boolean prepared;
        try {
            prepared = !(claim.proxy instanceof MobileRemoteProxy) || ((MobileRemoteProxy) claim.proxy).prepare(claim.session);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, String.format("The preparation of the device of %s for a session failed.", claim.proxy), t);
            prepared = false;
        }
        finish(claim, prepared);
    }

    private void finish(Claim claim, boolean prepared) {
        boolean bound = false;
        lock.lock();
        try {
            preparing.remove(claim.handler);
            // the node may be lost or registered again meanwhile, the slot is then released by forceRelease
            boolean slotKept = proxies.getProxyById(claim.proxy.getId()) == claim.proxy
                    && claim.session.getSlot().getSession() == claim.session;
            // the request may be gone meanwhile: timed out, its client disconnected
            if (prepared && slotKept && queue.removeIf(queued -> queued == claim.handler)) {
                failedClaims.remove(claim.handler);
                activeTestSessions.add(claim.session);
                claim.handler.bindSession(claim.session);
                bound = true;
            } else {
                if (slotKept) {
                    claim.session.getSlot().doFinishRelease();
                }
                if (!prepared) {
                    failedClaims.computeIfAbsent(claim.handler, handler -> new HashMap<>())
                            .put(claim.proxy, Instant.now().plus(FAILED_CLAIM_BACKOFF));
                }
            }
            fireStateChanged();
        } finally {
            lock.unlock();
        }
        if (prepared && !bound && claim.proxy instanceof MobileRemoteProxy) {
            ((MobileRemoteProxy) claim.proxy).abandon(claim.session);
        }
    }

    private void runQuietly(Runnable task) {
        try {
            preparation.execute(task);
        } catch (RuntimeException e) {
            LOGGER.fine(() -> "The registry is stopped, the task is not run: " + e);
        }
    }

    @Override
    public void setThrowOnCapabilityNotPresent(boolean throwOnCapabilityNotPresent) {
        proxies.setThrowOnCapabilityNotPresent(throwOnCapabilityNotPresent);
    }

    @Override
    public ProxySet getAllProxies() {
        return proxies;
    }

    @Override
    public List<RemoteProxy> getUsedProxies() {
        return proxies.getBusyProxies();
    }

    @Override
    public TestSession getSession(ExternalSessionKey externalKey) {
        return activeTestSessions.findSessionByExternalKey(externalKey);
    }

    @Override
    public TestSession getExistingSession(ExternalSessionKey externalKey) {
        return activeTestSessions.getExistingSession(externalKey);
    }

    @Override
    public int getNewSessionRequestCount() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The queued session requests: the console of the hub (the only user) prints them as HTML.
     */
    @Override
    public Iterable<DesiredCapabilities> getDesiredCapabilities() {
        List<DesiredCapabilities> requests = new ArrayList<>();
        lock.lock();
        try {
            // the requests being prepared have their devices, their capabilities are changed without the lock
            for (RequestHandler handler : queue) {
                if (!preparing.containsKey(handler)) {
                    requests.add(new ConsoleCapabilities(new DesiredCapabilities(handler.getRequest().getDesiredCapabilities())));
                }
            }
        } finally {
            lock.unlock();
        }
        return requests;
    }

    @Override
    public Set<TestSession> getActiveSessions() {
        return activeTestSessions.unmodifiableSet();
    }

    @Override
    public RemoteProxy getProxyById(String id) {
        return proxies.getProxyById(id);
    }

    /**
     * A device claimed for a request, being prepared.
     */
    private static final class Claim {
        private final RequestHandler handler;
        private final RemoteProxy proxy;
        private final TestSession session;

        Claim(RequestHandler handler, RemoteProxy proxy, TestSession session) {
            this.handler = handler;
            this.proxy = proxy;
            this.session = session;
        }
    }

    private static final class DaemonThreads implements java.util.concurrent.ThreadFactory {
        private final String name;
        private final AtomicInteger count = new AtomicInteger();

        DaemonThreads(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, name + " " + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * Capabilities of a session request without secrets, escaped as HTML by toString().
     */
    static final class ConsoleCapabilities extends DesiredCapabilities {
        private static final long serialVersionUID = 1L;

        ConsoleCapabilities(DesiredCapabilities request) {
            super(SecretMasking.mask(request.asMap()));
        }

        @Override
        public String toString() {
            return ProxyHtmlRenderer.escape(super.toString());
        }
    }
}
