# Project guidance

## Goal and scope

Build a small counter state machine with Aeron Cluster, a Kotlin/Spring Boot
cluster application, a Kotlin/Spring Boot trading gateway that connects as an
Aeron Cluster client and exposes Vert.x WebSockets, and a React browser client.
The counter supports increment and decrement commands. Keep domain behavior to
the counter; do not add banking or trading logic.

## Before changing code

- Read this file, the root README, and the relevant source first.
- Check the working tree and preserve existing user changes.
- Use APIs and documentation matching the versions pinned in the build.
- Keep changes focused and explain design decisions that affect the learning
  goals or state semantics.

## Correctness and architecture

- Keep the clustered service deterministic: the same prior state and ordered
  command must produce the same next state and result on every member.
- Do not read wall-clock time, generate random values, or perform external I/O
  while applying a command. Follow Aeron's documented callback lifecycle.
- Keep command and result encoding explicit. Preserve compatibility or explain
  migration implications when changing messages, state, or snapshots.
- Keep Aeron state transitions in the cluster service. The gateway transports
  commands and publishes egress results; the React app is a client only.
- Keep the gateway's Aeron client access on one owning thread and bridge results
  to Vert.x safely. Do not block Vert.x event-loop threads.
- Keep versions pinned and reproducible. Use the Gradle wrapper and the
  frontend package lockfile.
- Never commit credentials, local IDE state, build output, or runtime cluster
  data.
- Update README setup, run, protocol, and status details when behavior changes.

## Verification and reporting

- Prefer focused compilation and build checks before wider checks.
- Distinguish local counter behavior from actual multi-node replication,
  snapshots, replay, and failover. Do not claim the latter were verified unless
  they were exercised.
- Do not start a multi-node cluster or long-running benchmark unless requested.
- Report what changed, checks run, and verification gaps.

## Commits

- Do not create commits or push unless the user asks.
- When asked, use a short imperative commit subject and keep each commit focused.
- Review the staged diff and exclude credentials, generated files, and runtime
  data before committing.
