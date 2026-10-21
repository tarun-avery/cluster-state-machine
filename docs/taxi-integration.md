# Taxi integration

The counter owns its schema in `backend/counter-protocol/src/main/taxi/counter.taxi`.
The sibling `taxi-kotlin-codegen` project supplies the reusable compiler/generator.
Its version and exact commit are pinned in root `gradle.properties`. Gradle checks
that the checkout matches that revision and is clean, then includes it as a build.
Generated messages live only under `backend/counter-protocol/build/` and are never
edited or committed. Taxi/compiler libraries are build-time dependencies only.

## Setup

Keep the two repositories as siblings. Use JDK 25 to build. If the generator is
elsewhere, add `-PtaxiCodegenDir=/path/to/taxi-kotlin-codegen` to Gradle commands.
Check out the commit listed as `taxiCodegenRevision` in `gradle.properties` in the
generator repository before building. Preserve existing changes in that checkout.
The revisions are local until explicitly pushed; another machine needs those commits.

From the cluster repository root:

```sh
./gradlew :counter-protocol:generateCounterCodecs
./gradlew :counter-protocol:checkCounterSchema
./gradlew :counter-protocol:test
./gradlew build
```

The generator itself emits JVM 24 bytecode and runs on JDK 25. Generated Kotlin is
compiled by this project for JVM 21. The generator and Taxi compiler do not become
runtime dependencies of the gateway or service.

## Domain and encoding

Add and Subtract quantities mean CounterDelta; Multiply uses Multiplier; Divide
uses Divisor. These semantic names are retained in Taxi, but generated Kotlin
properties currently use Long, not distinct value classes. Arithmetic and overflow
rules remain in the deterministic cluster service. Taxi names do not execute rules.

The four command templates are 101–104 in schema 2, version 0. Each has a required
full-range quantity, encoded as a nine-byte fixed composite: tag 0 plus the signed
int64 value, or tag 1 plus raw value 0 for Long.MIN_VALUE. This avoids using an SBE
null sentinel as ordinary data. Header plus body is 17 bytes. CounterResult is
schema 2/template 105 with an eight-byte status and nine-byte count (25 bytes total).
The generator's reference SBE interoperability tests cover the full-range composite.

The preserved `baselines/counter-v0.taxi` is checked during Gradle verification.
Do not modify the baseline to bypass a failure. Field order determines offsets;
existing IDs, encodings, and offsets must remain stable. Fixed-profile append-only
optional-field evolution is supported; rich enum evolution is not, so status uses
a documented Long code. Generated code is excluded from formatter mutation.

## Runtime flow and migration

The gateway's owning Aeron thread encodes add/subtract/multiply/divide using the
generated classes. Legacy browser increment/decrement commands map to generated
Add(1)/Subtract(1). GET retains the one-byte legacy encoding: it needs no operand
and the generator deliberately does not generate empty data classes.

The cluster adapter reads the header, chooses the generated decoder, and copies
the quantity before applying arithmetic. It rejects malformed lengths, schemas,
templates, unsupported versions, and noncanonical full-range encodings without
changing state. Version 0 is supported explicitly; a future version requires an
adapter update even when the schema compatibility check passes.

Generated commands receive generated CounterResult replies. Legacy one-byte and
nine-byte commands still decode and receive legacy nine-byte replies, preserving
old log replay and old gateway behavior. The new gateway reads both reply formats
and materializes an owned CounterMessage before handing it to Vert.x. Buffer views
never escape Aeron's callback. Snapshots remain the existing raw eight-byte Long.

Deploy the updated cluster before starting the updated gateway. Older services
reject generated requests; downgrade is unsafe once generated commands enter the
recorded log. Existing data requires no reset. Mixed service versions must not
process generated commands. Browser JSON, arithmetic rules, rounding, and the full
signed 64-bit range are unchanged. No performance improvement is claimed.
