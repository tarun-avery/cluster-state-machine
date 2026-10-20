# Cluster State Machine

A learning project showing how a React counter sends commands through a
Kotlin/Spring Boot gateway to an Aeron Cluster state machine. Click **+1** or
**-1** to update the count; all browsers connected to the gateway receive the result.

```text
React → Vert.x WebSocket gateway → Aeron Cluster counter → gateway → browsers
```

The backend modules live in `backend/`: `cluster` hosts the single-node cluster,
`trading-gateway` connects to it, and `counter-protocol` defines shared messages.
The React app lives in `frontend/`.

**Status:** single-node development demo. Multi-node replication and leader
failover have not been exercised.

## Requirements

- JDK 21 or newer.
- Node.js 22.13 or newer; Node.js 24 is a suitable choice.
- pnpm 11.25.0, matching `frontend/package.json`.
- The included Gradle wrapper; no separate Gradle installation is needed.

If pnpm is missing, install it after Node.js:

```sh
npm install --global pnpm@11.25.0
```

## Run locally

Clone the repository and install frontend dependencies:

```sh
git clone https://github.com/tarun-avery/cluster-state-machine.git
cd cluster-state-machine
pnpm --dir frontend install --frozen-lockfile
```

If you already have the project, open its root directory instead of cloning it
again. In IntelliJ, load or reload the root Gradle project.

Start these processes in separate terminals, each from the repository root:

**1. Cluster**

```sh
./gradlew :cluster:bootRun
```

Wait for `Started ClusterApplicationKt`.

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
show **Connected** and the current count. Test **+1** and **-1**, then open another
browser tab to see both counts update together.

All three commands keep running. Gradle showing `EXECUTING` is normal after
startup. Stop each process with **Ctrl+C**, starting with the frontend, then the
gateway, then the cluster.

## Development

Build and verify the project:

```sh
./gradlew build
pnpm --dir frontend format:check
pnpm --dir frontend build
```

The backend build runs the tests and ktlint checks. Kotlin and Gradle scripts
follow the official Kotlin style with four spaces; frontend files use Prettier
defaults with two spaces. Apply formatting with:

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

Runtime data is stored under `cluster-data/` and `gateway-data/` at the repository
root. Preserve these across restarts. Deleting `cluster-data/` while the apps are
stopped resets the counter's recorded state.

To remove generated backend and frontend output, stop the apps and run:

```sh
./gradlew clean
```

This preserves runtime data and installed frontend dependencies. Generated output
and caches are ignored by Git and reappear when needed.

## Limitations

- This is a single-member cluster; the gateway is a client, not another member.
- Real snapshot/restart recovery, archive replay after restart, and leader failover
  have not been verified. Snapshot callbacks are covered by unit tests.
- Commands have no exactly-once retry guarantee. A lost response may follow a
  committed update; fetch current state before deciding whether to retry.
- Broadcasts cover browsers connected to the same gateway. Independent gateways
  do not currently receive each other's updates.

See [protocol and connection behavior](docs/protocol.md) for message formats and
reconnection details.

## Help and contributions

Use [GitHub issues](https://github.com/tarun-avery/cluster-state-machine/issues)
for questions or bugs. Include the command you ran, relevant logs, and your Java,
Node.js and pnpm versions. Before contributing, read [AGENTS.md](AGENTS.md) and run
the development checks above.
