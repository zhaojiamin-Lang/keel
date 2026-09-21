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

@SpringBootTest
class SingleShotKeelAgentAutoConfigurationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Test
    void runPingReturnsSuccess() {
        AgentResult result = keelAgent.run(request("ping"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("pong");
    }

    private AgentRequest request(String input) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .input(input)
                .build();
    }

    @SpringBootApplication
    static class TestApplication {

        @Bean
        ChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    static class StubChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("pong"))));
        }
    }
}
