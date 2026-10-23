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

Verified so far: baseline codec/service tests, real single-member restart replay of
both legacy/generated commands, actual audit pair replay without a client, missing
session result capture, backpressure retry and recursion prevention. This is not
proof of multi-node failover or power-loss durability. The remaining delivery work
includes interruption tests, external Archive bridge, SQL and UI.

The source identity will be a persistent cluster incarnation UUID created by runtime
setup outside state transitions, plus the absolute committed command position.
Keep it with the cluster data. Resetting a cluster must create a new incarnation.
GET and legacy commands are included; malformed admitted commands preserve their
raw bytes and INVALID_COMMAND result. Gateway validation failures are excluded.
An outgoing record proves result production, not browser receipt.
