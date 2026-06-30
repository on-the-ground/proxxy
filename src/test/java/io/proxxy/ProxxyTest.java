package io.proxxy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntBiFunction;

import static org.junit.jupiter.api.Assertions.*;

class ProxxyTest {

    interface Greeter {
        String greet(String userId);
    }

    interface Counter {
        int increment(String key);
    }

    interface Notifier {
        void notify(String key);
    }

    interface Risky {
        String run(String key) throws IOException;
    }

    interface Blocking {
        String block(String key) throws Exception;
    }

    private static final ToIntBiFunction<Method, Object[]> BY_FIRST_ARG =
            (method, args) -> args[0].hashCode();

    private static final ToIntBiFunction<Method, Object[]> FIXED_ZERO =
            (method, args) -> 0;

    @Test
    void sameRoutingKeyAlwaysGoesToSameThread() throws Exception {
        Set<Long> threadIds = ConcurrentHashMap.newKeySet();

        try (var handle = Proxxy.start(Greeter.class, () -> userId -> {
            threadIds.add(Thread.currentThread().getId());
            return "Hello " + userId;
        }, 4, 64, BY_FIRST_ARG)) {
            for (int i = 0; i < 50; i++) {
                handle.proxy().greet("alice");
            }
        }

        assertEquals(1, threadIds.size());
    }

    @Test
    void targetFactoryIsCalledOncePerPartition() throws Exception {
        AtomicInteger instanceCount = new AtomicInteger();

        try (var handle = Proxxy.start(Counter.class, () -> {
            instanceCount.incrementAndGet();
            return key -> 0;
        }, 4, 64, BY_FIRST_ARG)) {
            handle.proxy().increment("x");
        }

        assertEquals(4, instanceCount.get());
    }

    @Test
    void differentRoutingKeysUseIndependentInstances() throws Exception {
        AtomicInteger[] counts = new AtomicInteger[]{
                new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new AtomicInteger()
        };
        AtomicInteger instanceIdx = new AtomicInteger();

        try (var handle = Proxxy.start(Counter.class, () -> {
            AtomicInteger mine = counts[instanceIdx.getAndIncrement()];
            return key -> mine.incrementAndGet();
        }, 4, 64, BY_FIRST_ARG)) {
            Counter proxy = handle.proxy();
            for (int i = 0; i < 20; i++) proxy.increment("alice");
            for (int i = 0; i < 20; i++) proxy.increment("bob");
        }

        int alicePartition = ("alice".hashCode() & 0x7fff_ffff) % 4;
        int bobPartition = ("bob".hashCode() & 0x7fff_ffff) % 4;
        assertEquals(20, counts[alicePartition].get());
        if (alicePartition != bobPartition) {
            assertEquals(20, counts[bobPartition].get());
        }
    }

    @Test
    void routerReceivesMethodAndArgs() throws Exception {
        AtomicReference<String> capturedMethodName = new AtomicReference<>();

        try (var handle = Proxxy.start(Greeter.class, () -> userId -> "hi", 1, 64,
                (method, args) -> {
                    capturedMethodName.set(method.getName());
                    return 0;
                })) {
            handle.proxy().greet("alice");
        }

        assertEquals("greet", capturedMethodName.get());
    }

    @Test
    void fixedRouterAlwaysUsesPartitionZero() throws Exception {
        Set<Long> threadIds = ConcurrentHashMap.newKeySet();

        try (var handle = Proxxy.start(Greeter.class, () -> userId -> {
            threadIds.add(Thread.currentThread().getId());
            return userId;
        }, 4, 64, FIXED_ZERO)) {
            handle.proxy().greet("alice");
            handle.proxy().greet("bob");
            handle.proxy().greet("carol");
        }

        assertEquals(1, threadIds.size());
    }

    @Test
    void voidMethodIsFireAndForget() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        try (var handle = Proxxy.start(Notifier.class, () -> key -> latch.countDown(), 2, 64, FIXED_ZERO)) {
            handle.proxy().notify("key");
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void exceptionFromTargetPropagatesUnwrapped() throws Exception {
        try (var handle = Proxxy.start(Risky.class,
                () -> key -> { throw new IOException("boom"); }, 2, 64, FIXED_ZERO)) {
            assertThrows(IOException.class, () -> handle.proxy().run("key"));
        }
    }

    @Test
    void voidMethodExceptionsReportedToUncaughtExceptionHandler() throws Exception {
        RuntimeException expected = new RuntimeException("void error");
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<Throwable> caught = new AtomicReference<>();

        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            caught.set(e);
            reported.countDown();
        });

        try (var handle = Proxxy.start(Notifier.class, () -> key -> { throw expected; }, 1, 64, FIXED_ZERO)) {
            handle.proxy().notify("key");
            assertTrue(reported.await(2, TimeUnit.SECONDS));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prev);
        }

        assertSame(expected, caught.get());
    }

    @Test
    void awaitRestoresInterruptFlagOnInterruption() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskProceed = new CountDownLatch(1);
        CountDownLatch taskDone = new CountDownLatch(1);
        AtomicBoolean interruptSeen = new AtomicBoolean();
        CountDownLatch callerDone = new CountDownLatch(1);

        try (var handle = Proxxy.start(Blocking.class, () -> key -> {
            taskStarted.countDown();
            taskProceed.await(5, TimeUnit.SECONDS);
            taskDone.countDown();
            return "done";
        }, 1, 64, FIXED_ZERO)) {

            Thread caller = new Thread(() -> {
                try {
                    handle.proxy().block("key");
                } catch (InterruptedException e) {
                    interruptSeen.set(Thread.currentThread().isInterrupted());
                } catch (Exception ignored) {
                } finally {
                    callerDone.countDown();
                }
            });
            caller.start();

            assertTrue(taskStarted.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(callerDone.await(2, TimeUnit.SECONDS));
            taskProceed.countDown();
            assertTrue(taskDone.await(2, TimeUnit.SECONDS));
        }

        assertTrue(interruptSeen.get());
    }

    @Test
    void replyAwaitTimesOutIfNeverSettled() {
        Reply<String> reply = new Reply<>();
        assertThrows(TimeoutException.class, () -> reply.await(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void rejectsNonInterfaceType() {
        assertThrows(IllegalArgumentException.class,
                () -> Proxxy.start(Object.class, Object::new, 2, 64, FIXED_ZERO));
    }

}
