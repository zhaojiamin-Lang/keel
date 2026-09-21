package io.keel.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelPrincipal;

class SingleShotKeelAgentTest {

    private final StubChatModel stubChatModel = new StubChatModel();
    private final SingleShotKeelAgent agent = new SingleShotKeelAgent(stubChatModel);

    @Test
    void returnsSuccessWithModelOutput() {
        AgentResult result = agent.run(request("ping"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("pong");
        assertThat(result.getCitations()).isEmpty();
        assertThat(result.getPendingActions()).isEmpty();
        assertThat(result.getTraceId()).isNotBlank();
    }

    @Test
    void blankInputReturnsErrorWithoutCallingModel() {
        AgentResult result = agent.run(request(" "));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.ERROR);
        assertThat(stubChatModel.getCallCount()).isZero();
    }

    private AgentRequest request(String input) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .input(input)
                .build();
    }
}
