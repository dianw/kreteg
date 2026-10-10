package io.kreteg.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@QuarkusTest
class ConversationToolsTest {

    @BeforeEach
    void pointClientAtServer() {
        // Set automatically under @QuarkusTest only; the native ConversationToolsIT needs it too
        McpAssured.baseUri = URI.create(RestAssured.baseURI + ":" + RestAssured.port + "/");
    }

    @Test
    void agentsConverseThroughTools() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alice = "alice-" + suffix;
        String bob = "bob-" + suffix;
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        String[] conversation = new String[1];

        client.when()
                .toolsCall("register", Map.of("me", alice, "description", "frontend"),
                        r -> assertThat(object(r).getString("status")).isEqualTo("live"))
                .toolsCall("register", Map.of("me", bob), r -> assertThat(r.isError()).isFalse())
                .thenAssertResults();

        client.when()
                .toolsCall("create_conversation", Map.of("me", alice, "title", "api shape", "members", List.of(bob)),
                        r -> {
                            JsonObject created = object(r);
                            conversation[0] = created.getString("id");
                            assertThat(created.getJsonArray("members").stream().map(String.class::cast)).containsExactly(alice, bob);
                        })
                .thenAssertResults();

        client.when()
                .toolsCall("send", Map.of("me", alice, "conversation", conversation[0], "text", "REST or MCP?",
                        "to", List.of(bob)), r -> assertThat(object(r).getString("id")).startsWith("m-"))
                .thenAssertResults();

        // Calls batched in one when() run concurrently, so each step that depends on the previous gets its own
        client.when()
                .toolsCall("inbox", Map.of("me", bob), r -> {
                    JsonArray messages = messages(r);
                    assertThat(messages).hasSize(1);
                    assertThat(messages.getJsonObject(0).getString("text")).isEqualTo("REST or MCP?");
                    assertThat(messages.getJsonObject(0).getString("from")).isEqualTo(alice);
                })
                .thenAssertResults();

        client.when()
                .toolsCall("inbox", Map.of("me", bob), r -> assertThat(messages(r)).isEmpty())
                .thenAssertResults();
    }

    @Test
    void businessErrorsBecomeToolErrors() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();

        client.when()
                .toolsCall("inbox", Map.of("me", "ghost-" + UUID.randomUUID()), r -> {
                    assertThat(r.isError()).isTrue();
                    assertThat(r.firstContent().asText().text()).contains("is not registered");
                })
                .thenAssertResults();
    }

    @Test
    void doneClosesAnAskWithoutAnAnswer() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alice = "alice-" + suffix;
        String bob = "bob-" + suffix;
        String carol = "carol-" + suffix;
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        String[] ids = new String[3];

        client.when()
                .toolsCall("register", Map.of("me", alice), r -> assertThat(r.isError()).isFalse())
                .toolsCall("register", Map.of("me", bob), r -> assertThat(r.isError()).isFalse())
                .toolsCall("register", Map.of("me", carol), r -> assertThat(r.isError()).isFalse())
                .thenAssertResults();
        client.when()
                .toolsCall("create_conversation", Map.of("me", alice, "title", "fyi", "members", List.of(bob)),
                        r -> ids[0] = object(r).getString("id"))
                .thenAssertResults();
        client.when()
                .toolsCall("send", Map.of("me", alice, "conversation", ids[0], "text", "first", "to", List.of(bob)),
                        r -> ids[1] = object(r).getString("id"))
                .thenAssertResults();
        client.when()
                .toolsCall("send", Map.of("me", alice, "conversation", ids[0], "text", "no need to reply",
                        "to", List.of(bob)), r -> ids[2] = object(r).getString("id"))
                .thenAssertResults();

        client.when()
                .toolsCall("done", Map.of("me", bob, "conversation", ids[0], "message", ids[2]),
                        r -> assertThat(r.isError()).isFalse())
                .thenAssertResults();
        // Closing an older ask afterwards keeps the newer mark
        client.when()
                .toolsCall("done", Map.of("me", bob, "conversation", ids[0], "message", ids[1]),
                        r -> assertThat(r.isError()).isFalse())
                .thenAssertResults();

        List<Map<String, Object>> history = RestAssured.given()
                .when().get("/api/conversations/{id}/messages", ids[0])
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        // done posts nothing
        assertThat(history).hasSize(2);
        long askSeq = ((Number) history.get(1).get("seq")).longValue();
        Map<String, Long> doneSeq = RestAssured.given()
                .when().get("/api/conversations/{id}", ids[0])
                .then().statusCode(200)
                .extract().jsonPath().getMap("doneSeq", String.class, Long.class);
        assertThat(doneSeq).containsEntry(bob, askSeq).containsEntry(alice, 0L);

        client.when()
                .toolsCall("done", Map.of("me", bob, "conversation", ids[0], "message", "m-missing"), r -> {
                    assertThat(r.isError()).isTrue();
                    assertThat(r.firstContent().asText().text()).contains("No message m-missing");
                })
                .toolsCall("done", Map.of("me", carol, "conversation", ids[0], "message", ids[2]), r -> {
                    assertThat(r.isError()).isTrue();
                    assertThat(r.firstContent().asText().text()).contains("is not a member");
                })
                .thenAssertResults();
    }

    @Test
    void listsAllTools() {
        McpAssured.newConnectedStreamableClient().when()
                .toolsList(page -> assertThat(page.tools().stream().map(McpAssured.ToolInfo::name))
                        .containsExactlyInAnyOrder("register", "who", "create_conversation", "join", "leave",
                                "my_conversations", "send", "done", "inbox", "history"))
                .thenAssertResults();
    }

    private static JsonObject object(ToolResponse response) {
        assertThat(response.isError()).as(response.toString()).isFalse();
        return new JsonObject(response.firstContent().asText().text());
    }

    private static JsonArray messages(ToolResponse response) {
        return object(response).getJsonArray("messages");
    }
}
