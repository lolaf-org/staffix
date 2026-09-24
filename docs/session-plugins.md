# Session plugins

A session plugin is how something attaches to a FIX session **without the application knowing**. Metrics, tracing,
compliance stamps, per-message auditing, rate accounting: all of it is behaviour that cuts across every message and
belongs to none of them, and all of it is a plugin here. The monitoring modules Staffix ships are not privileged
components: they are plugins written against the two interfaces in this guide, and yours sits beside them on equal
terms.

The runnable version of everything below is [`examples/plugin-api`](../examples/plugin-api), a plugin that adds a
user-defined field to every outbound `Email`, with the applications on both sides unaware of it.

---

## Two interfaces

Both live in `staffix-api`, in `org.lolaf.staffix.api.session.plugins`.

**`FixSessionsPlugin<C>`** is the engine-level component. One instance per registration, and its only job is to be
asked, per session, whether it wants that session:

```java
Optional<? extends FixSessionPlugin<C, ?>> onSessionCreated(
        String fixInstanceId, FixSession fixSession,
        Collection<MessageType> incomingMessageTypes, Collection<MessageType> outgoingMessageTypes);
```

`Optional.empty()` means "not interested", and that session then costs nothing: no callback of yours is ever
invoked on it. The two collections are what the session's `FixApplication` declared it decodes and encodes, so the
decision can be made on what the session actually carries rather than on its name:

```java
if (!outgoingMessageTypes.contains(MessageTypes.Email)) {
    return Optional.empty();
}
```

**`FixSessionPlugin<C, T>`** is the per-session instance you return, and where the callbacks live. It **must be a
fresh instance for that session**; shared instances across sessions are not supported. That is not a formality: it
is the reason a plugin can hold mutable state (counters, buffers, the last message's context) without a lock, and
the engine gives you nothing back if you break it.

Every callback is a `default` method, so you implement only the ones you want.

---

## Registering one

Same shape as every other pluggable part of Staffix: a settings object, an SPI factory, an id.

**1. A settings class** implementing `FixSessionsPluginSettings<YourPlugin>`, carrying whatever your plugin needs
plus the `instanceId` sessions will name it by.

**2. An SPI factory**, usually a nested class of the settings, and a `ServiceLoader` declaration for it in
`src/main/resources/META-INF/services/org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings$FixSessionsPluginFactory`:

```java
public static class StampingFixSessionsPluginFactory
        implements FixSessionsPluginFactory<StampingFixSessionsPluginSettings> {

    @Override
    public Class<StampingFixSessionsPluginSettings> getSettingsClass() {
        return StampingFixSessionsPluginSettings.class;
    }

    @Override
    public FixSessionsPlugin<?> newInstance(StampingFixSessionsPluginSettings settings) {
        return new StampingFixSessionsPlugin(settings);
    }
}
```

Miss the services file and the engine fails at startup with `Unable to find any SPI instance for target settings
class`; the settings object alone tells it nothing about what to build.

**3. Register it on the engine**, and **name it from the sessions that want it**:

```java
FixEngineBuilder.builder()
        .fixSessionsPlugin(StampingFixSessionsPluginSettings.builder()
                .instanceId("email-stamping")
                .build())
        // …

FixSessionSettings.builder()
        .fixSessionPluginsInstanceId(StampingFixSessionsPlugin.class, "email-stamping")
        // …
```

The class in that selector is matched through `matchesPluginClass`, which defaults to "my own class". Overriding it
to answer for an *interface* instead is what lets a session say `FixSessionsMonitoringManager.class` and get
whichever implementation the engine was given, swapping backends without touching session configuration. A session
naming an id that no registered plugin answers to is a startup failure rather than a silent no-op, with one gap
worth knowing: when the engine has no plugins registered at all, the selection is skipped entirely and the session's
references go unchecked.

In Spring Boot the same plugin joins the properties model through `FixSessionsPluginSettingsContributor`; see
[Spring Boot](spring-boot.md#extending-it).

---

## The callbacks

| callback | fires |
|----------|-------|
| `onLogon()` / `onLogout()` | session up, session down |
| `onDecoderSetup(decoder, mapper)` | as each decoder is built, where you add your own field mappings |
| `onMessageDecodingStarted(type, …)` | bytes received, parsing begins |
| `onMessageDecodingFinished(type, …)` | the application has decoded the message or failed to |
| `onMessageReceived(type, payloadSize, …)` | inbound message complete, logged and stored |
| `getMessageEncodingToken(type, …)` | an outbound message is starting to be encoded |
| `onMessageEncodingStarted(type, …, token)` | immediately after, same thread |
| `onMessageEncodedBody(type, body, encoder, …, token)` | the body is encoded, the header and trailer are not |
| `onMessageEncodingFinished(type, …, token)` | encoding complete |
| `onMessageSent(type, payloadSize, …)` | handed to the network adapter |
| `onRttMeasurement(measurement)` | an accepted round-trip sample, if RTT probing is on |
| `onSessionDestroyed(instanceId, sessionId)` | the acceptor or initiator is stopping, so release what you hold |

Admin messages go through the encoding callbacks too. A plugin that only cares about business flow filters on
`messageType.isAdmin()`, as the tracing plugin does.

Several plugins can serve one session. They are invoked in registration order, each with its own encoding token, and
each unaware of the others.

---

## Which thread you are on

**`getMessageEncodingToken` and `onMessageEncodingStarted` run on the thread that produced the message**, whichever
application thread called `begin()`, and **may run concurrently for the same session**, because several threads may
encode on one session at once. Their implementations must be thread-safe and cheap: this is the latency-critical
producing path.

**Every other callback runs on the session's single I/O thread**, and never concurrently with another. Including
`onMessageEncodedBody` and `onMessageEncodingFinished`, which fire later, from `FixMessageEncoder.encode()`, just
before the message goes to the socket.

So one message's callbacks are generally split across two threads, and **a thread local set at encoding start is not
visible to the callbacks that follow**. The **encoding token** is the way across: whatever
`getMessageEncodingToken` returns is stashed on the encoder by the engine and handed back to *that same plugin's*
`onMessageEncodingStarted`, `onMessageEncodedBody` and `onMessageEncodingFinished` for that message. The started
callback happens-before the I/O-thread ones (the encoder is published through the outbound ring buffer), so the token
is safely visible; nothing is ordered between different messages.

Make the token a self-contained value (a flag, a captured context, a string), never an open resource. A message
abandoned after `begin()` without being encoded never has its token handed back, and nothing will close it for you.
`null` is a perfectly good token, and the cheapest way to say "leave this message alone".

The general threading rules are in [Threading model](threading-model.md); the two wrappers that move plugin callbacks
off the session thread entirely are in [Monitoring](monitoring.md#keeping-monitoring-off-the-message-path).

---

## Adding a field to an outbound message

`onMessageEncodedBody` fires **before BodyLength(9) and CheckSum(10) are computed**. A field appended to the encoder
there is therefore an ordinary part of the message: it lands in the body, and the length and checksum that follow
account for it. Nothing has to be recomputed, and nothing may be written into the `encodedBody` buffer by hand.

```java
@Override
public String getMessageEncodingToken(MessageType messageType, long encodingStartTimeInNanos) {
    // on the producing thread: decide, and capture what only this thread knows
    return MessageTypes.Email.equals(messageType) ? SENDING_DESK.get() : null;
}

@Override
public void onMessageEncodedBody(MessageType messageType, ByteBuffer encodedBody,
                                 FixFieldsEncoder<?> fixFieldsEncoder, long encodingStartTimeInNanos,
                                 String encodingToken) {
    // on the I/O thread: nothing left to decide
    if (encodingToken != null) {
        fixFieldsEncoder.addString(stampField, encodingToken);
    }
}
```

The field has to exist in the session's fields registry first, which the plugin does for itself at
`onSessionCreated`, again so the application never learns about it:

```java
FixField stampField = fixSession.getFieldsRegistry()
        .addUserDefinedField(20001, FieldType.STRING, FieldLocation.BODY);
```

Two constraints:

- **The tag must be in the user-defined range 5000..39999** (`FixField.isUserDefined(int)`). A tag the standard owns
  is refused rather than registered, whether or not this dictionary happens to define it.
- **`fixFieldsEncoder` is `null` when the plugin runs behind the [async wrapper](monitoring.md#async-move-the-work-to-other-threads)**:
  a pooled encoder cannot cross threads, so only the encoded bytes are replayed to the delegate. A plugin that
  writes fields cannot be made asynchronous; throw, as the shipped tracing plugin does, rather than silently
  dropping the field.

The counterparty needs the tag too. On the receiving side it is registered the same way and mapped in the decoder:
`fieldsRegistry.addUserDefinedField(…)` inside `mapFieldsForDecoding`, then `mapper.mapStringField(field, …)`. A
field nobody maps is never parsed, which is where the latency goes. If it should be mapped without the application
knowing either, `onDecoderSetup` hands you the decoder and its mapper as it is built, which is how cross-firm trace
propagation works.

---

## Not making the session pay

The engine's stance applies to plugins as much as to itself: nothing costs anything unless someone asks for it.

**`requiresTimeMeasurement()` defaults to `false`.** The nanosecond timestamps passed to the message callbacks are
only captured when some plugin on the session has asked for them; otherwise the engine swaps in a void clock and the
session never reads the clock at all. Ask only if you actually measure.

**Decline sessions you do not need**, from `onSessionCreated`. It is the difference between no cost and a call per
message.

**Decide on the producing thread, act on the I/O thread.** A `null` token costs the I/O thread one null check.

**Do not block, allocate or do I/O in a callback.** They run on the session's I/O thread, and whatever they take is
taken from the message path. If the work is real, wrap the plugin in the
[async or throttling wrapper](monitoring.md#keeping-monitoring-off-the-message-path): both take any
`FixSessionsPlugin`, including yours, and neither needs a line of code from you.

---

## Exposing state back to the application

`FixSessionPlugin` is generic in `C`, a `PluginContext`, for the case where the application wants to read something
the plugin keeps:

```java
Optional<FixTracer> tracer = fixSession.getPluginContext(FixTracer.class);
```

The engine walks the session's plugins, asks each `isForPluginContext(pluginClass)`, and returns the first
`getPluginContext()` that claims it. A plugin that publishes nothing answers `false` and returns
`Optional.empty()`, using `PluginContext.VoidPluginContext` as `C`. Note that plugins do not exist yet during
`FixApplication.setup(…)`, which runs first so that the plugin can see what the session encodes and decodes;
`getPluginContext` resolves from `FixApplication.onSessionCreated(…)` onwards.

---

## What the engine does for you

Every plugin is wrapped in a `FailSafeFixSessionPlugin` before it sees a single callback, so an exception out of your
code is logged and swallowed rather than disturbing the session. That is a safety net, not a licence: the message
still goes out, your work on it simply did not happen, and the only trace is a line in the log. Test the plugin as
you would test the application.

---

## The whole thing, running

```bash
mvn package -pl examples/plugin-api -am
cd examples/plugin-api
./PluginApiExample.sh -d 10 -c 2
```

Each initiator streams `Email` messages from an application thread of its own; the plugin stamps each with tag 20001
(`-sf` to change it) taken from a thread local only that thread can see; the acceptor decodes the field and prints
it. It is a [picocli](https://picocli.info) command like the other `examples-core` examples, so `--help` lists the
rest of the options, `-ei` being the interval between two Emails.

```
stamping plugin: session acceptor-session-1 does not send C, declining it
acceptor: Email thread-1 "Daily commentary 1": tag 20001 says it came from INITIATOR_1-desk
acceptor: Email thread-1 "Daily commentary 1": tag 20001 says it came from INITIATOR_2-desk
stamping plugin: stamped 4 C message(s) on session initiator-session-1
```

The message log (`target/logs/`, the examples' default `FILE` logger) shows it arriving inside a well-formed
message, counted by BodyLength(9) and covered by CheckSum(10):

```
8=FIX.4.4|9=156|35=C|34=2|…|147=Daily commentary 1|33=1|58=Nothing to report.|20001=INITIATOR_1-desk|10=220
```

Neither `FixApplication` mentions tag 20001.
