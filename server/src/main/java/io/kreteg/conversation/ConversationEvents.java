package io.kreteg.conversation;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;

/**
 * Publishes what happens on the hub to the Vert.x event bus, for the SSE stream at {@code /api/events}: hub-wide
 * events on {@link #HUB}, and each conversation's own on {@link #address(String)}. Each event is a JSON object with
 * its SSE {@code type}, its SSE {@code id} (a message's seq, otherwise null), and its {@code data} already serialized,
 * as the same JSON the REST API returns. The service calls these after its database write has committed.
 */
@ApplicationScoped
public class ConversationEvents {

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

    /** Conversations and participants changing. */
    static final String HUB = "kreteg.hub";

    /** One conversation's messages, statuses, receipts and members. */
    static String address(String conversationId) {
        return "kreteg.conversation." + conversationId;
    }

    private final EventBus bus;
    private final ObjectMapper json;

    @Inject
    public ConversationEvents(EventBus bus, ObjectMapper json) {
        this.bus = bus;
        this.json = json;
    }

    void message(Message message) {
        bus.publish(address(message.conversation()), event("message", message.seq(), message));
    }

    void status(String conversationId, MemberStatus status) {
        bus.publish(address(conversationId), event("status", null, status));
    }

    void receipt(String conversationId, String name, long readSeq, long doneSeq) {
        bus.publish(address(conversationId), event("receipt", null, new Receipt(name, readSeq, doneSeq)));
    }

    void members(String conversationId, List<String> members) {
        bus.publish(address(conversationId), event("members", null, new Members(members)));
    }

    void conversation(Conversation conversation) {
        bus.publish(HUB, event("conversation", null, conversation));
    }

    void participant(Participant participant) {
        bus.publish(HUB, event("participant", null, participant));
    }

    /** An event as published, also used for the replay at the start of a stream. */
    JsonObject event(String type, Long id, Object data) {
        try {
            return new JsonObject().put("type", type).put("id", id).put("data", json.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize a " + type + " event", e);
        }
    }
}
