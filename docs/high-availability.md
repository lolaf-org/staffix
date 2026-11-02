# High availability

Two engines run the same sessions. One leads and runs them; the other is a **standby**: started, but with no acceptor
bound and no initiator connecting. It receives every write the leader makes to its session stores, over TCP, and
applies it to its own stores. When the leader fails, the standby takes over its sessions from where the leader left
them, close enough that the counterparty only sees a disconnection and, at worst, a sequence gap it fills by itself.

What it is not: **your application's state is not replicated.** Staffix replicates what the session layer stores
(sequence numbers and the messages it may have to resend). The orders, positions or anything else your application
built from the messages it received are yours to make available on the other node.

The pair is two nodes: a leader and one follower. [`examples/high-availability`](../examples/high-availability) runs
one on your machine, with a witness and a counterparty, to kill and restart by hand.

---

## How it fits together

- **A leader election decides which engine leads.** The shipped one is [primos](https://github.com/lolaf-org/primos),
  which runs the election half of Raft. With two nodes it needs a third voter that never leads, a primos **witness**,
  in a third failure domain, or losing either node would leave no majority.
- **The standby engine builds no session.** Its acceptors bind nothing and its initiators connect nowhere until it
  leads. Session actions sent to it through the admin API (logon, reset, send a message) are refused with a clear
  message (HTTP 409), since running one there would put the session up on both nodes.
- **The leader replicates each write as it makes it**, per session, in the mode the session asks for (see below). The
  follower acknowledges what it applied.
- **A follower that connects late, or lost the stream, catches up**: from where it left off when the leader still holds
  those writes, else by a snapshot of each session (its sequence numbers and every message its store keeps). A snapshot
  goes in chunks between the other sessions' writes, so they keep replicating, and their `SYNC` sends keep flowing,
  while it lasts, if more slowly: `replicationLinks` keeps that to the sessions sharing its link.
- **On taking over**, the new leader runs the sessions at the sequence numbers the old one left them, when the old
  leader handed over cleanly. After a failure it moves them ahead first, since the old leader may have sent messages it
  never received (see [Failover and sequence numbers](#failover-and-sequence-numbers)).

## Setting it up

Depend on `staffix-ha-impl` and, for the primos election, `staffix-ha-primos`. Then give the engine a
`ReplicationHaSettings`:

```java
FixEngineBuilder.builder()
        // stores, applications, settings stores as usual
        .highAvailability(ReplicationHaSettings.builder()
                .nodeId("1")
                .peer("1", new InetSocketAddress("fix-a.internal", 17101))
                .peer("2", new InetSocketAddress("fix-b.internal", 17101))
                .leaderElection(PrimosLeaderElectionSettings.builder()
                        .cluster(ClusterConfig.builder()
                                .applicationName("fix-gateway")
                                .nodeId(1)
                                .peer(Peer.application(2, "fix-b.internal", 7421))
                                .witness(Peer.witness(Peer.MAX_ID, "witness.internal", 7421))
                                .build())
                        .stateDirectory(Path.of("/var/lib/fix-gateway/primos"))
                        .bindAddress(new InetSocketAddress(7421))
                        .clusterSecret(secret)
                        .build())
                .build())
        .build();
```

- **`peers` is the same map on every node**: where each node accepts replication while it leads. A node binds its own
  entry and connects to the leader's.
- **The replication node ids are the primos node ids, as decimal text**: primos names the leader by its id, and the
  replication looks that name up in `peers`.
- **Each node has its own `stateDirectory`**, durable: primos keeps its term and vote there, and a node that forgets
  them across a restart can elect two leaders.
- **`clusterSecret` signs every primos datagram.** Without it, a forged datagram can make a leader no majority elected.
- When the application already runs a primos node for its own leader-only work, hand it over with `.node(primosNode)`
  instead of `.cluster(...)`: the FIX sessions and that work then follow one election. Staffix only listens to that
  node; it never starts or stops it.

Then ask for replication per session, in its settings:

```yaml
haSessionSettings:
  replicationMode: ASYNC        # NONE (default), ASYNC, SYNC_RECEIVED or SYNC_APPLIED
  replicationAckTimeout: PT1S   # SYNC modes only: how long a send waits for the follower, zero for ever
```

**Configure the same sessions, with the same settings, on both nodes.** Each node reads its own settings, and a node
that becomes leader runs the sessions it has. A follower whose `replicationMode` for a session differs from the
leader's refuses to replicate it, logs an error and makes the leader raise the `ha-replication` alert, until both agree.

The other settings of `ReplicationHaSettings`, with their defaults:

| setting | default | what it decides |
|---|---|---|
| `logCapacity` | 16 MiB | Off-heap memory holding the writes the follower has not received yet. A follower this far behind makes the sessions' writes wait for it, at most `logFullTimeout`. The largest message replicated is half of it. |
| `replicationLinks` | 1 | How many links the leader splits the sessions over, each its own connection, `logCapacity` log and sender thread, with a network thread on each node. A snapshot, or a follower slow to apply, then holds back only the sessions of its link, and the follower applies the links in parallel. Costs `logCapacity` of off-heap memory and the threads again per link. The follower opens as many as the leader has. |
| `heartbeatInterval` | 1s | How often an idle leader tells its follower where it stands. |
| `reconnectInterval` | 1s | How long a follower waits between two attempts to reach its leader. |
| `sendBuffers` | 4 | Write buffers per follower link, a power of two, allocated as first needed. More help a distant follower; each costs the largest event frame (`logCapacity` / 8, 64 KiB at least) of off-heap memory per follower link. |
| `snapshotChunkBytes` | 64 KiB | The most bytes of a snapshot sent between two batches of the other sessions' writes. The leader reads each chunk from the session's store while the link's live writes wait, about 70 µs from a file store at 64 KiB. Larger finishes a snapshot sooner under load, smaller delays the other sessions less. |
| `senderIdleStrategy` | backs off | How the leader's sender, a thread per follower link, waits for writes. It parks when idle, woken by a write; `BusySpinIdleStrategy` saves the wake-up, for a core per follower. |
| `syncAckIdleStrategy` | backs off | How a `SYNC` send waits for its acknowledgement, on the session's thread. `BusySpinIdleStrategy` sees it at once, for that thread's core while it waits. |
| `followerStoreWriterThreads` | 0 | Threads writing what the follower receives into its stores, its links spread over them; 0 writes on each link's network thread. Set it, to the link count typically, with a durable follower store or `SYNC_RECEIVED` sessions: the network thread then keeps reading while a store writes. A memory store gains nothing. |
| `followerStoreWriterIdleStrategy` | backs off | How a store writer thread waits for what to write, woken by the network thread that read it. |
| `selectStrategy` | betty's, blocking | How the replication's network threads wait, on both nodes. An `IdleStrategySelectStrategy` busy spinning saves a system call per wake-up, for a core on each node. |
| `promotionOutgoingSeqNumJump` | 10 000 | How far a new leader moves its outgoing sequence numbers ahead. |
| `handOverTimeout` | 5s | How long a leader stopping cleanly waits to give its follower every write. |
| `switchoverTimeout` | 30s | How long a planned switchover may take before this node leads again. |
| `followerLagAlertThreshold` | 5s | How long a connected follower may go without applying a write before the leader raises a major alert. A follower stalled while connected still reads as in sync, as of its last acknowledgement. |
| `followerStallTimeout` | 10s | How long a follower owing an acknowledgement may stay silent before the leader drops it as hung; it reconnects and is caught up. A slow follower still acknowledges each read and is kept. Zero never drops. |
| `logFullTimeout` | 1s | How long a write waits for a follower a whole `logCapacity` behind before the leader drops it as too slow; it reconnects and is caught up by snapshot. The wait holds the FIX IO thread writing, and every session on it. Must be positive. |
| `slowFollowerHoldOff` | 1s | How long a follower dropped as too slow is refused before it may resync, since catching it up adds to the load that made it too slow. Doubled at each drop up to a minute, and started over once it stayed in sync that long. Zero never holds a follower off. |
| `monitoringInstanceId` | none | The monitoring manager publishing the replication's meters. |
| `alertsPublisherInstanceId` | none | The alerts publisher publishing the replication's probes. |

## Replication modes

Each session picks one.

- **`NONE`**: not replicated. After a failover the session starts from the new leader's own store; see
  [Caveats](#caveats).
- **`ASYNC`**: each write is replicated as it is made, after the message went out. Nothing waits unless a follower is
  a whole `logCapacity` behind, so it costs the session almost nothing; a leader that fails may have sent messages the
  follower never received, which the new leader covers by moving its sequence numbers ahead.
- **`SYNC_APPLIED`**: a message is replicated before it is sent, and the send waits for the follower to have applied it
  to its store. The counterparty never receives a message the follower does not have. Each send pays a round trip to
  the follower: on one machine, about 30 µs more than `ASYNC` with the defaults, 15 µs with the replication busy
  spinning; see [Latency](#latency).

**`SYNC` never stops trading for a missing follower.** A send waits at most `replicationAckTimeout` (1 s by default),
then goes out anyway, and the sends after it do not wait for that follower until it has caught up, so a hung follower
costs one timeout, not one per message; the leader drops it after `followerStallTimeout`. With no follower in sync
(not connected, catching up), a send does not wait at all. Either way the
session is flagged unprotected and its `ha-replication` probe alerts, and waiting resumes once a follower is back in
sync. `syncAckLatencyEnabled: true` adds a timer of the wait to the session's own meters.

**A follower that cannot keep up slows the writes, briefly, and is then dropped.** The leader keeps what a connected
follower has not been sent in its replication log, `logCapacity` bytes. When the follower falls that far behind, a
session's next write waits for it, holding the FIX IO thread it runs on, and every session on that thread, for at most
`logFullTimeout` (1 s by default). Past that, the leader drops the follower, carries on, and refuses it for
`slowFollowerHoldOff` (1 s, doubling at each drop up to a minute; `0s` never refuses it) before it catches up by
snapshot, so its catching up does not add to the load. Until it is back in sync, the `ha-followers` probe raises a
major alert and the status lists it under `droppedFollowers`. Size `logCapacity` so a healthy follower never falls a
whole log behind.

**More replication links keep a slow stream to its own sessions.** With `replicationLinks` above 1, session `n` (in
the order the sessions were created) goes over link `n` modulo the link count. Each link has its own log, so a follower
behind on one link makes only that link's sessions wait, and is dropped from that link alone; a snapshot of a session
with many messages delays only its link's sessions. The status has an entry per follower and link, and the
per-follower meters a `link` tag. Raise it when snapshots of a busy session slow the others, or when the follower's
store applies one stream too slowly, a database for instance.

Neither mode replicates what the session receives as it arrives: only the expected incoming sequence number, after the
message was processed. A new leader that is a few messages behind asks the counterparty to resend them, which it does.

## Latency

A `SYNC` send waits for the leader's sender to pick the write up, for the follower to apply it and acknowledge it, and
for the session to see the acknowledgement. By default each of those threads backs off when idle and parks, and the
write or the acknowledgement wakes it, so most of the wait is wake-ups, not work. Three settings trade cores for it:

- **`senderIdleStrategy`**: the leader's sender, one thread per follower link, busy spinning. One core per link.
- **`syncAckIdleStrategy`**: the session thread waiting for its acknowledgement, busy spinning. That thread's core,
  for as long as it waits.
- **`selectStrategy`**: the replication's network threads on both nodes, busy spinning instead of blocking in the
  selector. One core on each node.

`BusySpinIdleStrategy` (from ringos) for the first two, and an `IdleStrategySelectStrategy` around it for the third;
in Spring Boot, `busy-spin` for each. Measured with `HaRttBenchmark` (in `benchmarks`): a counterparty's request and the
leader's answer, the answer's store write replicated `SYNC`, leader, follower and counterparty in one JVM on 14 cores,
the FIX side busy spinning. Round trip in µs, mean / p99:

| busy spinning | one session | beside a second session trading continuously |
|---|---|---|
| nothing (the defaults) | 43 / 60 | 82 / 98 |
| sender | 42 / 57 | 79 / 85 |
| sender and waiter | 35 / 41 | 73 / 86 |
| sender, waiter and network threads | 28 / 31 | 51 / 56 |

For comparison, the same round trip is 11 µs without replication and 15 µs with `ASYNC`.

- **Spin all three, or none.** Spinning the sender or the waiter alone saves a few µs; spinning the network threads
  too is what pays. Each spinning thread needs a core of its own: pin them, or keep their count below the free cores,
  else a thread still blocking wakes late.
- **These are lower bounds.** Both nodes ran in one JVM over loopback: a network between them adds its round trip to
  every `SYNC` send, typically tens of microseconds in one data center.
- **One message at a time.** The benchmark waits for each answer before sending the next, which measures latency, not
  the latency at a high message rate.
- **A snapshot slows the `SYNC` sends of its link while it runs.** The leader sends a snapshot in chunks between the
  link's live writes, so a write can wait for the chunk in progress, and the follower for the one it is applying.
  With a snapshot always running beside a busy session, a `SYNC` send's wait for its acknowledgement went from 35 to
  93 µs at p50 and from 68 to 229 µs at p99. A snapshot is the exception (a follower that connects late or lost the stream, one dropped as
  too slow, a re-sync from the admin API), `ASYNC` sends do not wait for it, and more `replicationLinks` keep it to the
  sessions of one link.

In production, `ha.follower.ack.latency` times the follower's part (a batch sent to its acknowledgement) and
`ha.sync.ack.latency` the whole wait; a large gap between them is the leader's wake-ups. See [Monitoring](monitoring.md).

## Failover and sequence numbers

**When the old leader hands over cleanly, the new one carries on at the same sequence numbers.** The counterparty sees
a Logout from one node and a Logon from the other, and nothing else. That is the case for:

- **a planned switchover**, below;
- **a leader engine stopping cleanly**: it logs its sessions out, sends its follower every remaining write, then tells
  it so. With the primos election staffix runs, stopping the engine also hands primos leadership over. With your own
  primos node, stop the engine before the node.

**When the old leader failed instead** (crashed, killed, cut off), the new leader cannot know whether it sent messages
the follower never received. Reusing their numbers would make the counterparty refuse the next message as too low, so
the new leader moves the outgoing sequence number of every replicated session ahead by `promotionOutgoingSeqNumJump`
before running it. The counterparty sees a gap, asks for it, and gets a SequenceReset-GapFill. A resend request and a
gap fill after an unplanned failover are expected.

The same applies to a follower that was not fully caught up when the leader stopped, and to **the first leader of a
fresh pair**: a node cannot tell a first start from a failover after the other node crashed, so a new deployment's
first logon already shows a gap of the jump's size.

## Planned switchover and maintenance

The high availability has its own admin entry point, `AdminApi.getHa()`, also over HTTP (under `v1/ha`) and JMX (MBean
`type=FixHa`); see [Runtime administration](runtime-administration.md).

- **Switchover** (`POST v1/ha/switchover`) hands leadership to the follower while this node keeps running: once the
  follower holds every replicated session, this node logs its sessions out, gives the follower every write, then has
  the election transfer leadership. The counterparty sees an orderly Logout and logs on to the new leader at the same
  sequence numbers. The answer names the outcome: `TRANSFERRED`; `NO_FOLLOWER_IN_SYNC`, nothing was changed;
  `NOT_HANDED_OVER` or `NOT_TRANSFERRED`, this node leads its sessions again.
- **Maintenance mode** (`POST v1/ha/candidacy`, `{"candidate": false}`) takes a node out of elections: it still votes,
  but will not lead. A leader withdrawing hands leadership over and keeps leading until the other node takes it, so
  this alone never leaves the pair without a leader.
- **Re-sync** (`POST v1/ha/resync`, or one session with `POST v1/ha/sessions/{group}/{name}/resync`) sends the follower
  a fresh copy of each replicated session. Until it holds a session again, that session's `SYNC` sends do not wait
  for it.

Switchover and maintenance need an election that can do them. A primos node staffix runs always can. With your own
primos node, set `PrimosLeaderElectionSettings.leadershipControl(true)` to let staffix move a leadership your
application shares. `v1/ha` reports what the election supports.

## Fencing

**A leader that can no longer prove it leads stops its sessions at once, without a Logout.** With primos, that is a
leader cut off from the majority (its follower and the witness). No other node can be elected until one minimum
election timeout (`electionTimeoutMin`) after the last heartbeat the majority answered was sent: the witness refuses to
vote before then. Staffix drops the leader's sessions `fencingMargin` (`PrimosLeaderElectionSettings`, Spring
`staffix.ha-replication.fencing-margin`, 50 ms by default) before that instant, so they are down before the other node
can even be elected.

Raise `fencingMargin` when dropping your sessions takes longer, or your machines' clocks may drift apart over an
election timeout (they rarely do by more than a fraction of a millisecond). It must stay below `electionTimeoutMin`:
a leader whose heartbeats go unanswered for longer than the difference drops its sessions, then leads them again with
a sequence number jump once contact is back. Keep the primos timings the same on all three nodes, and put the witness
where neither node's network failure takes it down too.

## Shared storage

When both nodes use the same storage, the same database or the same directory, replicating into it would rewrite the
leader's own rows. The follower detects it and replicates nothing for that session, which a failover then reads
directly from the storage. Each store names its storage: the JDBC store from the database server's own id (or its URL)
plus catalog, schema and table prefix; the file store from a marker file in its directory. When the two nodes reach one
database under different names (a VIP, a proxy), set `JdbcMessageStoreSettings.storageIdentity` to the same value on
both. A storage that cannot be identified is not replicated at all, rather than risk it.

Taking over after a failure still moves the sequence numbers ahead with shared storage: the old leader may have sent a
message it never stored. A clean handover does not.

## Caveats

- **Application state is not replicated.** See the introduction.
- **A session not replicated (`NONE`) starts on the new leader from that node's own store**, empty or stale. Its
  counterparty refuses the new leader's Logons as MsgSeqNum too low, but each refused Logon still uses a sequence
  number, so after enough reconnections one is accepted. The new leader then asks for everything it never received,
  and **the counterparty's old messages, which the old leader already processed, reach your application again**, as
  possible duplicates. Replicate every session that must survive a failover, or give it `resetSeqNumOnLogon` or a
  shared store.
- **A message longer than half of `logCapacity` is not replicated** (8 MiB with the default). The session logs an
  error and alerts; a resend of that message after a failover is gap filled. Messages up to that size are replicated
  in pieces, but a large message takes a large share of the log, and a follower a whole log behind makes writes wait:
  size `logCapacity` to at least four times your largest message.
- **A snapshot copies every message the leader's store keeps, and the follower's store then keeps what its own
  settings allow.** Give the follower's store at least the leader's retention (`maxEntriesInMemory`, the file store's
  `blocksCount` and `blockSize`, `maxMessagesPerSession`): a resend asking for a message it dropped is answered with a
  gap fill once it leads. A store keeping everything makes a long snapshot, which slows the `SYNC` sends of its link a little
  (see Latency), and the session is not in sync until it ends.
- **A restarted follower is caught up by snapshot**: it does not remember where it left the stream.

## Watching it

- **Meters** (`ha.*`): role, epoch, followers connected and in sync, unsent bytes, how far each follower is behind,
  `SYNC` sends that went out unacknowledged, snapshots, followers dropped as too slow, and the acknowledgement round
  trip per follower and link; see
  [Monitoring](monitoring.md).
- **Probes**: the node's (`ha-followers`, `ha-leader-link`, `ha-leadership`) and each replicated session's
  (`ha-replication`); see [Alerting](alerting.md).
- **Status**: `GET v1/ha` and `GET v1/ha/sessions` say who leads, whether the follower holds each session and how far
  behind it is, which followers were dropped as too slow and are not back in sync, and, on the follower, whether a
  session would be taken over cleanly now.
