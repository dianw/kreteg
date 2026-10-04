package io.kreteg.core;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.a2a.server.config.A2AConfigProvider;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class A2aConfigTest {

    @Inject
    A2AConfigProvider config;

    @Test
    void sdkReadsApplicationProperties() {
        // Falls back to the SDK default (30) if the MicroProfile provider is not indexed
        assertThat(config.getValue("a2a.blocking.agent.timeout.seconds")).isEqualTo("120");
    }
}
