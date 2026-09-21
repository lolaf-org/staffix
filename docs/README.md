# Staffix documentation

Start with the [quickstart](../examples/README.md#quickstart-the-smallest-complete-staffix-application): it runs in
one command and is the shortest path to a working session. Then come back here.

## Guides

| guide | what it answers |
|-------|-----------------|
| [Examples](../examples/README.md) | What does a working Staffix program look like, and which one do I copy from? |
| [Threading model](threading-model.md) | Which thread runs my code, what may I share, and how do I get work off the message path? |
| [Configuring a session](configuring-sessions.md) | How do I declare a session, wire it to the engine's parts, and make it behave the way my counterparty expects? |
| [Decoding a message](decoding-messages.md) | How do I read an inbound message, and why does Staffix only decode the fields I asked for? |
| [FIX versions and dictionaries](fix-versions-and-dictionaries.md) | Which package do I depend on, how do I run several FIX versions at once, and how do I generate one from a counterparty's dictionary? |
| [Stores and loggers](stores-and-loggers.md) | Where do my messages get persisted and logged, and how do I keep that off the latency path? |
| [Tuning for latency](tuning-for-latency.md) | What do I actually change to get the numbers in the README, and what does each one cost? |
| [Efficient identifier usage](efficient-identifiers-usage.md) | How do I mint and decode ids without allocating, and do I want a UUID or a Snowflake? |
| [Monitoring](monitoring.md) | How do I get metrics, traces and dashboards without putting them on the message path? |
| [Session plugins](session-plugins.md) | How do I attach my own behaviour to every message (stamp a field, audit, measure) without touching the application? |
| [Network monitoring](network-monitoring.md) | How long is the line to my counterparty, and do our clocks agree? |
| [Runtime administration](runtime-administration.md) | How do I log a session on, reset a sequence number, or reload settings on a running engine, over the protocol I actually use? |
| [Spring Boot](spring-boot.md) | How do I declare the whole engine in `application.properties`? |

## How to read the API

Three ideas explain most of Staffix's shape:

**A message is a stream of fields, not an object.** That is the "Streaming API for FIX" the name is built on: a
decoder declares once which tags it wants, and a field nobody asked for is stepped over rather than parsed. See
[Decoding a message](decoding-messages.md).

**Everything pluggable is an interface plus a settings object.** Stores, loggers, application factories, session
settings stores, admin exporters and monitoring plugins all follow the same pattern: a `…Settings` builder handed to
`FixEngineBuilder`, and an implementation chosen by which jar is on the classpath.

**Instances are addressed by id.** You register a store as `instanceId("acceptor")`, and a session says
`fixMessageStoreInstanceId("acceptor")`. That indirection is what lets one engine run an acceptor and an initiator
with entirely different persistence, logging and applications, which is exactly what the quickstart does.
