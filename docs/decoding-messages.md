# Decoding a message

Staffix is a **Streaming API for FIX**. The parser walks a message's `tag=value` fields once, and a field becomes a
Java value only where your decoder asked for it; everything else is stepped over. You declare once which tags you
care about and are handed them as they go past. The message is never assembled into an object.

The runnable version of everything below is
[`examples/quickstart`](../examples/quickstart/src/main/java/org/lolaf/staffix/examples/quickstart/QuickstartExample.java),
whose acceptor reads two fields out of a `QuoteRequest` and answers it. [Decoding in depth](decoding-messages-advanced.md)
covers what this guide leaves out: why it is built this way, the message lifecycle in detail, header fields, raw
access and the whole-message decoder.

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

Header fields after `MsgType(35)` are mapped like any other, and every mapping also has a VarHandle form; both are
in [Decoding in depth](decoding-messages-advanced.md#header-fields-are-fields-too).

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

## When your code runs

For each message, on the session's I/O thread: `onBegin`, then your setters as their tags go past (with the
`onGroup*` callbacks around group entries), then `validate()`, then **`onDecoded`** on success or
**`onDecodingFailed`** on failure.

**Act only in `onDecoded` and `onDecodingFailed`.** Send an order, book a fill, publish to another system from there,
never from a setter: until the last field is in, the message may still turn out garbled, out of sequence or invalid,
and a garbled message is retransmitted, so its setters run twice. The other callbacks accumulate, clear and shape
state. [Decoding in depth](decoding-messages-advanced.md#the-lifecycle-of-one-inbound-message) has the reasons one by
one.

**All of it runs on the I/O thread**, with no queue between the wire and your decoder. What you do in `onDecoded` is
inside your round trip, your peer's, and in front of every other session on that thread. Read your fields, and answer
the peer if that is the whole job, as the quickstart does; no database write, no lock another thread holds, no HTTP
call. Bigger work goes to
[`FixSession.getMessageExecutor`](threading-model.md#getting-work-off-the-io-thread-messageexecutor), which keeps
per-key ordering and allocates nothing to enqueue.

---

## Failing a message

`validate()` runs after the last field and before `onDecoded`. Throw `ValidationException` from it and the message
is not delivered: `onDecodingFailed` is called instead and a `BusinessMessageReject` goes back to the peer.

An exception escaping a setter or `onDecoded` is caught too, and answered with a reject rather than taking the
session down.

The session layer can also reject for you (required fields, duplicate tags, field order, empty values), each check
costing something on every message and most of them off by default: see
[Configuring a session](configuring-sessions.md#validation).

---

## The rules that come with the speed

- **Accumulate in the field callbacks, act in `onDecoded` and `onDecodingFailed`.** Until then the message may still
  turn out garbled, out of sequence or invalid.
- **A decoder belongs to one session.** Its state is reused without a lock, safe only because one thread drives it.
- **Everything runs on the session's I/O thread**, `onDecoded` included: do the minimum, move the rest with
  [`MessageExecutor`](threading-model.md#getting-work-off-the-io-thread-messageexecutor).
- **Nothing you are handed outlives the callback**: a `THREAD_LOCAL` value, a `UTCTime`, a `DecodedFixMessage`. Copy
  what has to survive.
