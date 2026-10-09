# Staffix documentation

Start with the [quickstart](../examples/README.md#quickstart-the-smallest-complete-staffix-application): it runs in
one command and is the shortest path to a working session. Then come back here.

## Guides

| guide | what it answers |
|-------|-----------------|
| [Examples](../examples/README.md) | What does a working Staffix program look like, and which one do I copy from? |
| [Threading model](threading-model.md) | Which thread runs my code, what may I share, and how do I get work off the message path? |
| [Configuring a session](configuring-sessions.md) | How do I declare a session, wire it to the engine's parts, and make it behave the way my counterparty expects? |
| [Session settings stores](session-settings-stores.md) | Where are my session settings kept, and how do I load them from YAML, the classpath or the environment? |
| [Decoding a message](decoding-messages.md) | How do I read an inbound message, and why does Staffix only decode the fields I asked for? |
| [Decoding in depth](decoding-messages-advanced.md) | Why decoding works this way, what a header field costs, and what do I use when typed bindings are not enough? |
| [FIX versions and dictionaries](fix-versions-and-dictionaries.md) | Which package do I depend on, how do I run several FIX versions at once, and how do I generate one from a counterparty's dictionary? |
| [Stores and loggers](stores-and-loggers.md) | Where do my messages get persisted and logged, and how do I keep that off the latency path? |
| [Tuning for latency](tuning-for-latency.md) | What do I actually change to get the numbers in [Performance](performance.md), and what does each one cost? |
| [Efficient identifier usage](efficient-identifiers-usage.md) | How do I mint and decode ids without allocating, and do I want a UUID or a Snowflake? |
| [Monitoring](monitoring.md) | How do I get metrics, traces and dashboards without putting them on the message path? |
| [Session plugins](session-plugins.md) | How do I attach my own behaviour to every message (stamp a field, audit, measure) without touching the application? |
| [Network monitoring](network-monitoring.md) | How long is the line to my counterparty, and do our clocks agree? |
| [Runtime administration](runtime-administration.md) | How do I log a session on, reset a sequence number, or reload settings on a running engine, over the protocol I actually use? |
| [Spring Boot](spring-boot.md) | How do I declare the whole engine in `application.properties`? |
| [Performance](performance.md) | What are the measured latency and allocation figures, and how is that latency achieved? |
| [Compared to other FIX engines](comparisons.md) | How does Staffix differ from QuickFIX/J and Artio, and when should I pick one of them instead? |
