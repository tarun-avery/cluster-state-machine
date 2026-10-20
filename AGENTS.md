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

## Repository layout

- Kotlin modules live in `backend/cluster`, `backend/counter-protocol`, and
  `backend/trading-gateway`. React lives in `frontend`.
- Run the Gradle wrapper from the repository root. Logical project paths stay
  `:cluster`, `:counter-protocol`, and `:trading-gateway`.
- Keep runtime data paths relative to the repository root when moving modules.

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
- Use a neutral project namespace for source packages and build coordinates.
  Do not put the user's name, home-directory path, or personal identifiers in
  source paths or package names unless the user explicitly requests it.
- Update README setup, run, protocol, and status details when behavior changes.

## Formatting

- Follow the official Kotlin conventions with four-space indentation for Kotlin
  and Gradle Kotlin scripts. Use ktlint with the `ktlint_official` style.
- Use Prettier defaults with two-space indentation for frontend files.
- Keep `.editorconfig`, formatter configuration, and pinned versions in the repo.
- Run `./gradlew ktlintFormat` and `pnpm --dir frontend format` after edits.
- Verify with `./gradlew ktlintCheck` and `pnpm --dir frontend format:check`.
  Backend `build` also runs ktlint checks.

## Verification and reporting

- Prefer focused compilation and build checks before wider checks.
- Distinguish local counter behavior from actual multi-node replication,
  snapshots, replay, and failover. Do not claim the latter were verified unless
  they were exercised.
- Do not start a multi-node cluster or long-running benchmark unless requested.
- Report what changed, checks run, and verification gaps.

## Commits

- Never create a commit or push until the user has reviewed the current changes
  and explicitly approved the specific commit or push. This applies even if the
  user previously authorized a different commit or push.
- After making changes, leave them uncommitted and present the diff for review.
- Once the user approves, use a short imperative commit subject and keep each
  commit focused. Review the staged diff and exclude credentials, generated
  files, and runtime data before committing.
