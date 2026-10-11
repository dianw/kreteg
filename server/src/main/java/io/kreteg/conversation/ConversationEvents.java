package io.kreteg.conversation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * In-process fan-out of what happens in each conversation, for the SSE stream at
 * {@code /api/conversations/{id}/events}. The service publishes after its database write has committed. Listeners
 * must not block: each SSE subscriber only queues the event for its own connection.
 */
@ApplicationScoped
public class ConversationEvents {

    /** One event: its SSE name, its SSE id (a message's seq, otherwise null), and the payload sent as JSON. */
    public record Event(String type, Long id, Object data) {

        static Event message(Message message) {
            return new Event("message", message.seq(), message);
        }

        static Event status(MemberStatus status) {
            return new Event("status", null, status);
        }

        static Event receipt(String name, long readSeq, long doneSeq) {
            return new Event("receipt", null, new Receipt(name, readSeq, doneSeq));
        }

        static Event members(List<String> members) {
            return new Event("members", null, new Members(members));
        }
    }

    /** A member's current status line; a null {@code text} clears it. {@code at} is when it was set or cleared. */
    @RegisterForReflection
    public record MemberStatus(String name, String text, long at) {
    }

    /** A member's read and done positions, as {@code GET /api/conversations/{id}} reports them. */
    @RegisterForReflection
    public record Receipt(String name, long readSeq, long doneSeq) {
    }

    @RegisterForReflection
    public record Members(List<String> members) {
    }

    private final Map<String, Set<Consumer<Event>>> listeners = new ConcurrentHashMap<>();

    /** Starts delivering the conversation's events to {@code listener}; closing the result stops it. */
    public AutoCloseable subscribe(String conversationId, Consumer<Event> listener) {
        listeners.computeIfAbsent(conversationId, k -> new CopyOnWriteArraySet<>()).add(listener);
        return () -> listeners.computeIfPresent(conversationId, (k, set) -> {
            set.remove(listener);
            return set.isEmpty() ? null : set;
        });
    }

    /** Whether anyone is subscribed to the conversation, so callers can skip work nobody would see. */
    public boolean watched(String conversationId) {
        return listeners.containsKey(conversationId);
    }

    void publish(String conversationId, Event event) {
        Set<Consumer<Event>> set = listeners.get(conversationId);
        if (set != null) {
            set.forEach(listener -> listener.accept(event));
        }
    }
}
