# Staffix plugin for Claude Code

Skills that create a staffix application, change its components, and migrate a QuickFIX/J application to staffix.

| Skill | What it does |
| :- | :- |
| `/staffix:new-app` | Creates a Maven project for a staffix initiator or acceptor |
| `/staffix:configure` | Switches or adds a store, logger, monitoring, alerting or admin component, or an environment |
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

`catalogue` links to the components catalogue the build generates, so build it first:

```bash
./mvnw install -pl build-tools/components-catalogue -am -DskipTests
```

`skills/new-app/reference-projects` links to `claude-plugin-reference-projects`, the projects `new-app` starts from.
They join the reactor in the `reference-projects` profile, on by itself in CI, so they build against the current
staffix. To run them locally:

```bash
./mvnw install -pl claude-plugin-reference-projects -am -Preference-projects
```

Check it before a release:

```bash
claude plugin validate --strict claude-plugin
```

## Release

A staffix release attaches the plugin zip, symlinks replaced by their content and the version set to staffix's, and
the `marketplace.json` listing it to its GitHub Release. The entry points at the zip there, pinned by its SHA-256, so
`marketplace.json` is the only file to put on `https://lolaf.org/claude/`. To build both locally:

```bash
./mvnw package -pl claude-plugin-archive -am -DskipTests
```
