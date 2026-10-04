package io.kreteg.bridge;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import io.a2a.server.PublicAgentCard;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class BridgeAgentCard {

    private final String name;
    private final String description;
    private final String version;
    private final String url;

    @Inject
    public BridgeAgentCard(
            @ConfigProperty(name = "kreteg.agent.name") String name,
            @ConfigProperty(name = "kreteg.agent.description") String description,
            @ConfigProperty(name = "kreteg.agent.version") String version,
            @ConfigProperty(name = "kreteg.agent.url") String url) {
        this.name = name;
        this.description = description;
        this.version = version;
        this.url = url;
    }

    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {
        return new AgentCard.Builder()
                .name(name)
                .description(description)
                .url(url)
                .version(version)
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .stateTransitionHistory(false)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(new AgentSkill.Builder()
                        .id("bridge")
                        .name("Bridge")
                        .description("Relays a message to the downstream agent and returns its reply")
                        .tags(List.of("bridge"))
                        .build()))
                .build();
    }
}
