package io.kreteg.conversation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.StartupEvent;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.Jdbi;

/**
 * SQLite persistence for participants, conversations, memberships and the message log. Rules live in
 * {@link ConversationService}; this class only reads and writes.
 */
@ApplicationScoped
public class ConversationStore {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS participant (
                name         TEXT PRIMARY KEY,
                description  TEXT,
                joined_at    INTEGER NOT NULL DEFAULT (unixepoch('subsec') * 1000),
                last_seen    INTEGER NOT NULL DEFAULT (unixepoch('subsec') * 1000)
            );
            CREATE TABLE IF NOT EXISTS conversation (
                id           TEXT PRIMARY KEY,
                title        TEXT NOT NULL,
                created_by   TEXT NOT NULL REFERENCES participant (name),
                created_at   INTEGER NOT NULL DEFAULT (unixepoch('subsec') * 1000)
            );
            CREATE TABLE IF NOT EXISTS member (
                conversation_id  TEXT NOT NULL REFERENCES conversation (id),
                participant      TEXT NOT NULL REFERENCES participant (name),
                cursor_seq       INTEGER NOT NULL DEFAULT 0,
                done_seq         INTEGER NOT NULL DEFAULT 0,
                joined_at        INTEGER NOT NULL DEFAULT (unixepoch('subsec') * 1000),
                PRIMARY KEY (conversation_id, participant)
            );
            CREATE INDEX IF NOT EXISTS member_participant_idx ON member (participant);
            CREATE TABLE IF NOT EXISTS message (
                seq              INTEGER PRIMARY KEY AUTOINCREMENT,
                id               TEXT NOT NULL UNIQUE,
                conversation_id  TEXT NOT NULL REFERENCES conversation (id),
                sender           TEXT NOT NULL REFERENCES participant (name),
                recipients       TEXT,
                reply_to         TEXT,
                text             TEXT NOT NULL,
                created_at       INTEGER NOT NULL DEFAULT (unixepoch('subsec') * 1000)
            );
            CREATE INDEX IF NOT EXISTS message_conversation_idx ON message (conversation_id, seq);
            """;

    /** Columns added after the first release, as {table, column, definition}, for databases created before them. */
    private static final String[][] ADDED_COLUMNS = {
            {"member", "done_seq", "INTEGER NOT NULL DEFAULT 0"},
    };

    private static final String COUNT_COLUMN = "SELECT count(*) FROM pragma_table_info(?) WHERE name = ?";

    private static final String UPSERT_PARTICIPANT = """
            INSERT INTO participant (name, description) VALUES (?, ?)
            ON CONFLICT (name) DO UPDATE SET
                description = coalesce(excluded.description, participant.description),
                last_seen   = unixepoch('subsec') * 1000
            """;

    private static final String TOUCH_PARTICIPANT =
            "UPDATE participant SET last_seen = unixepoch('subsec') * 1000 WHERE name = ?";

    private static final String SELECT_PARTICIPANTS =
            "SELECT name, description, joined_at, last_seen FROM participant ORDER BY last_seen DESC";

    private static final String SELECT_PARTICIPANT =
            "SELECT name, description, joined_at, last_seen FROM participant WHERE name = ?";

    private static final String INSERT_CONVERSATION =
            "INSERT INTO conversation (id, title, created_by) VALUES (?, ?, ?)";

    private static final String SELECT_CONVERSATIONS = """
            SELECT c.id, c.title, c.created_by, c.created_at,
                   (SELECT group_concat(participant, ',') FROM member WHERE conversation_id = c.id) AS members,
                   coalesce((SELECT created_at FROM message WHERE conversation_id = c.id ORDER BY seq DESC LIMIT 1),
                            c.created_at) AS last_activity_at
            FROM conversation c
            """;

    private static final String SELECT_CONVERSATION_BY_ID = SELECT_CONVERSATIONS + " WHERE c.id = ?";

    private static final String SELECT_CONVERSATIONS_OF_MEMBER = SELECT_CONVERSATIONS
            + " JOIN member mb ON mb.conversation_id = c.id WHERE mb.participant = ? ORDER BY last_activity_at DESC";

    private static final String SELECT_ALL_CONVERSATIONS = SELECT_CONVERSATIONS + " ORDER BY last_activity_at DESC";

    /** A new member starts at the end of the log; earlier messages stay reachable through history. */
    private static final String INSERT_MEMBER = """
            INSERT INTO member (conversation_id, participant, cursor_seq)
            VALUES (?, ?, (SELECT coalesce(max(seq), 0) FROM message WHERE conversation_id = ?))
            ON CONFLICT (conversation_id, participant) DO NOTHING
            """;

    private static final String SELECT_READ_SEQ =
            "SELECT participant, cursor_seq FROM member WHERE conversation_id = ? ORDER BY joined_at, participant";

    private static final String SELECT_DONE_SEQ =
            "SELECT participant, done_seq FROM member WHERE conversation_id = ? ORDER BY joined_at, participant";

    /** Never moves backwards, so closing an older ask after a newer one keeps the newer mark. */
    private static final String MARK_DONE = """
            UPDATE member SET done_seq = max(done_seq, coalesce((SELECT seq FROM message WHERE id = ? AND conversation_id = ?), 0))
            WHERE conversation_id = ? AND participant = ?
            """;

    private static final String DELETE_MEMBER = "DELETE FROM member WHERE conversation_id = ? AND participant = ?";

    private static final String INSERT_MESSAGE = """
            INSERT INTO message (id, conversation_id, sender, recipients, reply_to, text) VALUES (?, ?, ?, ?, ?, ?)
            RETURNING seq, created_at
            """;

    private static final String COUNT_MESSAGE_IN_CONVERSATION =
            "SELECT count(*) FROM message WHERE id = ? AND conversation_id = ?";

    private static final String SELECT_MESSAGES = """
            SELECT m.seq, m.id, m.conversation_id, c.title, m.sender, m.recipients, m.reply_to, m.text, m.created_at
            FROM message m JOIN conversation c ON c.id = m.conversation_id
            """;

    private static final String SELECT_HISTORY = SELECT_MESSAGES
            + " WHERE m.conversation_id = ? AND m.seq > ? ORDER BY m.seq LIMIT ?";

    /** Messages addressed to the participant, or to nobody in particular (an empty {@code to}). */
    private static final String SELECT_INBOX = SELECT_MESSAGES + """
             JOIN member mb ON mb.conversation_id = m.conversation_id AND m.seq > mb.cursor_seq
            WHERE mb.participant = ? AND m.sender <> ?
              AND (m.recipients IS NULL OR instr(',' || m.recipients || ',', ',' || ? || ',') > 0)
            ORDER BY m.seq LIMIT ?
            """;

    /**
     * Moves each of the participant's cursors to the last message at or below {@code seq} in that conversation, so
     * messages addressed to others that the inbox scan passed over are not scanned again.
     */
    private static final String ADVANCE_CURSORS = """
            UPDATE member SET cursor_seq = (
                SELECT max(m.seq) FROM message m WHERE m.conversation_id = member.conversation_id AND m.seq <= ?)
            WHERE participant = ? AND cursor_seq < (
                SELECT coalesce(max(m.seq), 0) FROM message m
                WHERE m.conversation_id = member.conversation_id AND m.seq <= ?)
            """;

    private final Jdbi jdbi;

    /**
     * SQLite has a single writer, and a deferred transaction that reads before it writes fails instead of waiting when
     * another connection wrote in between. Serializing writes in-process avoids both.
     */
    private final ReentrantLock writeLock = new ReentrantLock();

    @Inject
    public ConversationStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    void onStart(@Observes StartupEvent event) {
        jdbi.useHandle(h -> {
            for (String ddl : SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    h.execute(ddl);
                }
            }
            for (String[] column : ADDED_COLUMNS) {
                boolean exists = h.createQuery(COUNT_COLUMN).bind(0, column[0]).bind(1, column[1])
                        .mapTo(Integer.class).one() > 0;
                if (!exists) {
                    h.execute("ALTER TABLE " + column[0] + " ADD COLUMN " + column[1] + " " + column[2]);
                }
            }
        });
    }

    public void upsertParticipant(String name, String description) {
        write(h -> h.createUpdate(UPSERT_PARTICIPANT).bind(0, name).bind(1, description).execute());
    }

    /** Updates {@code last_seen}; returns false when the participant is not registered. */
    public boolean touchParticipant(String name) {
        return write(h -> h.createUpdate(TOUCH_PARTICIPANT).bind(0, name).execute()) > 0;
    }

    public Optional<Participant> findParticipant(String name) {
        long now = System.currentTimeMillis();
        return jdbi.withHandle(h -> h.createQuery(SELECT_PARTICIPANT)
                .bind(0, name)
                .map((rs, ctx) -> participant(rs, now))
                .findOne());
    }

    public List<Participant> listParticipants() {
        long now = System.currentTimeMillis();
        return jdbi.withHandle(h -> h.createQuery(SELECT_PARTICIPANTS)
                .map((rs, ctx) -> participant(rs, now))
                .list());
    }

    /** Creates the conversation and its memberships in one transaction. */
    public void insertConversation(String id, String title, String createdBy, List<String> members) {
        write(h -> {
            h.createUpdate(INSERT_CONVERSATION).bind(0, id).bind(1, title).bind(2, createdBy).execute();
            for (String member : members) {
                insertMember(h, id, member);
            }
            return null;
        });
    }

    public void addMember(String conversationId, String participant) {
        write(h -> insertMember(h, conversationId, participant));
    }

    public void removeMember(String conversationId, String participant) {
        write(h -> h.createUpdate(DELETE_MEMBER).bind(0, conversationId).bind(1, participant).execute());
    }

    public Optional<Conversation> findConversation(String id) {
        return jdbi.withHandle(h -> h.createQuery(SELECT_CONVERSATION_BY_ID)
                .bind(0, id)
                .map((rs, ctx) -> conversation(rs))
                .findOne());
    }

    public List<Conversation> listConversations() {
        return jdbi.withHandle(h -> h.createQuery(SELECT_ALL_CONVERSATIONS)
                .map((rs, ctx) -> conversation(rs))
                .list());
    }

    public List<Conversation> listConversationsOf(String participant) {
        return jdbi.withHandle(h -> h.createQuery(SELECT_CONVERSATIONS_OF_MEMBER)
                .bind(0, participant)
                .map((rs, ctx) -> conversation(rs))
                .list());
    }

    /** Each member's cursor in the conversation, in joining order. */
    public Map<String, Long> readSeqs(String conversationId) {
        return memberSeqs(SELECT_READ_SEQ, conversationId);
    }

    /** Each member's last ask closed without an answer (see {@link #markDone}), in joining order. */
    public Map<String, Long> doneSeqs(String conversationId) {
        return memberSeqs(SELECT_DONE_SEQ, conversationId);
    }

    /** Records that the member has handled the message and everything before it without answering. */
    public void markDone(String conversationId, String participant, String messageId) {
        write(h -> h.createUpdate(MARK_DONE)
                .bind(0, messageId)
                .bind(1, conversationId)
                .bind(2, conversationId)
                .bind(3, participant)
                .execute());
    }

    /** Appends a message; returns it with its assigned {@code seq} and {@code createdAt}. */
    public Message insertMessage(String id, Conversation conversation, String sender, List<String> to,
                                 String replyTo, String text) {
        return write(h -> h.createQuery(INSERT_MESSAGE)
                .bind(0, id)
                .bind(1, conversation.id())
                .bind(2, sender)
                .bind(3, to.isEmpty() ? null : String.join(",", to))
                .bind(4, replyTo)
                .bind(5, text)
                .map((rs, ctx) -> new Message(id, rs.getLong(1), conversation.id(), conversation.title(), sender, to,
                        replyTo, text, rs.getLong(2)))
                .one());
    }

    public boolean messageExists(String messageId, String conversationId) {
        return jdbi.withHandle(h -> h.createQuery(COUNT_MESSAGE_IN_CONVERSATION)
                .bind(0, messageId)
                .bind(1, conversationId)
                .mapTo(Integer.class)
                .one()) > 0;
    }

    public List<Message> history(String conversationId, long sinceSeq, int limit) {
        return jdbi.withHandle(h -> h.createQuery(SELECT_HISTORY)
                .bind(0, conversationId)
                .bind(1, sinceSeq)
                .bind(2, limit)
                .map((rs, ctx) -> message(rs))
                .list());
    }

    /**
     * Returns the participant's undelivered messages from all their conversations, oldest first, excluding their own
     * and those addressed only to others, and moves each membership cursor past everything the scan covered: up to
     * the last returned message when {@code limit} cut the batch short, otherwise to the end of each conversation.
     * Messages are inserted under the same write lock, so none can appear between the scan and the cursor update.
     */
    public List<Message> takeInbox(String participant, int limit) {
        return write(h -> {
            List<Message> messages = h.createQuery(SELECT_INBOX)
                    .bind(0, participant)
                    .bind(1, participant)
                    .bind(2, participant)
                    .bind(3, limit)
                    .map((rs, ctx) -> message(rs))
                    .list();
            long scannedTo = messages.size() < limit ? Long.MAX_VALUE : messages.getLast().seq();
            h.createUpdate(ADVANCE_CURSORS)
                    .bind(0, scannedTo)
                    .bind(1, participant)
                    .bind(2, scannedTo)
                    .execute();
            return messages;
        });
    }

    private Map<String, Long> memberSeqs(String sql, String conversationId) {
        Map<String, Long> seqs = new LinkedHashMap<>();
        jdbi.useHandle(h -> h.createQuery(sql)
                .bind(0, conversationId)
                .map((rs, ctx) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .forEach(e -> seqs.put(e.getKey(), e.getValue())));
        return seqs;
    }

    private static int insertMember(Handle h, String conversationId, String participant) {
        return h.createUpdate(INSERT_MEMBER)
                .bind(0, conversationId)
                .bind(1, participant)
                .bind(2, conversationId)
                .execute();
    }

    private <R> R write(HandleCallback<R, RuntimeException> callback) {
        writeLock.lock();
        try {
            return jdbi.inTransaction(callback);
        } finally {
            writeLock.unlock();
        }
    }

    private static Participant participant(ResultSet rs, long now) throws SQLException {
        return Participant.of(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), now);
    }

    private static Conversation conversation(ResultSet rs) throws SQLException {
        return new Conversation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(6),
                names(rs.getString(5)));
    }

    private static Message message(ResultSet rs) throws SQLException {
        return new Message(rs.getString(2), rs.getLong(1), rs.getString(3), rs.getString(4), rs.getString(5),
                names(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getLong(9));
    }

    /** Names never contain commas (see {@link ConversationService}), so a comma-joined list round-trips. */
    private static List<String> names(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split(","));
    }
}
