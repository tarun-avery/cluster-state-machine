# Cluster State Machine

A learning project focused on building a small replicated counter with Aeron
Cluster. The initial behavior is deliberately limited to increment and
decrement commands and publishing the resulting count.

The goal is to understand ordered command processing, deterministic state
transitions, snapshots, replay, and cluster recovery. Banking, trading, gateways,
web UIs, and deployment are outside the current scope.

## Language and build

The clustered service will be written in Java, matching Aeron Cluster's
clustered-service API and official examples. The JDK and build-tool versions will
be pinned when the initial application scaffold is added.

## Current status

The repository currently contains project guidance and scope documentation. The
counter service has not been implemented yet.

## Build and run

Build and run instructions will be added with the first Java implementation.
Keep the Java, Gradle, and Aeron versions pinned and use documentation matching
those versions.

## Learning resources

- [Aeron Cluster tutorial](https://github.com/aeron-io/aeron/wiki/Cluster-Tutorial)
- [Aeron Cluster module](https://github.com/aeron-io/aeron/tree/master/aeron-cluster)
