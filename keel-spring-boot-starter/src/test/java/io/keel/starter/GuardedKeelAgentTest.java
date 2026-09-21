package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;
import io.keel.guard.CitationGuard;
import io.keel.skill.SkillRegistry;

class GuardedKeelAgentTest {

    private KeelAgent delegate;
    private SkillRegistry registry;
    private GuardedKeelAgent agent;

    @BeforeEach
    void setUp() {
        delegate = mock(KeelAgent.class);
        registry = new SkillRegistry(List.of(
                SkillDefinition.builder()
                        .name("chat")
                        .requireCitation(false)
                        .writable(false)
                        .build(),
                SkillDefinition.builder()
                        .name("wiki-qa")
                        .requireCitation(true)
                        .writable(false)
                        .build()));
        agent = new GuardedKeelAgent(delegate, registry, new CitationGuard());
    }

    @Test
    void returnsUnknownSkillWithoutCallingDelegate() {
        AgentRequest request = request("not-exist");

        AgentResult result = agent.run(request);

        assertThat(result.getStatus()).isEqualTo(AgentStatus.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("UNKNOWN_SKILL");
        verify(delegate, never()).run(request);
    }

    @Test
    void defaultsToChatWhenSkillIsBlank() {
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .input("ping")
                .build();
        when(delegate.run(request)).thenReturn(AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("pong")
                .citations(List.of())
                .pendingActions(List.of())
                .traceId("trace")
                .build());

        AgentResult result = agent.run(request);

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("pong");
    }

    @Test
    void demotesWikiQaSuccessWithoutCitation() {
        AgentRequest request = request("wiki-qa");
        when(delegate.run(request)).thenReturn(AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("pong")
                .citations(List.of())
                .pendingActions(List.of())
                .traceId("trace")
                .build());

        AgentResult result = agent.run(request);

        assertThat(result.getStatus()).isEqualTo(AgentStatus.UNCERTAIN);
        assertThat(result.getText()).isEqualTo(CitationGuard.UNCERTAIN_TEXT);
        assertThat(result.getCitations()).isEmpty();
    }

    @Test
    void returnsErrorWhenDelegateThrows() {
        AgentRequest request = request("chat");
        when(delegate.run(request)).thenThrow(new IllegalStateException("boom"));

        AgentResult result = agent.run(request);

        assertThat(result.getStatus()).isEqualTo(AgentStatus.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("AGENT_ERROR");
    }

    private AgentRequest request(String skill) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .input("ping")
                .skill(skill)
                .build();
    }
}
