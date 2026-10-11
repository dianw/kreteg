package io.kreteg.cli;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The hub's REST API at {@code /api}, as the CLI sees it. JSON goes through Jackson's tree model, which the native
 * image can use without reflection config.
 */
class Hub {

    /** The server answered with an error status; {@code message} is its {@code error} text when it sent one. */
    static class HubException extends RuntimeException {
        HubException(String message) {
            super(message);
        }
    }

    /** No answer from the hub at all: it isn't running, or {@code KRETEG_URL} points elsewhere. */
    static class UnreachableException extends RuntimeException {
        UnreachableException(String url, Throwable cause) {
            super("cannot reach Kreteg at " + url + " (" + describe(cause) + "); is it running?", cause);
        }

        private static String describe(Throwable cause) {
            return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        }
    }

    static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http;

    Hub(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    String baseUrl() {
        return baseUrl;
    }

    /** {@code path} with each {@code {}} replaced by the next segment, percent-encoded. */
    static String path(String template, String... segments) {
        StringBuilder path = new StringBuilder();
        int from = 0;
        for (String segment : segments) {
            int at = template.indexOf("{}", from);
            path.append(template, from, at)
                    .append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
            from = at + 2;
        }
        return path.append(template.substring(from)).toString();
    }

    JsonNode get(String path, Duration timeout) {
        return send(request(path, timeout).GET());
    }

    JsonNode get(String path) {
        return get(path, Duration.ofSeconds(30));
    }

    JsonNode post(String path, ObjectNode body) {
        return send(request(path, Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    JsonNode delete(String path) {
        return send(request(path, Duration.ofSeconds(30)).DELETE());
    }

    private HttpRequest.Builder request(String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout).header("Accept", "application/json");
    }

    /** The response body as JSON, or a missing node for an empty one (204). */
    private JsonNode send(HttpRequest.Builder request) {
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new UnreachableException(baseUrl, e);
        } catch (HttpTimeoutException e) {
            throw new HubException("no answer from " + baseUrl + " in time");
        } catch (IOException e) {
            throw new UnreachableException(baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HubException("interrupted");
        }
        JsonNode body = parse(response.body());
        if (response.statusCode() >= 400) {
            String error = body.path("error").asText("");
            throw new HubException(error.isEmpty() ? "HTTP " + response.statusCode() : error);
        }
        return body;
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return JSON.missingNode();
        }
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return JSON.missingNode();
        }
    }
}
