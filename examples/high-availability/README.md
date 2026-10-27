# High availability example

An acceptor pair elected by [primos](https://github.com/lolaf-org/primos), as the
[high availability guide](../../docs/high-availability.md) describes it, with each process on its own so you can stop,
kill and restart them by hand:

| process            | what it is                                                                                                                                                                      | listens on                                                  |
|--------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|
| `witness`          | the primos witness: the third voter, which never leads, so either node alone still has a majority                                                                               | UDP 7420                                                    |
| `node-1`, `node-2` | the same engine twice ([`HaNode`](src/main/java/org/lolaf/staffix/examples/ha/HaNode.java)): an `ASYNC` replicated acceptor session answering each Email, primos run by staffix | FIX 7111 / 7112, replication 17101 / 17102, UDP 7421 / 7422 |
| `counterparty`     | an initiator given both nodes' addresses ([`Counterparty`](src/main/java/org/lolaf/staffix/examples/ha/Counterparty.java)), sending an Email every second                       | none                                                        |

All on `127.0.0.1`. Each node keeps its message store and its primos term and vote under `target/node-<id>`, the
witness its votes under `target/witness`, so a restarted process carries on where it stopped; `mvn clean` starts the
pair afresh.

## Running it

```bash
mvn package -pl examples/high-availability -am -DskipTests     # from the repository root
cd examples/high-availability
./start-witness.sh
./start-node.sh 1
until ./status.sh | grep -q "node 1: LEADER"; do sleep 1; done
./start-node.sh 2
./start-counterparty.sh
tail -f target/logs/*.log
```

Each script starts its process in the background, logging to `target/logs/<name>.log`. Node 1 starts alone with the
witness, so it wins the first election, a few seconds after it starts; node 2 then joins as its follower.
`./status.sh` tells which node leads:

```
node 1: LEADER
node 2: FOLLOWER
```

Once the nodes have been stopped and restarted, either may lead, so the commands below name a node by its role:
`./stop-node.sh leader` stops whichever node leads, cleanly (SIGTERM), and `./stop-node.sh leader -9` kills it, as a
crash would; `follower`, `1` and `2` work too. `stop-witness.sh` and `stop-counterparty.sh` take the same `-9`.

The lines below are from a real run of these steps, trimmed of their time, thread and log level.

## What to try

### The pair comes up

Both nodes start as standby: the acceptor binds nothing. Node 1 is elected, binds its FIX port and listens for
replication; node 2 connects to it and receives a snapshot.

```
node-1        Replica - FIX session ACCEPTOR default.ha:VERSION_44:GATEWAY->CLIENT outgoing sequence number moved from 1 to 10001, ...
node-1        ReplicationFixEngineHa - Node 1 is now LEADER at epoch 1
node-2        ReplicationFixEngineHa - Node 2 is now FOLLOWER at epoch 1, following /127.0.0.1:17101
counterparty  Counterparty - Gap in the gateway's MsgSeqNum: asked for 1 to 10000 again
counterparty  Counterparty - Logged on
counterparty  Counterparty - Gap 1 to 10000 filled
node-2        LeaderLink - Following leader 1 at epoch 1, from its log tail, needing a snapshot
node-1        FollowerLink - Snapshot of FIX session ACCEPTOR default.ha:VERSION_44:GATEWAY->CLIENT sent to follower 2: 0 messages
counterparty  Counterparty - request 1 answered by node 1
```

The gap on the very first logon is expected: a node taking leadership cannot tell a first start from a takeover after
the other node crashed, so it moves its outgoing sequence numbers ahead by `promotionOutgoingSeqNumJump` (10 000), and
the counterparty asks for the gap and gets a gap fill.

### Kill the leader: a jump and a gap fill

```bash
./stop-node.sh leader -9
```

It prints which node it killed, node 1 here. Node 2 is elected within the primos election timeout (2 to 4 seconds),
moves its sequence numbers ahead, since node 1 may have sent messages it never replicated, and the counterparty logs on
to it after its next connection attempt:

```
counterparty  Counterparty - Logged out: Remote disconnection
node-2        ReplicationFixEngineHa - Node 2 is now UNKNOWN at epoch -1
node-2        Replica - FIX session ACCEPTOR default.ha:VERSION_44:GATEWAY->CLIENT outgoing sequence number moved from 10022 to 20022, ...
node-2        ReplicationFixEngineHa - Node 2 is now LEADER at epoch 2
counterparty  Counterparty - Gap in the gateway's MsgSeqNum: asked for 10022 to 20021 again
counterparty  Counterparty - Logged on
node-2        HaNode - Node 2: gap filled up to MsgSeqNum 20022
counterparty  Counterparty - Gap 10022 to 20021 filled
counterparty  Counterparty - request 21 answered by node 2
```

A request sent just before the kill may never be answered: an answer node 1 sent but did not replicate falls in that
gap, which the counterparty receives as a gap fill. Staffix replicates the session, not the application; recovering
such a request is the application's business.

### Restart it: it comes back as follower

Start the node you killed again, by the number `stop-node.sh` printed:

```bash
./start-node.sh 1
```

The pair already has a leader, so it follows it and is caught up by snapshot:

```
node-1        ReplicationFixEngineHa - Node 1 is now FOLLOWER at epoch 2, following /127.0.0.1:17102
node-2        FollowerLink - Follower 1 connected at epoch 2, needing a snapshot
node-1        LeaderLink - Following leader 2 at epoch 2, from its log tail, needing a snapshot
node-2        FollowerLink - Snapshot of FIX session ACCEPTOR default.ha:VERSION_44:GATEWAY->CLIENT sent to follower 1: 15 messages
```

The counterparty sees nothing of it.

### Stop the leader cleanly: no jump

```bash
./stop-node.sh leader
```

Node 2, the leader now, logs its session out, sends node 1 every write it has left and tells it so, then hands primos
leadership over. Node 1 takes the session over at the same sequence numbers, and the counterparty logs on again with
no gap:

```
counterparty  Counterparty - Logged out: FIX server stop
node-2        HaNode - Node 2: logged out, FIX server stop
node-2        FollowerLink - Closing the replication stream to follower 1: SHUTDOWN the leader stopped cleanly, every event was sent
node-1        LeaderLink - Leader 2 stopped cleanly and handed over: the leader stopped cleanly, every event was sent
node-1        Replica - FIX session ACCEPTOR default.ha:VERSION_44:GATEWAY->CLIENT taken over where the old leader cleanly left it
node-1        ReplicationFixEngineHa - Node 1 is now LEADER at epoch 3
counterparty  Counterparty - Logged on
counterparty  Counterparty - request 46 answered by node 1
```

Start the stopped node again and it follows; `./stop-node.sh leader` then switches back to it, the same way.

### Lose the witness

With both nodes running again:

```bash
./stop-witness.sh
```

Nothing changes while both nodes run: two of three voters are still a majority. Kill the follower as well
(`./stop-node.sh follower -9`) and the leader can no longer prove it leads: it drops the session, without a Logout,
and the counterparty retries both addresses in vain.

```
counterparty  Counterparty - Logged out: Remote disconnection
node-1        ReplicationFixEngineHa - Node 1 is now UNKNOWN at epoch -1
```

Start the witness again (`./start-witness.sh`) and the leader is re-elected, logging the session back on with a
sequence number jump:

```
node-1        Replica - FIX session ACCEPTOR default.ha:VERSION_44:GATEWAY->CLIENT outgoing sequence number moved from 20073 to 30073, ...
node-1        ReplicationFixEngineHa - Node 1 is now LEADER at epoch 4
counterparty  Counterparty - Gap in the gateway's MsgSeqNum: asked for 20073 to 30072 again
counterparty  Counterparty - Logged on
counterparty  Counterparty - Gap 20073 to 30072 filled
```

### Stop everything

```bash
./stop-counterparty.sh; ./stop-node.sh 1; ./stop-node.sh 2; ./stop-witness.sh
```

## Going further

- **Planned switchover** without stopping a process: the leader's admin API (`POST v1/ha/switchover`, see
  [Runtime administration](../../docs/runtime-administration.md)); this example does not start the HTTP admin.
- **`SYNC`** replication: change `ReplicationMode.ASYNC` in `HaNode`. A message then reaches the counterparty only once
  the follower has it.
- **Real deployment**: the witness on a third machine, the secret out of the code and out of `witness.properties`
  (`env:` or `file:`), and the same session settings on both nodes. See the
  [high availability guide](../../docs/high-availability.md).
