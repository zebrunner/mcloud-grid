package com.zebrunner.mcloud.grid;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The registrations of the nodes run in threads of their own: a registration that hangs is answered after the deadline,
 * blocks no other node beyond the threads of the registrations, is logged with its stack and makes the hub unhealthy.
 */
public class NodeRegistrationsTest {
    private static final Duration DEADLINE = Duration.ofMillis(300);
    private static final Duration STUCK = Duration.ofMillis(1500);

    private static NodeRegistrations registrations(int threads, int queue) {
        return new NodeRegistrations(threads, queue, DEADLINE, STUCK, Instant::now);
    }

    private static NodeRegistrations.Unavailable unavailable(NodeRegistrations registrations, String key, NodeRegistrations.Registration registration) {
        try {
            registrations.register(key, registration);
        } catch (NodeRegistrations.Unavailable e) {
            return e;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("The registration is done");
    }

    @Test
    public void aRegistrationIsDoneInItsThreadAndItsFailureIsThrown() throws Exception {
        NodeRegistrations registrations = registrations(2, 2);
        AtomicInteger runs = new AtomicInteger();

        registrations.register("node-1", runs::incrementAndGet);
        registrations.register("node-1", runs::incrementAndGet);
        Assert.assertEquals(runs.get(), 2, "a done registration is not in progress any more");

        IllegalStateException rejected = Assert.expectThrows(IllegalStateException.class,
                () -> registrations.register("node-1", () -> {
                    throw new IllegalStateException("Node is rejected.");
                }));
        Assert.assertEquals(rejected.getMessage(), "Node is rejected.");
        IOException failed = Assert.expectThrows(IOException.class, () -> registrations.register("node-1", () -> {
            throw new IOException("broken request");
        }));
        Assert.assertEquals(failed.getMessage(), "broken request");
        Assert.assertEquals(registrations.pending(), 0);
        Assert.assertTrue(registrations.problem().isEmpty());
        Assert.assertEquals(new NodeRegistrations().deadline(), NodeRegistrations.DEADLINE);
        Assert.assertTrue(NodeRegistrations.DEADLINE.compareTo(Duration.ofSeconds(60)) < 0, "a node gets the answer before stf-proxy gives up");
    }

    @Test
    public void aHangingRegistrationIsAnsweredAfterTheDeadlineAndLoggedWithItsStack() throws Exception {
        NodeRegistrations registrations = registrations(2, 2);
        CountDownLatch stf = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        // the tests log SEVERE only (src/test/resources/logging.properties)
        Logger logger = Logger.getLogger(NodeRegistrations.class.getName());
        Level level = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            long start = System.nanoTime();
            NodeRegistrations.Unavailable timedOut = unavailable(registrations, "node-1", () -> {
                awaitStf(stf);
                done.incrementAndGet();
            });
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Assert.assertTrue(waited >= DEADLINE.toMillis() && waited < DEADLINE.toMillis() + 2000, waited + " ms");
            Assert.assertTrue(timedOut.getMessage().startsWith("The hub did not register the node within"), timedOut.getMessage());

            // the node registers again every 5 seconds: it is answered at once while its registration runs
            start = System.nanoTime();
            NodeRegistrations.Unavailable again = unavailable(registrations, "node-1", done::incrementAndGet);
            Assert.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < DEADLINE.toMillis(), "answered at once");
            Assert.assertEquals(again.getMessage(), "The hub is still registering this node, it registers again later.");

            // another node registers in another thread
            registrations.register("node-2", done::incrementAndGet);
            Assert.assertEquals(done.get(), 1);

            Thread.sleep(100);
            registrations.reportSlow();
            Assert.assertEquals(records.stream().filter(record -> record.getLevel() == Level.WARNING).count(), 1, records.toString());
            int warnings = records.size();
            registrations.reportSlow();
            Assert.assertEquals(records.size(), warnings, "logged once");

            Assert.assertTrue(registrations.problem().isEmpty(), "slow, not stuck yet");
            Thread.sleep(STUCK.toMillis() + 100);
            Assert.assertTrue(registrations.problem().orElseThrow().startsWith("a registration of a node has been running for"),
                    registrations.problem().toString());

            // STF answers at last: the registration is done (the hub adds the node), the hub is healthy again
            stf.countDown();
            waitFor(() -> registrations.pending() == 0);
            Assert.assertEquals(done.get(), 2);
            Assert.assertTrue(registrations.problem().isEmpty());
            registrations.register("node-1", done::incrementAndGet);
            Assert.assertEquals(done.get(), 3);
        } finally {
            stf.countDown();
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
    }

    @Test
    public void theStackOfAHangingRegistrationIsLogged() throws Exception {
        NodeRegistrations registrations = registrations(1, 1);
        CountDownLatch stf = new CountDownLatch(1);
        List<String> messages = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage() == null ? "" : record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(NodeRegistrations.class.getName());
        Level level = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            unavailable(registrations, "node-1", () -> awaitStf(stf));
            registrations.reportSlow();
            String warning = messages.stream().filter(message -> message.startsWith("[NODE REGISTRATION] A registration of a node has been running"))
                    .findFirst().orElseThrow(() -> new AssertionError(messages.toString()));
            Assert.assertTrue(warning.contains("awaitStf"), "where it waits: " + warning);
            Assert.assertTrue(warning.contains("Node registration 1"), warning);
        } finally {
            stf.countDown();
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
    }

    @Test
    public void nodesAreAnsweredAtOnceWhileEveryThreadIsBusy() throws Exception {
        NodeRegistrations registrations = registrations(1, 1);
        CountDownLatch stf = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        try {
            unavailable(registrations, "node-1", () -> awaitStf(stf));
            Thread waiting = new Thread(() -> unavailable(registrations, "node-2", runs::incrementAndGet));
            waiting.start();
            waitFor(() -> registrations.pending() == 2);

            long start = System.nanoTime();
            NodeRegistrations.Unavailable busy = unavailable(registrations, "node-3", runs::incrementAndGet);
            Assert.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < DEADLINE.toMillis(), "answered at once");
            Assert.assertEquals(busy.getMessage(), "The hub registers 1 nodes at once and 1 more wait, the node registers again later.");

            // the waiting registration of a node that was answered is dropped: the node sends it again
            waiting.join(5000);
            Assert.assertEquals(registrations.pending(), 1);
            stf.countDown();
            waitFor(() -> registrations.pending() == 0);
            Assert.assertEquals(runs.get(), 0);
        } finally {
            stf.countDown();
        }
    }

    private static void awaitStf(CountDownLatch stf) {
        try {
            stf.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Assert.assertTrue(condition.getAsBoolean());
    }
}
