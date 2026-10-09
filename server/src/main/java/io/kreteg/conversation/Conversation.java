package io.kreteg.conversation;

import java.util.List;

public record Conversation(String id, String title, String createdBy, long createdAt, List<String> members) {
}
