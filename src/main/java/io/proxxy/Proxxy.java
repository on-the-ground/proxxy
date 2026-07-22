package io.proxxy;

import io.github.ontheground.daemonizer.Daemon;
import io.github.ontheground.daemonizer.PartitionedDaemon;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;

/**
 * Creates thread-safe proxies for Java interfaces by routing method calls to
 * per-partition daemon threads based on a caller-supplied routing function.
 *
 * <p>The routing function receives the invoked {@link Method} and its arguments,
 * and returns an integer whose value (mod {@code partitionCount}) determines
 * which partition thread handles the call. Calls that map to the same partition
 * always execute on the same thread against the same target instance — no
 * synchronization required.
 *
 * <pre>{@code
 * interface OrderProcessor {
 *     String process(String userId, String orderId);
 * }
 *
 * try (var handle = Proxxy.start(OrderProcessor.class, MyProcessor::new, 16, 1024,
 *         (method, args) -> ((String) args[0]).hashCode())) {
 *     OrderProcessor processor = handle.proxy();
 *     processor.process("alice", "ORD-1");  // always runs on alice's partition thread
 * }
 * }</pre>
 */
public final class Proxxy {

    private static final Object[] EMPTY_ARGS = {};

    private interface InvocationTask {
        Object invoke(Object target) throws Throwable;
    }

    private record Invocation(InvocationTask task, Reply<Object> reply, int routingHash) {}

    /**
     * Holds the proxy instance and the lifecycle of its backing daemon threads.
     * Must be closed when the proxy is no longer needed to release daemon threads.
     *
     * @param <T> the proxied interface type
     */
    public record ProxyHandle<T>(T proxy, AutoCloseable closeable) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            closeable.close();
        }
    }

    private Proxxy() {}

    /**
     * Creates a partitioned proxy for the given interface.
     *
     * <p>{@code targetFactory} is invoked {@code partitionCount} times to produce one
     * independent target instance per partition. The {@code router} function is called
     * on every invocation to determine the target partition; its return value is taken
     * modulo {@code partitionCount}, so any integer is valid.
     *
     * <p>The returned {@link ProxyHandle} must be closed to shut down the backing daemon
     * threads. Invocations already enqueued by the time {@code close()} is called are drained
     * and executed before shutdown completes. An invocation whose {@code pushEvent} call races
     * with a concurrent {@code close()} from another thread is not covered by this guarantee —
     * callers should ensure all in-flight calls have returned before closing.
     *
     * @param interfaceType          the interface to proxy; must be an interface
     * @param targetFactory          called once per partition to produce a target instance
     * @param partitionCount         number of independent partitions (threads + target instances); must be positive
     * @param bufferSizePerPartition capacity of each partition's event queue
     * @param router                 maps (method, args) to a routing hash; any int is valid;
     *                               unchecked exceptions thrown by the router propagate directly to the caller
     * @param <T>                    the interface type
     * @return a handle holding the proxy and its lifecycle
     * @throws IllegalArgumentException if {@code interfaceType} is not an interface, or if
     *                                  {@code partitionCount} is not positive
     */
    @SuppressWarnings("unchecked")
    public static <T> ProxyHandle<T> start(
            Class<T> interfaceType,
            Supplier<T> targetFactory,
            int partitionCount,
            int bufferSizePerPartition,
            ToIntBiFunction<Method, Object[]> router) {

        Objects.requireNonNull(interfaceType);
        Objects.requireNonNull(targetFactory);
        Objects.requireNonNull(router);

        if (!interfaceType.isInterface()) {
            throw new IllegalArgumentException(interfaceType.getName() + " is not an interface.");
        }
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("partitionCount must be greater than 0.");
        }

        T[] targets = (T[]) new Object[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            targets[i] = targetFactory.get();
        }

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Map<Method, MethodHandle> methodHandles = new HashMap<>();
        for (Method method : interfaceType.getMethods()) {
            try {
                method.setAccessible(true);
                methodHandles.put(method, lookup.unreflect(method));
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Cannot access method: " + method, e);
            }
        }

        var daemon = createDaemon(targets, bufferSizePerPartition);

        // Runs on the caller thread: intercepts the call and dispatches it into the queue.
        InvocationHandler dispatchHandler = (proxy, method, args) -> {

            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Proxxy[" + interfaceType.getSimpleName() + " x" + partitionCount + "]";
                    default -> throw new AssertionError("Unexpected Object method: " + method.getName());
                };
            }

            Object[] actualArgs = args == null ? EMPTY_ARGS : args;
            int routingHash = router.applyAsInt(method, actualArgs) & 0x7fff_ffff;

            boolean isVoid = method.getReturnType() == void.class;
            Reply<Object> reply = isVoid ? null : new Reply<>();

            MethodHandle mh = methodHandles.get(method);
            var invocation = new Invocation(target -> {
                Object[] argsWithTarget = new Object[actualArgs.length + 1];
                argsWithTarget[0] = target;
                System.arraycopy(actualArgs, 0, argsWithTarget, 1, actualArgs.length);
                return mh.invokeWithArguments(argsWithTarget);
            }, reply, routingHash);

            try {
                if (!daemon.pushEvent(invocation)) {
                    throw new IllegalStateException("Daemon already closed.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while enqueueing invocation.", e);
            }

            return isVoid ? null : reply.await();
        };

        T proxy = (T) Proxy.newProxyInstance(interfaceType.getClassLoader(), new Class<?>[]{interfaceType}, dispatchHandler);

        return new ProxyHandle<>(proxy, daemon);
    }

    private static PartitionedDaemon<Invocation> createDaemon(Object[] targets, int bufferSizePerPartition) {
        return new PartitionedDaemon<>(
                idx -> new Daemon<>(bufferSizePerPartition, invocationConsumerFor(targets[idx])),
                targets.length,
                Invocation::routingHash);
    }

    // Runs on the partition's daemon thread: dequeues and executes a queued invocation against its bound target.
    private static BiConsumer<Invocation, Thread> invocationConsumerFor(Object target) {
        return (invocation, thread) -> {
            Reply<Object> reply = invocation.reply();
            if (reply == null) {
                try {
                    invocation.task().invoke(target);
                } catch (Throwable t) {
                    thread.getUncaughtExceptionHandler().uncaughtException(thread, t);
                }
                return;
            }
            try {
                reply.send(invocation.task().invoke(target));
            } catch (Throwable t) {
                reply.fail(t);
            }
        };
    }
}
