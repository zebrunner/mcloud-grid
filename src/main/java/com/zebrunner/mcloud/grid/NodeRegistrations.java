package com.zebrunner.mcloud.grid;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Runs the registrations of the nodes (the checks of MobileRemoteProxy: the node token with STF, the reachability of the
 * node, its device in STF) in a few threads of their own, so a registration that hangs never holds a thread of Jetty: the
 * request of the node waits {@link #DEADLINE} at most and is answered 503 then, the node registers again later. A node
 * whose registration still runs is answered 503 at once (the nodes retry every 5 seconds), and so is every node while
 * all the threads are busy and {@link #QUEUE} registrations wait. A registration that runs longer than the deadline is
 * logged with the stack of its thread, one that runs longer than {@link #STUCK} makes the hub unhealthy
 * ({@link #problem()}, HubHealth).
 */
public final class NodeRegistrations {
    private static final Logger LOGGER = Logger.getLogger(NodeRegistrations.class.getName());
    static final int THREADS = 8;
    static final int QUEUE = 32;
    // below the timeouts of stf-proxy (60 s) and of the nodes: the node gets the answer of the hub
    static final Duration DEADLINE = Duration.ofSeconds(25);
    // longer than any registration that is only slow: the first one also returns the devices left reserved in STF
    static final Duration STUCK = Duration.ofMinutes(3);
    private static final Duration WATCHDOG_PERIOD = Duration.ofSeconds(5);
    private static final int MAX_STACK_FRAMES = 40;

    private final Duration deadline;
    private final Duration stuck;
    private final Supplier<Instant> clock;
    private final ThreadPoolExecutor executor;
    private final Map<String, Task> running = new ConcurrentHashMap<>();

    public NodeRegistrations() {
        this(THREADS, QUEUE, DEADLINE, STUCK, Instant::now);
    }

    NodeRegistrations(int threads, int queue, Duration deadline, Duration stuck, Supplier<Instant> clock) {
        this.deadline = deadline;
        this.stuck = stuck;
        this.clock = clock;
        this.executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue),
                new DaemonThreads("Node registration"), new ThreadPoolExecutor.AbortPolicy());
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(new DaemonThreads("Node registration watchdog"));
        watchdog.scheduleWithFixedDelay(this::reportSlow, WATCHDOG_PERIOD.toMillis(), WATCHDOG_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * A registration of a node.
     */
    @FunctionalInterface
    public interface Registration {
        void run() throws IOException;
    }

    /**
     * The hub cannot register the node now: the answer is 503, the node registers again later.
     */
    public static final class Unavailable extends Exception {
        private static final long serialVersionUID = 1L;

        Unavailable(String message) {
            super(message);
        }
    }

    /**
     * Runs the registration of the node of the key (the same key for the same node: its registration request) in a thread
     * of the registrations and waits for it up to the deadline. A registration that is not done by then goes on in its
     * thread: the hub adds the node when it is done.
     *
     * @throws Unavailable    the registration of the node is still running, the hub registers too many nodes now, or the
     *                        registration is not done within the deadline
     * @throws IOException    of the registration
     * @throws RuntimeException of the registration (e.g. the rejection of the node)
     */
    public void register(String key, Registration registration) throws Unavailable, IOException {
        Task task = new Task();
        if (running.putIfAbsent(key, task) != null) {
            throw new Unavailable("The hub is still registering this node, it registers again later.");
        }
        Future<?> future;
        try {
            future = executor.submit(() -> {
                if (!task.start(Thread.currentThread(), clock.get())) {
                    return null;
                }
                try {
                    registration.run();
                } finally {
                    task.finish();
                    running.remove(key, task);
                }
                return null;
            });
        } catch (RejectedExecutionException e) {
            running.remove(key, task);
            throw new Unavailable(String.format("The hub registers %s nodes at once and %s more wait, the node registers again later.",
                    executor.getMaximumPoolSize(), executor.getQueue().size()));
        }
        try {
            future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // a registration that did not start yet is dropped, a running one goes on (its node is added when it is done)
            if (task.cancel()) {
                future.cancel(false);
                running.remove(key, task);
            }
            throw new Unavailable(String.format("The hub did not register the node within %ss, it registers again later.", deadline.toSeconds()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("The hub is stopping.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        }
    }

    /**
     * @return why the registrations of the nodes do not work: a registration has been running for longer than
     *         {@link #STUCK} (its stack is in the log)
     */
    public Optional<String> problem() {
        Instant now = clock.get();
        long longest = running.values().stream()
                .map(task -> task.runningFor(now))
                .filter(time -> time.compareTo(stuck) > 0)
                .mapToLong(Duration::getSeconds)
                .max()
                .orElse(-1);
        return longest < 0 ? Optional.empty() : Optional.of(String.format(
                "a registration of a node has been running for %ss: the hub does not register the nodes (the log of the hub has its stack)", longest));
    }

    /**
     * @return how long a node waits for its registration
     */
    public Duration deadline() {
        return deadline;
    }

    /**
     * @return number of the registrations that run or wait now
     */
    int pending() {
        return running.size();
    }

    /**
     * Logs a registration that runs longer than the deadline once, with the stack of its thread: where it waits.
     */
    void reportSlow() {
        try {
            Instant now = clock.get();
            for (Task task : running.values()) {
                Thread thread = task.thread;
                if (thread != null && task.runningFor(now).compareTo(deadline) > 0 && task.markReported()) {
                    String stack = Arrays.stream(thread.getStackTrace())
                            .limit(MAX_STACK_FRAMES)
                            .map(frame -> "\tat " + frame)
                            .collect(Collectors.joining("\n"));
                    LOGGER.warning(() -> String.format("[NODE REGISTRATION] A registration of a node has been running for %ss (thread '%s', %s), "
                            + "its node is answered 503 and registers again later; it waits at:%n%s",
                            task.runningFor(now).getSeconds(), thread.getName(), thread.getState(), stack));
                }
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "The check of the registrations of the nodes failed.", e);
        }
    }

    private static final class Task {
        private static final int NEW = 0;
        private static final int STARTED = 1;
        private static final int CANCELLED = 2;

        private final AtomicInteger state = new AtomicInteger(NEW);
        private volatile Thread thread;
        private volatile Instant startedAt;
        private volatile boolean reported;

        boolean start(Thread current, Instant now) {
            if (!state.compareAndSet(NEW, STARTED)) {
                return false;
            }
            startedAt = now;
            thread = current;
            return true;
        }

        boolean cancel() {
            return state.compareAndSet(NEW, CANCELLED);
        }

        void finish() {
            if (reported) {
                LOGGER.info(() -> "[NODE REGISTRATION] The slow registration of a node is done.");
            }
            thread = null;
        }

        Duration runningFor(Instant now) {
            Instant started = startedAt;
            return started == null || thread == null ? Duration.ZERO : Duration.between(started, now);
        }

        synchronized boolean markReported() {
            if (reported) {
                return false;
            }
            reported = true;
            return true;
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
}
