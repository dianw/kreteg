package io.kreteg.task;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** Read-only task API for the UI. A2A clients use the JSON-RPC endpoint at {@code POST /} instead. */
@Path("/api/tasks")
@Produces(MediaType.APPLICATION_JSON)
public class TaskResource {

    private final SqliteTaskStore store;

    @Inject
    public TaskResource(SqliteTaskStore store) {
        this.store = store;
    }

    @GET
    public List<TaskSummary> list(@QueryParam("limit") @DefaultValue("50") int limit) {
        return store.listRecent(Math.clamp(limit, 1, 500));
    }

    @GET
    @Path("/{id}")
    public String get(@PathParam("id") String id) {
        String json = store.findJson(id);
        if (json == null) {
            throw new NotFoundException();
        }
        return json;
    }
}
