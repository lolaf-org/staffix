# Configuring a session

Everything about one FIX session lives in a `FixSessionSettings`. This guide covers the settings you will need on
the first day, then the ones you will need when a counterparty asks for something specific.

Every default quoted here is read from
[`FixSessionSettings`](../api/src/main/java/org/lolaf/staffix/api/session/FixSessionSettings.java); the javadoc there
is the authority.

---

## The minimum

```java
FixSessionId sessionId = FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
        .id("initiator-session")
        .senderCompID("initiator")
        .targetCompID("acceptor")
        .build());

FixSessionSettings settings = FixSessionSettings.builder()
        .fixSessionId(sessionId)
        .fixSessionType(FixSession.FixSessionType.INITIATOR)
        .build();
```

Only two settings have no default: **`fixSessionId`** and **`fixSessionType`**. Everything else has a value chosen to
be safe and cheap.

A `FixSessionId` is the FIX version plus the CompID pair. It is the identity of the session on the wire *and* the key
the engine uses to find the right application, store and logger, so `id` needs to be unique within the engine.

---

## Wiring the session to the engine

A session does not hold a store or an application; it holds the **instance id** of one. You register components on
the engine under an id, and the session names the id it wants:

```java
FixEngineBuilder.builder()
        .fixMessagesStore(MemoryMessageStoreSettings.builder().instanceId("acceptor").build())
        .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                .instanceId("acceptor")
                .application(sessionId.getId(), myApplication).build())
        // …
```

```java
FixSessionSettings.builder()
        .fixSessionId(sessionId)
        .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
        .fixMessageStoreInstanceId("acceptor")
        .fixMessageLoggerInstanceId("acceptor")
        .fixApplicationFactoryInstanceId("acceptor")
        .fixApplicationInstanceId(sessionId.getId())
        .build();
```

| setting | default | what it selects |
|---------|---------|-----------------|
| `fixMessageStoreInstanceId` | `DEFAULT_INSTANCE_ID` | which registered message store persists this session |
| `fixMessageLoggerInstanceId` | `DEFAULT_INSTANCE_ID` | which registered logger records it |
| `fixApplicationFactoryInstanceId` | `DEFAULT_INSTANCE_ID` | which factory produces its application |
| `fixApplicationInstanceId` | `DEFAULT_INSTANCE_ID` | which application *within* that factory |
| `dictionaryId` | `FixDictionaryId.DEFAULT_ID` | which FIX dictionary this session speaks |

All four default to the same id, so a single-session engine can leave every one of them out. The moment you run an
acceptor and an initiator in one process, set them: this is what keeps their sequence numbers, logs and applications
apart.

---

## Sequence numbers and recovery

| setting | default | notes |
|---------|---------|-------|
| `resetSeqNumOnLogon` | `null` | `true` asks for a sequence reset on every logon. Convenient in development, rarely right in production |
| `enabledLogonNextExpectedMsgSeqNum` | `true` | uses NextExpectedMsgSeqNum(789) on the Logon to synchronise, which avoids a resend round trip |
| `maxOutOfSequenceMessagesQueued` | `10_000` | how many messages arriving ahead of the gap are held while recovering |
| `maxOutgoingMessagesHeldDuringRecovery` | `1_000` | how many of your own messages are held back while a resend is in flight |
| `maxMessagesResentPerRequest` | `10_000` | cap on one ResendRequest's answer |
| `resendRequestRange` | `CLOSED` | whether resend requests are asked for as a closed range or open-ended |
| `resendRequestResponseTimeout` | 30 seconds | how long an answer to your own ResendRequest may stop making progress before it is asked for again, then given up on. `Duration.ZERO` waits forever |

`resetSeqNumOnLogon(true)` is what the quickstart uses so it can be run repeatedly without state. Production sessions
usually want persistence and real recovery instead; see [Stores and loggers](stores-and-loggers.md).

A retransmission drives everything behind it: messages received on top of the gap are only delivered once the request
completes, and your own application messages are held back meanwhile. `resendRequestResponseTimeout` is what keeps an
answer that stops half way (a garbled message inside the range is the ordinary way there) from stalling the session
for good while heartbeats keep both ends believing it is healthy. It is measured from the last message that advanced
the recovery rather than from the request, so a long but progressing retransmission never trips it; on expiry the
missing part of the range is asked for once more, and a second expiry logs the session out. Note that the second
request means the application sees `onResendRequestInitiated` twice for one gap, against a single
`onResendRequestTerminated`.

---

## Heartbeats, timeouts and schedules

| setting | default |
|---------|---------|
| `heartBeatInterval` | `HeartbeatInterval.builder().build()` |
| `logInOrOutResponseTimeout` | 10 seconds |
| `disconnectMessagesFlushDeadline` | 2 seconds |
| `desiredSessionState` | `LOGGED_IN` |
| `sessionScheduleSettings` | see below |

`desiredSessionState` is the state the engine works to maintain: leave it `LOGGED_IN` and an initiator reconnects and
logs on by itself.

Session schedules live in `sessionScheduleSettings` and decide when the session is allowed to be up:
`sessionSchedules`, `nonStopSchedules`, a `timeZone` (default: the JVM's), a `withinSessionTimeCheckInterval`
(1 second) and `outsideSessionTimePreTriggerDelay` (2 seconds), which is how much warning
`FixApplication#onPreOutsideSessionTime` gets before the window closes.

---

## Validation

Validation is where Staffix's defaults will surprise you if you come from another engine: **almost everything is off**.
That is deliberate: every check costs time on the message path, and the engine will not spend it unless you say so.

Enabled by default:

| setting | what it does |
|---------|--------------|
| `validateChecksum` | verifies the CheckSum(10) of every received message |
| `validateFieldsHaveValues` | rejects `tag=` with an empty value |
| `allowUndefinedTagsForMessage` | *permits* tags the dictionary does not define for that message type |

Disabled by default, so turn on the ones your counterparty relationship needs:

| setting | what it does |
|---------|--------------|
| `validateRequiredFields` | rejects messages missing fields the dictionary marks required |
| `validateFieldsOutOfOrder` | rejects header/body/trailer or repeating-group fields out of declared order |
| `validateDuplicateTags` | rejects a tag repeated at the same level |
| `validateCompId` | checks SenderCompID and TargetCompID against the session's configured values |
| `validateBeginString` | checks BeginString(8) matches the session's FIX version |
| `detectGarbledMessages` | the section 4.5.2 garbled-message conditions |
| `allowUserDefinedFields` | permits user-defined tags absent from the dictionary |
| `allowUnknownFields` | permits standard tags this dictionary does not define, which is how you talk to a peer on a later FIX version |
| `maxSendingTime` | rejects and then logs out on a SendingTime(52) outside the tolerance, in either direction |

```java
FixSessionSettings.builder()
        .fixSessionId(sessionId)
        .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
        .validationSettings(FixSessionSettings.ValidationSettings.builder()
                .validateRequiredFields(true)
                .validateCompId(true)
                .build())
        .build();
```

Two of these are about talking to someone on a **different FIX version**: `allowUnknownFields` covers standard tags
your dictionary predates (the Global Technical Committee's 40000+ block, for instance) and `allowUserDefinedFields`
covers the private range. Without them a peer on a newer version gets rejects for fields that are perfectly legal.

Third-party routing has two more: `expectedOnBehalfOfCompIds` and `expectedDeliverToCompIds` (both `null` = unchecked)
restrict who the peer may send on behalf of, or address through you. A value outside the set is rejected with
SessionRejectReason 9, *CompID problem*, without dropping the session.

---

## Message size limits

| setting | default | effect |
|---------|---------|--------|
| `maxMessageSize` | `null` | advertised to the peer in MaxMessageSize(383) on the Logon, and used to reject oversized inbound messages |
| `requiredPeerMaxMessageSize` | `null` | the smallest MaxMessageSize you will accept from the peer; a smaller one ends the session with a Logout explaining why |

---

## Measuring round-trip time and clock offset

Set `rttMeasurementSettings.probeInterval` to a positive duration and the session continuously measures round-trip
time and the peer's clock offset using TestRequest probes. It is off by default.

| setting | default |
|---------|---------|
| `probeInterval` | `null` (disabled) |
| `emaTimeWindow` | 30 seconds; longer absorbs more jitter, converges slower |
| `maxAcceptedRtt` | 2 seconds; samples above this are discarded as outliers |
| `probeTestReqIdPrefix` | `"RTT-measurement-"`, so probes are distinguishable in logs |
| `sendingTimeToWireDelay` | 1500 ns, the modelled delay between the peer stamping SendingTime and the bytes leaving its wire |

That last one is worth understanding before you trust the offset: the NTP-style formula assumes the remote's
timestamp is taken at transmission, and it is not; it is taken while encoding. `sendingTimeToWireDelay` is the
empirical correction for the encode tail, write syscall, kernel queueing and NIC handoff.

How the measurement works, how to calibrate that correction, what the numbers are worth and where they surface are
all in [Network monitoring](network-monitoring.md).

---

## Cancel on disconnect

Cancel on disconnect (COD) is the agreement that if the session goes away, resting orders should not be left working
on the venue. It is not part of the FIX standard: it is a bilateral convention, negotiated on the Logon with
user-defined fields, and every venue spells it slightly differently. So Staffix implements the *mechanism* and leaves
the numbers configurable.

**Staffix does not cancel anything.** It negotiates the agreement, watches the session, and calls your application
when the condition is met:

```java
@Override
public void onCancelOnDisconnectTriggered(FixSession session, CancelOnDisconnectType type) {
    // pull your resting orders for this session
}
```

Cancelling is your side's job because only your application knows what an order is. The engine's job is to tell you,
reliably and on time.

### Turning it on

```java
FixSessionSettings.builder()
        .fixSessionId(sessionId)
        .fixSessionType(FixSession.FixSessionType.INITIATOR)
        .cancelOnDisconnectSettings(FixSessionSettings.CancelOnDisconnectSettings.builder()
                .enabled(true)
                .cancelOnDisconnectType(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT)
                .codTimeoutWindow(Duration.ofSeconds(10))
                .build())
        .build();
```

`enabled` is `false` by default, and means different things on each side:

- **on an initiator**: send the COD fields on the Logon, requesting the agreement;
- **on an acceptor**: honour COD fields arriving on an inbound Logon.

### What gets negotiated

| setting | default | meaning |
|---------|---------|---------|
| `cancelOnDisconnectType` | `CANCEL_ON_DISCONNECT_OR_LOGOUT` | which events trigger a cancel |
| `codTimeoutWindow` | 10 seconds | grace period before the trigger fires |
| `codTimeoutWindowScale` | `MILLISECONDS` | the unit the window is expressed in on the wire |
| `cancelOnDisconnectTypeFieldCode` | `35002` | tag carrying the type |
| `codTimeoutWindowFieldCode` | `35003` | tag carrying the window |
| `cancelOnDisconnectTypeFieldCodes` | `0`–`3` as below | wire value per type |

The four types, and the characters they map to by default:

| `CancelOnDisconnectType` | wire value | triggers on |
|--------------------------|-----------|-------------|
| `DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT` | `0` | nothing |
| `CANCEL_ON_DISCONNECT_ONLY` | `1` | an unexpected disconnect |
| `CANCEL_ON_LOGOUT_ONLY` | `2` | a clean logout |
| `CANCEL_ON_DISCONNECT_OR_LOGOUT` | `3` | either |

**Tags 35002 and 35003 are defaults, not a standard.** They sit in the user-defined range precisely because no
standard owns them, and your venue will very likely use different ones, so change
`cancelOnDisconnectTypeFieldCode` and `codTimeoutWindowFieldCode` to whatever their specification says. The same
applies to `cancelOnDisconnectTypeFieldCodes` if they encode the types with different characters.

You do **not** need `allowUserDefinedFields(true)` for this. Although these are user-defined tags, enabling COD
registers exactly those two with the session's field registry, so they are known fields for this session and nothing
else in the user-defined range is let in as a side effect.

### The two asymmetries worth knowing

**`codTimeoutWindow` means different things on each side.** On an initiator it is the window *sent* in the Logon. On
an acceptor it is the *minimum acceptable* window received, and a peer asking for less than you are prepared to honour
is not silently accepted.

**`cancelOnDisconnectType` is also the acceptor's fallback.** If an inbound Logon carries no COD fields at all, an
acceptor with `enabled(true)` still applies this value, which means COD can be in force for a counterparty that
never asked for it. That is deliberate, and it is how you protect yourself from a peer who simply does not implement
the convention. Set it to `null` to disable that fallback and honour only what the Logon actually requests.

### Choosing the window

The window is a grace period, not a delay to be minimised. Too short and a brief network blip cancels a book that
would have recovered on reconnect; too long and orders rest unmanaged after a real failure. It is a risk decision
rather than a technical one, and it usually belongs to the same people who set your kill-switch thresholds.

---

## When settings change under a running session

Settings can change while the session is up, through a reload of the settings store or a direct
`add`/`remove`/`update` on it. Two flags decide what that does to the live session:

| setting | default | effect |
|---------|---------|--------|
| `disconnectOnRemove` | `true` | removing these settings disconnects the session |
| `restartLiveSessionOnUpdate` | `true` | updating them restarts the session so the new values take effect now |

Both default to the safe, obvious behaviour: a removed session goes down, and an update reaches the running session.
Turn them off when the session matters more than the promptness of the change:

```java
FixSessionSettings.builder()
        .fixSessionId(sessionId)
        .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
        .disconnectOnRemove(false)          // stop managing it, but leave it running
        .restartLiveSessionOnUpdate(false)  // store the new settings, apply them on next connect
        .build();
```

With `restartLiveSessionOnUpdate(false)` the new settings are managed immediately and the running session keeps the
ones it was created with: **nothing is applied in place**. That is one rule rather than a list of which settings can
be changed live and which cannot.

"Live" is the whole of the condition: a session that is not connected is never restarted, because it does not need
to be: it picks the new settings up when it next connects.

Both flags are read from the settings the session is **running under**, not from the ones replacing them. The flag
describes how *this* session may be treated, and this session is the one a restart would disturb; a new policy
governs the update after it.

---

## Where settings come from

Everything above builds settings in Java. Two other sources exist:

- **YAML files**, via the file session settings store, with a JSON schema generated at build time and shipped in the
  jar so your editor completes and validates the file. See the
  [`file-session-settings`](../examples/file-session-settings) example.
- **Spring properties**, via the Spring Boot starter, where sessions are declared entirely in
  `application.properties`. See the [`spring-boot-starter-example`](../examples/spring-boot-starter-example).

The settings model is the same in all three cases; only the transport differs. [Session settings
stores](session-settings-stores.md) covers the stores that hold them: the memory store, and the file store with its
`default.yaml` merge, classpath and URI sources, and `${...}` placeholders.

Sessions declared in Spring properties need no placeholders of their own: Spring resolves `${...}` in
`application.properties` itself, from every property source it knows.
