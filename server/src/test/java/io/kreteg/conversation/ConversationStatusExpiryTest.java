package io.kreteg.conversation;

import static io.kreteg.conversation.ConversationEventsTest.create;
import static io.kreteg.conversation.ConversationEventsTest.register;
import static io.kreteg.conversation.ConversationEventsTest.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.kreteg.conversation.ConversationEventsTest.SseEvent;
import io.kreteg.conversation.ConversationEventsTest.SseStream;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/** The timers behind the event stream, shortened to a second so the test can watch them fire. */
@QuarkusTest
@TestProfile(ConversationStatusExpiryTest.ShortTimers.class)
class ConversationStatusExpiryTest {

    public static class ShortTimers implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kreteg.status.ttl", "1S", "kreteg.events.ping-interval", "1S",
                    "kreteg.events.presence-interval", "1S");
        }
    }

    @TestHTTPResource("/")
    URL url;

    @Test
    void statusExpiresAndPingsKeepComing() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alice = register("alice-" + suffix);
        String bob = register("bob-" + suffix);
        String conversation = create(alice, "expiry", bob);

        try (SseStream stream = new SseStream(url.toURI().resolve("api/events?conversation=" + conversation),
                Map.of())) {
            status(bob, conversation, "compiling").then().statusCode(204);
            SseEvent set = stream.next();
            assertThat(set.json().getString("text")).isEqualTo("compiling");

            long start = System.nanoTime();
            SseEvent expired = stream.next();
            assertThat(expired.event()).isEqualTo("status");
            assertThat(expired.json().getString("name")).isEqualTo(bob);
            assertThat(expired.json().getString("text")).isNull();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));

            stream.awaitPings(2);
        }
    }

    @Test
    void aThrottledTouchIsSentAtTheEndOfTheWindow() throws Exception {
        try (SseStream hub = new SseStream(url.toURI().resolve("api/events"), Map.of())) {
            String dave = register("dave-" + UUID.randomUUID().toString().substring(0, 8));
            long registeredSeen = hub.nextHub("participant", dave).json().getLong("lastSeen");

            Thread.sleep(200);
            io.restassured.RestAssured.given().queryParam("wait", 0)
                    .when().get("/api/participants/{name}/inbox", dave).then().statusCode(200);
            long touchedAt = System.nanoTime();

            // Suppressed now, sent when the one-second window ends, with the newer last seen
            SseEvent trailing = hub.nextHub("participant", dave);
            assertThat(Duration.ofNanos(System.nanoTime() - touchedAt)).isLessThan(Duration.ofSeconds(3));
            assertThat(trailing.json().getLong("lastSeen")).isGreaterThan(registeredSeen);
            // Only one for the burst
            hub.assertNoHub("participant", dave, 1500);
        }
    }
}
