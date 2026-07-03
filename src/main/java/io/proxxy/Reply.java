package io.proxxy;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A one-shot reply channel for request-reply hand-off between threads.
 *
 * <p>The caller creates a {@code Reply}, hands it to the partition thread as part of an
 * invocation, and blocks on {@link #await()}. The partition thread calls {@link #send} to
 * deliver the result and unblock the caller, {@link #fail} to unblock it with a thrown
 * cause, or {@link #cancel} to unblock it with a {@link CancellationException}.
 *
 * <pre>{@code
 * var reply = new Reply<String>();
 * // ... hand `reply` to the partition thread, which eventually calls reply.send("hello") ...
 * String result = reply.await();  // blocks until the partition thread settles the reply
 * }</pre>
 *
 * @param <R> the result type
 */
public final class Reply<R> {

    /** Creates a new, unsettled reply channel. */
    public Reply() {}

    private final CompletableFuture<R> future = new CompletableFuture<>();

    /**
     * Sends {@code result} to the caller, unblocking any thread waiting on {@link #await()}.
     *
     * @param result the result to deliver
     */
    public void send(R result) { future.complete(result); }

    /**
     * Cancels this reply channel, unblocking any thread waiting on {@link #await()} with a
     * {@link CancellationException}.
     */
    public void cancel() { future.cancel(false); }

    /**
     * Fails this reply channel, unblocking any thread waiting on {@link #await()} with the
     * given throwable.
     *
     * @param cause the throwable to deliver
     */
    public void fail(Throwable cause) { future.completeExceptionally(cause); }

    /**
     * Blocks until the partition thread calls {@link #send}, {@link #fail}, or {@link #cancel}.
     *
     * @return the result passed to {@link #send}
     * @throws CancellationException if {@link #cancel} was called
     * @throws Exception if the handler completed with an exceptional result
     */
    public R await() throws Exception {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Error err) throw err;
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    /**
     * Blocks until the handler settles this reply, or the timeout elapses.
     *
     * @param timeout maximum time to wait
     * @param unit    time unit of {@code timeout}
     * @return the result passed to {@link #send}
     * @throws TimeoutException      if the timeout elapses before the reply is settled
     * @throws CancellationException if {@link #cancel} was called
     * @throws Exception             if the handler completed with an exceptional result
     */
    public R await(long timeout, TimeUnit unit) throws Exception {
        try {
            return future.get(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Error err) throw err;
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }
}
