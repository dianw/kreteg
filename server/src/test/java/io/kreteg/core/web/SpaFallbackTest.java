package io.kreteg.core.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

@QuarkusTest
class SpaFallbackTest {

    @Test
    void pageUrlServesTheApp() {
        Response root = page("/");
        Response deepLink = page("/conversations/abc");

        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(deepLink.statusCode()).isEqualTo(200);
        assertThat(deepLink.contentType()).startsWith("text/html");
        assertThat(deepLink.asString()).isEqualTo(root.asString());
    }

    @Test
    void unknownApiPathStaysNotFound() {
        Response response = page("/api/nope");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.asString()).isNotEqualTo(page("/").asString());
    }

    @Test
    void missingAssetStaysNotFound() {
        assertThat(page("/_nuxt/missing.js").statusCode()).isEqualTo(404);
    }

    @Test
    void nonBrowserRequestIsNotRerouted() {
        assertThat(given().accept("application/json").get("/conversations/abc").statusCode()).isEqualTo(404);
    }

    @Test
    void apiStillAnswers() {
        Response response = page("/api/participants");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.contentType()).startsWith("application/json");
    }

    private static Response page(String path) {
        return given().accept("text/html,application/xhtml+xml,*/*;q=0.8").get(path);
    }
}
