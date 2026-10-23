# Cluster State Machine

A learning project showing how a React counter sends commands through a
Kotlin/Spring Boot gateway to an Aeron Cluster state machine. Enter a quantity
and choose **add**, **subtract**, **multiply**, or **divide** to update the count;
all browsers connected to the gateway receive the result.

```text
React → Vert.x WebSocket gateway → Aeron Cluster counter → gateway → browsers
```

The backend modules live in `backend/`: `cluster` hosts the single-node cluster,
`trading-gateway` connects to it, and `counter-protocol` generates shared messages
from Taxi using the pinned Kotlin generator.
The React app lives in `frontend/`.

**Status:** the Taxi-generated arithmetic protocol is integrated and tested,
including single-node recorded-log replay across restarts. Multi-node replication
and leader failover have not been exercised.

## Requirements

- JDK 25 for building (the generator runs on JDK 25); application bytecode targets JDK 21.
- The pinned sibling Taxi generator checkout; see [Taxi integration setup](docs/taxi-integration.md).
- Node.js 22.13 or newer; Node.js 24 is a suitable choice.
- pnpm 11.25.0, matching `frontend/package.json`.
- The included Gradle wrapper; no separate Gradle installation is needed.

If pnpm is missing, install it after Node.js:

```sh
npm install --global pnpm@11.25.0
```

## Run locally

Clone the repository:

```sh
git clone https://github.com/tarun-avery/cluster-state-machine.git
cd cluster-state-machine
```

If you already have the project, open its root directory instead of cloning it
again. Follow [Taxi integration setup](docs/taxi-integration.md#setup) to prepare
the sibling generator at the exact revision in `gradle.properties`. The build
checks that revision and rejects uncommitted generator build-input changes;
README and `docs/` edits are allowed. These integration commits must be available
in both repositories; they have not been pushed automatically.

Install the frontend dependencies and verify the shared schema:

```sh
pnpm --dir frontend install --frozen-lockfile
./gradlew :counter-protocol:checkCounterSchema
```

In IntelliJ, load or reload the root Gradle project using JDK 25. Kotlin compiler
and runtime libraries are pinned to 2.2.21 for the installed IntelliJ IDEA 2025.2
Kotlin tooling. Gradle enforces that library alignment, including dependencies
managed by Spring Boot. If an already-open IDE reports an unsupported Kotlin
binary format, open **View > Tool Windows > Gradle**, then click **Reload All Gradle Projects**
(or **Sync All Gradle Projects**) to import these versions. Avoid the
banner's blanket downgrade action; keep version changes in the Gradle build.
A project can control dependencies, but cannot upgrade the IDE's bundled Kotlin
analyser automatically.

Start these processes in separate terminals, each from the repository root:

**1. Cluster**

```sh
./gradlew :cluster:bootRun
```

Wait for `Started ClusterApplicationKt`. Start the cluster before the gateway.
The first run also generates and compiles the shared Taxi codecs.

**2. Gateway**

```sh
./gradlew :trading-gateway:bootRun
```

Wait for `Connected to Aeron Cluster` and `WebSocket listening`.

**3. Frontend**

```sh
pnpm --dir frontend dev
```

Open the address printed by Vite, usually <http://127.0.0.1:5173>. The page should
show **Connected** and the current count. Enter `10` and click **+10**, then enter
`3` and click **×3**, then enter `4` and click **/4**. From an initial count of
zero this produces `10 → 30 → 7`. Open another browser tab to see both counts
update together.

Quantities and counts are signed 64-bit whole numbers. Division rounds toward
zero; division by zero and overflow leave the count unchanged. Negative quantities
are supported.

All three commands keep running. Gradle showing `EXECUTING` is normal after
startup. Stop each process with **Ctrl+C**, starting with the frontend, then the
gateway, then the cluster.

## IntelliJ launch and debugging

Shared configurations are in `.run/`. After reloading the Gradle project, choose
**Run - Taxi x Cluster web app** to launch the cluster, gateway and frontend.
Open <http://127.0.0.1:5173>. Use **Debug - serialization walkthrough** with the
Debug button to step through the real generated codecs and state machine without
live transport timeouts. See [IntelliJ instructions](docs/intellij.md) and the
[GitHub-rendered flow diagrams](docs/flow.md).

## Development

Build and verify the project:

```sh
./gradlew build
pnpm --dir frontend format:check
pnpm --dir frontend build
```

The backend build regenerates Taxi codecs as needed, checks the schema baseline,
and runs unit tests and ktlint checks. To also exercise a real single-node cluster,
WebSocket broadcasts, and legacy/generated log replay across restarts, run:

```sh
./gradlew :cluster:integrationTest
```

This test uses temporary data and available local ports, closes its cluster and
clients, and leaves your normal cluster data intact.

Kotlin and Gradle scripts follow the official Kotlin style with four spaces;
frontend files use Prettier defaults with two spaces. Apply formatting with:

```sh
./gradlew ktlintFormat
pnpm --dir frontend format
```

Editor settings are shared through `.editorconfig`. The Gradle run tasks supply
the JVM access setting required by Aeron/Agrona. For a direct IntelliJ application
run configuration, use the repository root as the working directory and add:

```text
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
```

## Configuration and data

The default gateway endpoint is `ws://127.0.0.1:8081/ws`; cluster ingress is
`127.0.0.1:9002`. Gateway properties are defined in
[`application.properties`](backend/trading-gateway/src/main/resources/application.properties).
Set `VITE_GATEWAY_WS_URL` when starting or building the frontend to use a different
gateway endpoint.

Runtime data defaults to project-local `data/cluster/` and `data/gateway/`.
Set `APP_DATA_ROOT` (Spring property `app.data-root`) to change the root. Preserve
the cluster tree, including its `source-id`, across restarts. Deleting it resets
the count and creates a new history source identity. See
[data migration](docs/data-storage.md) before running against older data.

To remove generated backend and frontend output, stop the apps and run:

```sh
./gradlew clean
```

This preserves runtime data and installed frontend dependencies. Generated output
and caches are ignored by Git and reappear when needed.

## Limitations

- This is a single-member cluster; the gateway is a client, not another member.
- Single-node recorded-log replay across restarts is covered by the opt-in
  integration test. Multi-node replication, leader failover, crash/power-loss
  recovery, and a real snapshot/restart cycle have not been verified. Snapshot
  callbacks are covered by unit tests.
- Commands have no exactly-once retry guarantee. A lost response may follow a
  committed update; fetch current state before deciding whether to retry.
- Broadcasts cover browsers connected to the same gateway. Independent gateways
  do not currently receive each other's updates.

See [Taxi integration](docs/taxi-integration.md) and
[protocol and connection behavior](docs/protocol.md) for message formats and
reconnection details.

## Help and contributions

Use [GitHub issues](https://github.com/tarun-avery/cluster-state-machine/issues)
for questions or bugs. Include the command you ran, relevant logs, and your Java,
Node.js and pnpm versions. Before contributing, read [AGENTS.md](AGENTS.md) and run
the development checks above.
