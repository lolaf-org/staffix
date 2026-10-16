# Staffix plugin for Claude Code

Skills that create a staffix application and migrate a QuickFIX/J application to staffix.

| Skill | What it does |
| :- | :- |
| `/staffix:new-app` | Creates a Maven project for a staffix initiator or acceptor |
| `/staffix:migrate-from-quickfixj` | Replaces QuickFIX/J in an existing application with staffix |

## Install

```
/plugin marketplace add https://lolaf.org/claude/marketplace.json
/plugin install staffix@lolaf
```

## Develop

Load the plugin from this directory, without installing it:

```bash
claude --plugin-dir claude-plugin
```

Check it before a release:

```bash
claude plugin validate --strict claude-plugin
```
