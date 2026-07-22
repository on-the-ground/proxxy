# proxxy

A tiny Java library that wraps any interface in a thread-safe, partitioned proxy.  
Methods are routed to dedicated daemon threads by a caller-supplied **router function**, so calls sharing the same routing key always execute on the same thread — no locks needed.

## How it works

1. You supply a `router` function that maps each call — the invoked `Method` and its arguments — to an `int`.
2. `Proxxy.start()` spawns *N* daemon threads and creates one target instance per thread (via a factory you provide).
3. At runtime, each call is routed by `router` value (mod *N*) and dispatched to the matching thread, where it runs against that thread's private target instance.

Same routing value → same thread → same instance. Different values may run concurrently on different threads, but each thread owns its instance exclusively — no sharing, no synchronization required.

## Usage

```java
interface OrderProcessor {
    String process(String userId, String orderId);
}

// Each of the 16 partitions gets its own MyProcessor instance.
// The router picks the partition; here it routes by userId (the first argument).
try (var handle = Proxxy.start(OrderProcessor.class, MyProcessor::new, 16, 1024,
        (method, args) -> args[0].hashCode())) {
    OrderProcessor proxy = handle.proxy();

    proxy.process("alice", "ORD-1");  // always runs on alice's partition thread
    proxy.process("alice", "ORD-2");  // same thread, same MyProcessor instance
    proxy.process("bob",   "ORD-3");  // bob's partition — different instance
}
```

`ProxyHandle` implements `AutoCloseable`; closing it shuts down the daemon threads.

## Rules

- The `router` is called on **every** invocation; its return value is taken modulo `partitionCount`, so any `int` is valid. Unchecked exceptions thrown by the router propagate directly to the caller.
- `Proxxy.start()` throws `IllegalArgumentException` at startup if `interfaceType` is not an interface, or if `partitionCount` is not positive.
- `void` methods are fire-and-forget: the caller returns immediately and exceptions from the target are reported to the thread's uncaught-exception handler.
- Non-`void` methods block until the result (or exception) is returned from the partition thread.
- Invocations already enqueued when `close()` is called are drained and executed before shutdown completes. Calling `close()` concurrently with an in-flight call from another thread is not covered by this guarantee — make sure all in-flight calls have returned before closing.

## Requirements

- Java 25+

## Dependency

**Gradle (Kotlin DSL)**
```kotlin
implementation("io.github.joohyung-park:proxxy:0.2.3")
```

**Gradle (Groovy DSL)**
```groovy
implementation 'io.github.joohyung-park:proxxy:0.2.3'
```

**Maven**
```xml
<dependency>
    <groupId>io.github.joohyung-park</groupId>
    <artifactId>proxxy</artifactId>
    <version>0.2.3</version>
</dependency>
```

## License

MIT
