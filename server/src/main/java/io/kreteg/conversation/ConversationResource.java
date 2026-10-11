package io.kreteg.conversation;

import static io.kreteg.conversation.ConversationException.Reason.INVALID;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kreteg.conversation.ConversationEvents.Event;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * REST view of the conversations, for the {@code kreteg} command (which agents run), scripts, and the UI. Clients
 * without a shell use the MCP tools at {@code /mcp} instead.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConversationResource {

    public record RegisterRequest(String name, String description) {
    }

    public record CreateRequest(String from, String title, List<String> members) {
    }

    public record JoinRequest(String name) {
    }

    public record SendRequest(String from, String text, List<String> to, String replyTo) {
    }

    public record DoneRequest(String name) {
    }

    public record StatusRequest(String name, String text) {
    }

    /** Only reached through the exception mapper, which native-image analysis doesn't see as a response type. */
    @RegisterForReflection
    public record ErrorBody(String error) {
    }

    /** History is replayed in pages of this size when a stream starts. */
    private static final int REPLAY_PAGE = 500;

    private final ConversationService service;
    private final ConversationEvents events;
    private final ObjectMapper json;
    private final Duration pingInterval;

    @Inject
    public ConversationResource(ConversationService service, ConversationEvents events, ObjectMapper json,
                                @ConfigProperty(name = "kreteg.events.ping-interval") Duration pingInterval) {
        this.service = service;
        this.events = events;
        this.json = json;
        this.pingInterval = pingInterval;
    }

    @POST
    @Path("/participants")
    public Participant register(RegisterRequest request) {
        return service.register(request.name(), request.description());
    }

    @GET
    @Path("/participants")
    public List<Participant> participants() {
        return service.participants();
    }

    @GET
    @Path("/participants/{name}/inbox")
    public List<Message> inbox(@PathParam("name") String name,
                               @QueryParam("wait") @DefaultValue("0") int waitSeconds,
                               @QueryParam("limit") @DefaultValue("50") int limit) {
        return service.inbox(name, waitSeconds, limit);
    }

    @POST
    @Path("/conversations")
    public Conversation create(CreateRequest request) {
        return service.create(request.from(), request.title(), request.members());
    }

    @GET
    @Path("/conversations")
    public List<Conversation> conversations(@QueryParam("member") String member) {
        return service.conversations(member);
    }

    @GET
    @Path("/conversations/{id}")
    public ConversationDetail conversation(@PathParam("id") String id) {
        return service.conversation(id);
    }

    @POST
    @Path("/conversations/{id}/members")
    public Conversation join(@PathParam("id") String id, JoinRequest request) {
        return service.join(request.name(), id);
    }

    @DELETE
    @Path("/conversations/{id}/members/{name}")
    public void leave(@PathParam("id") String id, @PathParam("name") String name) {
        service.leave(name, id);
    }

    @POST
    @Path("/conversations/{id}/messages")
    public Message send(@PathParam("id") String id, SendRequest request) {
        return service.send(request.from(), id, request.text(), request.to(), request.replyTo());
    }

    @POST
    @Path("/conversations/{id}/messages/{message}/done")
    public void done(@PathParam("id") String id, @PathParam("message") String message, DoneRequest request) {
        service.done(request.name(), id, message);
    }

    /**
     * Messages after {@code since} (default 0), or with {@code before} instead, the last ones below that seq; both are
     * returned oldest first. Open a conversation at its end with {@code before=9223372036854775807}.
     */
    @GET
    @Path("/conversations/{id}/messages")
    public List<Message> history(@PathParam("id") String id,
                                 @QueryParam("since") Long since,
                                 @QueryParam("before") Long before,
                                 @QueryParam("limit") @DefaultValue("100") int limit) {
        if (before == null) {
            return service.history(id, since == null ? 0 : since, limit);
        }
        if (since != null) {
            throw new ConversationException(INVALID, "Use either since or before, not both");
        }
        return service.historyBefore(id, before, limit);
    }

    @POST
    @Path("/conversations/{id}/status")
    public void status(@PathParam("id") String id, StatusRequest request) {
        service.status(request.name(), id, request.text());
    }

    /**
     * The conversation as a stream of server-sent events: first the messages after {@code since} (or after the
     * {@code Last-Event-ID} a reconnecting {@code EventSource} sends, which wins), then the current status lines, then
     * {@code message}, {@code status}, {@code receipt} and {@code members} events as they happen, with a ping comment
     * every {@code kreteg.events.ping-interval}. Blocking, because the replay reads the database.
     */
    @GET
    @Path("/conversations/{id}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    @Blocking
    public Multi<OutboundSseEvent> events(@PathParam("id") String id,
                                          @QueryParam("since") Long since,
                                          @HeaderParam("Last-Event-ID") String lastEventId,
                                          @Context Sse sse) {
        service.conversation(id);  // 404 before the stream starts
        long after = lastEventId != null && lastEventId.strip().matches("\\d+")
                ? Long.parseLong(lastEventId.strip())
                : since == null ? 0 : Math.max(0, since);

        // Subscribe before reading the replay, so nothing committed in between is missed; what the replay already
        // holds is dropped from the live side by seq
        UnicastProcessor<Event> live = UnicastProcessor.create();
        AutoCloseable subscription = events.subscribe(id, event -> {
            synchronized (live) {
                live.onNext(event);
            }
        });
        List<Event> replay = new ArrayList<>();
        long last = after;
        try {
            for (List<Message> page = service.history(id, after, REPLAY_PAGE); !page.isEmpty();
                    page = service.history(id, last, REPLAY_PAGE)) {
                for (Message message : page) {
                    replay.add(Event.message(message));
                    last = message.seq();
                }
            }
            service.statuses(id).forEach(status -> replay.add(Event.status(status)));
        } catch (RuntimeException e) {
            // No stream will ever be returned to close it
            close(subscription);
            throw e;
        }
        long replayedTo = last;

        Multi<Event> conversation = Multi.createBy().concatenating().streams(
                Multi.createFrom().iterable(replay),
                live.filter(event -> event.id() == null || event.id() > replayedTo));
        Multi<OutboundSseEvent> pings = Multi.createFrom().ticks().every(pingInterval)
                .map(tick -> sse.newEventBuilder().comment("ping").build());
        return Multi.createBy().merging().streams(conversation.map(event -> toSse(sse, event)), pings)
                .onTermination().invoke(() -> close(subscription));
    }

    private OutboundSseEvent toSse(Sse sse, Event event) {
        OutboundSseEvent.Builder builder = sse.newEventBuilder().name(event.type());
        if (event.id() != null) {
            builder.id(Long.toString(event.id()));
        }
        try {
            // Serialized here, with the same mapper as the JSON responses, so the data is written as is
            return builder.data(json.writeValueAsString(event.data())).build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize a " + event.type() + " event", e);
        }
    }

    private static void close(AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @ServerExceptionMapper
    public RestResponse<ErrorBody> map(ConversationException e) {
        Status status = switch (e.reason()) {
            case NOT_FOUND -> Status.NOT_FOUND;
            case FORBIDDEN -> Status.FORBIDDEN;
            case INVALID -> Status.BAD_REQUEST;
        };
        return RestResponse.status(status, new ErrorBody(e.getMessage()));
    }
}
