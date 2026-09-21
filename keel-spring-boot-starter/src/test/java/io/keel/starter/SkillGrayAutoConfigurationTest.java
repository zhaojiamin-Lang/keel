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
import io.keel.core.SkillGrayPolicy;
import io.keel.skill.PercentGrayPolicy;
import io.keel.skill.SkillRegistry;

/**
 * Skill 灰度装配测试（§8 第三期）：
 * <ol>
 *   <li>配置 keel.skill-gray.* 时：skills-gray/ 目录的灰度版本注册进 SkillRegistry，
 *       策略 bean 按 strategy 创建（percent），graph 请求端到端可跑；</li>
 *   <li>未配置时：策略 bean 不存在，图内核行为零变化（灰度目录仍被加载，
 *       但没有策略意味着永远稳定版）。</li>
 * </ol>
 */
class SkillGrayAutoConfigurationTest {

    @SpringBootTest(properties = {
            "keel.agent.mode=graph",
            "keel.skill-gray.enabled=true",
            "keel.skill-gray.strategy=percent",
            "keel.skill-gray.percent=100"})
    static class EnabledGrayTest {

        @Autowired
        private KeelAgent keelAgent;

        @Autowired
        private SkillRegistry skillRegistry;

        @Autowired
        private SkillGrayPolicy skillGrayPolicy;

        @Autowired
        private GraphRagTestFixtures.QueuedChatModel chatModel;

        @Test
        void grayVersionIsRegisteredAndPolicyIsPercent() {
            assertThat(skillRegistry.get("chat")).hasValueSatisfying(
                    s -> assertThat(s.getVersion()).isEqualTo("1.0"));
            assertThat(skillRegistry.grayVersion("chat")).hasValueSatisfying(
                    g -> assertThat(g.getVersion()).isEqualTo("2.0"));
            assertThat(skillGrayPolicy).isInstanceOf(PercentGrayPolicy.class);
        }

        @Test
        void chatRequestSucceedsUnderFullGray() {
            // percent=100 全量灰度：灰度版本（requireCitation=false，无工具）
            // 不改变答复链路，端到端仍 SUCCESS
            chatModel.reset(
                    "{\"action\":\"ANSWER\",\"answer\":\"pong\"}",
                    "{\"verdict\":\"APPROVED\"}");

            AgentResult result = keelAgent.run(AgentRequest.builder()
                    .principal(new KeelPrincipal("tenant", "subject", List.of()))
                    .skill("chat")
                    .input("ping")
                    .build());

            assertThat(result.getStatus()).isEqualTo(AgentStatus.SUCCESS);
            assertThat(result.getText()).contains("pong");
        }

        @SpringBootApplication
        static class GrayApplication {

            @Bean
            ChatModel chatModel() {
                return new GraphRagTestFixtures.QueuedChatModel();
            }
        }
    }

    @SpringBootTest(properties = "keel.agent.mode=graph")
    static class DisabledGrayTest {

        @Autowired
        private SkillRegistry skillRegistry;

        @Autowired
        private org.springframework.beans.factory.ObjectProvider<SkillGrayPolicy> grayPolicies;

        @Test
        void noGrayPolicyBeanWithoutConfiguration() {
            // 灰度目录加载与策略开关解耦：目录加载成功，但没有策略 = 永远稳定版
            assertThat(skillRegistry.grayVersion("chat")).isPresent();
            assertThat(grayPolicies.getIfAvailable()).isNull();
        }

        @SpringBootApplication
        static class NoGrayApplication {

            @Bean
            ChatModel chatModel() {
                return new GraphRagTestFixtures.QueuedChatModel();
            }
        }
    }
}
