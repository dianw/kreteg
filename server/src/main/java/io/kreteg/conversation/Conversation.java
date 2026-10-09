package io.kreteg.conversation;

import java.util.List;

/** {@code lastActivityAt} is when the latest message was sent, or {@code createdAt} while there are none. */
public record Conversation(String id, String title, String createdBy, long createdAt, long lastActivityAt,
                           List<String> members) {
}
