package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.KeelPrincipal;
import io.keel.graph.Verdict;
import io.keel.graph.spi.GuardPort;

/**
 * GuardPort 桥接 keel-guard 的装配测试（FR-64）：
 * <ol>
 *   <li>默认装配时，图内 GuardPort 是 {@link CompositeGuardPort}（组合 ResourceIdGuardPort + CitationGuardPort）；</li>
 *   <li>业务自定义 GuardPort bean 时，默认的不创建，且图真正使用业务规则（rejected → REJECTED）。</li>
 * </ol>
 */
@SpringBootTest(properties = "keel.agent.mode=graph")
class GraphGuardPortAutoConfigurationTest {

    @Autowired
    private KeelAgent keelAgent;

    @Autowired
    private GraphRagTestFixtures.QueuedChatModel chatModel;

    @Autowired
    private GuardPort guardPort;

    @Test
    void defaultGuardPortIsCompositeWithResourceIdAndCitation() {
        // FR-64：默认 GuardPort 是 CompositeGuardPort，组合 ResourceIdGuardPort（FR-61）+ CitationGuardPort（FR-42）
        assertThat(guardPort).isInstanceOf(CompositeGuardPort.class);
    }

    @Test
    void readOnlySkillAnswerStillSucceedsUnderDefaultGuard() {
        // chat 不 requireCitation，CitationGuardPort 放行 → SUCCESS
        chatModel.reset(
                "{\"action\":\"ANSWER\",\"answer\":\"pong\"}",
                "{\"verdict\":\"APPROVED\"}");

        AgentResult result = keelAgent.run(request("chat", "ping"));

        assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.getText()).contains("pong");
    }

    private AgentRequest request(String skill, String input) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal("tenant", "subject", List.of()))
                .skill(skill)
                .input(input)
                .build();
    }

    @SpringBootApplication
    static class DefaultGuardTestApplication {

        @Bean
        ChatModel chatModel() {
            return new GraphRagTestFixtures.QueuedChatModel();
        }
    }

    /**
     * 业务自定义 GuardPort：拒绝一切（模拟发布冻结窗口）。
     * 验证 @ConditionalOnMissingBean 让默认 CitationGuardPort 让位，且图真正执行此规则。
     */
    @SpringBootTest(properties = "keel.agent.mode=graph")
    static class BusinessGuardOverrideTest {

        @Autowired
        private KeelAgent keelAgent;

        @Autowired
        private GraphRagTestFixtures.QueuedChatModel chatModel;

        @Test
        void businessGuardPortOverridesDefaultAndRejects() {
            chatModel.reset(
                    "{\"action\":\"ANSWER\",\"answer\":\"今天可以关单\"}",
                    "{\"verdict\":\"APPROVED\"}");

            AgentResult result = keelAgent.run(request("chat", "今天能关单吗"));

            assertThat(result.getStatus()).isEqualTo(AgentStatus.REJECTED);
            assertThat(result.getErrorCode()).isEqualTo("RELEASE_FROZEN");
        }

        private AgentRequest request(String skill, String input) {
            return AgentRequest.builder()
                    .principal(new KeelPrincipal("tenant", "subject", List.of()))
                    .skill(skill)
                    .input(input)
                    .build();
        }

        @SpringBootApplication
        static class OverrideApplication {

            @Bean
            ChatModel chatModel() {
                return new GraphRagTestFixtures.QueuedChatModel();
            }

            @Bean
            GuardPort customGuardPort() {
                return state -> Verdict.rejected(
                        "RELEASE_FROZEN", "发布冻结窗口内禁止该操作");
            }
        }
    }
}
