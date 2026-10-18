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

The plugin is released with staffix, by the same workflow, and needs no edit:

- The reference projects' version and `staffix.version` become the release's, since they are in the reactor in CI.
- `plugin.json` and the bundled catalogue carry the release's version, which the skills write into the projects
  they create.
- The GitHub Release gets the plugin zip and the `marketplace.json` listing it, pinned by its SHA-256.
  `https://lolaf.org/claude/marketplace.json` redirects to the latest one, so users get it with
  `claude plugin update staffix@lolaf`.

Before releasing, check that `spring-boot.version` in `claude-plugin-reference-projects/spring-boot-initiator/pom.xml`
is staffix's own (root `pom.xml`): generated Spring Boot apps take it from there, and nothing compares the two.

To build the zip and `marketplace.json` locally:

```bash
./mvnw package -pl claude-plugin-archive -am -DskipTests
```
