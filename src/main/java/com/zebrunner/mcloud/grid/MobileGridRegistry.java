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
package com.zebrunner.mcloud.grid;

import java.lang.reflect.Field;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.internal.DefaultGridRegistry;
import org.openqa.grid.internal.SessionTerminationReason;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.web.Hub;

/**
 * The registry of the hub ("registry" of the hub config, see generate_config).
 * <p>
 * DefaultGridRegistry ends a session in a thread of its own by its slot, whatever session the slot holds by then. A late
 * termination of a session (a command of the client after the end of its session, the cleanup thread) released the
 * next session of the device while it was being created: its afterSession failed, Selenium left the slot "being
 * released" forever, and the device stayed busy until the restart of the hub. Here only the slot that still holds the
 * terminated session is released, and the slot is released even if afterSession fails.
 */
public class MobileGridRegistry extends DefaultGridRegistry {
    private static final Logger LOGGER = Logger.getLogger(MobileGridRegistry.class.getName());

    // the lock of DefaultGridRegistry: a slot gets a new session and ends its session under it
    private final ReentrantLock lock = registryLock(this);

    public MobileGridRegistry() {
        super();
    }

    public MobileGridRegistry(Hub hub) {
        super(hub);
    }

    @Override
    public void terminate(TestSession session, SessionTerminationReason reason) {
        // the release (afterSession: STF) may take long, the caller does not wait
        new Thread(() -> release(session, reason)).start();
    }

    void release(TestSession session, SessionTerminationReason reason) {
        TestSlot slot = session.getSlot();
        lock.lock();
        try {
            if (slot.getSession() != session) {
                LOGGER.fine(() -> String.format("Session %s (%s) has already ended, the slot is not released.", session, reason));
                return;
            }
            if (!slot.startReleaseProcess()) {
                return;
            }
        } finally {
            lock.unlock();
        }

        if (!slot.performAfterSessionEvent()) {
            LOGGER.warning(() -> String.format("afterSession failed for session %s, the slot is released anyway.", session));
        }

        lock.lock();
        try {
            // removes the session from the active ones and frees the slot, as the release of DefaultGridRegistry
            forceRelease(slot, reason);
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock registryLock(DefaultGridRegistry registry) {
        try {
            Field field = DefaultGridRegistry.class.getDeclaredField("lock");
            field.setAccessible(true);
            return (ReentrantLock) field.get(registry);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new GridException("Could not access the lock of DefaultGridRegistry: " + e.getMessage(), e);
        }
    }
}
