# Cluster State Machine

A learning project with an Aeron Cluster counter, a Kotlin Spring Boot trading
gateway using Vert.x WebSockets, and a React client. The cluster accepts
increment and decrement commands and returns the resulting count.

The current cluster is a **single-node development cluster**. It demonstrates
the Aeron Cluster service and client path, but does not demonstrate multi-node
replication or failover yet.

## Modules

| Module | Role |
| --- | --- |
| `counter-protocol` | Shared Kotlin command and response codes used by both backend apps. |
| `cluster` | Spring Boot process hosting Aeron Media Driver, Archive, Consensus Module, and the counter service. |
| `trading-gateway` | Spring Boot process with an Aeron Cluster client and Vert.x WebSocket endpoint. |
| `frontend` | React counter UI that sends increment/decrement commands and displays count updates. |

## Message flow

```text
React browser
    │ JSON over WebSocket
    ▼
Vert.x trading gateway
    │ Aeron Cluster ingress command
    ▼
Aeron Cluster counter service
    │ count response over cluster egress
    ▼
Vert.x broadcasts the count to connected browsers
```

The WebSocket accepts JSON commands:

```json
{"type":"increment"}
{"type":"decrement"}
{"type":"get"}
```

Count responses have this shape:

```json
{"type":"count","count":"1","status":"ok"}
```

`count` is a decimal string so the browser preserves the full signed 64-bit
range without JavaScript number rounding. This changes the original WebSocket
count field from a JSON number to a string; other clients must update accordingly.
The Aeron command, response, and snapshot formats are unchanged.

The cluster command payload is one byte: `0` for get, `1` for increment, and
`2` for decrement. The egress response is one status byte followed by the
64-bit count in little-endian byte order. Invalid commands and counter overflow
leave the count unchanged.

## Requirements

- JDK 21 or newer
- Gradle wrapper (included)
- Node.js 20.19 or newer and pnpm 11 for the frontend

Dependency versions are pinned in `gradle/libs.versions.toml` and
`frontend/package.json`.

## Run locally

Start each process in its own terminal from the repository root. The Gradle
run tasks use the repository root as their working directory, so runtime data
always goes into the root directories described below.

The run tasks and executable JAR manifests supply the `java.base/jdk.internal.misc`
export required by Aeron/Agrona. For an IDE run configuration, add this VM option:

```text
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
```

1. Start the single-node cluster:

   ```sh
   ./gradlew :cluster:bootRun
   ```

2. Start the gateway:

   ```sh
   ./gradlew :trading-gateway:bootRun
   ```

3. Install and start the React UI:

   ```sh
   cd frontend
   pnpm install
   pnpm dev
   ```

Open the local address printed by Vite. The gateway listens on
`ws://127.0.0.1:8081/ws`; the cluster ingress port is `9002`.

## Build

```sh
./gradlew build
cd frontend
pnpm build
```

The backend build includes tests for ordered counter commands, invalid payloads,
overflow, snapshot encoding/restoration, replay callbacks, WebSocket command
handling, exact 64-bit JSON counts, disconnected submissions, and port conflicts.

## Formatting

Kotlin and Gradle Kotlin scripts use the official Kotlin conventions (four
spaces), enforced by ktlint. The React frontend uses Prettier defaults (two
spaces). `.editorconfig` shares indentation and line-ending settings with editors.
Formatter versions are pinned in the version catalog and frontend package lock.

Apply formatting from the repository root:

```sh
./gradlew ktlintFormat
pnpm --dir frontend format
```

Check formatting without changing files:

```sh
./gradlew ktlintCheck
pnpm --dir frontend format:check
```

Backend `build` includes ktlint checks; run the frontend formatting check alongside
`pnpm --dir frontend build`. In IntelliJ, keep EditorConfig support enabled and
select **Editor → Code Style → Kotlin → Set from → Kotlin style guide**. Run the
formatter commands above for the exact formatting used by the build.

## Connection behavior

- One gateway owns one Aeron Cluster session on a dedicated thread. It sends
  keep-alives even when browsers are idle and fetches current state on reconnect.
- The clustered service replies to the requesting gateway. That gateway broadcasts
  the count entity to all of its connected browsers. Multiple independent gateway
  instances do not currently subscribe to each other's updates.
- Browsers reconnect automatically after a gateway restart and receive fresh state.
  Buttons stay disabled until the first count arrives on a new connection.
- Commands are rejected while the gateway knows it is disconnected or its bounded
  queue is full. Commands already queued when a connection fails are discarded;
  an in-flight command may have applied before its response was lost. There is no
  automatic command replay or exactly-once retry guarantee. Fetch current state
  before deciding whether to retry.
- A blocked Aeron offer times out after five seconds and triggers reconnect.
  Connection retries pause for one second. A WebSocket port conflict fails Spring
  startup instead of leaving the app apparently running without its endpoint.
- `VITE_GATEWAY_WS_URL` can override the browser endpoint at frontend startup/build.

## Verification status

A local single-node smoke check exercised initial state, increment/decrement,
two-browser broadcasts, malformed WebSocket commands, and an idle session longer
than the default cluster session timeout. The React controls and automatic browser
reconnection after restarting the gateway were also exercised.

Snapshot tests use Aeron callback doubles; a real snapshot/restart cycle,
archive replay after process restart, multi-node replication, and leader failover
have not been exercised.

## Data and reset

The cluster stores its Aeron and archive data under `cluster-data/`; the gateway
uses `gateway-data/` for its local Media Driver. These directories are ignored
by Git. Stop the processes before deleting local data. Removing `cluster-data/`
resets the counter's persisted cluster state.

## Next learning steps

1. Understand the command and response encoding and the counter's snapshot.
2. Exercise restart and snapshot/replay behavior.
3. Evolve the cluster configuration to three members and verify replication
   and leader failover.

## References

- [Aeron Cluster tutorial](https://github.com/aeron-io/aeron/wiki/Cluster-Tutorial)
- [Aeron Cluster component model](https://github.com/aeron-io/aeron/wiki/Cluster-Component-Model)
- [Vert.x Web documentation](https://vertx.io/docs/vertx-web/java/)
