# Taxi x Cluster: code and message flow

These diagrams use Mermaid fences, which GitHub renders directly in Markdown.
The source paths below describe the current implementation; generated files remain
under build/ and are not committed.

## Build time: define the contract once

```mermaid
flowchart TD
    Catalog["gradle.properties + settings.gradle.kts<br/>Check generator version and revision"]
    Schema["counter.taxi<br/>CounterDelta, Multiplier, Divisor, CounterValue"]
    Task["counter-protocol/build.gradle.kts<br/>generateCounterCodecs on JDK 25"]
    Main["Generator Main.kt<br/>Read schema and select --codecs"]
    Compile["TaxiCompilerAdapter.kt<br/>Official Taxi compiler to DomainSchema"]
    Layout["WireLayout.kt + CodecGenerator.kt<br/>Validate offsets and emit Kotlin"]
    Output["build/generated/sources/taxi/main/kotlin<br/>Encoders, decoders, POJOs, flyweights"]
    Shared["counter-protocol JAR<br/>Compile generated source for JVM 21"]
    Gateway["Gateway runtime"]
    Cluster["Cluster runtime"]
    Catalog --> Task
    Schema --> Task --> Main --> Compile --> Layout --> Output --> Shared
    Shared --> Gateway
    Shared --> Cluster
```

The generator is a build-time tool. Neither application needs a running Taxi
compiler. `checkCounterSchema` compares the schema with the preserved
`baselines/counter-v0.taxi`; the adapter currently accepts schema version 0 only,
so compatibility checks do not automatically enable a new runtime version.

## Runtime: one Multiply request

```mermaid
sequenceDiagram
    actor User
    participant UI as App.jsx
    participant WS as VertxWebSocketServer.kt
    participant Client as CounterClusterClient.kt
    participant Wire as CounterWireCodec + generated codecs
    participant Aeron as Aeron Cluster
    participant Service as CounterClusteredService.kt
    User->>UI: Enter 3 and choose Multiply
    UI->>WS: JSON type=multiply, quantity="3"
    WS->>WS: Validate type and signed Long string
    WS->>Client: submit CounterRequest(MULTIPLY, 3)
    Client->>Wire: encodeRequest on owning Aeron thread
    Wire-->>Client: 17 application payload bytes
    Client->>Aeron: offer(buffer, offset, length)
    Aeron->>Service: Ordered onSessionMessage callback
    Service->>Wire: decodeRequest, then MultiplyDecoder.quantity()
    Wire-->>Service: Owned request with quantity=3
    Service->>Service: apply Math.multiplyExact(count, 3)
    Service->>Wire: encodeResult(status, count)
    Wire-->>Service: 25 application payload bytes
    Service->>Aeron: session.offer(reply)
    Aeron-->>Client: Egress callback
    Client->>Wire: decodeResult into owned CounterMessage
    Client-->>WS: Update listener
    WS-->>UI: JSON count as string, status
    UI-->>User: Display authoritative count
```

For count 20, Multiply(3) returns 60. Add(20), Multiply(3), Subtract(10),
Divide(4) from zero produces 20 -> 60 -> 50 -> 12. Division rounds toward zero;
overflow and division by zero leave state unchanged. Taxi names express meaning;
`CounterClusteredService.apply()` implements the arithmetic rules.

## What is serialized

```mermaid
flowchart LR
    Request["CounterRequest<br/>operation + Long quantity"]
    Encode["Generated operation encoder<br/>template 101 / 102 / 103 / 104"]
    Bytes["8-byte header + 9-byte quantity<br/>17 application bytes"]
    Decode["Generated operation decoder"]
    Owned["Owned CounterRequest<br/>service applies arithmetic"]
    Request --> Encode --> Bytes --> Decode --> Owned
```

The header contains four little-endian uint16 fields: block length, template ID,
schema ID, and version. All counter templates use schema 2, version 0.
The full-range quantity/count representation is one tag byte plus int64 raw data:
tag 0 means the raw value, tag 1 plus raw 0 means Long.MIN_VALUE. Generated
CounterResult uses template 105, an 8-byte status and 9-byte count after its header.
Aeron adds its own transport/session framing outside these application payloads.

The live path uses generated encoders and decoders directly; generated POJOs and
flyweights are available but not used by that path. Returned CounterMessage values
own their data; an Aeron callback buffer must not escape through a borrowed view.

## Read the code in order

| File | Job |
| --- | --- |
| [counter.taxi](../backend/counter-protocol/src/main/taxi/counter.taxi) | Shared semantic types, fields, message IDs, and full-range policy. |
| [protocol build](../backend/counter-protocol/build.gradle.kts) | Run the pinned generator, compile output, check the baseline. |
| [App.jsx](../frontend/src/App.jsx) | Validate input with BigInt, send JSON, display replies, reconnect. |
| [VertxWebSocketServer.kt](../backend/trading-gateway/src/main/kotlin/com/example/clusterstatemachine/gateway/VertxWebSocketServer.kt) | Validate browser requests and broadcast on a Vert.x context. |
| [CounterClusterClient.kt](../backend/trading-gateway/src/main/kotlin/com/example/clusterstatemachine/gateway/CounterClusterClient.kt) | Own the queue, Aeron connection, offers, keep-alives, and egress. |
| [CounterWireCodec.kt](../backend/counter-protocol/src/main/kotlin/com/example/clusterstatemachine/protocol/CounterWireCodec.kt) | Dispatch generated codecs and retain legacy formats. |
| [CounterClusteredService.kt](../backend/cluster/src/main/kotlin/com/example/clusterstatemachine/cluster/CounterClusteredService.kt) | Own count, deterministic arithmetic, snapshot callbacks, and replies. |
| [SingleNodeClusterRuntime.kt](../backend/cluster/src/main/kotlin/com/example/clusterstatemachine/cluster/SingleNodeClusterRuntime.kt) | Configure the embedded driver, archive, consensus module, and service container. |

## Recovery is a separate flow

```mermaid
flowchart TD
    Data["Existing cluster-data"] --> Snapshot{"Snapshot available?"}
    Snapshot -->|Yes| Restore["onStart restores raw 8-byte count"]
    Snapshot -->|No| Zero["Initial count is zero"]
    Restore --> Replay["Aeron replays applicable recorded messages"]
    Zero --> Replay
    Replay --> Decode["CounterWireCodec decodes legacy or generated payload"]
    Decode --> Apply["Same apply logic reconstructs state"]
    Apply --> Live["Resume live ordered commands"]
```

Legacy commands still decode and receive legacy replies. GET remains one byte;
snapshots remain raw 8-byte Longs. Upgrade the service before the gateway; an older
service cannot replay generated commands recorded in the log. The integration
test verifies graceful single-node restart replay; crash recovery, a real snapshot
restart, and multi-node failover remain unverified.

See [IntelliJ launch and debugging](intellij.md) for shared run configurations,
breakpoint locations, and a walkthrough that can be paused without live timeouts.
