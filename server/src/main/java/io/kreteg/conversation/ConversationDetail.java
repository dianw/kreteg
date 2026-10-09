package io.kreteg.conversation;

import java.util.List;
import java.util.Map;

/**
 * One conversation with each member's read position, for the UI. {@code readSeq} maps a member to the {@code seq} of
 * the last message taken from their inbox in this conversation; a member who joined later starts at the end of the
 * log at that time. Reading through history doesn't move it. Not returned to agents, whose tools use
 * {@link Conversation}.
 */
public record ConversationDetail(String id, String title, String createdBy, long createdAt, List<String> members,
                                 Map<String, Long> readSeq) {
}
