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
  those writes, else by a snapshot of each session (its sequence numbers and its most recent messages). A snapshot
  goes in chunks between the other sessions' writes, so they keep replicating, and their `SYNC` sends keep flowing,
  while it lasts.
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
  replicationMode: ASYNC        # NONE (default), ASYNC or SYNC
  replicationAckTimeout: PT0S   # SYNC only: how long a send waits for the follower, zero for ever
```

**Configure the same sessions, with the same settings, on both nodes.** Each node reads its own settings, and a node
that becomes leader runs the sessions it has. A follower whose `replicationMode` for a session differs from the
leader's refuses to replicate it, logs an error and makes the leader raise the `ha-replication` alert, until both agree.

The other settings of `ReplicationHaSettings`, with their defaults:

| setting | default | what it decides |
|---|---|---|
| `logCapacity` | 16 MiB | Off-heap memory holding the writes the follower has not received yet. A follower falling further behind is caught up by snapshot. The largest message replicated is half of it. |
| `heartbeatInterval` | 1s | How often an idle leader tells its follower where it stands. |
| `reconnectInterval` | 1s | How long a follower waits between two attempts to reach its leader. |
| `snapshotMaxMessages` | 10 000 | The most recent messages of a session a snapshot copies. |
| `promotionOutgoingSeqNumJump` | 10 000 | How far a new leader moves its outgoing sequence numbers ahead. |
| `handOverTimeout` | 5s | How long a leader stopping cleanly waits to give its follower every write. |
| `switchoverTimeout` | 30s | How long a planned switchover may take before this node leads again. |
| `followerLagAlertThreshold` | 5s | How long a connected follower may go without applying a write before the leader raises a major alert. A follower stalled while connected still reads as in sync, as of its last acknowledgement. |
| `monitoringInstanceId` | none | The monitoring manager publishing the replication's meters. |
| `alertsPublisherInstanceId` | none | The alerts publisher publishing the replication's probes. |

## Replication modes

Each session picks one.

- **`NONE`**: not replicated. After a failover the session starts from the new leader's own store; see
  [Caveats](#caveats).
- **`ASYNC`**: each write is replicated as it is made, after the message went out. Nothing waits, so it costs the
  session almost nothing; a leader that fails may have sent messages the follower never received, which the new leader
  covers by moving its sequence numbers ahead.
- **`SYNC`**: a message is replicated before it is sent, and the send waits for the follower's acknowledgement. The
  counterparty never receives a message the follower does not have. Each send pays a round trip to the follower: about
  330 µs more than `ASYNC` on one machine.

**`SYNC` never stops trading for a missing follower.** With `replicationAckTimeout`, a send waits at most that long, then
goes out anyway. With no follower in sync (not connected, catching up), a send does not wait at all. Either way the
session is flagged unprotected and its `ha-replication` probe alerts, and waiting resumes once a follower is back in
sync. `syncAckLatencyEnabled: true` adds a timer of the wait to the session's own meters.

Neither mode replicates what the session receives as it arrives: only the expected incoming sequence number, after the
message was processed. A new leader that is a few messages behind asks the counterparty to resend them, which it does.

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
  in pieces, but a large message takes a large share of the log, and a follower that falls a whole log behind is
  caught up by snapshot: size `logCapacity` to at least four times your largest message.
- **A snapshot copies the last `snapshotMaxMessages` messages of a session.** A follower caught up by snapshot answers
  an older resend request with a gap fill once it leads, even when the old leader's store kept everything. Size it to
  how far back your counterparties ask.
- **A restarted follower is caught up by snapshot**: it does not remember where it left the stream.

## Watching it

- **Meters** (`ha.*`): role, epoch, followers connected and in sync, unsent bytes, how far each follower is behind,
  `SYNC` sends that went out unacknowledged, snapshots, laps, and the acknowledgement round trip per follower; see
  [Monitoring](monitoring.md).
- **Probes**: the node's (`ha-followers`, `ha-leader-link`, `ha-leadership`) and each replicated session's
  (`ha-replication`); see [Alerting](alerting.md).
- **Status**: `GET v1/ha` and `GET v1/ha/sessions` say who leads, whether the follower holds each session and how far
  behind it is, and, on the follower, whether a session would be taken over cleanly now.
