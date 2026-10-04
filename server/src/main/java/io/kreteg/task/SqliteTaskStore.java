package io.kreteg.task;

import java.io.UncheckedIOException;
import java.util.List;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.util.Utils;
import io.quarkus.runtime.StartupEvent;
import org.jdbi.v3.core.Jdbi;

/**
 * Persists A2A tasks in SQLite as JSON. Replaces the SDK's {@code InMemoryTaskStore}.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class SqliteTaskStore implements TaskStore, TaskStateProvider {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS a2a_task (
                id          TEXT PRIMARY KEY,
                context_id  TEXT NOT NULL,
                state       TEXT,
                payload     TEXT NOT NULL,
                created_at  INTEGER NOT NULL DEFAULT (unixepoch('subsec') * 1000),
                updated_at  INTEGER NOT NULL DEFAULT (unixepoch('subsec') * 1000)
            );
            CREATE INDEX IF NOT EXISTS a2a_task_context_idx ON a2a_task (context_id);
            CREATE INDEX IF NOT EXISTS a2a_task_updated_idx ON a2a_task (updated_at);
            """;

    private static final String UPSERT = """
            INSERT INTO a2a_task (id, context_id, state, payload) VALUES (?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                context_id = excluded.context_id,
                state      = excluded.state,
                payload    = excluded.payload,
                updated_at = unixepoch('subsec') * 1000
            """;

    private static final String DELETE_BY_ID = "DELETE FROM a2a_task WHERE id = ?";

    private static final String SELECT_PAYLOAD_BY_ID = "SELECT payload FROM a2a_task WHERE id = ?";

    private static final String SELECT_RECENT =
            "SELECT id, context_id, state, created_at, updated_at FROM a2a_task ORDER BY updated_at DESC LIMIT ?";

    private final Jdbi jdbi;

    @Inject
    public SqliteTaskStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    void onStart(@Observes StartupEvent event) {
        jdbi.useHandle(h -> {
            for (String ddl : SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    h.execute(ddl);
                }
            }
        });
    }

    @Override
    public void save(Task task) {
        jdbi.useHandle(h -> h.createUpdate(UPSERT)
                .bind(0, task.getId())
                .bind(1, task.getContextId())
                .bind(2, stateOf(task) == null ? null : stateOf(task).asString())
                .bind(3, toJson(task))
                .execute());
    }

    @Override
    public Task get(String taskId) {
        String json = findJson(taskId);
        return json == null ? null : fromJson(json);
    }

    @Override
    public void delete(String taskId) {
        jdbi.useHandle(h -> h.createUpdate(DELETE_BY_ID).bind(0, taskId).execute());
    }

    @Override
    public boolean isTaskActive(String taskId) {
        Task task = get(taskId);
        if (task == null) {
            return false;
        }
        TaskState state = stateOf(task);
        return state == null || !state.isFinal();
    }

    @Override
    public boolean isTaskFinalized(String taskId) {
        Task task = get(taskId);
        if (task == null) {
            return false;
        }
        TaskState state = stateOf(task);
        return state != null && state.isFinal();
    }

    /** Raw task JSON, as served to the UI. */
    public String findJson(String taskId) {
        return jdbi.withHandle(h -> h.createQuery(SELECT_PAYLOAD_BY_ID)
                .bind(0, taskId)
                .mapTo(String.class)
                .findOne()
                .orElse(null));
    }

    /** Most recently updated tasks first. */
    public List<TaskSummary> listRecent(int limit) {
        return jdbi.withHandle(h -> h.createQuery(SELECT_RECENT)
                .bind(0, limit)
                .map((rs, ctx) -> new TaskSummary(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5)))
                .list());
    }

    private static TaskState stateOf(Task task) {
        return task.getStatus() == null ? null : task.getStatus().state();
    }

    private static String toJson(Task task) {
        try {
            return Utils.OBJECT_MAPPER.writeValueAsString(task);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Task fromJson(String json) {
        try {
            return Utils.OBJECT_MAPPER.readValue(json, Task.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
