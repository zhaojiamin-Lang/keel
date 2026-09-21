package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.KeelPrincipal;
import io.keel.harness.EvalCase;
import io.keel.harness.EvalReport;
import io.keel.harness.KeelEvalChecker;

@SpringBootTest
class GuardedKeelAgentIntegrationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private CountingStubChatModel chatModel;

    @Test
    void chatSkillReturnsSuccessPong() {
        AgentResult result = keelAgent.run(request("chat"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("pong");
        assertThat(result.getCitations()).isEmpty();
    }

    @Test
    void wikiQaSkillIsDemotedToUncertain() {
        AgentResult result = keelAgent.run(request("wiki-qa"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.UNCERTAIN);
        assertThat(result.getText()).isEqualTo("没有检索依据，无法给出确定结论。");
        assertThat(result.getCitations()).isEmpty();
    }

    @Test
    void unknownSkillDoesNotCallModel() {
        int callsBefore = chatModel.getCallCount();

        AgentResult result = keelAgent.run(request("not-exist"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("UNKNOWN_SKILL");
        assertThat(chatModel.getCallCount()).isEqualTo(callsBefore);
    }

    @Test
    void harnessPassesUncertainResultAndFailsManualSuccessWithoutCitation() {
        EvalCase evalCase = EvalCase.builder()
                .id("citation-guard")
                .requireCitation(true)
                .forbiddenSubstrings(List.of())
                .allowedIssueIds(List.of())
                .expectWrite(false)
                .build();
        KeelEvalChecker checker = new KeelEvalChecker();

        AgentResult guarded = keelAgent.run(request("wiki-qa"));
        EvalReport guardedReport = checker.check(evalCase, guarded);

        assertThat(guardedReport.isPassed()).isTrue();

        AgentResult fakeSuccess = AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("没有出处的结论")
                .citations(List.of())
                .pendingActions(List.of())
                .build();
        EvalReport fakeReport = checker.check(evalCase, fakeSuccess);

        assertThat(fakeReport.isPassed()).isFalse();
        assertThat(fakeReport.getFailedRules())
                .contains(KeelEvalChecker.RULE_MISSING_CITATION);
    }

    private AgentRequest request(String skill) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .input("ping")
                .skill(skill)
                .build();
    }

    @SpringBootApplication
    static class TestApplication {

        @Bean
        CountingStubChatModel chatModel() {
            return new CountingStubChatModel();
        }
    }

    static class CountingStubChatModel implements ChatModel {

        private int callCount;

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount++;
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("pong"))));
        }

        int getCallCount() {
            return callCount;
        }
    }
}
