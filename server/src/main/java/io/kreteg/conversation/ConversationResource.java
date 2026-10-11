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

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
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
    private final EventBus bus;
    private final Duration pingInterval;

    @Inject
    public ConversationResource(ConversationService service, ConversationEvents events, EventBus bus,
                                @ConfigProperty(name = "kreteg.events.ping-interval") Duration pingInterval) {
        this.service = service;
        this.events = events;
        this.bus = bus;
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
     * The hub as a stream of server-sent events, one per browser tab: {@code conversation} and {@code participant}
     * events for the whole hub, and with {@code conversation}, that conversation's messages after {@code since} (or
     * after the {@code Last-Event-ID} a reconnecting {@code EventSource} sends, which wins), its current status lines,
     * and then its {@code message}, {@code status}, {@code receipt} and {@code members} events as they happen. A ping
     * comment goes out every {@code kreteg.events.ping-interval}. Hub-wide events have no replay: clients fetch the
     * conversation and participant lists when the stream opens. Blocking, because the replay reads the database.
     */
    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    @Blocking
    public Multi<OutboundSseEvent> events(@QueryParam("conversation") String conversationId,
                                          @QueryParam("since") Long since,
                                          @HeaderParam("Last-Event-ID") String lastEventId,
                                          @Context Sse sse) {
        String id = conversationId == null || conversationId.isBlank() ? null : conversationId.strip();
        if (id != null) {
            service.conversation(id);  // 404 before the stream starts
        }
        long after = lastEventId != null && lastEventId.strip().matches("\\d+")
                ? Long.parseLong(lastEventId.strip())
                : since == null ? 0 : Math.max(0, since);

        // Listen before reading the replay, so nothing committed in between is missed; what the replay already
        // holds is dropped from the live side by seq
        UnicastProcessor<JsonObject> live = UnicastProcessor.create();
        List<MessageConsumer<JsonObject>> consumers = new ArrayList<>();
        for (String address : id == null ? List.of(ConversationEvents.HUB)
                : List.of(ConversationEvents.HUB, ConversationEvents.address(id))) {
            consumers.add(bus.<JsonObject>localConsumer(address, message -> {
                synchronized (live) {
                    live.onNext(message.body());
                }
            }));
        }
        Runnable unsubscribe = () -> consumers.forEach(MessageConsumer::unregister);

        List<JsonObject> replay = new ArrayList<>();
        long last = after;
        if (id != null) {
            try {
                for (List<Message> page = service.history(id, after, REPLAY_PAGE); !page.isEmpty();
                        page = service.history(id, last, REPLAY_PAGE)) {
                    for (Message message : page) {
                        replay.add(events.event("message", message.seq(), message));
                        last = message.seq();
                    }
                }
                service.statuses(id).forEach(status -> replay.add(events.event("status", null, status)));
            } catch (RuntimeException e) {
                unsubscribe.run();  // no stream will ever be returned to do it
                throw e;
            }
        }
        long replayedTo = last;

        Multi<JsonObject> stream = Multi.createBy().concatenating().streams(
                Multi.createFrom().iterable(replay),
                live.filter(event -> event.getLong("id") == null || event.getLong("id") > replayedTo));
        Multi<OutboundSseEvent> pings = Multi.createFrom().ticks().every(pingInterval)
                .map(tick -> sse.newEventBuilder().comment("ping").build());
        return Multi.createBy().merging().streams(stream.map(event -> toSse(sse, event)), pings)
                .onTermination().invoke(unsubscribe);
    }

    private static OutboundSseEvent toSse(Sse sse, JsonObject event) {
        OutboundSseEvent.Builder builder = sse.newEventBuilder()
                .name(event.getString("type"))
                .data(event.getString("data"));
        Long id = event.getLong("id");
        if (id != null) {
            builder.id(Long.toString(id));
        }
        return builder.build();
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
