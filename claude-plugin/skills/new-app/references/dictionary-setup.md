# Setting up the dictionary

The build generates the FIX encoders, field classes and registries from a dictionary: nothing FIX is hand-written.
The reference projects show case 1 for FIX 4.4 (`pom.xml`, `src/main/dictionaries/messages.txt`). Background:
`${CLAUDE_PLUGIN_ROOT}/docs/fix-versions-and-dictionaries.md`.

Every case generates into `<base package>.fix` and the application returns its dictionary from `getDictionaryId()`:
`FixDictionaryId.of(FixRegularVersion.VERSION_44)` for the default id, `FixDictionaryId.of("<id>", version)` for a
named one.

## 1. From scratch, FIX 4.4 and later: cut to the chosen messages

Three plugins in the `initialize` and `generate-sources` phases, as in the reference `pom.xml`:

1. `staffix-fix-orchestra-dictionary-generator-maven-plugin`, goal `generate`: `upToVersion`, `upToExtensionPack`,
   `markDeprecated` true, `includeMessagesFile` = `src/main/dictionaries/messages.txt`, `outputFile` under `target/`.
   Use the cut the shipped dictionaries use:

   | version | `upToVersion` | `upToExtensionPack` | session id version |
   | :- | :- | :- | :- |
   | FIX 4.4 | `FIX.4.4` | `38` | `VERSION_44` |
   | FIX 5.0 | `FIX.5.0` | `41` | FIXT, `VERSION_50` |
   | FIX 5.0 SP1 | `FIX.5.0SP1` | `76` | FIXT, `VERSION_50_SP1` |
   | FIX 5.0 SP2 | `FIX.5.0SP2` | `98` | FIXT, `VERSION_50_SP2` |
   | FIX Latest | leave both out | | FIXT, `VERSION_LATEST` |

2. `staffix-fix-dictionary-sanitizer-maven-plugin`, goal `sanitize`, from the cut to the file the generator reads.
3. `staffix-fix-encoders-generator-maven-plugin`, goal `code-generator`, `packageName` `<base package>.fix`.

`messages.txt` lists one message name per line, with `#` comments. **Always add `BusinessMessageReject`** with the
comment `# required by the session layer`: staffix 0.9.1 drops it from a cut that does not name it, and every session
then fails at creation. A later staffix keeps it by itself; the line stays harmless.

## 2. FIX 4.2 or 4.3: a copy of the shipped dictionary

Orchestra cannot cut them. Copy `${CLAUDE_PLUGIN_ROOT}/dictionaries/FIX42.xml` or `FIX43.xml` to
`src/main/dictionaries/`, sanitize it into `target/` and generate from the result. The sanitizer removes only what
nothing references, so the whole message catalogue stays; removing unused `<message>` elements from the copy by hand
shrinks it, if the user wants that.

## 3. A dictionary the provider supplies

Copy the file to `src/main/dictionaries/` unchanged, so the provider's next version replaces it as it is. Read the
version from its root element (`<fix major="4" minor="4">`; `servicepack` for 5.0 SP1/SP2) and give it a
`dictionaryId` named after the provider. Generate from it directly, or from a sanitized copy under `target/`.

Grep it for `msgtype="j"`. If BusinessMessageReject is missing, staffix refuses the session: tell the user, and add
the message from the shipped dictionary of the same version, saying so in the project README.

## FIX 5.0 and later

The session layer is FIXT.1.1:

- add `staffix-fixt-11` as a dependency of the project;
- for a cut, sanitize with `sanitizeMsgTypeField` false and `keepFields` BeginString, BodyLength, MsgType, MsgSeqNum,
  SenderCompID, TargetCompID, SendingTime, CheckSum and ApplVerID (as `fix-packages/fix-latest/pom.xml` in staffix);
- declare the session as FIXT: in YAML `fixVersion: "FIX.5.0-SP2"` with `fixTSession: true`; in Spring properties
  `fix-version=FIXT_11` with `default-appl-ver-id=VERSION_50_SP2`; in Java
  `FixSessionId.ofFIXT11(name, FixApplVerID.FIX50SP2, sender, target)`.

No reference project covers FIX 5.0+ yet: the generated project's tests are what prove it, so run them.
