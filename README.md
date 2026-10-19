# Cluster State Machine

A learning project focused on building a small replicated counter with Aeron
Cluster. The initial behavior is deliberately limited to increment and
decrement commands and publishing the resulting count.

The goal is to understand ordered command processing, deterministic state
transitions, snapshots, replay, and cluster recovery. Banking, trading, gateways,
web UIs, and deployment are outside the current scope.

## Language choice

Aeron Cluster's clustered-service API and official examples are Java-based.
Java is the simplest starting point for following the Aeron Cluster tutorials
directly. Kotlin/JVM is also a valid choice and can call Aeron's Java APIs; use it
if keeping the original Kotlin direction is more important than matching the
examples line by line. Aeron also has C, C++, and .NET clients for Aeron messaging,
but those are not the normal implementation path for an Aeron Cluster service.

**Current implementation language:** undecided; choose Java or Kotlin/JVM before
adding application code.

## Getting started

Setup and run instructions will be added once the language, JDK, and Aeron
versions are selected. Keep versions pinned and use the official Aeron Cluster
documentation for the selected version.
