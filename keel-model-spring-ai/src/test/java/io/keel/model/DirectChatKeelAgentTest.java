package io.keel.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelPrincipal;

class DirectChatKeelAgentTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final DirectChatKeelAgent agent = new DirectChatKeelAgent(chatModel);

    @Test
    void returnsUncertainWithModelText() {
        when(chatModel.call("hi")).thenReturn("hello");

        AgentResult result = agent.run(request("hi"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.UNCERTAIN);
        assertThat(result.getText()).contains("hello");
        assertThat(result.getCitations()).isEmpty();
        assertThat(result.getPendingActions()).isEmpty();
        assertThat(result.getTraceId()).isNotBlank();
    }

    @Test
    void returnsModelErrorWhenChatModelThrows() {
        when(chatModel.call(anyString())).thenThrow(new IllegalStateException("boom"));

        AgentResult result = agent.run(request("hi"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("MODEL_ERROR");
    }

    @Test
    void returnsInvalidRequestWhenInputIsBlank() {
        AgentResult result = agent.run(request(" "));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("INVALID_REQUEST");
    }

    private AgentRequest request(String input) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .input(input)
                .build();
    }
}
