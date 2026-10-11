package io.kreteg.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The built artifact (native executable or JVM jar) run as a separate process, the way agents run it: proves the
 * picocli config made it into the native image and that client commands don't start a second server.
 */
@QuarkusIntegrationTest
class CliIT {

    record Result(int code, String out, String err) {
    }

    @TestHTTPResource("/")
    URL url;

    @Test
    void roundTripThroughTheBinary() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alice = "alice-" + suffix;
        String bob = "bob-" + suffix;
        assertThat(kreteg("", "register", "--from", alice).code()).isZero();
        assertThat(kreteg("", "register", "--from", bob, "--description", "native test").code()).isZero();

        Result created = kreteg("", "create", "--from", alice, "--title", "native", "--members", bob, "--json");
        assertThat(created.code()).as(created.err()).isZero();
        String conversation = Hub.JSON.readTree(created.out()).path("id").asText();

        String text = "`$HOME` stays \"as is\"\n```sh\necho $(date)\n```";
        Result sent = kreteg(text, "send", "--from", alice, "--to", bob, conversation);
        assertThat(sent.code()).as(sent.err()).isZero();

        Result inbox = kreteg("", "inbox", "--from", bob, "--json");
        assertThat(inbox.code()).as(inbox.err()).isZero();
        JsonNode messages = Hub.JSON.readTree(inbox.out());
        assertThat(messages.findValuesAsText("text")).containsExactly(text);

        Result who = kreteg("", "who");
        assertThat(who.out()).contains(bob, "native test");
    }

    @Test
    void versionGuideAndUsageErrorsWithoutAHub() throws Exception {
        Result version = kreteg("", "--version");
        assertThat(version.code()).as(version.err()).isZero();
        assertThat(version.out()).startsWith("kreteg ");

        Result guide = kreteg("", "guide");
        assertThat(guide.code()).as(guide.err()).isZero();
        assertThat(guide.out()).contains("## Listen", "kreteg watch --once NAME", "Monitor({");

        assertThat(kreteg("", "inbox").code()).isEqualTo(2);
        assertThat(kreteg("", "no-such-command").code()).isEqualTo(2);
    }

    private Result kreteg(String stdin, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(launcher());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("KRETEG_URL", url.toString());
        Path out = Files.createTempFile("kreteg-out", ".txt");
        Path err = Files.createTempFile("kreteg-err", ".txt");
        Process process = builder.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("kreteg %s finished", String.join(" ", args)).isTrue();
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    /** What Quarkus built, as recorded in {@code target/quarkus-artifact.properties}. */
    private static List<String> launcher() throws IOException {
        Properties artifact = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("target", "quarkus-artifact.properties"))) {
            artifact.load(in);
        }
        Path path = Path.of("target").resolve(artifact.getProperty("path"));
        return "native".equals(artifact.getProperty("type"))
                ? List.of(path.toAbsolutePath().toString())
                : List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
                        path.toAbsolutePath().toString());
    }
}
