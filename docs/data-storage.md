# Runtime storage and migration

All new runtime storage belongs under repository-local `data/` (ignored by Git).
Cluster: `cluster/{aeron,archive,cluster,source-id}`. Gateway: `gateway/aeron-client`.
Public recording, history driver, PostgreSQL, backups and logs use separate siblings.
Media Driver deletion only applies to transient driver directories. Gradle clean
never removes this root. Use the repository root as each process working directory.

## Existing data

Stop cluster and gateway before changing paths. Copy the complete old
`cluster-data/` and `gateway-data/` trees into a dated `data/backups/` directory
first. Move the complete old cluster tree to `data/cluster/`, preserving Archive
catalogue, recording files and consensus metadata together; gateway driver data
can be recreated at `data/gateway/aeron-client/`. Do not merge two cluster trees.
A new `source-id` is created once on first startup. Never replace it while keeping
the same cluster log. Earlier results were not captured at their original execution;
new audit records produced during legacy replay must be treated as reconstructed.

Check metadata paths before migration and verify a stopped-process restart with
GET before making further updates. Roll back by stopping everything, restoring the
complete backup to the old paths and running the previous revision. Do not mix
files from pre- and post-migration logs. Keep backups until verified.

PostgreSQL and recordings must be backed up together with replay checkpoint
metadata. Retain internal logs until all required audit pairs are exported; an
8-byte count snapshot cannot rebuild history. No automatic retention deletion is
configured. Watch disk usage; storage exhaustion stops reliable progress.

## Local PostgreSQL

Docker Desktop must be running. Copy `.env.example` to `.env`, choose a password,
then `docker compose up -d --wait`. PostgreSQL 18 stores versioned PGDATA beneath
`/var/lib/postgresql`, so the bind mount is `data/postgres:/var/lib/postgresql`.
The image tag and digest are pinned. Only loopback exposes the database port.
`docker compose stop` preserves files; `docker compose up -d --wait` restarts it.
`docker compose logs postgres` shows database logs. Do not delete `data/postgres`.
Flyway, not Compose, owns schema creation.

In IntelliJ with Database Tools available, open View > Tool Windows > Database,
add PostgreSQL: host `127.0.0.1`, port from `.env` (default 5432), database
`counter_history`, user `counter`, password from `.env`. Download the JDBC driver
when prompted, then Test Connection. If Database Tools is unavailable, use
`docker compose exec postgres psql -U counter -d counter_history`.
No shared IDE configuration contains the password.
