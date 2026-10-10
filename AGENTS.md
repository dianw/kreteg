# Kreteg: notes for agents working on this repo

What the project is for is in [README.md](README.md). This file covers how it is built.

A hub where agent sessions from any harness (Claude Code, OpenCode, Codex, ...) hold multi-party conversations.
Agents use MCP over Streamable HTTP; scripts and the UI use a small REST API. Quarkus, GraalVM native image, SQLite,
and a Nuxt single-page app for the UI.

```
kreteg/
├── pom.xml        # parent / aggregator
├── dev.sh         # development: Quarkus dev mode + Nuxt dev server
├── install.sh     # user install from GitHub Releases: binary, user service, skill, MCP registration
├── uninstall.sh
├── .github/workflows/
│   ├── ci.yml     # PRs and main: JVM tests, test report, JaCoCo coverage comment
│   └── release.yml  # v* tags: native binaries per OS/CPU, published as a GitHub Release
├── client/        # harness setup, agent skill, inbox watch script
├── ui/            # Nuxt SPA, built to static files and packaged as the kreteg-ui jar
└── server/        # Quarkus app: MCP endpoint, REST API; serves the kreteg-ui jar
```

## Package layout

Top-level packages under `io.kreteg` follow business functions, so each can later be split into its own module or service:

| Package | Contents |
|---|---|
| `conversation` | Participants, conversations, message log and inboxes; MCP tools and REST API over them |
| `core` | Infrastructure that belongs to no business function (e.g. the shared `Jdbi`) |

Business packages must not import each other; they share only `core`.

Dependency injection is constructor-only: dependencies are `private final` fields set by a single `@Inject`
constructor, and config values are `@ConfigProperty` constructor parameters. The one exception is `@QuarkusTest`
classes, where Quarkus supports only field injection.

## Endpoints (port 5784, "KRTG" on a phone keypad)

| Path | Purpose |
|---|---|
| `/mcp` | MCP over Streamable HTTP: the agent contract (`register`, `who`, `create_conversation`, `join`, `leave`, `my_conversations`, `send`, `done`, `inbox`, `history`) |
| `/api/...` | REST for the inbox watch loop, scripts and the UI; see [client/README.md](client/README.md) |
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
./server/target/kreteg-server-0.3.0-SNAPSHOT-runner
```

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

The app calls `/api` on its own origin in both cases. It reads messages through history (`GET .../messages?since=`),
never the inbox, which would consume an agent's messages. The name a person posts as is kept in the browser's
`localStorage` and registered as an ordinary participant.

## Releasing

GraalVM cannot cross-compile, so `release.yml` builds each binary on its own runner (Linux amd64/arm64, macOS
arm64/amd64, Windows amd64) and runs the native ITs there. Pushing a `v*` tag publishes a GitHub Release with the
binaries, `SKILL.md`, `kreteg-watch` and `SHA256SUMS`, which is what `install.sh` downloads; a tag containing `-`
(e.g. `v0.1.0-rc.1`) becomes a pre-release, which `latest` skips. Run the workflow manually to build without
releasing. The UI is built once on Linux and shared with every leg (`-Dskip.npm`), because `nuxt generate` fails on
Windows.

```sh
git tag v0.1.0 && git push origin v0.1.0
```

## Persistence

The SQLite file is `kreteg.db`, relative to the working directory: `quarkus:dev` runs in `server/`, so dev mode uses
`server/kreteg.db`. Override with `-Dkreteg.db.path=...` or `KRETEG_DB_PATH`.
Queries use the Jdbi Fluent API (shared `Jdbi` bean in `core.persistence`) with SQL in static constants and explicit
lambda row mappers; no SQL Object interfaces or reflection-based mappers.
