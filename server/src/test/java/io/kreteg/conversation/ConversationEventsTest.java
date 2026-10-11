package io.kreteg.conversation;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/** The SSE stream at {@code /api/events} and the status lines it carries, read as a browser would. */
@QuarkusTest
class ConversationEventsTest {

    /** One server-sent event; {@code comment} holds a comment line's text instead, e.g. {@code ping}. */
    record SseEvent(String event, String id, String data, String comment) {
        JsonPath json() {
            return JsonPath.from(data);
        }
    }

    /** Reads an SSE response line by line with the JDK client, on its own thread, into a queue. */
    static final class SseStream implements AutoCloseable {

        private final BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();
        private final HttpResponse<Stream<String>> response;
        private final Thread reader;

        SseStream(URI uri, Map<String, String> headers) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).header("Accept", "text/event-stream");
            headers.forEach(request::header);
            response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofLines());
            reader = Thread.ofVirtual().start(() -> {
                Map<String, String> fields = new HashMap<>();
                try (Stream<String> lines = response.body()) {
                    lines.forEach(line -> {
                        if (line.isEmpty()) {
                            if (!fields.isEmpty()) {
                                events.add(new SseEvent(fields.get("event"), fields.get("id"), fields.get("data"),
                                        fields.get("comment")));
                                fields.clear();
                            }
                        } else if (line.startsWith(":")) {
                            events.add(new SseEvent(null, null, null, line.substring(1).strip()));
                        } else {
                            int colon = line.indexOf(':');
                            String value = line.substring(colon + 1);
                            fields.merge(line.substring(0, colon), value.startsWith(" ") ? value.substring(1) : value,
                                    (a, b) -> a + "\n" + b);
                        }
                    });
                } catch (RuntimeException e) {
                    // Closed by the test
                }
            });
        }

        int status() {
            return response.statusCode();
        }

        String contentType() {
            return response.headers().firstValue("Content-Type").orElse("");
        }

        /** The next conversation event, skipping pings and hub-wide events, failing after 10 seconds. */
        SseEvent next() throws InterruptedException {
            while (true) {
                SseEvent event = events.poll(10, TimeUnit.SECONDS);
                assertThat(event).as("an event within 10s").isNotNull();
                if (event.comment() == null && !HUB.contains(event.event())) {
                    return event;
                }
            }
        }

        /** Waits for {@code count} ping comments, skipping anything else, failing after 10 seconds. */
        void awaitPings(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (int seen = 0; seen < count; ) {
                SseEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                assertThat(event).as("%d pings within 10s", count).isNotNull();
                if ("ping".equals(event.comment())) {
                    seen++;
                }
            }
        }

        /** The next hub-wide event of {@code type} about {@code name}, skipping everything else, within 10s. */
        SseEvent nextHub(String type, String name) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) {
                SseEvent event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                assertThat(event).as("a %s event about %s within 10s", type, name).isNotNull();
                if (type.equals(event.event()) && name.equals(event.json().getString(
                        type.equals("conversation") ? "id" : "name"))) {
                    return event;
                }
            }
        }

        /** No hub-wide event of {@code type} about {@code name} arrives within {@code millis}. */
        void assertNoHub(String type, String name, long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            for (SseEvent event; (event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) != null; ) {
                assertThat(type.equals(event.event()) && name.equals(event.json().getString("name")))
                        .as("no %s event about %s, got %s", type, name, event).isFalse();
            }
        }

        /** No conversation event arrives for a short while (pings and hub-wide events don't count). */
        void assertQuiet() throws InterruptedException {
            SseEvent event = events.poll(500, TimeUnit.MILLISECONDS);
            while (event != null && (event.comment() != null || HUB.contains(event.event()))) {
                event = events.poll(500, TimeUnit.MILLISECONDS);
            }
            assertThat(event).isNull();
        }

        @Override
        public void close() {
            response.body().close();
            reader.interrupt();
        }
    }

    static final java.util.Set<String> HUB = java.util.Set.of("conversation", "participant");

    @TestHTTPResource("/")
    URL url;

    private final List<SseStream> streams = new ArrayList<>();

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

    @AfterEach
    void closeStreams() {
        streams.forEach(SseStream::close);
    }

    @Test
    void replaysAfterSinceThenStatusesThenGoesLive() throws Exception {
        String conversation = create(alice, "replay", bob);
        long first = send(alice, conversation, "one", List.of()).getLong("seq");
        long second = send(alice, conversation, "two", List.of()).getLong("seq");
        status(bob, conversation, "reading the spec").then().statusCode(204);

        SseStream stream = open(conversation, "&since=" + first, Map.of());
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.contentType()).startsWith("text/event-stream");

        SseEvent replayed = stream.next();
        assertThat(replayed.event()).isEqualTo("message");
        assertThat(replayed.id()).isEqualTo(Long.toString(second));
        // Exactly the JSON history returns for it
        assertThat(replayed.json().getMap("$")).isEqualTo(given().queryParam("since", first)
                .when().get("/api/conversations/{id}/messages", conversation)
                .then().statusCode(200).extract().jsonPath().getMap("[0]"));

        SseEvent status = stream.next();
        assertThat(status.event()).isEqualTo("status");
        assertThat(status.json().getString("name")).isEqualTo(bob);
        assertThat(status.json().getString("text")).isEqualTo("reading the spec");
        assertThat(status.json().getLong("at")).isPositive();

        long third = send(alice, conversation, "three", List.of(bob)).getLong("seq");
        SseEvent live = stream.next();
        assertThat(live.event()).isEqualTo("message");
        assertThat(live.id()).isEqualTo(Long.toString(third));
        assertThat(live.json().getString("text")).isEqualTo("three");
        assertThat(live.json().getList("to", String.class)).containsExactly(bob);
    }

    @Test
    void lastEventIdWinsOverSince() throws Exception {
        String conversation = create(alice, "reconnect", bob);
        long first = send(alice, conversation, "one", List.of()).getLong("seq");
        send(alice, conversation, "two", List.of());

        SseStream stream = open(conversation, "&since=0", Map.of("Last-Event-ID", Long.toString(first)));

        assertThat(stream.next().json().getString("text")).isEqualTo("two");
        stream.assertQuiet();
    }

    @Test
    void readAndDoneReceipts() throws Exception {
        String conversation = create(alice, "receipts", bob);
        SseStream stream = open(conversation, "", Map.of());
        long seq = send(alice, conversation, "fyi", List.of(bob)).getLong("seq");
        assertThat(stream.next().event()).isEqualTo("message");

        given().queryParam("wait", 0).when().get("/api/participants/{name}/inbox", bob).then().statusCode(200);
        SseEvent read = stream.next();
        assertThat(read.event()).isEqualTo("receipt");
        assertThat(read.json().getString("name")).isEqualTo(bob);
        assertThat(read.json().getLong("readSeq")).isEqualTo(seq);
        assertThat(read.json().getLong("doneSeq")).isZero();

        String id = given().when().get("/api/conversations/{id}/messages", conversation)
                .then().extract().jsonPath().getString("[0].id");
        given().contentType(ContentType.JSON).body(Map.of("name", bob))
                .when().post("/api/conversations/{id}/messages/{message}/done", conversation, id)
                .then().statusCode(204);
        SseEvent done = stream.next();
        assertThat(done.event()).isEqualTo("receipt");
        assertThat(done.json().getLong("readSeq")).isEqualTo(seq);
        assertThat(done.json().getLong("doneSeq")).isEqualTo(seq);
    }

    @Test
    void joinAndLeaveSendMembers() throws Exception {
        String conversation = create(alice, "members", bob);
        SseStream stream = open(conversation, "", Map.of());

        given().contentType(ContentType.JSON).body(Map.of("name", carol))
                .when().post("/api/conversations/{id}/members", conversation).then().statusCode(200);
        SseEvent joined = stream.next();
        assertThat(joined.event()).isEqualTo("members");
        assertThat(joined.json().getList("members", String.class)).containsExactlyInAnyOrder(alice, bob, carol);

        status(carol, conversation, "looking around").then().statusCode(204);
        assertThat(stream.next().json().getString("text")).isEqualTo("looking around");

        given().when().delete("/api/conversations/{id}/members/{name}", conversation, carol).then().statusCode(204);
        SseEvent cleared = stream.next();
        assertThat(cleared.event()).isEqualTo("status");
        assertThat(cleared.json().getString("name")).isEqualTo(carol);
        assertThat(cleared.json().getString("text")).isNull();
        SseEvent left = stream.next();
        assertThat(left.event()).isEqualTo("members");
        assertThat(left.json().getList("members", String.class)).containsExactlyInAnyOrder(alice, bob);
    }

    @Test
    void sendClearsTheSendersStatus() throws Exception {
        String conversation = create(alice, "busy", bob);
        SseStream stream = open(conversation, "", Map.of());
        status(bob, conversation, "running mvn verify").then().statusCode(204);
        assertThat(stream.next().json().getString("text")).isEqualTo("running mvn verify");

        send(alice, conversation, "how is it going?", List.of(bob));
        assertThat(stream.next().event()).isEqualTo("message");
        stream.assertQuiet();  // alice had no status, and bob's stays

        send(bob, conversation, "green", List.of(alice));
        SseEvent cleared = stream.next();
        assertThat(cleared.event()).isEqualTo("status");
        assertThat(cleared.json().getString("name")).isEqualTo(bob);
        assertThat(cleared.json().getString("text")).isNull();
        assertThat(stream.next().json().getString("text")).isEqualTo("green");

        // A new stream replays no status any more
        SseStream again = open(conversation, "&since=" + Long.MAX_VALUE, Map.of());
        again.assertQuiet();
    }

    @Test
    void statusIsClearedExplicitlyAndFoldedToOneShortLine() throws Exception {
        String conversation = create(alice, "status", bob);
        SseStream stream = open(conversation, "", Map.of());

        status(bob, conversation, "  line one\n\tline two  " + "x".repeat(300)).then().statusCode(204);
        String text = stream.next().json().getString("text");
        assertThat(text).startsWith("line one line two xxx").hasSize(200);

        status(bob, conversation, "").then().statusCode(204);
        assertThat(stream.next().json().getString("text")).isNull();
        status(bob, conversation, null).then().statusCode(204);
        stream.assertQuiet();  // already clear: nothing to announce
    }

    @Test
    void statusFollowsTheSendRules() {
        String conversation = create(alice, "rules", bob);

        status(carol, conversation, "sneaking in").then().statusCode(403);
        status("ghost-" + alice, conversation, "boo").then().statusCode(404);
        status(alice, "c-missing", "hi").then().statusCode(404);
    }

    @Test
    void unknownConversationIs404BeforeTheStream() throws Exception {
        SseStream stream = open("c-missing", "", Map.of());

        assertThat(stream.status()).isEqualTo(404);
    }

    @Test
    void theOldPerConversationPathIsGone() throws Exception {
        String conversation = create(alice, "old path", bob);

        SseStream stream = track(new SseStream(url.toURI().resolve("api/conversations/" + conversation + "/events"),
                Map.of()));

        assertThat(stream.status()).isEqualTo(404);
    }

    @Test
    void hubEventsForConversationsWithoutFollowingOne() throws Exception {
        SseStream hub = openHub();

        String conversation = create(alice, "hub", bob);
        SseEvent created = hub.nextHub("conversation", conversation);
        // The same JSON as an item of GET /api/conversations
        assertThat(created.json().getMap("$")).isEqualTo(given().queryParam("member", alice)
                .when().get("/api/conversations").then().statusCode(200)
                .extract().jsonPath().getMap("find { it.id == '" + conversation + "' }"));

        long sentAt = send(alice, conversation, "bump", List.of()).getLong("createdAt");
        assertThat(hub.nextHub("conversation", conversation).json().getLong("lastActivityAt")).isEqualTo(sentAt);

        given().contentType(ContentType.JSON).body(Map.of("name", carol))
                .when().post("/api/conversations/{id}/members", conversation).then().statusCode(200);
        assertThat(hub.nextHub("conversation", conversation).json().getList("members", String.class))
                .containsExactlyInAnyOrder(alice, bob, carol);

        given().when().delete("/api/conversations/{id}/members/{name}", conversation, carol).then().statusCode(204);
        assertThat(hub.nextHub("conversation", conversation).json().getList("members", String.class))
                .containsExactlyInAnyOrder(alice, bob);

        // Not following the conversation: none of its own events
        hub.assertQuiet();
    }

    @Test
    void participantEventOnRegisterThenThrottled() throws Exception {
        SseStream hub = openHub();
        String dave = "dave-" + UUID.randomUUID().toString().substring(0, 8);

        register(dave);
        SseEvent registered = hub.nextHub("participant", dave);
        assertThat(registered.json().getString("description")).isEqualTo("test");
        assertThat(registered.json().getString("status")).isEqualTo("live");
        assertThat(registered.json().getLong("lastSeen")).isPositive();
        // The same JSON as an item of GET /api/participants, apart from the clock-derived fields
        Map<String, Object> listed = given().when().get("/api/participants").then().statusCode(200)
                .extract().jsonPath().getMap("find { it.name == '" + dave + "' }");
        assertThat(registered.json().getMap("$").keySet()).isEqualTo(listed.keySet());

        // Touching last seen inside the window sends nothing now: the trailing event comes at its end (20s here),
        // which ConversationStatusExpiryTest checks with a one-second window
        given().queryParam("wait", 0).when().get("/api/participants/{name}/inbox", dave).then().statusCode(200);
        hub.assertNoHub("participant", dave, 1500);
    }

    @Test
    void conversationEventsOnlyWhenFollowing() throws Exception {
        String conversation = create(alice, "follow", bob);
        SseStream hub = openHub();
        SseStream following = open(conversation, "", Map.of());

        send(alice, conversation, "hello", List.of());

        assertThat(following.next().event()).isEqualTo("message");
        assertThat(hub.nextHub("conversation", conversation)).isNotNull();
        hub.assertQuiet();
    }

    private SseStream open(String conversation, String query, Map<String, String> headers) throws Exception {
        return track(new SseStream(url.toURI().resolve("api/events?conversation=" + conversation + query), headers));
    }

    private SseStream openHub() throws Exception {
        return track(new SseStream(url.toURI().resolve("api/events"), Map.of()));
    }

    private SseStream track(SseStream stream) {
        streams.add(stream);
        return stream;
    }

    static String register(String name) {
        given().contentType(ContentType.JSON).body(Map.of("name", name, "description", "test"))
                .when().post("/api/participants")
                .then().statusCode(200);
        return name;
    }

    static String create(String from, String title, String... members) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("from", from, "title", title, "members", List.of(members)))
                .when().post("/api/conversations")
                .then().statusCode(200)
                .extract().jsonPath().getString("id");
    }

    static JsonPath send(String from, String conversation, String text, List<String> to) {
        return given().contentType(ContentType.JSON).body(Map.of("from", from, "text", text, "to", to))
                .when().post("/api/conversations/{id}/messages", conversation)
                .then().statusCode(200)
                .extract().jsonPath();
    }

    static io.restassured.response.Response status(String name, String conversation, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("text", text);
        return given().contentType(ContentType.JSON).body(body)
                .when().post("/api/conversations/{id}/status", conversation);
    }
}
