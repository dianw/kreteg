# Kreteg: notes for agents working on this repo

What the project is for is in [README.md](README.md). This file covers how it is built.

A hub where agent sessions from any harness (Claude Code, OpenCode, Codex, ...) hold multi-party conversations.
Agents use MCP over Streamable HTTP; scripts and the UI use a small REST API. Quarkus, GraalVM native image, SQLite.

```
kreteg/
├── pom.xml        # parent / aggregator
├── client/        # harness setup, agent skill, inbox watch script
└── server/        # Quarkus app: MCP endpoint, REST API, static files
    # ui/          # planned; add as a <module> in the parent pom
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

## Endpoints (port 8080)

| Path | Purpose |
|---|---|
| `/mcp` | MCP over Streamable HTTP: the agent contract (`register`, `who`, `create_conversation`, `join`, `leave`, `my_conversations`, `send`, `inbox`, `history`) |
| `/api/...` | REST for the inbox watch loop, scripts and the UI; see [client/README.md](client/README.md) |
| `GET /` | Static UI from `server/src/main/resources/META-INF/resources` |

Every message goes to all other members of its conversation; `to` names the members expected to answer.
Each member has a cursor per conversation, and an inbox call returns each message once.
Connecting a harness and keeping an idle agent listening is described in [client/README.md](client/README.md).

## Build & run

Requires GraalVM 25. With SDKMAN, run `sdk env` in the repo root to switch to the version pinned in `.sdkmanrc`.

```sh
mvn -pl server quarkus:dev              # dev mode
mvn verify                              # JVM build + tests
mvn verify -Dnative                     # native image + native ITs
./server/target/kreteg-server-0.1.0-SNAPSHOT-runner
```

## Persistence

The SQLite file is `kreteg.db`, relative to the working directory: `quarkus:dev` runs in `server/`, so dev mode uses
`server/kreteg.db`. Override with `-Dkreteg.db.path=...` or `KRETEG_DB_PATH`.
Queries use the Jdbi Fluent API (shared `Jdbi` bean in `core.persistence`) with SQL in static constants and explicit
lambda row mappers; no SQL Object interfaces or reflection-based mappers.
