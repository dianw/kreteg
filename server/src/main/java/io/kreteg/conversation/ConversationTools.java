package io.kreteg.conversation;

import java.util.List;

import jakarta.inject.Inject;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;

/**
 * MCP tools served over Streamable HTTP at {@code /mcp}: the contract agents of any harness use. Each tool takes the
 * caller's participant name explicitly, so identity survives reconnects and behaves the same in every client.
 */
@WrapBusinessError(ConversationException.class)
public class ConversationTools {

    private static final String ME = "Your participant name, as registered";

    private static final int MAX_TOOL_WAIT_SECONDS = 30;

    // Lists are wrapped so a tool always returns one JSON object; a bare empty list would come back as no content

    public record Participants(List<Participant> participants) {
    }

    public record Conversations(List<Conversation> conversations) {
    }

    public record Messages(List<Message> messages) {
    }

    private final ConversationService service;

    @Inject
    public ConversationTools(ConversationService service) {
        this.service = service;
    }

    @Tool(description = """
            Register yourself on Kreteg under a short, stable, descriptive name (e.g. 'backend', 'reviewer'), or \
            refresh an existing registration. Call this once before any other tool, and tell the user the name you \
            picked so others can address you.""")
    public Participant register(@ToolArg(description = ME) String me,
                                @ToolArg(description = "What you are working on, shown to other participants",
                                        required = false) String description) {
        return service.register(me, description);
    }

    @Tool(description = """
            List registered participants with their status: 'live' (seen within a minute), 'idle' (within 15 \
            minutes) or 'stale'. Stale participants may still read messages later.""")
    public Participants who() {
        return new Participants(service.participants());
    }

    @Tool(name = "create_conversation", description = """
            Start a conversation with other registered participants. You are added automatically. A message reaches \
            the inboxes of the members named in its 'to', or of every member when 'to' is empty; history shows every \
            message to every member.""")
    public Conversation createConversation(@ToolArg(description = ME) String me,
                                           @ToolArg(description = "Short topic of the conversation") String title,
                                           @ToolArg(description = "Names of the other participants to add",
                                                   required = false) List<String> members) {
        return service.create(me, title, members);
    }

    @Tool(description = "Join an existing conversation. You receive messages sent after you join; use history for "
            + "earlier ones.")
    public Conversation join(@ToolArg(description = ME) String me,
                             @ToolArg(description = "Conversation id, e.g. c-1a2b3c4d5e6f") String conversation) {
        return service.join(me, conversation);
    }

    @Tool(description = "Leave a conversation. Its history is kept.")
    public String leave(@ToolArg(description = ME) String me,
                        @ToolArg(description = "Conversation id") String conversation) {
        service.leave(me, conversation);
        return "Left " + conversation;
    }

    @Tool(name = "my_conversations", description = "List the conversations you are a member of, with their members.")
    public Conversations myConversations(@ToolArg(description = ME) String me) {
        return new Conversations(service.conversations(me));
    }

    @Tool(description = """
            Send a message to a conversation. If 'to' names members, only they receive it in their inbox, so name \
            everyone who needs to act on it or know about it; leave 'to' out for an announcement every other member \
            receives. Everyone can still read it with history. When answering a message, set 'reply_to' to its id. \
            Put one request per message, with enough context to act on it without asking back.""")
    public Message send(@ToolArg(description = ME) String me,
                        @ToolArg(description = "Conversation id") String conversation,
                        @ToolArg(description = "Message text") String text,
                        @ToolArg(description = "Members expected to answer", required = false) List<String> to,
                        @ToolArg(name = "reply_to", description = "Id of the message this answers",
                                required = false) String replyTo) {
        return service.send(me, conversation, text, to, replyTo);
    }

    @Tool(description = """
            Fetch your new messages from all your conversations, oldest first: those that name you in 'to', and \
            announcements with no 'to'. Messages addressed only to others are not delivered here; read them with \
            history. Each message is returned only once. \
            If nothing is waiting, blocks up to 'wait_seconds' for the first message. Answer messages that name you \
            in 'to', even if only to say you cannot help: the sender is waiting.""")
    public Messages inbox(@ToolArg(description = ME) String me,
                               @ToolArg(name = "wait_seconds", description = "Seconds to wait when empty, 0-30",
                                       defaultValue = "0") int waitSeconds) {
        return new Messages(service.inbox(me, Math.clamp(waitSeconds, 0, MAX_TOOL_WAIT_SECONDS), 50));
    }

    @Tool(description = "Read a conversation's messages, oldest first, including ones already delivered to you and "
            + "ones addressed only to others.")
    public Messages history(@ToolArg(description = "Conversation id") String conversation,
                                 @ToolArg(description = "Only messages with seq greater than this",
                                         defaultValue = "0") long since) {
        return new Messages(service.history(conversation, since, 100));
    }
}
