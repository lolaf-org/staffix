# Staffix Replication

Replicates an application's data from a leader to its followers over TCP, with snapshots to catch a follower up,
optional encryption, and writes that can wait for the followers' acknowledgements. It knows nothing of FIX: the
records it carries are the application's bytes. Staffix's own high availability (`staffix-ha-impl`) is built on it.

It replicates; it does not elect. Which node leads, and the epoch numbering each leadership, come from the
application's election, such as `staffix-ha-primos`.

```xml
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-ha-replication</artifactId>
    <version>${staffix.version}</version>
</dependency>
```

## Model

- **A stream** is a sequence of records, each at a position that increases between two resets. The application
  stores its records itself; the library only carries them.
- **A record** has a content type (one byte, the application's), a flag, its position and a payload.
- **A replication mode**, per stream: `ASYNC` waits for nothing, `SYNC_RECEIVED` and `SYNC_APPLIED` let a write wait
  until the followers received or applied it.
- **A snapshot** copies a stream's state and records to a follower that is missing history, while the stream keeps
  being written.

The wire format is in [PROTOCOL.md](PROTOCOL.md).

## What the application implements

The interfaces of `org.lolaf.staffix.ha.replication.spi`:

| interface | role |
|---|---|
| `LeaderStreams`, `LeaderStream` | the streams the leader announces: key, name, mode, where it stores them |
| `SnapshotSource` | reads a stream's state and records back from the application's storage, for a snapshot |
| `ReplicaSinks` | on a follower, opens the sink each announced stream is applied into, or refuses it |
| `ReplicaSink` | applies one stream's records and snapshots into the follower's storage |

`ReplicationClock`, `ReplicationMetrics` and `LatencyRecorder` are optional: the defaults are the system clock and no
metrics.

## Running a node

```java
ReplicationNode node = ReplicationNode.builder()
        .nodeId("node-a")
        .bindAddress(new InetSocketAddress(7000))
        .streamsToReplicate(myLeaderStreams)
        .replicaSinks(myReplicaSinks)
        .build();

// as the election decides
node.follow(leaderAddress, () -> knownEpoch, Deadline.of(Duration.ofSeconds(5)));
node.lead(epoch, Deadline.of(Duration.ofSeconds(5)));

// on the leader, once a record is stored
StreamWriter writer = node.writer(streamKey);
long offset = writer.append(contentType, RecordFlag.NONE, position, buffer, index, length);
writer.awaitAck(offset, StreamReplicationMode.SYNC_APPLIED, timeoutNanos);
```

Both nodes give the same `ReplicaSinks.getApplicationId()`; a peer replicating another application is refused.
`handOver` before stopping a leader lets the next one take over each stream where it was left.

The builder's other settings (lanes, log capacity, timeouts, threads, encryption) have defaults, documented on
`ReplicationNodeBuilder`.

## Rules the application keeps

- **Store, then append.** A record is appended after the application stored it, in position order: a snapshot reads
  it back from there.
- **One thread per writer.** A `StreamWriter` is used from one thread at a time; it allocates nothing.
- **A sink failure reconnects.** A `ReplicaSink` throwing `WireFormatException` reports the leader's error; any other
  exception is the follower's own failure to apply, after which it reconnects and resumes past the last record
  written.
- **Each node stores its own copy.** `ReplicaSinks.open` refuses a stream the leader stores where this node would
  write it, or whose storage it cannot tell apart: replicating into it would rewrite the leader's data.
