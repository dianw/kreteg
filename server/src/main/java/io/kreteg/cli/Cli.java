package io.kreteg.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * The client subcommands of the {@code kreteg} binary: the same operations as the MCP tools, over the REST API, for
 * agents in any harness with a shell. Runs without Quarkus, so it never opens the database or the server port.
 */
@Command(name = "kreteg", mixinStandardHelpOptions = true, versionProvider = Cli.Version.class,
        description = {"Talk to the Kreteg hub at $KRETEG_URL (default " + Cli.DEFAULT_URL + ").",
                "Run 'kreteg serve' (or 'kreteg' alone) to start the hub itself."})
public class Cli {

    static final String DEFAULT_URL = "http://localhost:5784";

    /** Exit code when the hub doesn't answer at all, as opposed to answering with an error (1). */
    static final int UNREACHABLE = 3;

    private static final int MAX_BACKOFF_SECONDS = 30;

    @Spec
    private CommandSpec spec;

    private final Hub hub;
    private final InputStream stdin;
    private final PrintWriter out;
    private final PrintWriter err;

    Cli(Hub hub, InputStream stdin, PrintWriter out, PrintWriter err) {
        this.hub = hub;
        this.stdin = stdin;
        this.out = out;
        this.err = err;
    }

    /** Runs one command against the hub at {@code KRETEG_URL} and returns its exit code. */
    public static int run(String... args) {
        String url = System.getenv("KRETEG_URL");
        return run(url == null || url.isBlank() ? DEFAULT_URL : url, System.in,
                new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                new PrintWriter(System.err, true, StandardCharsets.UTF_8), args);
    }

    static int run(String url, InputStream stdin, PrintWriter out, PrintWriter err, String... args) {
        CommandLine commandLine = new CommandLine(new Cli(new Hub(url), stdin, out, err))
                .setOut(out)
                .setErr(err)
                .setExecutionExceptionHandler((e, cmd, parsed) -> {
                    cmd.getErr().println("kreteg: " + e.getMessage());
                    return switch (e) {
                        case ParameterException _ -> cmd.getCommandSpec().exitCodeOnInvalidInput();
                        case Hub.UnreachableException _ -> UNREACHABLE;
                        default -> 1;
                    };
                });
        int code = commandLine.execute(args);
        out.flush();
        err.flush();
        return code;
    }

    @Command(name = "register", description = "Register NAME on the hub, or refresh its registration.")
    int register(@Option(names = "--from", required = true, paramLabel = "NAME") String name,
                 @Option(names = "--description", paramLabel = "TEXT",
                         description = "What this session works on, shown to others") String description,
                 @Option(names = "--json") boolean json) {
        ObjectNode body = Hub.JSON.createObjectNode().put("name", name);
        if (description != null) {
            body.put("description", description);
        }
        JsonNode participant = hub.post("/api/participants", body);
        return print(json, participant, () -> out.println("registered " + participant.path("name").asText()));
    }

    @Command(name = "who", description = "List participants: live (seen within a minute), idle, or stale.")
    int who(@Option(names = "--json") boolean json) {
        JsonNode participants = hub.get("/api/participants");
        return print(json, participants, () -> Format.participants(participants).forEach(out::println));
    }

    @Command(name = "create", description = "Create a conversation with you and MEMBERS in it; prints its id.")
    int create(@Option(names = "--from", required = true, paramLabel = "NAME") String from,
               @Option(names = "--title", required = true, paramLabel = "TEXT") String title,
               @Option(names = "--members", split = ",", paramLabel = "NAME[,NAME...]") List<String> members,
               @Option(names = "--json") boolean json) {
        ObjectNode body = Hub.JSON.createObjectNode().put("from", from).put("title", title);
        ArrayNode list = body.putArray("members");
        if (members != null) {
            members.forEach(list::add);
        }
        JsonNode conversation = hub.post("/api/conversations", body);
        return print(json, conversation, () -> out.println(Format.conversation(conversation)));
    }

    @Command(name = "join", description = "Join a conversation. You receive messages sent from now on.")
    int join(@Option(names = "--from", required = true, paramLabel = "NAME") String from,
             @Parameters(paramLabel = "CONVERSATION") String conversation,
             @Option(names = "--json") boolean json) {
        JsonNode joined = hub.post(Hub.path("/api/conversations/{}/members", conversation),
                Hub.JSON.createObjectNode().put("name", from));
        return print(json, joined, () -> out.println(Format.conversation(joined)));
    }

    @Command(name = "leave", description = "Leave a conversation. Its history is kept.")
    int leave(@Option(names = "--from", required = true, paramLabel = "NAME") String from,
              @Parameters(paramLabel = "CONVERSATION") String conversation) {
        hub.delete(Hub.path("/api/conversations/{}/members/{}", conversation, from));
        out.println("left " + conversation);
        return 0;
    }

    @Command(name = "conversations", description = "List the conversations you are a member of.")
    int conversations(@Option(names = "--from", required = true, paramLabel = "NAME") String from,
                      @Option(names = "--json") boolean json) {
        JsonNode conversations = hub.get(Hub.path("/api/conversations?member={}", from));
        return print(json, conversations, () -> conversations.forEach(c -> out.println(Format.conversation(c))));
    }

    @Command(name = "send", description = {"Send the message on stdin to a conversation; prints its id.",
            "Members named in --to get it in their inbox; without --to, every other member does."})
    int send(@Option(names = "--from", required = true, paramLabel = "NAME") String from,
             @Option(names = "--to", split = ",", paramLabel = "NAME[,NAME...]",
                     description = "Members expected to answer") List<String> to,
             @Option(names = "--reply-to", paramLabel = "MESSAGE", description = "Id of the message this answers")
             String replyTo,
             @Parameters(paramLabel = "CONVERSATION") String conversation,
             @Option(names = "--json") boolean json) throws IOException {
        String text = new String(stdin.readAllBytes(), StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            throw new ParameterException(spec.commandLine(),
                    "send reads the message from stdin, which was empty; e.g. kreteg send ... <<'EOF'");
        }
        ObjectNode body = Hub.JSON.createObjectNode().put("from", from).put("text", text);
        ArrayNode recipients = body.putArray("to");
        if (to != null) {
            to.forEach(recipients::add);
        }
        if (replyTo != null) {
            body.put("replyTo", replyTo);
        }
        JsonNode message = hub.post(Hub.path("/api/conversations/{}/messages", conversation), body);
        return print(json, message, () -> out.println("sent " + message.path("id").asText()));
    }

    @Command(name = "done", description = "Mark a message that names you as handled without an answer.")
    int done(@Option(names = "--from", required = true, paramLabel = "NAME") String from,
             @Parameters(index = "0", paramLabel = "CONVERSATION") String conversation,
             @Parameters(index = "1", paramLabel = "MESSAGE") String message) {
        hub.post(Hub.path("/api/conversations/{}/messages/{}/done", conversation, message),
                Hub.JSON.createObjectNode().put("name", from));
        out.println("marked " + message + " done");
        return 0;
    }

    @Command(name = "inbox", description = {"Take your new messages, oldest first. Each is returned only once,",
            "so don't run this while 'kreteg watch' runs for the same name."})
    int inbox(@Option(names = "--from", required = true, paramLabel = "NAME") String from,
              @Option(names = "--wait", defaultValue = "0", paramLabel = "SECONDS",
                      description = "Wait this long for the first message when there is none") int waitSeconds,
              @Option(names = "--limit", defaultValue = "50", paramLabel = "N") int limit,
              @Option(names = "--json") boolean json) {
        JsonNode messages = hub.get(Hub.path("/api/participants/{}/inbox", from)
                + "?wait=" + waitSeconds + "&limit=" + limit, Duration.ofSeconds(waitSeconds + 15L));
        return print(json, messages, () -> {
            if (messages.isEmpty()) {
                out.println("no new messages");
            }
            messages.forEach(m -> out.println(Format.message(m)));
        });
    }

    @Command(name = "history", description = "Read a conversation's messages, oldest first, including ones "
            + "addressed to others.")
    int history(@Parameters(paramLabel = "CONVERSATION") String conversation,
                @Option(names = "--since", defaultValue = "0", paramLabel = "SEQ",
                        description = "Only messages after this seq") long since,
                @Option(names = "--limit", defaultValue = "100", paramLabel = "N") int limit,
                @Option(names = "--json") boolean json) {
        JsonNode messages = hub.get(Hub.path("/api/conversations/{}/messages", conversation)
                + "?since=" + since + "&limit=" + limit);
        return print(json, messages, () -> messages.forEach(m -> out.println(Format.message(m))));
    }

    @Command(name = "watch", description = {"Print one block per new message for NAME until stopped, for harnesses",
            "that turn command output into notifications; with --once, exit after the first messages, for harnesses",
            "that wake the agent when a background command ends. Consumes the inbox, like 'kreteg inbox'."})
    int watch(@Parameters(index = "0", paramLabel = "NAME") String name,
              @Parameters(index = "1", defaultValue = "30", paramLabel = "WAIT",
                      description = "Seconds each poll waits (default 30)") int waitSeconds,
              @Option(names = "--once", description = "Wait for messages however long it takes, print them, and "
                      + "exit") boolean once) {
        ObjectNode registration = Hub.JSON.createObjectNode().put("name", name);
        String inbox = Hub.path("/api/participants/{}/inbox", name) + "?wait=" + waitSeconds;
        try {
            // Re-registering keeps the description the agent set and refreshes last seen
            for (int backoff = 1; !tryPost("/api/participants", registration); backoff = next(backoff)) {
                err.println("[kreteg] cannot reach " + hub.baseUrl() + ", retrying in " + backoff + "s");
                Thread.sleep(backoff * 1000L);
            }
            // With --once, stdout carries only the messages, which is what the woken agent reads
            (once ? err : out).println("[kreteg] watching inbox of " + name + " at " + hub.baseUrl());
            (once ? err : out).flush();
            int backoff = 1;
            while (!Thread.currentThread().isInterrupted()) {
                JsonNode messages;
                try {
                    messages = hub.get(inbox, Duration.ofSeconds(waitSeconds + 15L));
                } catch (Hub.HubException | Hub.UnreachableException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    err.println("[kreteg] poll failed (" + e.getMessage() + "), retrying in " + backoff + "s");
                    err.flush();
                    Thread.sleep(backoff * 1000L);
                    backoff = next(backoff);
                    tryPost("/api/participants", registration);
                    continue;
                }
                backoff = 1;
                for (JsonNode message : messages) {
                    out.println(Format.message(message));
                    out.flush();
                }
                // The server caps each poll (kreteg.inbox.max-wait-seconds), so an empty one just polls again
                if (once && !messages.isEmpty()) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    @Command(name = "guide", description = {"Print how an agent uses Kreteg (the skill, with its references), for",
            "harnesses without skills. Needs no hub."})
    int guide(@Option(names = "--write-skill", paramLabel = "DIR",
            description = "Write the skill files into DIR instead, e.g. ~/.claude/skills/kreteg") Path dir)
            throws IOException {
        if (dir == null) {
            out.print(Skill.guide());
        } else {
            Skill.write(dir).forEach(path -> out.println("wrote " + path));
        }
        return 0;
    }

    private boolean tryPost(String path, ObjectNode body) {
        try {
            hub.post(path, body);
            return true;
        } catch (Hub.HubException | Hub.UnreachableException e) {
            return false;
        }
    }

    private static int next(int backoff) {
        return Math.min(backoff * 2, MAX_BACKOFF_SECONDS);
    }

    private int print(boolean json, JsonNode node, Runnable text) {
        if (json) {
            out.println(node.toString());
        } else {
            text.run();
        }
        return 0;
    }

    static class Version implements IVersionProvider {
        @Override
        public String[] getVersion() throws IOException {
            Properties properties = new Properties();
            try (InputStream in = Cli.class.getResourceAsStream("version.properties")) {
                if (in != null) {
                    properties.load(in);
                }
            }
            return new String[] {"kreteg " + properties.getProperty("version", "unknown")};
        }
    }
}
