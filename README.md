# Kreteg

A2A bridge agent on Quarkus, compiled to a GraalVM native image, with SQLite persistence.

```
kreteg/
├── pom.xml        # parent / aggregator
└── server/        # Quarkus app: A2A endpoint, UI API, static files
    # ui/          # planned; add as a <module> in the parent pom
```

## Package layout

Top-level packages under `io.kreteg` follow business functions, so each can later be split into its own module or service:

| Package | Contents |
|---|---|
| `bridge` | A2A agent: agent card and executor |
| `task` | A2A task persistence (SQLite) and the UI task API |
| `core` | Infrastructure that belongs to no business function (e.g. native-image registrations) |

Business packages must not import each other; they share only the A2A SDK and `core`.

Dependency injection is constructor-only: dependencies are `private final` fields set by a single `@Inject`
constructor, and config values are `@ConfigProperty` constructor parameters. The one exception is `@QuarkusTest`
classes, where Quarkus supports only field injection.

## Endpoints (port 8080)

| Path | Purpose |
|---|---|
| `GET /.well-known/agent-card.json` | A2A agent card |
| `POST /` | A2A JSON-RPC (`message/send`, `message/stream`, `tasks/get`, `tasks/cancel`, ...) |
| `GET /api/tasks`, `GET /api/tasks/{id}` | Task API for the UI |
| `GET /` | Static UI from `server/src/main/resources/META-INF/resources` |

## Build & run

Requires GraalVM 25 (`sdk use java 25.0.2-graalce`).

```sh
mvn -pl server quarkus:dev              # dev mode
mvn verify                              # JVM build + tests
mvn verify -Dnative                     # native image + native ITs
./server/target/kreteg-server-0.1.0-SNAPSHOT-runner
```

The SQLite file defaults to `./kreteg.db`; override with `-Dkreteg.db.path=...` or `KRETEG_DB_PATH`.
Queries use the Jdbi Fluent API (shared `Jdbi` bean in `core.persistence`) with SQL in static constants and explicit
lambda row mappers; no SQL Object interfaces or reflection-based mappers.

## Where to plug in the bridged agent

`server/src/main/java/io/kreteg/bridge/BridgeAgentExecutor.java` (`forward(...)` currently echoes).

## Native-image notes

The A2A SDK ships no native metadata. `io.kreteg.core.nativeimage.A2aNativeFeature` registers all `io.a2a.spec`
classes for reflection plus the JSON-RPC transport provider, and `application.properties` includes the SDK's
classpath resources. Keep these in mind when upgrading the SDK.
