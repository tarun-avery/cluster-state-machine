# Protocol and connection behavior

## Browser WebSocket

The gateway accepts connections at `/ws`. Send one JSON command per message:

```json
{ "type": "increment" }
```

The legacy types `increment`, `decrement`, and `get` accept no quantity. Arithmetic
commands use `add`, `subtract`, `multiply`, or `divide` with a required decimal
string quantity (never a JSON number, to avoid rounding):

```json
{ "type": "multiply", "quantity": "3" }
```

For `/4`, send `{"type":"divide","quantity":"4"}`; for `-10`, send
`{"type":"subtract","quantity":"10"}`. Quantities may be negative and must fit
in a signed 64-bit integer. Fractional, missing, and out-of-range quantities are
rejected before submission. Each message applies one operation to the current
count in cluster order. Division rounds toward zero.

Count updates look like:

```json
{ "type": "count", "count": "1", "status": "ok" }
```

`count` is a decimal string so JavaScript preserves the full signed 64-bit range.
Other statuses are `invalid_command`, `overflow`, `division_by_zero`, and `unknown`. Invalid browser
commands or rejected submissions return a message with `type: "error"` and a
`message` field. Invalid cluster commands, counter overflow, and division by zero leave state unchanged.

## Cluster messages and snapshots

Taxi now defines arithmetic command and result payloads. Generated encoders and
decoders implement the application protocol; Aeron carries those bytes as the
application payload after its own session header. See [Taxi integration](taxi-integration.md)
for setup, schema locations, numeric encoding, and compatibility policy.

| Payload | Encoding |
| --- | --- |
| GET | One byte: `0` |
| Generated arithmetic command | Eight-byte LE SBE header (block length 9, template 101 Add / 102 Subtract / 103 Multiply / 104 Divide, schema 2, version 0), then nine-byte full-range quantity |
| Generated result | Eight-byte LE SBE header (block length 17, template 105, schema 2, version 0), int64 status, nine-byte full-range count |
| Legacy command (still readable) | One byte: `0` get, `1` increment, `2` decrement; or one byte `3` add / `4` subtract / `5` multiply / `6` divide followed by LE int64 quantity |
| Legacy result | One status byte followed by LE int64 count |
| Snapshot | The existing raw LE int64 count (unchanged) |

A full-range Long is tag 0 plus its int64 value, or tag 1 plus raw value 0 for
Long.MIN_VALUE. Noncanonical tags/values are invalid. Status codes remain 0 OK,
1 invalid command, 2 overflow, 3 division by zero. Generated results use int64
status to stay within the generator's evolvable fixed-field profile.

The service selects legacy replies for legacy command lengths (1 or 9), generated
replies otherwise. New arithmetic commands are 17 bytes and generated results are
25 bytes. The adapter validates exact lengths, schema/template identity, and version
0. It never infers payload length from the surrounding buffer capacity.

Legacy formats are retained for recorded command replay and older gateways. Upgrade
the service before the gateway; older services reject generated messages. A service
downgrade cannot replay a log containing generated commands. Do not mix service
versions while processing them. No snapshot migration or data reset is required.
The opt-in `:cluster:integrationTest` verifies single-node legacy and generated
recorded-log replay across graceful restarts. Multi-node failover, crash recovery,
and a real snapshot/restart cycle have not been verified.

## Connections and delivery

One gateway owns one Aeron Cluster session on a dedicated thread. Keep-alives
maintain idle sessions, and the gateway requests current state after reconnecting.
The service replies to the requesting gateway, which broadcasts results to all
of its connected browsers.

Browsers reconnect automatically after a gateway restart. Buttons remain disabled
until a count arrives on the new connection. The gateway rejects submissions when
it knows the cluster is disconnected or its bounded queue is full.

A blocked Aeron offer times out after five seconds and triggers reconnect.
Connection retries pause for one second. Pending queued commands are discarded
when the connection fails, and in-flight commands may have applied before their
responses were lost. There is no automatic command replay or request deduplication.

A WebSocket port conflict fails gateway startup rather than leaving the process
running without a listening endpoint.

## Further reading

- [Aeron Cluster tutorial](https://github.com/aeron-io/aeron/wiki/Cluster-Tutorial)
- [Aeron Cluster component model](https://github.com/aeron-io/aeron/wiki/Cluster-Component-Model)
- [Aeron Archive](https://github.com/aeron-io/aeron/wiki/Aeron-Archive)
