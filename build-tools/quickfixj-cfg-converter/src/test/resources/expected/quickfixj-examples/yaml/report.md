# QuickFIX/J settings conversion

Converted by `CfgToStaffix.java` to YAML session files, with QuickFIX/J's validation checks kept. Every value QuickFIX/J defaulted that staffix defaults otherwise is written, so each session behaves as it did.

## Sessions

| session | type | from | store |
| :- | :- | :- | :- |
| banzai-exec-fix42 | initiator | banzai.cfg:21 | default |
| banzai-exec-fix43 | initiator | banzai.cfg:25 | default |
| banzai-exec-fix44 | initiator | banzai.cfg:29 | default |
| banzai-exec-fix50 | initiator | banzai.cfg:33 | default |
| exec-banzai-fix42 | acceptor | executor.cfg:22 | acceptor-9878 |
| exec-banzai-fix43 | acceptor | executor.cfg:26 | acceptor-9879 |
| exec-banzai-fix44 | acceptor | executor.cfg:30 | acceptor-9880 |
| exec-banzai-fix50 | acceptor | executor.cfg:34 | acceptor-9881 |

## Sessions not converted: staffix starts at FIX 4.2

| from | version |
| :- | :- |
| banzai.cfg:13 | BeginString FIX.4.0 |
| banzai.cfg:17 | BeginString FIX.4.1 |
| executor.cfg:14 | BeginString FIX.4.0 |
| executor.cfg:18 | BeginString FIX.4.1 |

## Initiators, one per session

| initiator and its session | connect addresses | connection retry | TLS |
| :- | :- | :- | :- |
| banzai-exec-fix42 | localhost:9878 | PT5S | no |
| banzai-exec-fix43 | localhost:9879 | PT5S | no |
| banzai-exec-fix44 | localhost:9880 | PT5S | no |
| banzai-exec-fix50 | localhost:9881 | PT5S | no |

## Acceptors

| acceptor | bind address | sessions settings store | sessions | TLS |
| :- | :- | :- | :- | :- |
| acceptor-9878 | 0.0.0.0:9878 | acceptor-9878 | exec-banzai-fix42 | no |
| acceptor-9879 | 0.0.0.0:9879 | acceptor-9879 | exec-banzai-fix43 | no |
| acceptor-9880 | 0.0.0.0:9880 | acceptor-9880 | exec-banzai-fix44 | no |
| acceptor-9881 | 0.0.0.0:9881 | acceptor-9881 | exec-banzai-fix50 | no |

## Dictionaries

None.

## Components and transport, to wire in code or properties

| key | value | staffix | sessions |
| :- | :- | :- | :- |
| FileStorePath | target/data/banzai | file store storageDirectoryPath: one file store instance per distinct path | banzai-exec-fix42, banzai-exec-fix43, banzai-exec-fix44, banzai-exec-fix50 |
| FileStorePath | target/data/executor | file store storageDirectoryPath: one file store instance per distinct path | exec-banzai-fix42, exec-banzai-fix43, exec-banzai-fix44, exec-banzai-fix50 |

## Needs code

None.

## Behaviour changes

| session | change |
| :- | :- |
| exec-banzai-fix42 | A staffix acceptor refuses a Logon whose HeartBtInt is outside 1 to 30 seconds; QuickFIX/J took any. Check the counterparty's value. |
| exec-banzai-fix43 | A staffix acceptor refuses a Logon whose HeartBtInt is outside 1 to 30 seconds; QuickFIX/J took any. Check the counterparty's value. |
| exec-banzai-fix44 | A staffix acceptor refuses a Logon whose HeartBtInt is outside 1 to 30 seconds; QuickFIX/J took any. Check the counterparty's value. |
| exec-banzai-fix50 | A staffix acceptor refuses a Logon whose HeartBtInt is outside 1 to 30 seconds; QuickFIX/J took any. Check the counterparty's value. |

## No equivalent

None.

## Application settings, written to fixApplicationSessionSettings

| session | key | value |
| :- | :- | :- |
| exec-banzai-fix42 | ValidOrderTypes | 1,2,F |
| exec-banzai-fix42 | DefaultMarketPrice | 12.30 |
| exec-banzai-fix43 | ValidOrderTypes | 1,2,F |
| exec-banzai-fix43 | DefaultMarketPrice | 12.30 |
| exec-banzai-fix44 | ValidOrderTypes | 1,2,F |
| exec-banzai-fix44 | DefaultMarketPrice | 12.30 |
| exec-banzai-fix50 | ValidOrderTypes | 1,2,F |
| exec-banzai-fix50 | DefaultMarketPrice | 12.30 |

## Questions

None.
