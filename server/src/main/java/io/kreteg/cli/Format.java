package io.kreteg.cli;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Readable text for the hub's JSON, which {@code --json} prints instead. */
final class Format {

    private Format() {
    }

    /**
     * One block per message, the same as the old {@code kreteg-watch} script printed, so a harness that turns output
     * into notifications gets one notification per message:
     *
     * <pre>
     * [kreteg] c-1a2b "release" | m-3c4d from alice to bob | reply to m-5e6f
     * text
     * </pre>
     */
    static String message(JsonNode m) {
        List<String> to = strings(m.path("to"));
        StringBuilder block = new StringBuilder("[kreteg] ")
                .append(m.path("conversation").asText()).append(" \"").append(m.path("title").asText()).append("\"")
                .append(" | ").append(m.path("id").asText()).append(" from ").append(m.path("from").asText())
                .append(to.isEmpty() ? " to everyone" : " to " + String.join(",", to));
        if (!m.path("replyTo").isNull() && !m.path("replyTo").asText().isEmpty()) {
            block.append(" | reply to ").append(m.path("replyTo").asText());
        }
        return block.append('\n').append(m.path("text").asText()).append('\n').toString();
    }

    /** {@code c-1a2b "release" | members: alice, bob} */
    static String conversation(JsonNode c) {
        return c.path("id").asText() + " \"" + c.path("title").asText() + "\" | members: "
                + String.join(", ", strings(c.path("members")));
    }

    /** One line per participant, columns aligned: name, status, idle time, description. */
    static List<String> participants(JsonNode participants) {
        int width = 0;
        for (JsonNode p : participants) {
            width = Math.max(width, p.path("name").asText().length());
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode p : participants) {
            lines.add(String.format("%-" + width + "s  %-5s  %8s  %s", p.path("name").asText(),
                    p.path("status").asText(), idle(p.path("idleSeconds").asLong()),
                    p.path("description").asText("")).stripTrailing());
        }
        return lines;
    }

    /** {@code 42s}, {@code 7m}, {@code 3h}, {@code 2d} idle. */
    static String idle(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return seconds / 60 + "m";
        }
        return seconds < 86400 ? seconds / 3600 + "h" : seconds / 86400 + "d";
    }

    static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }
}
