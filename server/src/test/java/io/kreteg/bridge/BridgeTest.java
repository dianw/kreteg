package io.kreteg.bridge;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.TextPart;
import io.a2a.util.Utils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

@QuarkusTest
class BridgeTest {

    @Test
    void servesAgentCard() {
        JsonPath card = given().when().get("/.well-known/agent-card.json")
                .then().statusCode(200)
                .extract().jsonPath();

        assertThat(card.getString("name")).isEqualTo("Kreteg Bridge");
        assertThat(card.getBoolean("capabilities.streaming")).isTrue();
    }

    @Test
    void sendMessagePersistsTask() throws JsonProcessingException {
        Message message = new Message.Builder()
                .role(Message.Role.USER)
                .messageId(UUID.randomUUID().toString())
                .parts(new TextPart("hello"))
                .build();
        SendMessageRequest request = new SendMessageRequest("1", new MessageSendParams.Builder()
                .message(message)
                .build());
        String body = Utils.OBJECT_MAPPER.writeValueAsString(request);

        JsonPath result = given().contentType(ContentType.JSON).body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath();

        assertThat(result.getString("result.status.state")).isEqualTo("completed");
        assertThat(result.getString("result.artifacts[0].parts[0].text")).isEqualTo("echo: hello");
        String taskId = result.getString("result.id");

        JsonPath tasks = given().when().get("/api/tasks")
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(tasks.getList("id", String.class)).contains(taskId);

        JsonPath task = given().when().get("/api/tasks/" + taskId)
                .then().statusCode(200)
                .extract().jsonPath();
        assertThat(task.getString("status.state")).isEqualTo("completed");
    }
}
