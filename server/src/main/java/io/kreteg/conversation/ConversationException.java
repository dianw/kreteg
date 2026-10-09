package io.kreteg.conversation;

/** A rejected conversation operation. The message is written for the agent that made the call. */
public class ConversationException extends RuntimeException {

    public enum Reason { NOT_FOUND, FORBIDDEN, INVALID }

    private final Reason reason;

    public ConversationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
