# QuickFIX/J concepts in staffix

Each QuickFIX/J 3.0.2 concept and what replaces it, checked against both code bases. Components (stores, loggers,
JMX) are named here and described in the catalogue (`${CLAUDE_PLUGIN_ROOT}/references/catalogue.md`); each `.cfg`
setting is in `cfg-keys.md`. A concept marked **no equivalent** goes into the migration report as such, never
silently dropped.

## Engine and connections

| QuickFIX/J | staffix |
| :- | :- |
| `SocketInitiator`, `ThreadedSocketInitiator` | `engine.newInitiator(FixInitiatorBuilder)`, with a `mainTarget` (`FixInitiatorTarget`: session id and `connectAddresses`) and optional `backupTarget`s; Spring Boot: `staffix.initiators.<name>` |
| `SocketAcceptor`, `ThreadedSocketAcceptor` | `engine.newAcceptor(FixAcceptorBuilder)`, with a `bindAddress`; Spring Boot: `staffix.acceptors.<name>` |
| one thread, or one thread per session (`Threaded...`) | staffix I/O threads either way; work too slow for the I/O thread goes to a `MessageExecutor` (`docs/threading-model.md`) |
| `connector.start()`, `stop()` | `engine.start()`, `engine.stop(Deadline)` |
| `SessionSettings` and its `.cfg` file | a sessions settings store: one YAML file per session with the file store, or Spring Boot properties; each key in `cfg-keys.md` |
| `SessionID` (BeginString, SenderCompID, TargetCompID, qualifier) | `FixSessionId.of(name, version, senderCompId, targetCompId)`; the name is staffix's own and replaces the qualifier |
| `Session.lookupSession(sessionID)` | `engine.getFixSessionRegistry().find(fixSessionId)` |
| `Session.sendToTarget(message, sessionID)` | `fixSession.send(encoder.begin().set...(...), null)` with a reusable encoder created once per session (`session.newEncoder(XEncoder.class).asReusable()`) |
| `session.logon()`, `session.logout(reason)` | `fixSession.logon()`, `fixSession.logout(reason)` |
| `session.reset()`, `setNextSenderMsgSeqNum`, `setNextTargetMsgSeqNum` | **no equivalent** in application code: an operator does it through an admin API exporter (`AdminApi.resetSession`, `setOutgoingSeqNum`, `setIncomingSeqNum`, `docs/runtime-administration.md`); report each call |
| `MessageStoreFactory`: `MemoryStoreFactory` | the memory messages store |
| `NoopStoreFactory` | the memory messages store with `maxEntriesInMemory` 0, which keeps no messages |
| `FileStoreFactory`, `CachedFileStoreFactory` | the file messages store |
| `JdbcStoreFactory` | the JDBC messages store, behind the async store |
| `SleepycatStoreFactory` | **no equivalent**: the file store, or JDBC |
| `LogFactory`: `ScreenLogFactory`, `SLF4JLogFactory` | the SLF4J messages logger |
| `FileLogFactory` | the file messages logger |
| `JdbcLogFactory` | **no equivalent**: the file or OTLP messages logger |
| `CompositeLogFactory` | the demux messages logger |
| JMX (`org.quickfixj.jmx`) | the JMX admin API exporter |
| SSL through MINA (`SocketUseSSL` and its keys) | `FixInitiatorBuilder.sslSettings`, `FixAcceptorBuilder.serverSSLSettings`; keys in `cfg-keys.md` |

## The application

A QuickFIX/J `Application` becomes a staffix `FixApplication`. Every callback has a default but `getFixApiVersion`,
`getDictionaryId` and `setup`.

| QuickFIX/J | staffix |
| :- | :- |
| `onCreate(sessionID)` | `onSessionCreated(FixSession, ...)`; decoders are created before it, in `setup` |
| `onLogon(sessionID)` | `onLogon(FixSession, DecodedFixMessage)` |
| `onLogout(sessionID)` | `onLogout(FixSession, String, DecodedFixMessage)`; also `onPreLogout`, `onDisconnected` |
| `toAdmin` adding fields, such as Username and Password on the Logon | `onAdminMessageEncoding`: for the Logon type, `bodyAppender.get().addString(JaasHelper.USERNAME, user)`; header fields through `headersAppender` |
| `fromAdmin` throwing `RejectLogon` | `validateLogon` returns a `CompletableFuture<Optional<String>>`: `Optional.of(reason)` answers with a Logout carrying the reason, `Optional.empty()` accepts, `null` skips the check. It runs on the I/O thread: blocking work goes on the given `Executor`. `JaasHelper.jaasLogin` checks Username and Password through JAAS |
| `fromAdmin` reading Heartbeat, TestRequest... | the session layer answers them; `onHeartbeat`, `onTestRequest` observe them |
| `toApp` adding header or trailer fields | `onMessageEncoding` with `headersAppender`, `trailerAppender` |
| `toApp` throwing `DoNotSend` on a resend (`PossDupFlag`) | `onResendRequest` returning `false`: that message is not resent |
| `toApp` throwing `DoNotSend` on a live send | do not call `send` |
| `fromApp` with a `MessageCracker` and an `onMessage` per type | one `FixMessageDecoder` per message type, created per session and returned by `setup` (`docs/decoding-messages.md`) |
| `UnsupportedMessageType` | `onNoDecoderSetupForMessage` returning `true` answers with a BusinessMessageReject |
| `FieldNotFound`, `IncorrectTagValue`, `IncorrectDataFormat` thrown from `fromApp` | `ValidationException` thrown from the decoder's `validate()`: the message is not delivered and a BusinessMessageReject goes back; or the session's validation settings |
| `ApplicationExtended.canLogon(sessionID)` | **no equivalent**: a session's `desiredSessionState` (`LOGGED_OUT` keeps it down) or its schedule |
| `ApplicationExtended.onBeforeSessionReset(sessionID)` | **no equivalent** |

## Messages

| QuickFIX/J | staffix |
| :- | :- |
| `quickfix.fixNN.*` message classes (`quickfixj-messages-fixNN`) | encoders and decoders generated from the project's dictionary by the encoders generator plugin, for the messages the application uses (`skills/new-app/references/dictionary-setup.md`) |
| a custom `DataDictionary` XML | the same file, kept unchanged, as the project's dictionary: the provider dictionary case of `dictionary-setup.md` |
| `message.set(new ClOrdID(id))` | `encoder.begin().setClOrdID(id)` |
| `new Side(Side.BUY)` | `Side.SideValues.BUY` |
| `UtcTimeStampField` values | a `UTCTime` and a `TimeUnit` |
| `message.getClOrdID().getValue()`, `getField(...)` | a setter bound in `mapFieldsForDecoding`, called during decoding; only bound fields are parsed |
| a group: `new NoRelatedSym()`, `message.getGroup(i, group)` | encoding: `addNoRelatedSym(count)` returns the group's encoder; decoding: `forGroup(...)` in `mapFieldsForDecoding` |
| a `Message` kept after `fromApp` returns | a decoder is reused for the next message: copy the values during `onDecoded` |
