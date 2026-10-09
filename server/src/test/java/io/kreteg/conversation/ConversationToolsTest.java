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
    void listsAllTools() {
        McpAssured.newConnectedStreamableClient().when()
                .toolsList(page -> assertThat(page.tools().stream().map(McpAssured.ToolInfo::name))
                        .containsExactlyInAnyOrder("register", "who", "create_conversation", "join", "leave",
                                "my_conversations", "send", "inbox", "history"))
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
