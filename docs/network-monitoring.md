# Network monitoring

Two operational questions about a FIX session: **how long the line takes**, and **whether the two clocks agree**.
Staffix answers both from inside the session, using the protocol's own TestRequest/Heartbeat exchange as an NTP-style
probe: nothing proprietary on the wire, nothing to agree with the counterparty.

Three numbers per session, updated as it runs:

| measurement | what it is |
|-------------|------------|
| `roundTripTime` | EMA-smoothed round trip to the peer, measured on a **monotonic** clock, immune to wall-clock skew and leap seconds on either side |
| `clockOffset` | EMA-smoothed remote-vs-local wall-clock offset. **Positive means the remote clock is ahead of yours** |
| `sampleTime` | local wall-clock instant of the most recent accepted sample |

---

## How it is measured

The probe is a plain `TestRequest(35=1)`, answered by the `Heartbeat(35=0)` the standard obliges the peer to send
with the same `TestReqID(112)`, so it works against any conforming counterparty.

1. The session sends a TestRequest whose `TestReqID` carries `probeTestReqIdPrefix`. In the **send callback**, so
   only if the message actually went out, it records the local monotonic time and the local wall-clock time.
2. The peer answers with a Heartbeat echoing the `TestReqID` and carrying its own `SendingTime(52)`, call it `R`.
3. On receipt the session computes, with `T1` and `T2` the local wall-clock at send and at receipt:

```
roundTripTime = recvMonotonic − sendMonotonic
clockOffset   = R − (T1 + T2) / 2          ← the NTP formula, assuming symmetric one-way latency
```

Both samples feed a **time-based EMA** weighted by the time since the previous accepted sample
(`tau = emaTimeWindow / 3`), so regular probes, occasional ones and dropped outliers behave the same, and a long idle
gap reseeds the estimate instead of averaging with something stale.

**Heartbeat-driven TestRequests feed the same estimator**, whether or not probing is on. Those are the ones the
session sends by itself when the peer has gone quiet for a heartbeat interval, so even a session with
`probeInterval` unset produces measurements, irregularly and only when the line is idle enough to need a
TestRequest. Continuous probing is what makes the numbers regular.

Samples are dropped, leaving the EMAs untouched, when the `TestReqID` is unknown or already matched (a duplicate or
a late answer) or when the measured RTT exceeds `maxAcceptedRtt`. An unanswered probe therefore costs a sample and
nothing else: it does not arm the liveness timer, which stays the heartbeat machinery's business.

When continuous probing is on, the whole state is discarded as the session goes down: a new connection is a new
line, and its predecessor's numbers say nothing about it. A session measuring only from heartbeat-driven
TestRequests keeps its last estimate across the reconnect, so treat `sampleTime` as part of the reading: an old
`sampleTime` means an old line.

---

## Turning it on

Off by default: a session that has not asked pays nothing at all.

```java
FixSessionSettings.builder()
        .fixSessionId(sessionId)
        .rttMeasurementSettings(FixSessionSettings.RttMeasurementSettings.builder()
                .probeInterval(Duration.ofSeconds(1))
                .build())
        .build();
```

| setting | default | what it does |
|---------|---------|--------------|
| `probeInterval` | `null`, disabled | interval between TestRequest probes. Floored at 25 ms |
| `emaTimeWindow` | 30 seconds | smoothing window; longer absorbs more jitter and converges slower |
| `maxAcceptedRtt` | 2 seconds | samples above this are discarded as outliers (GC pause, scheduling spike, network blip). `null` accepts anything |
| `probeTestReqIdPrefix` | `"RTT-measurement-"` | so probes are distinguishable from heartbeat-driven TestRequests in the logs |
| `sendingTimeToWireDelay` | 1500 ns | bias subtracted from each raw offset sample; see [below](#calibrating-sendingtimetowiredelay) |

The same settings are available where sessions are declared in YAML:

```yaml
rttMeasurementSettings:
  probeInterval: PT1S
  emaTimeWindow: PT30S
  maxAcceptedRtt: PT2S
```

and in Spring Boot properties, in kebab-case under `rtt-measurement`:

```properties
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].rtt-measurement.probe-interval=1s
```

**Probing is not free on the wire.** Each interval costs a TestRequest and a Heartbeat, each taking a sequence number
and being stored and logged: at one second, 86,400 extra messages a day each way. Pick the interval from how fast you
need to see the line change; the EMA provides the precision. Off the wire the cost is small: one scheduled task, a map
insert and removal per probe, and two EMA updates per sample, on the session's I/O thread.

---

## Reading the numbers

**From the session**, at any time:

```java
Optional<RttMeasurement> measurement = fixSession.getRttMeasurement();
```

Empty until the first sample lands, and empty again once a probing session goes down.

**As they happen**, in a [session plugin](session-plugins.md), called only for *accepted* samples, on the session's
I/O thread, so keep it cheap or put the plugin behind the async wrapper:

```java
@Override
public void onRttMeasurement(RttMeasurement measurement) {
    // measurement.getRoundTripTime(), .getClockOffset(), .getSampleTime()
}
```

**As metrics**, through the Micrometer plugin, both off by default and both fed from the same accepted samples, so
without `rttMeasurementSettings.probeInterval` on the session they report only whatever the heartbeat-driven
TestRequests happen to produce:

```java
OtlpMicrometerMonitoringManagerSettings.builder()
        .instanceId("monitoring-acceptor")
        .rttLatencyEnabled(true)     // session.rtt, a Timer fed the EMA after each sample
        .clockOffsetEnabled(true)    // session.clock.offset, a Gauge in nanoseconds
        // …
```

`session.clock.offset` is a Gauge rather than a histogram on purpose: the offset is **signed**, and Micrometer's
distribution instruments silently drop negative values, which are exactly the case where your clock is ahead of the
peer's.

**On a dashboard**, the provisioned Grafana board in [`monitoring/grafana`](../monitoring/grafana) already has both:
a *Network round trip time* time series in µs and a *Clock offset* gauge in ns, per session id. See
[Monitoring](monitoring.md). The [`advanced-monitoring`](../examples/advanced-monitoring) example runs the whole
chain (sessions probing every second, metrics exported over OTLP, dashboard included) against a loopback session,
which is also the setup to calibrate on.

The underlying protocol event is also available raw, without any of the estimation, as
`FixApplication.onTestRequestResponse(session, testReqID, remoteTimestamp)`.

---

## Calibrating `sendingTimeToWireDelay`

Worth understanding before you trust the offset. The NTP formula assumes the remote's timestamp is taken at the
instant of transmission. It is not: `SendingTime(52)` is stamped while the message is being *encoded*, and then the
encode tail, the write syscall, kernel queueing and the NIC handoff all happen before the bytes leave. That delay
lands in the offset as a **persistent positive bias**: the remote looks like its clock is ahead by the length of its
own send path, even when the two clocks are identical.

`sendingTimeToWireDelay` is the empirical correction, subtracted from each raw sample before it reaches the EMA.
It defaults to 1500 ns, and the right value is specific to the peer's engine and host, so measure it:

1. Run both sides on **loopback**, where the true offset is zero by construction.
2. Set `sendingTimeToWireDelay` to `Duration.ZERO` to see the raw bias.
3. Let the EMA settle and take the steady-state offset it reports. That is your value.

A few microseconds is typical for a Java FIX engine on a tuned host; a busy or untuned one shows more. The
correction is only applied when the raw offset exceeds it, so a small sample is left alone rather than being pushed
through zero into a negative reading, since a bias correction that flips the sign of the answer is worse than no
correction.

Against a real counterparty you cannot calibrate this directly, since you cannot separate their send path from the
genuine skew. Calibrate against your own engine on loopback and treat the result as the floor of what any peer's
send path costs.

---

## What the numbers are and are not

- **RTT is application-level, not ICMP.** It measures the line *plus* the peer's engine picking the TestRequest up
  and answering it. For a trading session that is the more useful number, the one your order will experience,
  but do not compare it with `ping` and expect agreement.
- **The offset assumes symmetric one-way latency.** That is NTP's assumption too, and it is what makes the midpoint
  meaningful. On an asymmetric route the offset is wrong by half the asymmetry; RTT is unaffected.
- **The offset cannot be finer than the peer's `SendingTime` precision.** A counterparty stamping tag 52 in
  milliseconds bounds your offset resolution at milliseconds however long you smooth. Staffix's own
  `sendingTimeAccuracy` defaults to microseconds and can be set to nanoseconds.
- **Both are EMAs, not the last sample.** They deliberately lag a step change in the line by roughly
  `emaTimeWindow`. If you want to alert on a sudden move, alert on the metric's own rate of change rather than
  expecting the estimate to jump.

---

## The liveness layer underneath

The same TestRequest machinery is what tells you the line is alive at all, and that part is always on: it is the
session protocol, not an option:

| setting | default | what it is |
|---------|---------|------------|
| `heartBeatInterval.initiatorInterval` | 10 s | what the initiator asks for on Logon |
| `heartBeatInterval.acceptorLowerBoundInterval` | 1 s | the smallest interval an acceptor will accept |
| `heartBeatInterval.acceptorUpperBoundInterval` | 20 s | the largest |

If nothing is received for a heartbeat interval the session sends a TestRequest, and if that goes unanswered within
another interval it disconnects, which is the point of it: a TCP connection can be dead for minutes without the
socket noticing.

---

## When the clocks disagree enough to matter

Clock offset is a measurement; `maxSendingTime` is a decision. Set it, and a message whose `SendingTime(52)` differs
from this session's clock by more than that (**stale or dated in the future, the threshold is two-sided**) is
rejected with `SessionRejectReason(373) = 10`, *SendingTime accuracy problem*, and the session is logged out
immediately after, which is what section 4.2.3 prescribes.

```java
.validationSettings(FixSessionSettings.ValidationSettings.builder()
        .maxSendingTime(Duration.ofSeconds(2))
        .build())
```

The two work together, and this is the argument for running the measurement even when you have no dashboard for it:
a peer's clock drifting toward your `maxSendingTime` is a session that is going to start dropping, and the offset
estimate is what lets you see it coming rather than reading it in a disconnect log. Note that the check compares the
received timestamp against your own clock and nothing else: it does not use the measured offset, and it cannot: the
estimate is yours, while the rejection has to be defensible to the counterparty.

`maxSendingTime` is `null` by default, and the check parses `SendingTime` on every inbound message, which the parser
otherwise defers. See [Tuning for latency](tuning-for-latency.md).
