package io.kreteg.conversation;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * REST view of the same conversations, for clients that are not a model: the inbox watch loop, scripts, and the UI.
 * Agents use the MCP tools at {@code /mcp} instead.
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

    /** Only reached through the exception mapper, which native-image analysis doesn't see as a response type. */
    @RegisterForReflection
    public record ErrorBody(String error) {
    }

    private final ConversationService service;

    @Inject
    public ConversationResource(ConversationService service) {
        this.service = service;
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

    @POST
    @Path("/conversations/{id}/messages")
    public Message send(@PathParam("id") String id, SendRequest request) {
        return service.send(request.from(), id, request.text(), request.to(), request.replyTo());
    }

    @GET
    @Path("/conversations/{id}/messages")
    public List<Message> history(@PathParam("id") String id,
                                 @QueryParam("since") @DefaultValue("0") long since,
                                 @QueryParam("limit") @DefaultValue("100") int limit) {
        return service.history(id, since, limit);
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
