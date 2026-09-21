package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * FR-61：运行时业务 ID 验证测试。
 * 模型在答案中编造 ISSUE-9999（不在 principal.resourceIds 内），
 * ResourceIdGuardPort 必须拒绝（FABRICATED_ID）。
 */
@SpringBootTest(properties = "keel.agent.mode=graph")
class ResourceIdGuardPortAutoConfigurationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private SingleReplyChatModel chatModel;

    @Test
    void fabricatedResourceIdIsRejectedAtRuntime() {
        chatModel.reply = "{\"action\":\"ANSWER\",\"answer\":\"已查询工单 ISSUE-9999 的状态\"}";
        chatModel.critic = "{\"verdict\":\"APPROVED\"}";

        AgentResult result = keelAgent.run(AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of("ISSUE-1001")))
                .skill("chat")
                .input("查一下我的工单")
                .build());

        assertThat(result.getStatus()).isEqualTo(AgentStatus.REJECTED);
        assertThat(result.getErrorCode()).isEqualTo("FABRICATED_ID");
        assertThat(result.getText()).contains("ISSUE-9999");
    }

    @Test
    void allowedResourceIdPassesThrough() {
        chatModel.reply = "{\"action\":\"ANSWER\",\"answer\":\"工单 ISSUE-1001 已处理\"}";
        chatModel.critic = "{\"verdict\":\"APPROVED\"}";

        AgentResult result = keelAgent.run(AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of("ISSUE-1001")))
                .skill("chat")
                .input("查一下 ISSUE-1001")
                .build());

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("ISSUE-1001");
    }

    @Test
    void emptyResourceIdsSkipsCheck() {
        // principal.resourceIds 为空时不检测（无资源约束的场景不强制）
        chatModel.reply = "{\"action\":\"ANSWER\",\"answer\":\"工单 ISSUE-12345 状态正常\"}";
        chatModel.critic = "{\"verdict\":\"APPROVED\"}";

        AgentResult result = keelAgent.run(AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .skill("chat")
                .input("查一下工单")
                .build());

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
    }

    @SpringBootApplication
    static class ResourceIdGuardTestApplication {

        @Bean
        ChatModel chatModel() {
            return new SingleReplyChatModel();
        }
    }

    /** 按固定字符串应答的脚本模型。 */
    static class SingleReplyChatModel implements ChatModel {

        private String reply;
        private String critic;

        @Override
        public ChatResponse call(Prompt prompt) {
            String user = prompt.getInstructions().stream()
                    .filter(message -> message instanceof UserMessage)
                    .map(Message::getText)
                    .findFirst()
                    .orElse("");
            String response = user.contains("[KEEL-PLANNER]") ? reply : critic;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }
    }
}