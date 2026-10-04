package io.kreteg.task;

public record TaskSummary(String id, String contextId, String state, long createdAt, long updatedAt) {
}
