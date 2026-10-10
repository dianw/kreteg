package io.kreteg.conversation;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

@QuarkusTest
class ConversationResourceTest {

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
    void announcementReachesEveryOtherMemberOnce() {
        String conversation = create(alice, "release", bob, carol);

        send(alice, conversation, Map.of("text", "release is tomorrow"));

        JsonPath bobInbox = inbox(bob, 0);
        assertThat(bobInbox.getList("text", String.class)).containsExactly("release is tomorrow");
        assertThat(bobInbox.getList("[0].to", String.class)).isEmpty();
        assertThat(bobInbox.getString("[0].from")).isEqualTo(alice);
        assertThat(bobInbox.getString("[0].title")).isEqualTo("release");
        assertThat(inbox(carol, 0).getList("text", String.class)).containsExactly("release is tomorrow");
        assertThat(inbox(alice, 0).getList("$")).isEmpty();
        assertThat(inbox(bob, 0).getList("$")).isEmpty();
    }

    @Test
    void addressedMessageReachesOnlyNamedMembers() {
        String conversation = create(alice, "changelog", bob, carol);

        long seq = send(alice, conversation, Map.of("text", "who owns the changelog?", "to", List.of(bob)))
                .getLong("seq");

        JsonPath bobInbox = inbox(bob, 0);
        assertThat(bobInbox.getList("text", String.class)).containsExactly("who owns the changelog?");
        assertThat(bobInbox.getList("[0].to", String.class)).containsExactly(bob);
        assertThat(inbox(carol, 0).getList("$")).isEmpty();
        // Skipped, not lost: history still shows it, and carol's cursor has moved past it
        assertThat(given().when().get("/api/conversations/{id}/messages", conversation)
                .then().statusCode(200)
                .extract().jsonPath().getList("text", String.class))
                .containsExactly("who owns the changelog?");
        assertThat(readSeq(conversation)).containsEntry(carol, seq);
    }

    @Test
    void waitingNonAddresseeGetsNothing() throws Exception {
        String conversation = create(alice, "quiet", bob, carol);

        CompletableFuture<JsonPath> carolWaiting = CompletableFuture.supplyAsync(() -> inbox(carol, 3));
        TimeUnit.MILLISECONDS.sleep(500);
        send(alice, conversation, Map.of("text", "bob only", "to", List.of(bob)));

        assertThat(carolWaiting.get(10, TimeUnit.SECONDS).getList("$")).isEmpty();
        assertThat(inbox(bob, 0).getList("text", String.class)).containsExactly("bob only");
    }

    @Test
    void truncatedInboxKeepsLaterAddressedMessages() {
        String conversation = create(alice, "batch", bob, carol);
        send(alice, conversation, Map.of("text", "carol 1", "to", List.of(carol)));
        long first = send(alice, conversation, Map.of("text", "bob 1", "to", List.of(bob))).getLong("seq");
        send(alice, conversation, Map.of("text", "carol 2", "to", List.of(carol)));
        send(alice, conversation, Map.of("text", "bob 2", "to", List.of(bob)));
        long last = send(alice, conversation, Map.of("text", "carol 3", "to", List.of(carol))).getLong("seq");

        assertThat(inbox(bob, 0, 1).getList("text", String.class)).containsExactly("bob 1");
        assertThat(readSeq(conversation)).containsEntry(bob, first);
        assertThat(inbox(bob, 0, 1).getList("text", String.class)).containsExactly("bob 2");
        assertThat(inbox(bob, 0, 1).getList("$")).isEmpty();
        assertThat(readSeq(conversation)).containsEntry(bob, last);
    }

    @Test
    void replyIsThreadedInHistory() {
        String conversation = create(alice, "naming", bob);
        String question = send(alice, conversation, Map.of("text", "kreteg or bridge?", "to", List.of(bob)))
                .getString("id");

        send(bob, conversation, Map.of("text", "kreteg", "to", List.of(alice), "replyTo", question));

        JsonPath history = given().when().get("/api/conversations/{id}/messages", conversation)
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(history.getList("text", String.class)).containsExactly("kreteg or bridge?", "kreteg");
        assertThat(history.getString("[1].replyTo")).isEqualTo(question);
        assertThat(inbox(alice, 0).getString("[0].replyTo")).isEqualTo(question);
    }

    @Test
    void conversationsAreSeparate() {
        String withBob = create(alice, "one", bob);
        String withCarol = create(alice, "two", carol);

        send(alice, withBob, Map.of("text", "for bob"));
        send(alice, withCarol, Map.of("text", "for carol"));

        assertThat(inbox(bob, 0).getList("text", String.class)).containsExactly("for bob");
        assertThat(inbox(carol, 0).getList("text", String.class)).containsExactly("for carol");
        assertThat(given().queryParam("member", alice).when().get("/api/conversations")
                .then().statusCode(200)
                .extract().jsonPath().getList("id", String.class))
                .containsExactlyInAnyOrder(withBob, withCarol);
    }

    @Test
    void joiningMemberStartsAtEndOfLog() {
        String conversation = create(alice, "late join", bob);
        send(alice, conversation, Map.of("text", "before"));

        given().contentType(ContentType.JSON).body(Map.of("name", carol))
                .when().post("/api/conversations/{id}/members", conversation)
                .then().statusCode(200);
        send(alice, conversation, Map.of("text", "after"));

        assertThat(inbox(carol, 0).getList("text", String.class)).containsExactly("after");
    }

    @Test
    void conversationShowsHowFarEachMemberHasRead() {
        String conversation = create(alice, "receipts", bob, carol);
        long seq = send(alice, conversation, Map.of("text", "anyone?")).getLong("seq");

        inbox(bob, 0);
        JsonPath detail = given().when().get("/api/conversations/{id}", conversation)
                .then().statusCode(200)
                .extract().jsonPath();

        assertThat(detail.getString("title")).isEqualTo("receipts");
        assertThat(detail.getList("members", String.class)).containsExactlyInAnyOrder(alice, bob, carol);
        assertThat(detail.getMap("readSeq", String.class, Long.class))
                .containsOnlyKeys(alice, bob, carol)
                .containsEntry(bob, seq)
                .containsEntry(carol, 0L)
                // A sender's own messages never pass through their inbox
                .containsEntry(alice, 0L);
    }

    @Test
    void historyPagesBackwardsWithBefore() {
        String conversation = create(alice, "long thread", bob);
        for (int i = 1; i <= 5; i++) {
            send(alice, conversation, Map.of("text", "m" + i));
        }

        JsonPath latest = given().queryParam("before", Long.MAX_VALUE).queryParam("limit", 2)
                .when().get("/api/conversations/{id}/messages", conversation)
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(latest.getList("text", String.class)).containsExactly("m4", "m5");

        long oldestShown = latest.getLong("[0].seq");
        assertThat(given().queryParam("before", oldestShown).queryParam("limit", 2)
                .when().get("/api/conversations/{id}/messages", conversation)
                .then().statusCode(200)
                .extract().jsonPath().getList("text", String.class))
                .containsExactly("m2", "m3");

        given().queryParam("before", oldestShown).queryParam("since", 0)
                .when().get("/api/conversations/{id}/messages", conversation)
                .then().statusCode(400);
    }

    @Test
    void conversationsAreListedByLatestActivity() {
        String older = create(alice, "older", bob);
        String newer = create(alice, "newer", bob);
        long sentAt = send(alice, older, Map.of("text", "bump")).getLong("createdAt");

        JsonPath conversations = given().queryParam("member", alice)
                .when().get("/api/conversations")
                .then().statusCode(200)
                .extract().jsonPath();

        assertThat(conversations.getList("id", String.class)).containsExactly(older, newer);
        assertThat(conversations.getLong("[0].lastActivityAt")).isEqualTo(sentAt);
        // No messages yet: the conversation's own creation time
        assertThat(conversations.getLong("[1].lastActivityAt")).isEqualTo(conversations.getLong("[1].createdAt"));
    }

    @Test
    void unknownConversationIsNotFound() {
        given().when().get("/api/conversations/{id}", "c-missing")
                .then().statusCode(404);
    }

    @Test
    void longPollReturnsWhenMessageArrives() throws Exception {
        String conversation = create(alice, "wake", bob);

        CompletableFuture<JsonPath> waiting = CompletableFuture.supplyAsync(() -> inbox(bob, 20));
        TimeUnit.MILLISECONDS.sleep(500);
        long start = System.nanoTime();
        send(alice, conversation, Map.of("text", "ping"));

        JsonPath result = waiting.get(10, TimeUnit.SECONDS);
        assertThat(result.getList("text", String.class)).containsExactly("ping");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void rejectsInvalidSends() {
        String conversation = create(alice, "rules", bob);

        given().contentType(ContentType.JSON).body(Map.of("from", carol, "text", "let me in"))
                .when().post("/api/conversations/{id}/messages", conversation)
                .then().statusCode(403);
        given().contentType(ContentType.JSON).body(Map.of("from", alice, "text", "hi", "to", List.of(carol)))
                .when().post("/api/conversations/{id}/messages", conversation)
                .then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("from", "nobody-" + alice, "text", "hi"))
                .when().post("/api/conversations/{id}/messages", conversation)
                .then().statusCode(404);
        given().contentType(ContentType.JSON).body(Map.of("from", alice, "text", "hi", "replyTo", "m-missing"))
                .when().post("/api/conversations/{id}/messages", conversation)
                .then().statusCode(404);
    }

    @Test
    void listsParticipantsWithStatus() {
        JsonPath participants = given().when().get("/api/participants")
                .then().statusCode(200)
                .extract().jsonPath();

        assertThat(participants.getList("name", String.class)).contains(alice, bob, carol);
        assertThat(participants.getString("find { it.name == '" + alice + "' }.status")).isEqualTo("live");
    }

    private static String register(String name) {
        given().contentType(ContentType.JSON).body(Map.of("name", name, "description", "test"))
                .when().post("/api/participants")
                .then().statusCode(200);
        return name;
    }

    private static String create(String from, String title, String... members) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("from", from, "title", title, "members", List.of(members)))
                .when().post("/api/conversations")
                .then().statusCode(200)
                .extract().jsonPath().getString("id");
    }

    private static JsonPath send(String from, String conversation, Map<String, Object> fields) {
        Map<String, Object> body = new java.util.HashMap<>(fields);
        body.put("from", from);
        return given().contentType(ContentType.JSON).body(body)
                .when().post("/api/conversations/{id}/messages", conversation)
                .then().statusCode(200)
                .extract().jsonPath();
    }

    private static JsonPath inbox(String name, int waitSeconds) {
        return inbox(name, waitSeconds, 50);
    }

    private static JsonPath inbox(String name, int waitSeconds, int limit) {
        return given().queryParam("wait", waitSeconds).queryParam("limit", limit)
                .when().get("/api/participants/{name}/inbox", name)
                .then().statusCode(200)
                .extract().jsonPath();
    }

    private static Map<String, Long> readSeq(String conversation) {
        return given().when().get("/api/conversations/{id}", conversation)
                .then().statusCode(200)
                .extract().jsonPath().getMap("readSeq", String.class, Long.class);
    }
}
