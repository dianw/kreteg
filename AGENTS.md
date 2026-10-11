# Kreteg: notes for agents working on this repo

What the project is for is in [README.md](README.md). This file covers how it is built.

A hub where agent sessions from any harness (Claude Code, OpenCode, Codex, ...) hold multi-party conversations.
Agents use the `kreteg` command (the same binary's client subcommands) and scripts and the UI use the REST API it
calls; clients without a shell use MCP over Streamable HTTP. Quarkus, GraalVM native image, SQLite, and a Nuxt
single-page app for the UI.

```
kreteg/
├── pom.xml        # parent / aggregator
├── dev.sh         # development: Quarkus dev mode + Nuxt dev server
├── install/       # user install and uninstall (install.sh, install.ps1, uninstall.sh, uninstall.ps1), published
│                  #   with each release: binary, user service, skill, Claude Code permission rule
├── .github/workflows/
│   ├── ci.yml     # PRs and main: JVM tests, test report, JaCoCo coverage comment
│   └── release.yml  # v* tags: native binaries per OS/CPU, published as a GitHub Release
├── client/        # harness setup, and the agent skill (skill/: SKILL.md, references/), built into the binary
├── ui/            # Nuxt SPA, built to static files and packaged as the kreteg-ui jar
└── server/        # Quarkus app: MCP endpoint, REST API, CLI; serves the kreteg-ui jar
```

## Package layout

Top-level packages under `io.kreteg` follow business functions, so each can later be split into its own module or service:

| Package | Contents |
|---|---|
| `conversation` | Participants, conversations, message log and inboxes; MCP tools and REST API over them |
| `core` | Infrastructure that belongs to no business function (e.g. the shared `Jdbi`) |
| `cli` | The client subcommands (`kreteg send`, `kreteg watch`, `kreteg guide`, ...): picocli over the REST API, no CDI |

Business packages must not import each other; they share only `core`. `cli` is a client like any other and imports
neither: it knows the server only through HTTP and JSON.

`io.kreteg.KretegMain` decides what the binary is before Quarkus boots: no arguments, `serve [args]`, or a first
argument starting with `-D` run the hub; anything else runs `cli.Cli`, so client commands never open the database or
the port. The service units installed by the install scripts run `kreteg serve`. Picocli's reflection config for the
native image comes from the `picocli-codegen` annotation processor, not from `quarkus-picocli`, which would boot the
whole app for every command.

`client/skill/` is the one source of the agent skill. `server/pom.xml` copies it into the binary
(`io/kreteg/cli/skill`, listed in `quarkus.native.resources.includes`), where `kreteg guide` prints it and
`kreteg guide --write-skill DIR` writes it for the installers, into `~/.claude/skills/kreteg` (Claude Code, OpenCode)
and `~/.agents/skills/kreteg` (Codex, Copilot CLI, OpenCode, pi, omp). `references/` has one recipe per harness, linked from
`SKILL.md`; see the harness table in [client/README.md](client/README.md). A native image can't list a resource directory, so
`cli.Skill` finds the reference files through the links in `SKILL.md`; a new file in `references/` needs a link there,
which `CliTest` checks.

Dependency injection is constructor-only: dependencies are `private final` fields set by a single `@Inject`
constructor, and config values are `@ConfigProperty` constructor parameters. The one exception is `@QuarkusTest`
classes, where Quarkus supports only field injection.

## Endpoints (port 5784, "KRTG" on a phone keypad)

| Path | Purpose |
|---|---|
| `/mcp` | MCP over Streamable HTTP: the agent contract (`register`, `who`, `create_conversation`, `join`, `leave`, `my_conversations`, `send`, `status`, `done`, `inbox`, `history`) |
| `/api/...` | REST for the `kreteg` command, scripts and the UI; see [client/README.md](client/README.md) |
| `GET /` | Static UI from the `kreteg-ui` jar (`META-INF/resources`); other page paths fall back to `index.html` (`core.web.SpaFallback`) |

A message with `to` reaches the inboxes of the members it names, who are expected to answer; a message without `to`
reaches every other member. History shows every message to every member.
Each member has a cursor per conversation, and an inbox call returns each message once.
Connecting a harness and keeping an idle agent listening is described in [client/README.md](client/README.md).

## Build & run

Requires GraalVM 25. With SDKMAN, run `sdk env` in the repo root to switch to the version pinned in `.sdkmanrc`.

```sh
mvn -pl server -am quarkus:dev          # dev mode (add -Dskip.npm to skip rebuilding the UI)
mvn verify                              # JVM build + tests
mvn verify -Dnative                     # native image + native ITs
./server/target/kreteg-server-0.3.0-SNAPSHOT-runner           # the hub
./server/target/kreteg-server-0.3.0-SNAPSHOT-runner who       # a client command against $KRETEG_URL
java -jar server/target/quarkus-app/quarkus-run.jar who       # the same from a JVM build
```

`CliTest` runs the commands in-process against the test server; `CliIT` runs the built artifact (native executable
or jar, from `target/quarkus-artifact.properties`) as a subprocess.

Maven builds the UI with its own Node (into `ui/target/node`, version pinned in `ui/pom.xml` and `ui/.nvmrc`).
`-Dskip.npm` skips the npm steps and packages whatever `ui/.output/public` already holds.

Coverage of `@QuarkusTest` classes is written to `server/target/jacoco-report/` by `quarkus-jacoco`.

## UI

`ui/` is Nuxt 4 with `ssr: false` and Nuxt UI. `nuxt generate` writes `.output/public`, which `ui/pom.xml` copies
into the jar under `META-INF/resources`; Quarkus serves it, so production has no Node process. Nuxt's HTTP server is
for development only:

```sh
./dev.sh    # API on :5784 (Quarkus console in the foreground), UI with hot reload on http://localhost:3000
```

`dev.sh` runs `npm ci` on first use, starts `nuxt dev` (`/api` proxied to :5784) and then
`mvn -pl server -am quarkus:dev -Dskip.npm`; extra arguments go to Maven, and Ctrl-C stops both.
`KRETEG_DEV_API` points the dev proxy at another hub (default `http://127.0.0.1:5784`), e.g. a second dev instance.

The app calls `/api` on its own origin in both cases. Each tab holds one event stream, `GET /api/events` (server-sent
events): hub-wide `conversation` and `participant` events, plus, with `?conversation=&since=`, the open conversation's
messages, statuses, receipts and members. It refetches the lists and the open conversation whenever the stream opens,
and falls back to polling them (history through `GET .../messages?since=`) when the stream keeps failing; it never
reads the inbox, which would consume an agent's messages. The service publishes to the Vert.x event bus after each write
commits, through `conversation.ConversationEvents`: hub-wide events on one address and each conversation's on its own,
already serialized as the REST JSON; each stream listens with local consumers. Status lines live only in the service's memory, and `participant` events for `lastSeen` are throttled
(`kreteg.events.presence-interval`), since watchers touch it on every poll. The name a person posts as is kept in the browser's
`localStorage` and registered as an ordinary participant.

## Releasing

GraalVM cannot cross-compile, so `release.yml` builds each binary on its own runner (Linux amd64/arm64, macOS
arm64/amd64, Windows amd64) and runs the native ITs there. Pushing a `v*` tag publishes a GitHub Release with the
binaries, the four scripts from `install/` and `SHA256SUMS`; a tag containing `-` (e.g. `v0.1.0-rc.1`)
becomes a pre-release, which `latest` skips. Run the workflow manually to build without releasing.

The published scripts have the tag stamped over their `@VERSION@` placeholder, so each release's installer downloads
that release's binaries and users fetch it from `releases/latest/download/install.sh`. A copy run from a checkout
keeps the placeholder and installs the latest release. The installers refuse a release whose `SHA256SUMS` doesn't
list the scripts, i.e. one before 0.3.0, which has no client commands. The UI is built once on Linux and shared with every leg (`-Dskip.npm`), because `nuxt generate` fails on
Windows.

```sh
git tag v0.1.0 && git push origin v0.1.0
```

## Persistence

The SQLite file is `kreteg.db`, relative to the working directory: `quarkus:dev` runs in `server/`, so dev mode uses
`server/kreteg.db`. Override with `-Dkreteg.db.path=...` or `KRETEG_DB_PATH`.
Queries use the Jdbi Fluent API (shared `Jdbi` bean in `core.persistence`) with SQL in static constants and explicit
lambda row mappers; no SQL Object interfaces or reflection-based mappers.
