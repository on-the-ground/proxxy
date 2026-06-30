# proxxy

A tiny Java library that wraps any interface in a thread-safe, partitioned proxy.  
Methods are routed to dedicated daemon threads by an **affinity key**, so calls sharing the same key always execute on the same thread — no locks needed.

## How it works

1. You annotate one parameter on each interface method with `@Proxxy.AffinityKey`.
2. `Proxxy.start()` spawns *N* daemon threads and creates one target instance per thread (via a factory you provide).
3. At runtime, each call is hashed by its affinity key and dispatched to the matching thread, where it runs against that thread's private target instance.

Same key → same thread → same instance. Different keys may run concurrently on different threads, but each thread owns its instance exclusively — no sharing, no synchronization required.

## Usage

```java
interface OrderProcessor {
    String process(@Proxxy.AffinityKey String userId, String orderId);
}

// Each of the 16 partitions gets its own MyProcessor instance
try (var handle = Proxxy.start(OrderProcessor.class, MyProcessor::new, 16, 1024)) {
    OrderProcessor proxy = handle.proxy();

    proxy.process("alice", "ORD-1");  // always runs on alice's partition thread
    proxy.process("alice", "ORD-2");  // same thread, same MyProcessor instance
    proxy.process("bob",   "ORD-3");  // bob's partition — different instance
}
```

`ProxyHandle` implements `AutoCloseable`; closing it shuts down the daemon threads.

## Rules

- Every method on the proxied interface must have **exactly one** `@Proxxy.AffinityKey` parameter. `Proxxy.start()` throws `IllegalArgumentException` at startup if this is violated.
- `void` methods are fire-and-forget: the caller returns immediately and exceptions from the target are silently dropped.
- Non-`void` methods block until the result (or exception) is returned from the partition thread.

## Requirements

- Java 25+

## Dependency

**Gradle (Kotlin DSL)**
```kotlin
implementation("io.github.joohyung-park:proxxy:0.2.1")
```

**Gradle (Groovy DSL)**
```groovy
implementation 'io.github.joohyung-park:proxxy:0.2.1'
```

**Maven**
```xml
<dependency>
    <groupId>io.github.joohyung-park</groupId>
    <artifactId>proxxy</artifactId>
    <version>0.2.1</version>
</dependency>
```

## License

MIT
