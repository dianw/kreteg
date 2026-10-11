package io.kreteg.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

/** The client subcommands, run in-process against the test server. */
@QuarkusTest
class CliTest {

    record Result(int code, String out, String err) {
        JsonNode json() throws Exception {
            return Hub.JSON.readTree(out);
        }
    }

    @TestHTTPResource("/")
    URL url;

    // The test database outlives a run, so every test uses fresh names
    private String alice;
    private String bob;
    private String carol;

    @BeforeEach
    void registerParticipants() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        alice = register("alice-" + suffix);
        bob = register("bob-" + suffix);
        carol = register("carol-" + suffix);
    }

    @Test
    void sendKeepsShellSensitiveMarkdownFromStdin() {
        String conversation = create(alice, "quoting", bob);
        String text = """
                Run this, `$HOME` and all:
                ```sh
                echo "$(whoami)" 'single' \\ done
                ```""";

        Result sent = pipe(text, "send", "--from", alice, "--to", bob, conversation);
        assertThat(sent.code()).isZero();
        assertThat(sent.out()).startsWith("sent m-");
        String id = sent.out().strip().substring("sent ".length());

        Result inbox = run("inbox", "--from", bob);
        assertThat(inbox.code()).isZero();
        assertThat(inbox.out()).isEqualTo("[kreteg] " + conversation + " \"quoting\" | " + id + " from " + alice
                + " to " + bob + "\n" + text + "\n\n");
    }

    @Test
    void replyShowsInBlockHeader() {
        String conversation = create(alice, "thread", bob);
        String question = pipe("ask", "send", "--json", "--from", alice, "--to", bob, conversation).out();
        String questionId = parse(question).path("id").asText();

        pipe("answer", "send", "--from", bob, "--reply-to", questionId, conversation);

        assertThat(run("inbox", "--from", alice).out()).contains(" from " + bob + " to everyone | reply to "
                + questionId + "\nanswer\n");
    }

    @Test
    void jsonPrintsTheServerResponse() throws Exception {
        JsonNode participants = run("who", "--json").json();
        assertThat(participants.isArray()).isTrue();
        assertThat(participants.findValuesAsText("name")).contains(alice, bob, carol);

        String conversation = create(alice, "json", bob);
        JsonNode message = pipe("hello", "send", "--from", alice, conversation, "--json").json();
        assertThat(message.path("conversation").asText()).isEqualTo(conversation);
        assertThat(message.path("text").asText()).isEqualTo("hello");

        JsonNode inbox = run("inbox", "--from", bob, "--json").json();
        assertThat(inbox.findValuesAsText("id")).containsExactly(message.path("id").asText());
    }

    @Test
    void whoListsParticipantsWithStatus() {
        Result who = run("who");
        assertThat(who.code()).isZero();
        assertThat(who.out().lines().filter(line -> line.startsWith(alice + " ")))
                .singleElement().asString().contains("live").endsWith("test");
    }

    @Test
    void joinLeaveAndConversations() {
        String conversation = create(alice, "membership", bob);

        Result joined = run("join", "--from", carol, conversation);
        assertThat(joined.code()).isZero();
        assertThat(joined.out()).contains(conversation, "\"membership\"", carol);
        assertThat(run("conversations", "--from", carol).out()).contains(conversation);

        Result left = run("leave", "--from", carol, conversation);
        assertThat(left.code()).isZero();
        assertThat(left.out()).isEqualTo("left " + conversation + "\n");
        assertThat(run("conversations", "--from", carol).out()).doesNotContain(conversation);

        Result again = run("leave", "--from", carol, conversation);
        assertThat(again.code()).isEqualTo(1);
        assertThat(again.err()).startsWith("kreteg: ");
    }

    @Test
    void doneMarksMessageHandled() throws Exception {
        String conversation = create(alice, "fyi", bob);
        JsonNode message = pipe("fyi only", "send", "--json", "--from", alice, "--to", bob, conversation).json();

        Result done = run("done", "--from", bob, conversation, message.path("id").asText());

        assertThat(done.code()).isZero();
        assertThat(new Hub(url.toString()).get(Hub.path("/api/conversations/{}", conversation))
                .path("doneSeq").path(bob).asLong()).isEqualTo(message.path("seq").asLong());
    }

    @Test
    void historyShowsMessagesAddressedToOthers() {
        String conversation = create(alice, "history", bob, carol);
        pipe("for bob", "send", "--from", alice, "--to", bob, conversation);
        pipe("for everyone", "send", "--from", alice, conversation);

        Result history = run("history", conversation, "--since", "0");

        assertThat(history.code()).isZero();
        assertThat(history.out()).contains(" to " + bob + "\nfor bob\n", " to everyone\nfor everyone\n");
        assertThat(run("inbox", "--from", carol).out()).doesNotContain("for bob").contains("for everyone");
    }

    @Test
    void emptyInboxSaysSo() {
        assertThat(run("inbox", "--from", alice).out()).isEqualTo("no new messages\n");
    }

    @Test
    void emptyStdinIsAUsageError() {
        String conversation = create(alice, "empty", bob);

        Result sent = pipe("  \n", "send", "--from", alice, conversation);

        assertThat(sent.code()).isEqualTo(2);
        assertThat(sent.err()).contains("stdin");
    }

    @Test
    void serverErrorsExitWithOne() {
        Result sent = pipe("hi", "send", "--from", alice, "c-missing");

        assertThat(sent.code()).isEqualTo(1);
        assertThat(sent.err()).contains("c-missing");
    }

    @Test
    void missingFromIsAUsageError() {
        Result inbox = run("inbox");

        assertThat(inbox.code()).isEqualTo(2);
        assertThat(inbox.err()).contains("--from");
    }

    @Test
    void unreachableHubExitsWithThree() {
        Result who = runAt("http://127.0.0.1:1", "", "who");

        assertThat(who.code()).isEqualTo(Cli.UNREACHABLE);
        assertThat(who.err()).contains("cannot reach Kreteg at http://127.0.0.1:1");
    }

    @Test
    void versionAndHelpNeedNoHub() {
        Result version = runAt("http://127.0.0.1:1", "", "--version");
        assertThat(version.code()).isZero();
        assertThat(version.out()).startsWith("kreteg ");

        Result help = runAt("http://127.0.0.1:1", "", "--help");
        assertThat(help.code()).isZero();
        assertThat(help.out()).contains("send", "watch", "serve");
    }

    @Test
    void watchPrintsOneBlockPerMessageUntilInterrupted() throws Exception {
        String conversation = create(alice, "watched", bob);
        StringWriter out = new StringWriter();
        Thread watcher = new Thread(() -> Cli.run(url.toString(), new ByteArrayInputStream(new byte[0]),
                new PrintWriter(out), new PrintWriter(new StringWriter()), "watch", bob, "1"));
        watcher.start();
        try {
            awaitOutput(out, "[kreteg] watching inbox of " + bob);
            pipe("first", "send", "--from", alice, conversation);
            pipe("second", "send", "--from", alice, "--to", bob, conversation);

            awaitOutput(out, "\nsecond\n");
            assertThat(out.toString()).contains(" to everyone\nfirst\n\n", " to " + bob + "\nsecond\n\n");
        } finally {
            watcher.interrupt();
            watcher.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertThat(watcher.isAlive()).isFalse();
    }

    private static void awaitOutput(StringWriter out, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!out.toString().contains(expected) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(out.toString()).contains(expected);
    }

    private String register(String name) {
        assertThat(run("register", "--from", name, "--description", "test").out()).isEqualTo("registered " + name
                + "\n");
        return name;
    }

    private String create(String from, String title, String... members) {
        Result created = run("create", "--from", from, "--title", title, "--members", String.join(",", members),
                "--json");
        assertThat(created.code()).isZero();
        return parse(created.out()).path("id").asText();
    }

    private static JsonNode parse(String json) {
        try {
            return Hub.JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + json, e);
        }
    }

    private Result run(String... args) {
        return runAt(url.toString(), "", args);
    }

    private Result pipe(String stdin, String... args) {
        return runAt(url.toString(), stdin, args);
    }

    private static Result runAt(String url, String stdin, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Cli.run(url, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(out), new PrintWriter(err), args);
        return new Result(code, out.toString(), err.toString());
    }
}
