# Launch and debug in IntelliJ IDEA

Open the cluster-state-machine root as a Gradle project with JDK 25. Use **View >
Tool Windows > Gradle > Sync All Gradle Projects** (also called Reload All Gradle
Projects). Shared configurations are stored in `.run/`, outside ignored `.idea/`.
If new configurations are not listed yet, reopen the project to load them.

## Choose a configuration

| Configuration | What it does |
| --- | --- |
| Run - Taxi x Cluster web app | Compound launch of cluster, gateway and frontend. Choose **Run** for normal browser use. |
| Cluster - counter service | `:cluster:bootRun`, with existing saved cluster-data. |
| Gateway - WebSocket bridge | `:trading-gateway:bootRun`, connecting to the local cluster. |
| Frontend - web counter | `frontendDev`, Vite at http://127.0.0.1:5173. |
| Taxi - generate counter codecs | Force `generateCounterCodecs` to rerun. Use Debug to pause in generator sources. |
| Debug - serialization walkthrough | Run the single SerializationWalkthroughTest. Choose **Debug**, not Run, to stop at breakpoints. |
| Verify - live transport and restart replay | Run the existing real single-node integration test with temporary data. |

The compound starts its children together; it does not guarantee startup order.
The gateway retries connection and sends fresh state when it connects, so a short
startup delay is normal. To start sequentially, run Cluster, Gateway, then Frontend.
Open http://127.0.0.1:5173 yourself after Vite reports ready. Existing runtime data
is preserved. Stop all three child runs in Services when finished; stopping only
the frontend leaves the backend running. Do not launch duplicates on the same ports.

The generator is a build-time dependency; the web compound does not start a fourth
Taxi server. Generate/compile tasks run automatically when the backend needs them.

`frontendDev` uses pnpm from `~/.local/bin` when available, otherwise PATH. It also
adds an available local Node binary to the process PATH. For a different installation,
set `pnpmExecutable` and/or `nodeExecutable` in your private Gradle properties file
(`~/.gradle/gradle.properties`), using absolute executable paths. Do not commit
machine-specific paths. Install frontend dependencies first with
`pnpm --dir frontend install --frozen-lockfile`.

## Start with the serialization walkthrough

1. Choose **Debug - serialization walkthrough** from the configuration selector.
2. Click the Debug bug icon. Gradle starts the selected test JVM under the debugger.
3. The harness starts count at zero and executes Add(20), Multiply(3), Subtract(10),
   Divide(4), then Divide(0). Expected counts: 20, 60, 50, 12, 12.
4. Use Step Over to observe each statement, Step Into to enter a codec/service call,
   and Resume Program to reach the next breakpoint. Find those named actions through
   Help > Find Action if your keyboard shortcuts differ.

This harness uses the **real generated encoders/decoders and real clustered service**.
Only the Aeron transport boundary is mocked. There is no running media driver,
WebSocket or browser in this configuration; it can stay suspended while you read.
The live transport/restart test is a separate configuration. Pausing a live cluster
can trigger heartbeat/offer timeouts, so use this isolated tour first.

## Breakpoint map

A local breakpoint preset was written to the ignored `.idea/workspace.xml` without
replacing existing breakpoints. IntelliJ may need a project close/reopen to load
externally saved debugger state. The shared manifest `debug-breakpoints.json`
records exact files, anchors and one-based line numbers so the stops can be restored
on another checkout. Generated files exist only after code generation; their stops
may need reattaching if the generator changes source layout.

C paths are relative to this repository; G paths are relative to taxi-kotlin-codegen.
Breakpoints are enabled and suspend all threads. Stops 1-7 belong to the runtime
serialization tour; stops 8-9 belong to debugging Taxi generation.

| Stop | Source | What to inspect |
| --- | --- | --- |
| 1 | [CounterWireCodec.kt:28](../backend/counter-protocol/src/main/kotlin/com/example/clusterstatemachine/protocol/CounterWireCodec.kt#L28) | Inspect request.command, request.quantity and the body offset before encoding. |
| 2 | [MultiplyCodec.kt:30](../backend/counter-protocol/build/generated/sources/taxi/main/kotlin/com/example/clusterstatemachine/messages/MultiplyCodec.kt#L30) | During Multiply, inspect value, buffer and offset; step over the tag and raw-value writes. |
| 3 | [MultiplyCodec.kt:80](../backend/counter-protocol/build/generated/sources/taxi/main/kotlin/com/example/clusterstatemachine/messages/MultiplyCodec.kt#L80) | Inspect the borrowed byte buffer and recover the quantity from tag/raw data. |
| 4 | [CounterClusteredService.kt:131](../backend/cluster/src/main/kotlin/com/example/clusterstatemachine/cluster/CounterClusteredService.kt#L131) | During Multiply, inspect count=20 and quantity=3; step over to compute 60. |
| 5 | [CounterWireCodec.kt:109](../backend/counter-protocol/src/main/kotlin/com/example/clusterstatemachine/protocol/CounterWireCodec.kt#L109) | Inspect message.status and message.count before writing the generated reply. |
| 6 | [CounterWireCodec.kt:124](../backend/counter-protocol/src/main/kotlin/com/example/clusterstatemachine/protocol/CounterWireCodec.kt#L124) | Inspect the received payload and watch it become an owned CounterMessage. |
| 7 | [SerializationWalkthroughTest.kt:59](../backend/cluster/src/test/kotlin/com/example/clusterstatemachine/cluster/SerializationWalkthroughTest.kt#L59) | Inspect the completed result after the callback has returned. |
| 8 | `G:src/main/kotlin/com/example/taxicodegen/Main.kt:29` | With Taxi generate config in Debug, inspect the input source and compiled DomainSchema. |
| 9 | `G:src/main/kotlin/com/example/taxicodegen/CodecGenerator.kt:5` | With Taxi generate config in Debug, inspect the schema before fixed-profile code emission. |

Generated Multiply stops trigger on the Multiply iteration only. The generic
CounterWireCodec stops trigger on every operation. The test prints an operation,
request length, returned count and status for each step. It encodes into a buffer
at offset 5 to make the difference between message offset and field offset visible.

To add/restore a stop manually, open the source at its anchor and click the gutter
beside that executable line. Use Run > View Breakpoints to review or disable stops.
The local workspace and its backup are not committed; `.run/` and the manifest are
shareable. No live cluster is left running by the walkthrough test.

## Diagrams and verification

[Code and message flow](flow.md) contains GitHub-rendered Mermaid diagrams and
source links. The generator repository has its own `docs/flow.md` walkthrough.

```sh
./gradlew :cluster:test --tests com.example.clusterstatemachine.cluster.SerializationWalkthroughTest
./gradlew build ktlintCheck
./gradlew :cluster:integrationTest
```

The first command verifies codec/state walkthrough behavior without live transport.
The last verifies actual WebSocket/Aeron interaction and graceful restart replay;
it does not verify crash recovery, snapshots or multi-node failover.
