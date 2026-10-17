# QuickFIX/J settings conversion

Converted by `CfgToStaffix.java` to Spring Boot properties, with QuickFIX/J's validation checks kept. Every value QuickFIX/J defaulted that staffix defaults otherwise is written, so each session behaves as it did.

## Sessions

| session | type | from | store |
| :- | :- | :- | :- |
| banzai-exec | initiator | banzai.cfg:14 | default |
| banzai-exec-2 | initiator | banzai.cfg:21 | default |
| exec-banzai | acceptor | executor.cfg:14 | acceptor-9880 |
| exec-banzai-2 | acceptor | executor.cfg:20 | acceptor-9881 |
| alt | initiator | options.cfg:6 | default |
| broker-client | acceptor | options.cfg:34 | acceptor-4000 |

## Initiators, one per session

| initiator and its session | connect addresses | connection retry | TLS |
| :- | :- | :- | :- |
| banzai-exec | localhost:9880 | PT5S | no |
| banzai-exec-2 | localhost:9881, backup:9891 | PT5S | no |
| alt | broker.example.com:4000 | PT3S | yes |

## Acceptors

| acceptor | bind address | sessions settings store | sessions | TLS |
| :- | :- | :- | :- | :- |
| acceptor-9880 | 0.0.0.0:9880 | acceptor-9880 | exec-banzai | yes |
| acceptor-9881 | 0.0.0.0:9881 | acceptor-9881 | exec-banzai-2 | yes |
| acceptor-4000 | 127.0.0.1:4000 | acceptor-4000 | broker-client | no |

## Dictionaries

None.

## Components and transport, to wire in code or properties

| key | value | staffix | sessions |
| :- | :- | :- | :- |
| FileStorePath | target/data/banzai | file store storageDirectoryPath: one file store instance per distinct path | banzai-exec, banzai-exec-2 |
| FileStorePath | target/data/executor | file store storageDirectoryPath: one file store instance per distinct path | exec-banzai, exec-banzai-2 |
| FileLogPath | target/logs | file logger logDirectory | banzai-exec, banzai-exec-2 |
| ScreenLogShowHeartBeats | N | N: the logger's messageFilter drops heartbeats (Java only) | banzai-exec, banzai-exec-2 |
| SocketTcpNoDelay | Y | Java only: IOSettings.socketOptions | alt |

## Needs code

| session | key | value | how |
| :- | :- | :- | :- |
| banzai-exec-2 | LogonTag | 553=user | add `553=user` to the Logon in the application's onAdminMessageEncoding (concepts.md) |
| alt | EnabledProtocols | TLSv1.3,TLSv1.2 | staffix's Spring properties take one protocol, TLSv1.3 is written; several need Java's engineSetupForSecureSession |

## Behaviour changes

| session | change |
| :- | :- |
| banzai-exec-2 | QuickFIX/J reset the sequence numbers at the first logon of each window; a staffix window keeps them. Does the counterparty expect the daily reset? resetSeqNumOnLogon: true gives it at every logon, intraday reconnects included. |
| exec-banzai | A staffix acceptor refuses a Logon whose HeartBtInt is outside 1 to 60 seconds; QuickFIX/J took any. Check the counterparty's value. |
| exec-banzai-2 | A staffix acceptor refuses a Logon whose HeartBtInt is outside 1 to 20 seconds; QuickFIX/J took any. Check the counterparty's value. |
| broker-client | A staffix acceptor refuses a Logon whose HeartBtInt is outside 1 to 20 seconds; QuickFIX/J took any. Check the counterparty's value. |
| broker-client | staffix has one order check for fields and groups: validateFieldsOutOfOrder is on, where QuickFIX/J checked only groups. |
| broker-client | QuickFIX/J reset the sequence numbers at the first logon of each window; a staffix window keeps them. Does the counterparty expect the daily reset? resetSeqNumOnLogon: true gives it at every logon, intraday reconnects included. |

## No equivalent

| session | key | value | closest |
| :- | :- | :- | :- |
| banzai-exec-2 | ResetOnDisconnect | Y | staffix resets only on logon, a non-stop day's roll or the admin API |
| alt | LogoutTimeout | 5 | staffix has one timeout for both, logInOrOutResponseTimeout, written from LogonTimeout |
| alt | ReconnectInterval | 5x3;30 | staffix waits one interval between attempts: PT3S, the first of 5x3;30 |
| alt | ResendRequestChunkSize | 100 | none; maxMessagesResentPerRequest caps the other side's requests |

## Not QuickFIX/J keys: read by the application?

| session | key | value |
| :- | :- | :- |
| banzai-exec-2 | MyAppKey | 42 |

## Questions

| session | question |
| :- | :- |
| banzai-exec-2 | Two sessions are named `banzai-exec`: this one is `banzai-exec-2`. Set a SessionQualifier, or rename it. |
| exec-banzai | The keystore password is in clear in the .cfg: it is written as ${FIX_KEY_STORE_PASSWORD}, an environment variable to set. |
| exec-banzai-2 | Two sessions are named `exec-banzai`: this one is `exec-banzai-2`. Set a SessionQualifier, or rename it. |
| exec-banzai-2 | The keystore password is in clear in the .cfg: it is written as ${FIX_KEY_STORE_PASSWORD}, an environment variable to set. |
