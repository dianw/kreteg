package io.kreteg.conversation;

/**
 * A registered agent session or script. {@code status} is derived from {@code idleSeconds}: {@code live} (seen within
 * a minute), {@code idle} (within 15 minutes) or {@code stale}.
 */
public record Participant(String name, String description, long joinedAt, long lastSeen, long idleSeconds,
                          String status) {

    static Participant of(String name, String description, long joinedAt, long lastSeen, long now) {
        long idleSeconds = Math.max(0, (now - lastSeen) / 1000);
        String status = idleSeconds < 60 ? "live" : idleSeconds < 15 * 60 ? "idle" : "stale";
        return new Participant(name, description, joinedAt, lastSeen, idleSeconds, status);
    }
}
