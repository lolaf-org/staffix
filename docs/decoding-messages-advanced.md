# Decoding in depth

The rest of [Decoding a message](decoding-messages.md): why decoding works the way it does, and the tools for when
typed field bindings are not enough.

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

The engine holds itself to the same rule: `SendingTime(52)` is only parsed if a validation needs it, and a message
type nobody decodes goes to a `VoidDecoder` whose `onField` does nothing.

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

A setter fires the moment its tag goes past, but the message is only known to be valid once the last field is in:

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

---

## Lambda or VarHandle

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

---

## Header fields are fields too

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
to keep, and is exactly the allocation the message path exists to avoid, so copy deliberately. `onLogout` is the
exception: it comes once the connection has closed, so the engine hands it a copy taken when the Logout arrived.
