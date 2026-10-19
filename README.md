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
{"type":"count","count":1,"status":"ok"}
```

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

Start each process in its own terminal from the repository root.

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
