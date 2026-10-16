# Listing a dictionary's messages

Use the Grep tool, not a script: it needs nothing installed.

- **Shipped dictionary**: `${CLAUDE_PLUGIN_ROOT}/dictionaries/<file>`: `FIX42.xml`, `FIX43.xml`, `FIX44.xml`,
  `FIX50.xml`, `FIX50SP1.xml`, `FIX50SP2.xml`, `fix-latest-minimal.xml` (FIX Latest).
- **Provider dictionary**: the file the user gave, once copied to `src/main/dictionaries/`.

Grep the file for `<message[ >]` with output mode `content`. Every start tag holds `name`, `msgtype` and `msgcat` on
one line, in any order. If a line lacks `name` or `msgtype`, the file wraps its attributes: read it around that line.

Offer only `msgcat="app"` messages, without BusinessMessageReject (35=j): the session layer sends and receives it, and
the build adds it to every cut. Group them by business area (orders, executions, quotes, market data, allocations,
reference data, and so on) so a list of a hundred reads at a glance, and ask which the application sends and which it
receives. A message can be both.
