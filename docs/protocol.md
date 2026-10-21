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

The application protocol is explicit binary encoding, not generated SBE codecs:

| Payload        | Encoding                                                            |
| -------------- | ------------------------------------------------------------------- |
| Legacy command | One byte: `0` for get, `1` for increment, `2` for decrement |
| Arithmetic command | One opcode byte (`3` add, `4` subtract, `5` multiply, `6` divide), then a signed 64-bit quantity in little-endian order |
| Response       | One status byte followed by the 64-bit count in little-endian order |
| Snapshot state | The 64-bit count in little-endian order                             |

Status bytes are `0` for OK, `1` for invalid command, `2` for overflow, and `3`
for division by zero. Command lengths must be exactly one byte for legacy
commands and nine bytes for arithmetic. `Long.MIN_VALUE / -1` reports overflow.

Existing command opcodes, response layout, and snapshots are unchanged, so stored
legacy commands can still replay. Deploy the updated cluster service before
sending arithmetic commands; older services reject the new opcodes. Existing
clients can continue sending legacy commands, but must understand the additional
status if receiving arithmetic results. No data reset or snapshot migration is
required. Mixed service versions must not process arithmetic commands.
The service implements snapshot writing and restoration callbacks. A real
snapshot/restart cycle has not yet been verified.

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
