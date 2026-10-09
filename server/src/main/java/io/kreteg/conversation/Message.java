package io.kreteg.conversation;

import java.util.List;

/**
 * One message in a conversation. Every other member receives it; {@code to} names the members expected to answer and
 * is empty for a message addressed to the room.
 */
public record Message(String id, long seq, String conversation, String title, String from, List<String> to,
                      String replyTo, String text, long createdAt) {
}
