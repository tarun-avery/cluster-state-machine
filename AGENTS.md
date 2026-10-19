# Project guidance

## Goal and scope

This project is for learning Aeron Cluster by building a small, correct replicated
state machine. The first application is a counter that accepts increment and
decrement commands and publishes the resulting count. Keep the work focused on
the cluster and state machine. Do not add banking, trading, gateway, WebSocket,
frontend, or deployment features unless the user asks.

## Before changing code

- Read this file, the root README, and the relevant source and tests first.
- Check the working tree and preserve existing user changes.
- Use documentation and examples matching the Aeron version pinned by this
  project. Do not assume APIs from a different version.
- Keep the change small and explain any design choice that affects learning,
  state semantics, persistence, or recovery.

## State machine correctness

- Keep command application deterministic: a given prior state and ordered
  command must produce the same next state and result on every cluster member.
- Do not read wall-clock time, generate random values, or perform external I/O
  while applying a command. Use cluster callbacks and APIs according to Aeron's
  documented lifecycle rules.
- Make state changes and client-visible results explicit. Reject invalid
  commands without partially changing state.
- Treat snapshots and replay as part of the state-machine contract. When state
  shape or message encoding changes, consider compatibility and recovery from
  existing snapshots and logs.
- Keep Aeron transport, command encoding, state transition logic, and process
  startup separable where that makes behavior easier to understand and verify.

## Implementation practices

- Follow the language and build choice documented in the README. Do not add a
  second implementation language or framework without asking.
- Prefer small, direct abstractions over frameworks or speculative features.
- Keep dependencies and versions explicit and reproducible; use the Gradle
  wrapper if this is a Gradle project.
- Never commit credentials, local IDE state, generated build output, or runtime
  cluster data.
- Update README instructions when setup, commands, behavior, or project scope
  changes.

## Verification and reporting

- Use the narrowest relevant checks first, followed by the project build when
  appropriate. Do not claim a check passed unless it was run successfully.
- Distinguish unit-level state transition evidence from actual cluster,
  replication, snapshot, replay, and failover verification.
- Keep verification local and reproducible. Do not launch a multi-node cluster
  or perform long-running benchmarks unless the task calls for them.
- Summarize behavior changed, checks run, and any known verification gaps.
