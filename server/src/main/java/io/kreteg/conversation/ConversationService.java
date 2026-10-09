package io.kreteg.conversation;

import static io.kreteg.conversation.ConversationException.Reason.FORBIDDEN;
import static io.kreteg.conversation.ConversationException.Reason.INVALID;
import static io.kreteg.conversation.ConversationException.Reason.NOT_FOUND;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Multi-party conversations between registered participants. Every message goes to all other members of its
 * conversation; {@code to} only marks whom the sender expects an answer from. Both the MCP tools and the REST API
 * delegate here.
 */
@ApplicationScoped
public class ConversationService {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private static final int MAX_LIMIT = 500;

    private final ConversationStore store;
    private final int maxWaitSeconds;

    /** One pending signal per participant blocked in {@link #inbox}; completed and removed when mail arrives. */
    private final Map<String, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();

    @Inject
    public ConversationService(ConversationStore store,
                               @ConfigProperty(name = "kreteg.inbox.max-wait-seconds") int maxWaitSeconds) {
        this.store = store;
        this.maxWaitSeconds = maxWaitSeconds;
    }

    /** Registers a participant, or refreshes an existing one. A null description keeps the current one. */
    public Participant register(String name, String description) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ConversationException(INVALID, "Invalid name '" + name
                    + "': use 1-64 letters, digits, '.', '_' or '-', starting with a letter or digit");
        }
        store.upsertParticipant(name, blankToNull(description));
        return store.findParticipant(name).orElseThrow();
    }

    public List<Participant> participants() {
        return store.listParticipants();
    }

    public Conversation create(String me, String title, List<String> members) {
        requireRegistered(me);
        if (title == null || title.isBlank()) {
            throw new ConversationException(INVALID, "A conversation needs a title");
        }
        Set<String> all = new LinkedHashSet<>();
        all.add(me);
        if (members != null) {
            for (String member : members) {
                requireExists(member);
                all.add(member);
            }
        }
        String id = "c-" + shortId();
        store.insertConversation(id, title.strip(), me, List.copyOf(all));
        return store.findConversation(id).orElseThrow();
    }

    public Conversation join(String me, String conversationId) {
        requireRegistered(me);
        requireConversation(conversationId);
        store.addMember(conversationId, me);
        return store.findConversation(conversationId).orElseThrow();
    }

    public void leave(String me, String conversationId) {
        requireRegistered(me);
        requireMembership(me, requireConversation(conversationId));
        store.removeMember(conversationId, me);
    }

    /** Conversations {@code member} belongs to, or every conversation when {@code member} is null. */
    public List<Conversation> conversations(String member) {
        if (member == null || member.isBlank()) {
            return store.listConversations();
        }
        requireExists(member);
        return store.listConversationsOf(member);
    }

    public Message send(String me, String conversationId, String text, List<String> to, String replyTo) {
        requireRegistered(me);
        Conversation conversation = requireConversation(conversationId);
        requireMembership(me, conversation);
        if (text == null || text.isBlank()) {
            throw new ConversationException(INVALID, "Message text is empty");
        }
        List<String> recipients = to == null ? List.of() : List.copyOf(new LinkedHashSet<>(to));
        List<String> strangers = new ArrayList<>(recipients);
        strangers.removeAll(conversation.members());
        if (!strangers.isEmpty()) {
            throw new ConversationException(INVALID, "Not members of " + conversationId + ": " + strangers
                    + ". Members are " + conversation.members());
        }
        String reply = blankToNull(replyTo);
        if (reply != null && !store.messageExists(reply, conversationId)) {
            throw new ConversationException(NOT_FOUND, "No message " + reply + " in " + conversationId);
        }

        Message message = store.insertMessage("m-" + shortId(), conversation, me, recipients, reply, text);
        for (String member : conversation.members()) {
            if (!member.equals(me)) {
                wake(member);
            }
        }
        return message;
    }

    /**
     * Takes {@code me}'s new messages across all their conversations, waiting up to {@code waitSeconds} (capped by
     * {@code kreteg.inbox.max-wait-seconds}) for the first one. Each message is returned once.
     */
    public List<Message> inbox(String me, int waitSeconds, int limit) {
        requireRegistered(me);
        int boundedLimit = Math.clamp(limit, 1, MAX_LIMIT);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.clamp(waitSeconds, 0, maxWaitSeconds));
        try {
            while (true) {
                // Register before reading, so a message sent between the read and the wait still wakes us
                CompletableFuture<Void> signal = waiters.computeIfAbsent(me, k -> new CompletableFuture<>());
                List<Message> messages = store.takeInbox(me, boundedLimit);
                long remaining = deadline - System.nanoTime();
                if (!messages.isEmpty() || remaining <= 0) {
                    return messages;
                }
                try {
                    signal.get(remaining, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    // Deadline reached: the next pass reads once more and returns
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return List.of();
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e);
                }
            }
        } finally {
            store.touchParticipant(me);
        }
    }

    public List<Message> history(String conversationId, long sinceSeq, int limit) {
        requireConversation(conversationId);
        return store.history(conversationId, Math.max(0, sinceSeq), Math.clamp(limit, 1, MAX_LIMIT));
    }

    private void wake(String participant) {
        CompletableFuture<Void> signal = waiters.remove(participant);
        if (signal != null) {
            signal.complete(null);
        }
    }

    /** Checks that {@code name} is registered and marks it as seen. */
    private void requireRegistered(String name) {
        if (name == null || !store.touchParticipant(name)) {
            throw notRegistered(name);
        }
    }

    private void requireExists(String name) {
        if (name == null || store.findParticipant(name).isEmpty()) {
            throw notRegistered(name);
        }
    }

    private Conversation requireConversation(String conversationId) {
        return store.findConversation(conversationId)
                .orElseThrow(() -> new ConversationException(NOT_FOUND, "No conversation " + conversationId));
    }

    private static void requireMembership(String me, Conversation conversation) {
        if (!conversation.members().contains(me)) {
            throw new ConversationException(FORBIDDEN, me + " is not a member of " + conversation.id()
                    + "; join it first");
        }
    }

    private static ConversationException notRegistered(String name) {
        return new ConversationException(NOT_FOUND, "Participant '" + name + "' is not registered; register it first");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
