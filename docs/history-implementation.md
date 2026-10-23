# Public Archive and history implementation

## Durable handoff proof

Aeron 1.53.3 `Cluster.offer` sends a service-generated message through consensus.
The service captures the exact incoming and encoded outgoing bytes before replying,
with the committed command position as its identity. Audit messages have their own
versioned envelope and never apply arithmetic or emit another audit message.
The Taxi command templates, legacy messages and 8-byte counter snapshot stay unchanged.
The bounded handwritten envelope supports variable raw byte arrays that the current
fixed-field Taxi profile does not generate; existing Taxi codecs still encode the
actual arithmetic messages.

Verification includes codec/service checks, real single-member log and snapshot
replay, process interruption before/after service publication, public recording and
duplicate re-export, PostgreSQL transactional/idempotent ingestion, separate-driver
UDP replay, malformed-record failure and database rebuild. See [the complete launch
and debug guide](history.md). Multi-node failover and power-loss durability remain
unverified.

The source identity is a persistent cluster incarnation UUID created by runtime
setup outside state transitions, plus the absolute committed command position.
Keep it with the cluster data. Resetting a cluster must create a new incarnation.
GET and legacy commands are included; malformed admitted commands preserve their
raw bytes and INVALID_COMMAND result. Gateway validation failures are excluded.
An outgoing record proves result production, not browser receipt.


## Capture provenance and compatibility

Audit envelope v2 adds capture kind: 0 UNCLASSIFIED (v1 compatibility), 1 CAPTURED,
2 RECONSTRUCTED. The unchanged original request/result bytes remain inside it.
Runtime setup persists `data/cluster/audit-start-position` before starting the
service. Commands at/before that boundary are historical reconstructions;
commands after it are captured during their original execution. The boundary
and `source-id` are immutable companions to the log and must be backed up.
Earlier v1 audit records remain UNCLASSIFIED: do not assert original capture.
Flyway V2 adds provenance and separates wire schema IDs from wire version 0.
V1 is preserved byte-for-byte after application.

## Public binary format

Public Archive control endpoint: `aeron:udp?endpoint=127.0.0.1:9021` (default
Archive control stream IDs). Recorded application channel: `aeron:ipc?term-length=8m`,
stream **19401**. Consumers request Archive UDP replay to their own bound loopback
endpoint; they do not subscribe directly to the recording IPC channel from another
driver. Each reassembled application message is one complete pair. Internal
Aeron cluster session headers are removed by the bridge, not included in this format.

All integers are little-endian. Envelope version 2 layout:

| Offset | Bytes | Field |
| ---: | ---: | --- |
| 0 | 4 | magic 0x41554454 |
| 4 | 4 | envelope version 2 |
| 8 | 8 | source UUID most significant bits |
| 16 | 8 | source UUID least significant bits |
| 24 | 8 | absolute committed command position |
| 32 | 8 | cluster-provided timestamp (default milliseconds) |
| 40 | 8 | original session ID, or -1 if unavailable |
| 48 | 4 | request byte length |
| 52 | 4 | result byte length |
| 56 | 4 | capture kind 0/1/2 |
| 60 | variable | original incoming request bytes |
| 60 + request length | variable | actual encoded outgoing result bytes |

Each payload length is bounded at 65,536 bytes, checked before allocation. v1 uses
56 header bytes and omits capture kind; the decoder assigns UNCLASSIFIED. Existing
arithmetic templates and Taxi schema baseline are unchanged. Decoding errors stop
replay before its checkpoint advances. A successful recording offer alone never
advances a source checkpoint.

SQL V3 retains per-recording replay positions as metadata on `outgoing`, including
when a duplicate pair appears in a later recording. This avoids repeatedly scanning
an older duplicate recording and preserves exactly two business tables. Raw payload
conflicts fail the transaction. SQL is a projection and is never used to reapply
counter commands.

API references for the pinned design:
[Aeron 1.53.3 Cluster](https://github.com/aeron-io/aeron/blob/1.53.3/aeron-cluster/src/main/java/io/aeron/cluster/service/Cluster.java),
[Aeron Archive](https://aeron.io/docs/aeron-archive/overview/),
[PostgreSQL image storage](https://github.com/docker-library/docs/blob/master/postgres/README.md),
[Flyway PostgreSQL support](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database).
