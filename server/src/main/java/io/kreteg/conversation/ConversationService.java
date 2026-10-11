package io.kreteg.conversation;

import static io.kreteg.conversation.ConversationException.Reason.FORBIDDEN;
import static io.kreteg.conversation.ConversationException.Reason.INVALID;
import static io.kreteg.conversation.ConversationException.Reason.NOT_FOUND;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.kreteg.conversation.ConversationEvents.Event;
import io.kreteg.conversation.ConversationEvents.MemberStatus;
import io.kreteg.conversation.ConversationStore.Positions;

/**
 * Multi-party conversations between registered participants. A message reaches the inboxes of the members named in
 * its {@code to}, or of every other member when {@code to} is empty; history shows every message to everyone. Both the
 * MCP tools and the REST API delegate here.
 */
@ApplicationScoped
public class ConversationService {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private static final int MAX_LIMIT = 500;

    private static final int MAX_STATUS_LENGTH = 200;

    private final ConversationStore store;
    private final ConversationEvents events;
    private final int maxWaitSeconds;
    private final Duration statusTtl;
    private final Duration participantThrottle;

    /** One pending signal per participant blocked in {@link #inbox}; completed and removed when mail arrives. */
    private final Map<String, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();

    /** When each participant's last {@code participant} event went out, to throttle the ones for last seen. */
    private final Map<String, Long> participantPublishedAt = new ConcurrentHashMap<>();

    /** Participants with an event already scheduled for the end of their throttle window. */
    private final Set<String> participantTrailing = ConcurrentHashMap.newKeySet();

    /** Each conversation's current status lines by member. In memory only: they are gone after a restart. */
    private final Map<String, Map<String, MemberStatus>> statuses = new ConcurrentHashMap<>();

    /** Status expiry and trailing participant events. */
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "kreteg-timers");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public ConversationService(ConversationStore store,
                               ConversationEvents events,
                               @ConfigProperty(name = "kreteg.inbox.max-wait-seconds") int maxWaitSeconds,
                               @ConfigProperty(name = "kreteg.status.ttl") Duration statusTtl,
                               @ConfigProperty(name = "kreteg.events.presence-interval")
                               Duration participantThrottle) {
        this.store = store;
        this.events = events;
        this.maxWaitSeconds = maxWaitSeconds;
        this.statusTtl = statusTtl;
        this.participantThrottle = participantThrottle;
    }

    @PreDestroy
    void stop() {
        timers.shutdownNow();
    }

    /** Registers a participant, or refreshes an existing one. A null description keeps the current one. */
    public Participant register(String name, String description) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ConversationException(INVALID, "Invalid name '" + name
                    + "': use 1-64 letters, digits, '.', '_' or '-', starting with a letter or digit");
        }
        store.upsertParticipant(name, blankToNull(description));
        Participant participant = store.findParticipant(name).orElseThrow();
        participantPublishedAt.put(name, System.currentTimeMillis());
        events.publishHub(Event.participant(participant));
        return participant;
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
        Conversation created = store.findConversation(id).orElseThrow();
        events.publishHub(Event.conversation(created));
        return created;
    }

    public Conversation join(String me, String conversationId) {
        requireRegistered(me);
        requireConversation(conversationId);
        store.addMember(conversationId, me);
        Conversation joined = store.findConversation(conversationId).orElseThrow();
        events.publish(conversationId, Event.members(joined.members()));
        events.publishHub(Event.conversation(joined));
        return joined;
    }

    public void leave(String me, String conversationId) {
        requireRegistered(me);
        requireMembership(me, requireConversation(conversationId));
        store.removeMember(conversationId, me);
        clearStatus(conversationId, me);
        Conversation left = requireConversation(conversationId);
        events.publish(conversationId, Event.members(left.members()));
        events.publishHub(Event.conversation(left));
    }

    /** Conversations {@code member} belongs to, or every conversation when {@code member} is null. */
    public List<Conversation> conversations(String member) {
        if (member == null || member.isBlank()) {
            return store.listConversations();
        }
        requireExists(member);
        return store.listConversationsOf(member);
    }

    /** The conversation with each member's read position. */
    public ConversationDetail conversation(String conversationId) {
        Conversation conversation = requireConversation(conversationId);
        return new ConversationDetail(conversation.id(), conversation.title(), conversation.createdBy(),
                conversation.createdAt(), conversation.lastActivityAt(), conversation.members(),
                store.readSeqs(conversationId), store.doneSeqs(conversationId));
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
        for (String member : recipients.isEmpty() ? conversation.members() : recipients) {
            if (!member.equals(me)) {
                wake(member);
            }
        }
        clearStatus(conversationId, me);
        events.publish(conversationId, Event.message(message));
        if (events.hubWatched()) {
            // Its last activity moved
            store.findConversation(conversationId).ifPresent(c -> events.publishHub(Event.conversation(c)));
        }
        return message;
    }

    /**
     * Sets {@code me}'s status line in the conversation, for watchers: what they are doing right now. It is kept in
     * memory, never stored or delivered to an inbox, and cleared by an empty text, by {@code me}'s next message or
     * leaving, or after {@code kreteg.status.ttl}. Longer or multi-line text is folded to one line of 200 characters.
     */
    public void status(String me, String conversationId, String text) {
        requireRegistered(me);
        requireMembership(me, requireConversation(conversationId));
        String line = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        if (line.isEmpty()) {
            clearStatus(conversationId, me);
            return;
        }
        MemberStatus status = new MemberStatus(me, line.substring(0, Math.min(line.length(), MAX_STATUS_LENGTH)),
                System.currentTimeMillis());
        statuses.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>()).put(me, status);
        events.publish(conversationId, Event.status(status));
        timers.schedule(() -> expire(conversationId, status), statusTtl.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The conversation's current status lines, oldest first. */
    public List<MemberStatus> statuses(String conversationId) {
        long oldest = System.currentTimeMillis() - statusTtl.toMillis();
        return statuses.getOrDefault(conversationId, Map.of()).values().stream()
                .filter(status -> status.at() > oldest)
                .sorted(Comparator.comparingLong(MemberStatus::at))
                .toList();
    }

    private void expire(String conversationId, MemberStatus status) {
        Map<String, MemberStatus> current = statuses.get(conversationId);
        // Only the status this timer was set for: a newer one has its own timer
        if (current != null && current.remove(status.name(), status)) {
            events.publish(conversationId, Event.status(new MemberStatus(status.name(), null,
                    System.currentTimeMillis())));
        }
    }

    private void clearStatus(String conversationId, String member) {
        Map<String, MemberStatus> current = statuses.get(conversationId);
        if (current != null && current.remove(member) != null) {
            events.publish(conversationId, Event.status(new MemberStatus(member, null, System.currentTimeMillis())));
        }
    }

    /**
     * Records that {@code me} has handled the message without answering it, e.g. because it said not to reply. Posts
     * nothing and wakes nobody; the UI uses it to stop showing {@code me} as working on the ask.
     */
    public void done(String me, String conversationId, String messageId) {
        requireRegistered(me);
        Conversation conversation = requireConversation(conversationId);
        requireMembership(me, conversation);
        if (messageId == null || !store.messageExists(messageId.strip(), conversationId)) {
            throw new ConversationException(NOT_FOUND, "No message " + messageId + " in " + conversationId);
        }
        Positions positions = store.markDone(conversationId, me, messageId.strip());
        events.publish(conversationId, Event.receipt(me, positions.readSeq(), positions.doneSeq()));
    }

    /**
     * Takes {@code me}'s new messages (addressed to them, or to nobody in particular) across all their conversations,
     * waiting up to {@code waitSeconds} (capped by
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
                ConversationStore.Taken taken = store.takeInbox(me, boundedLimit);
                for (Positions moved : taken.moved()) {
                    events.publish(moved.conversationId(), Event.receipt(me, moved.readSeq(), moved.doneSeq()));
                }
                List<Message> messages = taken.messages();
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
            touched(me);
        }
    }

    public List<Message> history(String conversationId, long sinceSeq, int limit) {
        requireConversation(conversationId);
        return store.history(conversationId, Math.max(0, sinceSeq), Math.clamp(limit, 1, MAX_LIMIT));
    }

    /** The last {@code limit} messages with a seq below {@code beforeSeq}, oldest first, for paging backwards. */
    public List<Message> historyBefore(String conversationId, long beforeSeq, int limit) {
        requireConversation(conversationId);
        return store.historyBefore(conversationId, beforeSeq, Math.clamp(limit, 1, MAX_LIMIT));
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
        touched(name);
    }

    /**
     * Announces that {@code name} was just seen, at most once per {@code kreteg.events.presence-interval}: watchers
     * touch last seen on every poll, and clients work out live, idle and stale from it with their own clock. A touch
     * inside the window is sent when the window ends, with the latest value, so what clients know lags by at most the
     * throttle.
     */
    private void touched(String name) {
        if (!events.hubWatched()) {
            return;
        }
        long now = System.currentTimeMillis();
        long throttle = participantThrottle.toMillis();
        long[] wait = {0};
        participantPublishedAt.compute(name, (k, last) -> {
            boolean due = last == null || now - last >= throttle;
            wait[0] = due ? 0 : last + throttle - now;
            return due ? now : last;
        });
        if (wait[0] == 0) {
            publishParticipant(name);
        } else if (participantTrailing.add(name)) {
            timers.schedule(() -> {
                participantTrailing.remove(name);
                participantPublishedAt.put(name, System.currentTimeMillis());
                publishParticipant(name);
            }, wait[0], TimeUnit.MILLISECONDS);
        }
    }

    private void publishParticipant(String name) {
        store.findParticipant(name).ifPresent(p -> events.publishHub(Event.participant(p)));
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
