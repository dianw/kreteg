package io.kreteg.bridge;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Task;
import io.a2a.spec.TaskNotCancelableError;
import io.a2a.spec.TextPart;

/**
 * Entry point for every A2A {@code message/send} and {@code message/stream} call.
 * Currently echoes the input; replace {@link #forward(String)} with the call to the bridged agent.
 */
@ApplicationScoped
public class BridgeAgentExecutor implements AgentExecutor {

    @Override
    public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        TaskUpdater updater = new TaskUpdater(context, eventQueue);
        if (context.getTask() == null) {
            updater.submit();
        }
        updater.startWork();

        String reply = forward(context.getUserInput("\n"));

        updater.addArtifact(List.of(new TextPart(reply)), null, "reply", null);
        updater.complete();
    }

    @Override
    public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        Task task = context.getTask();
        if (task.getStatus().state().isFinal()) {
            throw new TaskNotCancelableError();
        }
        new TaskUpdater(context, eventQueue).cancel();
    }

    private String forward(String input) {
        return "echo: " + input;
    }
}
