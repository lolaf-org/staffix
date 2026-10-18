# Listing a dictionary's messages

Use the Grep tool, or `grep -h -A2 '<message[ >]' <file>` in the shell when there is none: Claude Code always has a
shell, Git Bash on Windows included, so nothing has to be installed.

- **Shipped dictionary**: `${CLAUDE_PLUGIN_ROOT}/dictionaries/<file>`: `FIX42.xml`, `FIX43.xml`, `FIX44.xml`,
  `FIX50.xml`, `FIX50SP1.xml`, `FIX50SP2.xml`, `fix-latest-minimal.xml` (FIX Latest).
- **Provider dictionary**: the file the user gave, once copied to `src/main/dictionaries/`.

Search the file for `<message[ >]`, showing each match and the two lines after it. Every start tag holds `name`,
`msgtype` and `msgcat` on one line, in any order. If a line lacks `name` or `msgtype`, the file wraps its attributes:
read it around that line.

Offer only `msgcat="app"` messages, without BusinessMessageReject (35=j): the session layer sends and receives it, and
the build adds it to every cut. Group them by business area (orders, executions, quotes, market data, allocations,
reference data, and so on) so a list of a hundred reads at a glance. A message can be both sent and received.

## What a message is for

Each message of a shipped dictionary has a `<description>` right after its start tag: the business intent from the
FIX Orchestra repository, a synopsis then its elaborations, in markdown. Its first line, shown by the search, is
enough to group and shortlist; read the whole description of each message proposed, with the Read tool around its
line. It describes FIX Latest, so it may mention fields this version lacks: the message's structure is in the
dictionary, not in the description.

**A dictionary may have no descriptions**: a QuickFIX/J-format file, which is what most providers hand over, or any
file staffix did not generate. Look each message up by `msgtype` in the shipped dictionary of the same version (the
root element's `major`, `minor` and `servicepack`; `FIX50SP2.xml` for a 5.0 file with no match), and say that the
description comes from the standard, not from the provider. A provider's own message (a `msgtype` the shipped
dictionary lacks, often starting with `U`) has none: infer its purpose from its name and fields, say it is a guess,
and ask the user for the provider's documentation when it matters.

## Who sends it

No dictionary records a message's direction; its description names the role that sends it ("used by the order
submitter", "sent by the sell side"). That role is a business side, not a connection role: an acceptor can be the one
submitting orders. Take the user's side (buy side or sell side, client or venue) from the purpose, or ask, then mark
each message sent or received from it. A message the standard uses both ways (quote negotiation, allocations, trade
capture reports) is offered as a choice, not decided.

## Preselect from the purpose

Propose the messages the purpose needs, sent and received, each with why (from its description), and nothing more.
Take only names this dictionary has, and add what the counterparty answers to each message sent: "place and cancel
orders with a broker" sends NewOrderSingle and OrderCancelRequest, and receives ExecutionReport and
OrderCancelReject. The AskUserQuestion tool takes four options per question, too few for a dictionary, so show the
proposal and the grouped list as text and ask the user to accept it or name what to add or remove.
