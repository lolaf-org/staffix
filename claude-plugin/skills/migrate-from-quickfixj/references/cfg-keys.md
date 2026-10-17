# QuickFIX/J settings in staffix

Every key QuickFIX/J 3.0.2 reads from a `.cfg` file, read from its jars, and what it becomes. Concepts are in
`concepts.md`; components are described in the catalogue.

## Reading the tables

- **Resolve first.** A session's value is its `[SESSION]` key, else the `[DEFAULT]` one, else the QuickFIX/J default
  (column `qfj default`). Map each session's resolved values, never the two sections apart.
- **YAML** is a path in the session document of the file sessions settings store. **Spring** is a property under
  `<store prefix>.instances.<id>.sessions[<n>].` (or `.defaults.` for a value shared by every session of that store).
- **Transforms**:
  - `copy`: the value unchanged.
  - `bool`: `Y` is `true`, `N` is `false`.
  - `not`: `Y` is `false`, `N` is `true`.
  - `seconds`: a number of seconds `n` is `PT<n>S`.
  - `list`: comma separated values become a list.
  - `rule:<name>`: the rule of that name below the tables.
- **When absent**: what to write when the key is not set, because QuickFIX/J's default and staffix's differ. Writing
  it keeps the session behaving as it did. Empty means write nothing.
- **No equivalent** goes in the migration report with the session, its value and the line in the table, never
  silently dropped.

## Session identity

| key | qfj default | YAML | Spring | transform | when absent |
| :- | :- | :- | :- | :- | :- |
| `BeginString` | required | `fixSessionId.fixVersion` | `fix-session-id.fix-version` | `rule:version` | |
| `DefaultApplVerID` | | `fixSessionId.fixVersion` | `fix-session-id.default-appl-ver-id` | `rule:version` | |
| `SenderCompID` | required | `fixSessionId.senderCompID` | `fix-session-id.sender-comp-id` | `copy` | |
| `SenderSubID` | | `fixSessionId.senderSubID` | `fix-session-id.sender-sub-id` | `copy` | |
| `SenderLocationID` | | `fixSessionId.senderLocationID` | `fix-session-id.sender-location-id` | `copy` | |
| `TargetCompID` | required | `fixSessionId.targetCompID` | `fix-session-id.target-comp-id` | `copy` | |
| `TargetSubID` | | `fixSessionId.targetSubID` | `fix-session-id.target-sub-id` | `copy` | |
| `TargetLocationID` | | `fixSessionId.targetLocationID` | `fix-session-id.target-location-id` | `copy` | |
| `SessionQualifier` | | `fixSessionId.name` | `fix-session-id.name` | `rule:name` | `rule:name` |
| `ConnectionType` | required | `fixSessionType` | `type` | `initiator` is `INITIATOR`, `acceptor` is `ACCEPTOR` | |
| `Description` | | | | **no equivalent**: keep it as a comment in the session file | |

## Session behaviour

| key | qfj default | YAML | Spring | transform | when absent |
| :- | :- | :- | :- | :- | :- |
| `HeartBtInt` | required (initiator) | `heartBeatInterval.initiatorInterval` | `heartbeat.initiator-interval` | `seconds` | |
| | acceptor: any | `heartBeatInterval.acceptorUpperBoundInterval` | `heartbeat.acceptor-upper-bound-interval` | `rule:acceptor-heartbeat` | `rule:acceptor-heartbeat` |
| `LogonTimeout` | `10` | `logInOrOutResponseTimeout` | `log-in-or-out-response-timeout` | `seconds`, `rule:logon-logout-timeout` | |
| `LogoutTimeout` | `2` | `logInOrOutResponseTimeout` | `log-in-or-out-response-timeout` | `rule:logon-logout-timeout` | |
| `ResetOnLogon` | `N` | `resetSeqNumOnLogon` | `reset-seq-num-on-logon` | `Y` is `true`; `N` writes nothing (staffix then follows the counterparty, as QuickFIX/J does; `false` would refuse its reset) | |
| `EnableNextExpectedMsgSeqNum` | `N` | `enabledLogonNextExpectedMsgSeqNum` | `enabled-logon-next-expected-msg-seq-num` | `bool` | `false` |
| `ClosedResendInterval` | `N` | `resendRequestRange` | `resend-request-range` | `Y` is `CLOSED`, `N` is `OPEN_ENDED` | `OPEN_ENDED` |
| `TimeStampPrecision` | `MILLIS` | `sendingTimeAccuracy` | `sending-time-accuracy` | `SECONDS`, `MILLIS`, `MICROS`, `NANOS` are `SECONDS`, `MILLISECONDS`, `MICROSECONDS`, `NANOSECONDS` | `MILLISECONDS` |
| `AllowedRemoteAddresses` | any | `allowedAddresses` | `allowed-addresses` | `list` | |
| `LogonTag`, `LogonTag<n>` | | | | `tag=value` on the Logon: code, `onAdminMessageEncoding` for the Logon (`concepts.md`) | |
| `PersistMessages` | `Y` | | | `N`: in Java, the store's `messageFilter` `(type, message) -> true`; with Spring, **no equivalent** | |

## Validation

QuickFIX/J validates every message against its dictionary when `UseDataDictionary` and `ValidateIncomingMessage` are
`Y`, their defaults; staffix runs only the checks turned on, because each costs time on every message. The **when
absent** column keeps QuickFIX/J's checks; the report says so, since turning them off is staffix's default.

| key | qfj default | YAML (`validationSettings.`) | Spring (`validation.`) | transform | when absent |
| :- | :- | :- | :- | :- | :- |
| `UseDataDictionary` | `Y` | | | `rule:dictionary` | |
| `ValidateIncomingMessage` | `Y` | | | `N` turns the dictionary checks off: `false` to `validateRequiredFields`, `validateDuplicateTags`, `validateFieldsOutOfOrder` and `validateFieldsHaveValues`, `true` to every `allow...`. The checksum, CompID, latency and BeginString checks stay | |
| (required fields, always checked) | | `validateRequiredFields` | `validate-required-fields` | | `true` |
| (tag not defined for the message type, always checked) | | `allowUndefinedTagsForMessage` | `allow-undefined-tags-for-message` | | `false` |
| (tag appearing twice, always checked) | | `validateDuplicateTags` | `validate-duplicate-tags` | | `true` |
| (BeginString, always checked) | | `validateBeginString` | `validate-begin-string` | | `true` |
| `ValidateChecksum` | `Y` | `validateChecksum` | `validate-checksum` | `bool` | |
| `ValidateFieldsHaveValues` | `Y` | `validateFieldsHaveValues` | `validate-fields-have-values` | `bool` | |
| `ValidateFieldsOutOfOrder` | `Y` | `validateFieldsOutOfOrder` | `validate-fields-out-of-order` | `bool` | `true` |
| `ValidateUnorderedGroupFields` | `Y` | `validateFieldsOutOfOrder` | `validate-fields-out-of-order` | staffix has one check for both: `Y` if either is `Y`; report a difference | |
| `ValidateUserDefinedFields` | `Y` | `allowUserDefinedFields` | `allow-user-defined-fields` | `not` | |
| `AllowUnknownMsgFields` | `N` | `allowUnknownFields` | `allow-unknown-fields` | `bool` | |
| `CheckCompID` | `Y` | `validateCompId` | `validate-comp-id` | `bool` | `true` |
| `CheckLatency` | `Y` | `maxSendingTime` | `max-sending-time` | `N` writes nothing; `Y`: `MaxLatency` in `seconds` | `PT120S` |
| `MaxLatency` | `120` | `maxSendingTime` | `max-sending-time` | `seconds`, when `CheckLatency` is `Y` | |
| `RejectGarbledMessage` | `N` | | | **no equivalent**: staffix ignores a garbled message, so the next one shows a gap; `detectGarbledMessages` adds the costly checks | |
| `RejectInvalidMessage` | `Y` | | | `N`: **no equivalent**, staffix always rejects | |
| `RequiresOrigSendingTime` | `Y` | | | **no equivalent** | |
| `FirstFieldInGroupIsDelimiter` | `N` | | | **no equivalent** | |
| `AllowPosDup` | `N` | | | **no equivalent** | |
| `ValidateSequenceNumbers` | `Y` | | | `N`: **no equivalent**, staffix always checks | |

QuickFIX/J also checks each value against the dictionary's enums and types; staffix does not, which the report says.

## Schedule

| key | qfj default | YAML (`sessionScheduleSettings.`) | Spring (`schedule.`) | transform | when absent |
| :- | :- | :- | :- | :- | :- |
| `StartTime`, `EndTime` | required, unless non stop | `sessionSchedules` | `session-schedules` | `rule:schedule` | |
| `StartDay`, `EndDay` | daily | `sessionSchedules` | `session-schedules` | `rule:schedule` | |
| `Weekdays` | | `sessionSchedules` | `session-schedules` | `rule:schedule` | |
| `NonStopSession` | `N` | | | `Y`: write no schedule, which staffix reads as always up | |
| `TimeZone` | `UTC` | `timeZone` | `time-zone` | `copy` | `UTC` (staffix defaults to the JVM's) |

## Initiators

QuickFIX/J runs every initiator session of a file in one `SocketInitiator`; staffix runs one initiator per session,
its main target. Spring: `staffix.initiators.<name>.`, with the session's name as `<name>`.

| key | qfj default | Java (`FixInitiatorBuilder`) | Spring | transform |
| :- | :- | :- | :- | :- |
| `SocketConnectHost`, `SocketConnectPort` | required | `mainTarget(FixInitiatorTarget.builder().fixSessionId(...).connectAddress(new InetSocketAddress(host, port)))` | `staffix.initiators.<name>.main-target.connect-addresses[0]` | `host:port` |
| `SocketConnectHost<n>`, `SocketConnectPort<n>` | | another `connectAddress`, in order | `staffix.initiators.<name>.main-target.connect-addresses[<n>]` | `host:port`; the same session at another address, tried in turn |
| `ReconnectInterval` | `30` | `connectionRetry` | `staffix.initiators.<name>.connection-retry` | `seconds`; a list (`5x10;30`): **no equivalent**, write its first value and report |
| `SocketConnectProtocol` | `TCP` | | | `VM_PIPE`: **no equivalent** |
| `SocketConnectTimeout` | | | | **no equivalent** |
| `SocketLocalHost`, `SocketLocalPort` | | | | **no equivalent** |
| `ProxyType`, `ProxyVersion`, `ProxyHost`, `ProxyPort`, `ProxyUser`, `ProxyPassword`, `ProxyDomain`, `ProxyWorkstation` | | | | **no equivalent** |

The session's own settings must also be in a sessions settings store, as for any initiator.

## Acceptors

QuickFIX/J listens on each session's own address; staffix runs one acceptor per address, serving every session of
the sessions settings stores it names. Spring: `staffix.acceptors.<name>.`.

| key | qfj default | Java (`FixAcceptorBuilder`) | Spring | transform |
| :- | :- | :- | :- | :- |
| `SocketAcceptPort`, `SocketAcceptAddress` | required, any address | `bindAddress(new InetSocketAddress(address, port))` | `staffix.acceptors.<name>.bind-address` | `rule:acceptors` |
| `SocketAcceptProtocol` | `TCP` | | | `VM_PIPE`: **no equivalent** |
| `AcceptorTemplate`, `DynamicSession` | `N` | | | `Y`: **no equivalent**, every session is declared |

## Sockets

staffix sets `TCP_NODELAY` itself. The rest go to `ioSettings(IOSettings.builder().socketOptions(Map.of(...)))` on
the initiator or acceptor builder, in Java only: with Spring, **no equivalent**.

| key | `StandardSocketOptions` |
| :- | :- |
| `SocketTcpNoDelay` | `TCP_NODELAY`; `N` is the only value worth writing |
| `SocketKeepAlive` | `SO_KEEPALIVE` |
| `SocketLinger` | `SO_LINGER` |
| `SocketReceiveBufferSize` | `SO_RCVBUF` |
| `SocketSendBufferSize` | `SO_SNDBUF` |
| `SocketReuseAddress` | `SO_REUSEADDR` |
| `SocketTrafficClass` | `IP_TOS` |
| `SocketOobInline` | **no equivalent** |
| `SocketSynchronousWrites`, `SocketSynchronousWriteTimeout` | **no equivalent** |
| `MaxScheduledWriteRequests` | **no equivalent**: `IOSettings.writeHighWatermark` warns, it never refuses |

## TLS

`SocketUseSSL` (`N` by default) set to `Y` turns these on. Java: an initiator's `sslSettings(SSLSettings)` or an acceptor's
`serverSSLSettings(ServerSSLSettings)`, built from an `SSLContext` the application loads. Spring: under
`staffix.initiators.<name>.ssl.` or `staffix.acceptors.<name>.ssl.`.

| key | qfj default | Spring | notes |
| :- | :- | :- | :- |
| `SocketKeyStore` | QuickFIX/J's bundled test keystore | `key-store-path` | never carry the bundled keystore over: report it |
| `SocketKeyStorePassword` | | `key-store-password` | an environment variable, never the value |
| `KeyStoreType` | `JKS` | `key-store-type` | when absent, `JKS` (staffix defaults to `PKCS12`) |
| `SocketTrustStore` | | `trust-store-path` | |
| `SocketTrustStorePassword` | | `trust-store-password` | an environment variable, never the value |
| `TrustStoreType` | `JKS` | `trust-store-type` | when absent, `JKS` |
| `EnabledProtocols` | the JVM's | `protocol` | one protocol only; several: Java, `engineSetupForSecureSession` |
| `NeedClientAuth` | `N` | `need-client-auth` (acceptor) | |
| `CipherSuites`, `UseSNI`, `SNIHostName`, `EndpointIdentificationAlgorithm` | | | Java only: `engineSetupForSecureSession(engine -> ...)`; with Spring, **no equivalent** |
| `KeyManagerFactoryAlgorithm`, `TrustManagerFactoryAlgorithm` | `SunX509`, `PKIX` | | Java: building the `SSLContext`; Spring: an `SSLContext` bean named by `ssl-context-bean` |

## Messages stores

The `.cfg` names no store: the application's `MessageStoreFactory` does (`concepts.md`). These keys set it up.

| key | qfj default | staffix | notes |
| :- | :- | :- | :- |
| `FileStorePath` | required | file store `storageDirectoryPath` | one file store instance per distinct path, and each session names its instance |
| `FileStoreSync` | `N` | file store `syncWrites` | when absent, `false` (staffix defaults to `true`); the report suggests `true` |
| `FileStoreMaxCachedMsgs` | `10000` | | **no equivalent** |
| `JdbcDataSourceName`, `JdbcURL`, `JdbcDriver`, `JdbcUser`, `JdbcPassword`, `JdbcMaxActiveConnection`, `JdbcMinIdleConnection`, `JdbcMaxConnectionLifeTime`, `JdbcConnectionTimeout`, `JdbcConnectionIdleTimeout`, `JdbcConnectionKeepaliveTime`, `JdbcConnectionTestQuery`, `JndiContextFactory`, `JndiProviderURL` | | the `DataSource` the JDBC store is given | plain Java, a pool the application builds; Spring, `spring.datasource.*` |
| `JdbcStoreMessagesTableName`, `JdbcStoreSessionsTableName` | `messages`, `sessions` | JDBC store `tablePrefix` | staffix has its own tables: the report says the QuickFIX/J data is not migrated |
| `JdbcSessionIdDefaultPropertyValue` | | | **no equivalent** |
| `SleepycatDatabaseDir`, `SleepycatMessageDbName`, `SleepycatSequenceDbName` | | | **no equivalent** (`concepts.md`) |

A changed store starts from sequence number 1: the report says the counterparty must agree to a reset, or the
numbers be set through the admin API before the first logon.

## Logs

The `.cfg` names no logger: the application's `LogFactory` does (`concepts.md`).

| key | qfj default | staffix | notes |
| :- | :- | :- | :- |
| `FileLogPath` | required | file logger `logDirectory` | the file format is staffix's own |
| `FileIncludeMilliseconds`, `FileIncludeTimeStampForMessages` | `N` | file logger `logTimePrecision` | `MILLISECONDS` when `FileIncludeMilliseconds` is `Y` |
| `FileLogHeartbeats` | `Y` | the logger's `messageFilter` | `N`: in Java, `(type, event) -> "0".equals(type.code())` drops heartbeats; with Spring, **no equivalent** |
| `ScreenLogShowIncoming`, `ScreenLogShowOutgoing`, `ScreenLogShowEvents` | `Y` | SLF4J logger `logIncoming`, `logOutgoing`, `logEvents` | `bool`; the screen logger becomes the SLF4J logger |
| `ScreenLogShowHeartBeats` | `Y` | the logger's `messageFilter` | `N` as `FileLogHeartbeats` |
| `ScreenIncludeMilliseconds` | `N` | | the logging backend's pattern |
| `SLF4JLogEventCategory`, `SLF4JLogIncomingMessageCategory`, `SLF4JLogOutgoingMessageCategory`, `SLF4JLogErrorEventCategory` | `quickfixj.event`, `quickfixj.msg.incoming`, `quickfixj.msg.outgoing`, `quickfixj.errorEvent` | SLF4J logger `loggerNameForFixSession` | one logger name per session for everything, by default `org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLogger`: the report lists the logging configuration that names the QuickFIX/J categories |
| `SLF4JLogPrependSessionID` | `Y` | SLF4J logger `useMDC` | the session is in the MDC (`fixSessionGroup`, `fixSessionName`), for the log pattern to print |
| `SLF4JLogHeartbeats` | `Y` | the logger's `messageFilter` | `N` as `FileLogHeartbeats` |
| `JdbcLogHeartBeats`, `JdbcLogIncomingTable`, `JdbcLogOutgoingTable`, `JdbcLogEventTable` | | | **no equivalent** (`concepts.md`) |

## The application's own keys

A key QuickFIX/J does not declare is the application's, read with `settings.getString(sessionID, key)`, as
Executor's `ValidOrderTypes`. It goes to the session's `fixApplicationSessionSettings` (Spring:
`application-session-settings[<key>]`), and the application reads it as `concepts.md` says.

## No equivalent

Each is reported with its value; the cell says what comes closest.

| key | qfj default | closest |
| :- | :- | :- |
| `ResetOnLogout`, `ResetOnDisconnect`, `ResetOnError` | `N` | none: staffix resets only on logon, a non-stop day's roll or the admin API |
| `RefreshOnLogon` | `N` | none |
| `DisconnectOnError` | `N` | none |
| `ResendRequestChunkSize` | `0` | none; `maxMessagesResentPerRequest` caps the other side's requests, not this side's |
| `SendRedundantResendRequests` | `N` | none |
| `ForceResendWhenCorruptedStore` | `N` | none |
| `EnableLastMsgSeqNumProcessed` | `N` | none |
| `DisableHeartBeatCheck` | `N` | none |
| `HeartBeatTimeoutMultiplier`, `TestRequestDelayMultiplier` | `1.4`, `0.5` | none: staffix follows the session specification's timings |
| `RejectMessageOnUnhandledException` | `N` | none |
| `LogMessageWhenSessionNotFound` | `Y` | the acceptor's `fixSessionEventsListener` sees refused sessions |
| `ContinueInitializationOnError` | `N` | none |

## Rules

- **version**: `BeginString` `FIX.4.2`, `FIX.4.3`, `FIX.4.4` are copied (Spring: `VERSION_42`, `VERSION_43`,
  `VERSION_44`). `FIX.4.0` and `FIX.4.1`: **no equivalent**, staffix starts at 4.2: the session is not converted, and is
  reported. `FIXT.1.1`: YAML
  `fixSessionId.fixTSession: true` and `fixVersion` from `DefaultApplVerID`; Spring `fix-version=FIXT_11` and
  `default-appl-ver-id` from it. `DefaultApplVerID` `7` or `FIX.5.0` is `FIX.5.0` (Spring `VERSION_50`), `8` or
  `FIX.5.0SP1` is `FIX.5.0-SP1` (`VERSION_50_SP1`), `9` or `FIX.5.0SP2` is `FIX.5.0-SP2` (`VERSION_50_SP2`); a FIX 4
  application version is not converted, and is reported.
- **name**: staffix names every session. `SessionQualifier` when set, else `<SenderCompID>-<TargetCompID>` in lower
  case. Sessions sharing those CompIDs each add their version (`exec-banzai-fix44`), and a number if they still
  collide.
- **acceptor-heartbeat**: a staffix acceptor refuses a Logon whose HeartBtInt is outside
  `[acceptorLowerBoundInterval, acceptorUpperBoundInterval]`, by default 1 to 20 seconds; QuickFIX/J takes any. For
  each acceptor session, write `acceptorUpperBoundInterval` as the larger of `PT20S` and the session's `HeartBtInt`,
  and report the bounds so the user checks them against the counterparty's value.
- **logon-logout-timeout**: staffix has one timeout for both. Write `LogonTimeout`; a `LogoutTimeout` other than 2 is
  reported.
- **dictionary**: `DataDictionary`, `AppDataDictionary` and `TransportDataDictionary` are not settings in staffix: the
  file becomes the project's dictionary (`concepts.md`, Messages). Sessions with different dictionaries need one
  application each. `UseDataDictionary=N` turns the dictionary checks off, as `ValidateIncomingMessage=N`.
- **schedule**: times are `HH:MM:SS`, optionally followed by a time zone; a time without one is in `TimeZone`. The
  start time's zone is written; a close time in another zone is reported. Days are QuickFIX/J day names, written in
  full and upper case (`MONDAY`).
  - `StartDay` and `EndDay`: one entry, `startDay`, `startTime`, `endDay`, `endTime`.
  - `Weekdays`: one entry per day listed, `startDay` that day.
  - Neither: one entry per day of the week.
  - For a per day entry, `endDay` is the same day, or the next one when `EndTime` is not after `StartTime`.
  - `StartTime` equal to `EndTime` on a daily session: QuickFIX/J stays up and resets once a day. Write a
    `nonStopSchedules` entry per day with `sequenceResetTime` that time, and ask the user which side sends the reset
    Logon (`initiatesReset`).

  QuickFIX/J resets the sequence numbers at the first logon of each new window; a staffix window does not, it keeps
  them. The report says so, and asks whether the counterparty expects the daily reset: `resetSeqNumOnLogon: true`
  gives it at every logon, intraday reconnects included.
- **acceptors**: one staffix acceptor per distinct `SocketAcceptAddress:SocketAcceptPort` (no address is `0.0.0.0`).
  Each acceptor's sessions go in sessions settings stores that only it names, through
  `targetFixSessionsSettingsStoreInstancesIds` (Spring: `staffix.acceptors.<name>.target-sessions-settings-store-instance-ids`).
  A single port needs no more than the `default` store.
