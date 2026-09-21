package io.keel.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.Citation;
import io.keel.core.KeelPrincipal;
import io.keel.core.PendingAction;
import io.keel.core.SkillDefinition;

class CitationGuardTest {

    private final CitationGuard guard = new CitationGuard();

    @Test
    void demotesWikiQaSuccessWithoutCitation() {
        AgentResult result = result(
                AgentStatus.SUCCESS,
                List.of(),
                List.of(new PendingAction("a1", "readWiki", "{}")));

        AgentResult guarded = guard.apply(request("wiki-qa"), result, skill("wiki-qa", true));

        assertThat(guarded.getStatus()).isEqualTo(AgentStatus.UNCERTAIN);
        assertThat(guarded.getText()).isEqualTo(CitationGuard.UNCERTAIN_TEXT);
        assertThat(guarded.getCitations()).isEmpty();
        assertThat(guarded.getPendingActions()).isEmpty();
        assertThat(guarded.getTraceId()).isEqualTo("trace");
    }

    @Test
    void keepsChatSuccessWithoutCitation() {
        AgentResult result = result(AgentStatus.SUCCESS, List.of(), List.of());

        AgentResult guarded = guard.apply(request("chat"), result, skill("chat", false));

        assertThat(guarded).isSameAs(result);
        assertThat(guarded.getStatus()).isEqualTo(AgentStatus.SUCCESS);
    }

    @Test
    void keepsWikiQaSuccessWithCitation() {
        AgentResult result = result(
                AgentStatus.SUCCESS,
                List.of(new Citation("chunk-1", "wiki://page/1", "snippet")),
                List.of());

        AgentResult guarded = guard.apply(request("wiki-qa"), result, skill("wiki-qa", true));

        assertThat(guarded).isSameAs(result);
        assertThat(guarded.getStatus()).isEqualTo(AgentStatus.SUCCESS);
    }

    @Test
    void keepsNonSuccessStatusUnchanged() {
        AgentResult result = result(AgentStatus.REJECTED, List.of(), List.of());

        AgentResult guarded = guard.apply(request("wiki-qa"), result, skill("wiki-qa", true));

        assertThat(guarded).isSameAs(result);
    }

    @Test
    void keepsResultWhenSkillIsNull() {
        AgentResult result = result(AgentStatus.SUCCESS, List.of(), List.of());

        AgentResult guarded = guard.apply(request("chat"), result, null);

        assertThat(guarded).isSameAs(result);
    }

    private SkillDefinition skill(String name, boolean requireCitation) {
        return SkillDefinition.builder()
                .name(name)
                .requireCitation(requireCitation)
                .writable(false)
                .build();
    }

    private AgentRequest request(String skill) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .input("ping")
                .skill(skill)
                .build();
    }

    private AgentResult result(
            AgentStatus status,
            List<Citation> citations,
            List<PendingAction> pendingActions) {
        return AgentResult.builder()
                .status(status)
                .text("pong")
                .citations(citations)
                .pendingActions(pendingActions)
                .traceId("trace")
                .build();
    }
}
