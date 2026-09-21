package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
import io.keel.core.MemoryFragment;
import io.keel.core.MemoryLayer;
import io.keel.memory.LongTermStore;

/**
 * L2/L3 记忆装配端到端测试（FR-54）：开启 keel.memory.enabled 后，
 * 预置一条 L3 长期记忆，验证其被注入到 Planner 的模型提示词中。
 */
@SpringBootTest(properties = {
        "keel.agent.mode=graph",
        "keel.memory.enabled=true"
})
class KeelMemoryAutoConfigurationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private RecordingChatModel chatModel;

    @Autowired
    private LongTermStore longTermStore;

    @Test
    void seededLongTermMemoryIsInjectedIntoPlannerPrompt() {
        longTermStore.upsert(new MemoryFragment(
                MemoryLayer.L3, "tenant", "subject", "lang", "偏好中文回答", Instant.now()));

        AgentResult result = keelAgent.run(AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .skill("chat")
                .input("你好")
                .build());

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(chatModel.planPrompts()).anyMatch(prompt -> prompt.contains("偏好中文回答"));
    }

    @SpringBootApplication
    static class MemoryTestApplication {

        @Bean
        ChatModel chatModel() {
            return new RecordingChatModel();
        }
    }

    /** 记录每次模型调用 user 文本的脚本模型：PLANNER 返回 ANSWER，其余按 Critic APPROVED。 */
    static class RecordingChatModel implements ChatModel {

        private final List<String> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            String user = prompt.getInstructions().stream()
                    .filter(message -> message instanceof UserMessage)
                    .map(Message::getText)
                    .findFirst()
                    .orElse("");
            prompts.add(user);
            String reply = user.contains("[KEEL-PLANNER]")
                    ? "{\"action\":\"ANSWER\",\"answer\":\"好的\"}"
                    : "{\"verdict\":\"APPROVED\"}";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }

        List<String> planPrompts() {
            return prompts.stream()
                    .filter(prompt -> prompt.contains("[KEEL-PLANNER]"))
                    .toList();
        }
    }
}
