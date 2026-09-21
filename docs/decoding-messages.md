# Decoding a message

Staffix is a **Streaming API for FIX**, and this guide is what the name means. A FIX message is a stream of
`tag=value` fields, and Staffix treats it as one: the parser walks that stream once, and a field becomes a Java
value only where your decoder asked for it. Everything else is stepped over.

So decoding here is not "turn the message into an object, then read the object". It is "declare once which tags
this application cares about, and be handed them as they go past". The message is never assembled into anything.

The runnable version of everything below is
[`examples/quickstart`](../examples/quickstart/src/main/java/org/lolaf/staffix/examples/quickstart/QuickstartExample.java),
whose acceptor reads two fields out of a `QuoteRequest` and answers it.

---

## Why it is built this way

A FIX message carries what the *standard* allows, not what *you* trade. An `ExecutionReport` has well over a
hundred defined fields; a counterparty sends you thirty; your matching logic reads six. An engine that materializes
a message before you look at it pays for all thirty: a substring per field, a `HashMap` entry per field, a boxed
number per field, and the garbage that follows.

Staffix pays for six. Concretely, per field of an incoming message:

| what the field is | what it costs |
|-------------------|---------------|
| bound to a setter in your decoder | finding its boundaries, then deserializing its bytes straight into your variable |
| not bound by anyone | finding its boundaries, and a lookup that finds nothing |
| in a message type no decoder was registered for | finding its boundaries |

Finding the boundaries is not optional: the byte count in `BodyLength(9)` and the `CheckSum(10)` have to be
computed over the whole message whatever you read from it. Everything above that line is yours to decide.

**The engine holds itself to the same rule**, which is the clearest illustration of it:

- `SendingTime(52)` is not decoded on the message path. The parser records where the value sits and parses it only
  if `ValidationSettings.maxSendingTime` asked for the accuracy check, or if an `OrigSendingTime(122)` on a
  retransmission needs comparing to it.
- A message type no application registered a decoder for is handed to a `VoidDecoder`, whose `onField` does
  nothing. Not a null check on the parsing loop: doing nothing per field is cheaper than testing per field whether
  to do something.

---

## The lifecycle of one inbound message

For each message, on the session's I/O thread:

1. `BeginString(8)`, `BodyLength(9)`, `MsgType(35)` are read first, in that order, as the session layer mandates.
   `MsgType(35)` is what selects the decoder: yours if you registered one for that type, the session layer's own
   for an admin message, `VoidDecoder` otherwise.
2. **`onBegin(localReceiveTimeInNanos, localReceiveTime)`**, parsing of the message starts.
3. Every remaining field, header fields included, is offered to the decoder, and your bound setters fire as their
   tags go past. Repeating groups interleave `onGroupStart`, `onGroupEntryStart`, `onGroupEntryEnd`, `onGroupEnd`.
   A tag absent from the dictionary reaches `onUnknownField` instead, and only if the session's validation settings
   allow it through.
4. **`validate()`**, once every field has been seen.
5. **`onDecoded(session, possDupFlag, possResend)`** on success, or `onDecodingFailed(session, exception)` if
   parsing or validation failed.

By the time `onDecoded` runs, your variables already hold this message's values. That is why a decoder overrides
almost nothing: the work happened as the bytes went past.

### Business action belongs in `onDecoded` and `onDecodingFailed`, nowhere else

**Steps 2 to 4 populate state. They decide nothing.** Send an order, book a fill, release a latch, publish to
another system only from `onDecoded`, or from `onDecodingFailed` when the failure is what your business has to
react to. Everything before that is the message still being read.

This is not a style rule. A setter fires the moment its tag goes past, and the message is only known to be a
message at all once the last field is in:

- `CheckSum(10)` and the `BodyLength(9)` byte count are verified at the end. A message that fails either is
  **garbled**: it is disregarded as a whole, nothing is answered to the peer, `NextNumIn` is not advanced, and the
  peer is free to retransmit it. Your setters will then run a second time for the same message.
- `MsgSeqNum(34)` is checked against what the session expects, again at the end. A gap makes the message fail and
  drives a `ResendRequest`, so acting per field would mean acting on a message the session layer refused.
- A required field that never arrives, a duplicate tag, a tag out of order, a wrong CompID: all of them are
  decided once every field has been seen, in step 4.
- `possDupFlag` and `possResend` are handed to you in `onDecoded`, and nowhere earlier. A retransmission looks
  exactly like a first delivery until then.

When a message does fail, the engine swaps the decoder for a `VoidDecoder` for the rest of it, so the setters after
the failure point never fire. Your fields are then holding half of a message that is never going to arrive, and the
only callback that says so is `onDecodingFailed`.

The corollary for `onBegin`, `onField`, `onUnknownField` and the `onGroup*` callbacks: use them to accumulate,
clear and shape state, never to act on it.

### And all of it runs on the session's I/O thread

Every callback above, `onDecoded` included, runs on the thread that reads the socket. There is no queue between
the wire and your decoder, which is where the latency numbers come from, and the bill for it is that **whatever you
do in `onDecoded` is inside your own round trip, inside your peer's, and in front of every other session sharing
that thread.**

So do the least that gets the message accepted: read your fields, build what the next stage needs, and answer the
peer if answering is the whole job, as the quickstart's acceptor does. Anything that blocks, allocates heavily or
waits on something else does not belong there: a database write, a lock another thread holds, an HTTP call, a
`CompletableFuture` you join, a log of the message you already have logged for free.

When the work is genuinely yours to do and too big for the thread, hand it over with
`FixSession.getMessageExecutor`, which keeps per-key ordering and allocates nothing to enqueue. That is
[Getting work off the I/O thread](threading-model.md#getting-work-off-the-io-thread-messageexecutor) in the
threading model guide, and [What runs on the session's I/O thread](threading-model.md#what-runs-on-the-sessions-io-thread)
is the full list of what shares the thread with you.

---

## Writing one

Three methods carry a decoder: which message type it is for, which fields it wants, and what to do once they are
all in. From the quickstart's acceptor:

```java
@Setter
private static final class QuoteRequestDecoder implements FixMessageDecoder {

    private final QuoteEncoder quoteEncoder;
    private String quoteReqId;
    private String symbol;

    QuoteRequestDecoder(FixSession session) {
        quoteEncoder = session.newEncoder(QuoteEncoder.class).asReusable();
    }

    @Override
    public MessageType getMessageType() {
        return MessageTypes.QuoteRequest;
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
        mapper.mapStringField(QuoteReqID.get(), this::setQuoteReqId, null)
                .forGroup(NoRelatedSym.get())
                .withFieldsAutoResetDisabled()
                .mapStringField(Symbol.get(), this::setSymbol, null);
    }

    @Override
    public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
        log.info("QuoteRequest {} for {}", quoteReqId, symbol);
        session.send(quoteEncoder.begin()
                .setQuoteReqID(quoteReqId)
                .setQuoteID(quoteReqId + "-1")
                .setSymbol(symbol)
                .setBidPx(101.25d), null);
    }
}
```

`mapFieldsForDecoding` is called **once**, when the session is created, never per message. That is the whole trick:
the binding from tag to setter is resolved at startup, and the message path is left with nothing to decide.

Encoders are generated from the dictionary; decoders are not. A decoder is your class, holding your variables, in
the shape your application wants them, which is why there is no generated type to adapt to or to copy out of.

### Registering it

A decoder belongs to one session and is stateful, so it is created per session, in `FixApplication.setup`:

```java
@Override
public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession session,
                                     Set<MessageType> encodedMessagesTypes) {
    encodedMessagesTypes.add(MessageTypes.Quote);      // what this side sends
    return List.of(new QuoteRequestDecoder(session));  // what this side reads
}
```

One decoder per message type. A type you do not return a decoder for reaches
`FixApplication.onNoDecoderSetupForMessage`, which logs a warning by default and can return `true` to answer the
peer with a `BusinessMessageReject`.

---

## Binding a field

Every `mapXxxField` returns the mapper, so declarations chain. There is one method per FIX data type, and the type
is **checked against the dictionary at registration**: mapping `Symbol(55)` with `mapIntField` throws
`IllegalArgumentException` when the session starts rather than failing on message ten thousand.

| FIX type | method | handed to you as |
|----------|--------|------------------|
| `INT`, `LENGTH`, `NUMINGROUP`, `SEQNUM`, `TAGNUM` | `mapIntField`, `mapLongField` | `int`, `long` |
| `FLOAT`, `QTY`, `PRICE`, `PRICEOFFSET`, `AMT`, `PERCENTAGE` | `mapDoubleField`, `mapDecimalFloatField`, `mapBigDecimalField` | `double`, `DecimalFloat`, `BigDecimal` |
| `STRING`, `CURRENCY`, `EXCHANGE`, `COUNTRY`, `DATA`, … | `mapStringField`, `mapUUIDField` | `String`, `UUID` |
| `CHAR`, `BOOLEAN` | `mapCharField`, `mapBooleanField`, `mapBooleanObjectField` | `char`, `boolean`, `Boolean` |
| `UTCTIMESTAMP`, `UTCDATEONLY`, `UTCTIMEONLY` | `mapUtcDateTimeField`, `mapUtcDateOnlyField`, `mapUtcTimeOnlyField` | `UTCTime`, `int`, `long` |
| `TZTIMESTAMP`, `TZTIMEONLY`, `LOCALMKTTIME` | `mapTzDateTimeField`, `mapTzTimeField`, `mapLocalMktTimeField` | `OffsetDateTime`, `OffsetTime`, `LocalTime` |
| any field the dictionary enumerates | `mapStringValuesEnumField`, `mapIntValuesEnumField`, `mapCharValuesEnumField` | the generated enum, or your own type through a mapping function |

`mapBooleanObjectField` exists for the case where an absent field and a received `N` must be told apart: give it a
`null` reset value.

The enum mappers take an optional `Function` so the wire enum never has to reach your domain:

```java
mapper.mapCharValuesEnumField(MDEntryType.get(), quoteBuilder::side,
        MarketDataSnapshotFullRefresh.Side::from, null);
```

### Lambda or VarHandle

Every mapping comes in two styles. The lambda style takes a setter and is what you normally want:

```java
mapper.mapDoubleField(BidPx.get(), this::setBidPx, 0d);
```

The VarHandle style names a Java field and supplies its holder. The engine resolves a `VarHandle` once at
registration and writes the decoded value straight into the field, with no lambda allocated per registration:

```java
mapper.mapDoubleField(BidPx.get(), "bidPx", () -> this, 0d);
```

Both cost the same per message. Choose the second only when the state holder is known up front and you are counting
startup allocations.

### Header fields are fields too

Everything after `MsgType(35)` is offered to your decoder, the rest of the standard header included, so a header
field is mapped like any other. `BeginString(8)` and `BodyLength(9)` are the exception: they are read before
`MsgType(35)` has selected a decoder, so there is nothing yet to hand them to.

What mapping one costs depends on whether the session layer already wanted the value:

| header field | what the parser does with it anyway | mapping it |
|--------------|-------------------------------------|------------|
| `MsgSeqNum(34)` | deserialized on every message, for the sequence check | decodes it a second time, and is the only way an application sees it |
| `PossDupFlag(43)`, `PossResend(97)` | deserialized on every message | decodes them a second time; they are already `onDecoded` arguments |
| `OrigSendingTime(122)` | deserialized when present, for the retransmission check | decodes it a second time |
| `SendingTime(52)` | **only its offset and length are recorded**, and it is parsed lazily if a validation needs it | a decode the engine otherwise avoids |
| any other header field | nothing | a decode the engine otherwise avoids |

So `MsgSeqNum(34)` is worth mapping when your application genuinely needs the sequence number, since nothing else
hands it over. `PossDupFlag(43)` and `PossResend(97)` are not: `onDecoded` already receives both.

### Fields the dictionary does not have

A counterparty's custom tag is in no dictionary, so declare it and map it in the same place:

```java
FixField userField = fieldsRegistry.addUserDefinedField(5001, FieldType.INT, FieldLocation.BODY);
mapper.mapLongField(userField, this::setUserDefinedField, -1);
```

The session's `ValidationSettings` decides whether such a tag is allowed through at all:
`allowUserDefinedFields` for tags in the user-defined range, `allowUnknownFields` for standard tags this dictionary
does not define, which is how a peer on a later FIX version reaches you. Both are off by default.

---

## Repeating groups

`forGroup(groupField)` switches the mapper to a group's scope. Every mapping registered after it binds a field
*inside* that group and fires once per entry. Chaining `forGroup` again enters a nested group:

```java
mapper.mapStringField(MDReqID.get(), this::setMdReqID, null)
        .forGroup(NoRelatedSym.get())
        .mapStringField(Symbol.get(), this::setSymbol, null, CACHED)
        .forGroup(NoMDEntryTypes.get())
        .mapCharValuesEnumField(MDEntryType.get(), this::setMdEntryType, null);
```

A group field the message does not declare throws `IllegalStateException` at registration.

Since a setter fires once per entry, accumulating entries is done from `onGroupEntryEnd`, which runs when an entry
is complete and before the next one starts:

```java
@Override
public void onBegin(long localReceiveTimeInNanos, UTCTime localReceiveTime) {
    builder.clearQuotes();
}

@Override
public void onGroupEntryEnd(FixField parentGroup, FixField groupField, FixField fixField) {
    if (groupField.equals(NoMDEntries.get())) {
        builder.quote(quoteBuilder.build());
    }
}
```

### Auto reset, and when to turn it off

Every mapping carries a `resetValue`, and **auto reset is on by default**: once the message is done, or once a
group entry is done for a group-scoped field, the setter is called again with that value. This is what stops one
message's values from being read as the next one's, and one group entry's from leaking into the next.

`withFieldsAutoResetDisabled()` turns it off for mappings registered after it in the current scope. Two reasons to
reach for it:

- the field is read in `onGroupEntryEnd`, where an already-reset value would be useless;
- the group has a single entry and you want to read it in `onDecoded`, which the quickstart does with `Symbol(55)`.

With it off, clearing state between messages is yours to do, typically in `onBegin`.

---

## What a decoded object costs

For fields that must become objects, the mapper takes an `ObjectInstanceStrategy`:

| strategy | allocation | constraint |
|----------|-----------|------------|
| `NEW_INSTANCE` (default) | one object per decoded value | none; safe to keep and to hand to another thread |
| `CACHED` | only for a value not seen before on this session | the cache never evicts, so small value universes only |
| `THREAD_LOCAL` | none | the reference dies at the next `THREAD_LOCAL` field; never hand it to another thread |

```java
mapper.mapStringField(Symbol.get(), this::setSymbol, null, CACHED)
        .mapStringField(QuoteReqID.get(), this::setQuoteReqId, null, THREAD_LOCAL);
```

`CACHED` suits symbols, currencies, exchanges and enumerations: values that repeat forever. On an order id it is a
memory leak.

`UTCTime` follows the same rule without asking for a strategy: the instance handed to a `mapUtcDateTimeField`
setter is reused across fields and messages. Read it there, and call `asImmutable()` or `asInstant()` if it must
outlive the call.

[Tuning for latency](tuning-for-latency.md#6-choose-an-object-strategy-per-field) is where these trade-offs are
argued in full.

---

## When the typed mappings are not enough

**`mapSerdeCtxField(field, consumer)`** hands you the raw `SerDe.DeserializationContext`: the byte array, the
offset and the length of the value. For a bespoke layout inside a `DATA` field, or parsing that depends on another
field already seen. You lose the dictionary type check and the optimised serdes, so prefer a typed mapping.

**`onField(field, context)`** is the same thing for every field of the message rather than one, and
**`onUnknownField`** for tags the dictionary does not define. Overriding `onField` puts a call on every field of
every message, which is precisely the cost the mapper exists to avoid. Both are still step 3: read and store, do
not act.

**`indexer(field, consumer)`** assigns a dense `int` index to a field's values as they are decoded, zero upwards,
one per distinct value. It is what feeds `FixSession.getMessageExecutor` for per-key ordered processing on another
thread, and it keys array-shaped state without a hash lookup. One indexer per decoder. See
[Threading model](threading-model.md).

---

## When you do want the whole message

Sometimes the point is the message rather than a few fields of it: a bridge, a recorder, a generic router.
`DecodedFixMessageDecoder` in `staffix-codec` is the decoder for that. It keeps every field as it arrived, in a
`DecodedFixMessage`, groups included:

```java
return new DecodedFixMessageDecoder(MessageTypes.Email) {
    @Override
    public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
        DecodedFixMessage decoded = getDecodedFixMessage();
        record(decoded.getString(Subject.get(), ""));
        super.onDecoded(session, possDupFlag, possResend);
    }
};
```

This is the model every other engine gives you by default, and here it is opt in, per message type, because it
costs what this guide is about. Its typed getters take a default rather than returning null, an absent optional
field being ordinary in FIX, and `foreach` walks everything.

The same type is what the session layer's own callbacks hand you: `FixApplication.onLogon`, `onLogout` and
`onResendRequest` receive a `DecodedFixMessage` because an admin message is the engine's to decode, not yours.

**It is valid only for the callback.** The engine reuses the instance for the next message. `copy()` makes one safe
to keep, and is exactly the allocation the message path exists to avoid, so copy deliberately.

---

## Failing a message

`validate()` runs after the last field and before `onDecoded`. Throw `ValidationException` from it and the message
is not delivered: `onDecodingFailed` is called instead and a `BusinessMessageReject` goes back to the peer.

An exception escaping a setter or `onDecoded` is caught too, and answered with a reject rather than taking the
session down.

The session layer can do part of this for you, each switch costing something on every message:

| setting | what it rejects | default |
|---------|-----------------|---------|
| `validateRequiredFields` | a message missing a field the dictionary makes required | off |
| `allowUndefinedTagsForMessage` | a valid tag that this message type does not define | on, so nothing is rejected |
| `validateDuplicateTags` | the same tag twice at the same level | off |
| `validateFieldsOutOfOrder` | header/body/trailer or group fields out of dictionary order | off |
| `validateFieldsHaveValues` | an empty value, `55=\001` | on |

The defaults are deliberate: they are the fast ones. Turn on what your counterparty actually makes you check.
[Configuring a session](configuring-sessions.md) has the rest of `ValidationSettings`.

---

## The rules that come with the speed

- **Only `onDecoded` and `onDecodingFailed` mean anything.** Every other callback is the message still being
  read, and the message may yet turn out to be garbled, out of sequence or invalid. Accumulate there, act here.
- **A decoder belongs to one session.** Its fields are mutable state reused across messages, with no lock, which
  works only because the same thread drives it every time. Never share an instance between sessions.
- **Everything happens on the session's I/O thread**, `onDecoded` included. What you do there is inside your own
  round trip, and inside your peer's. Reusable encoders are safe there for the same reason, and the quickstart's
  acceptor answers from inside `onDecoded`. Do the minimum, and move the rest with
  [`MessageExecutor`](threading-model.md#getting-work-off-the-io-thread-messageexecutor).
- **Nothing you are handed outlives the callback** unless the strategy you chose says so: a `THREAD_LOCAL` value, a
  `UTCTime`, a `DecodedFixMessage`. Copy what has to survive, and get real work off the thread with
  `FixSession.getMessageExecutor`.

[Threading model](threading-model.md) is the full account of which thread runs what.
