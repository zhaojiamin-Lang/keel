package io.keel.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.chat.model.ChatModel;

import io.keel.core.KeelPrincipal;
import io.keel.mcp.auth.CredentialProvider;
import io.keel.mcp.auth.StaticCredentialProvider;

/**
 * FR-31：keel.tools.mcp.credentials / require-principal-match 配置绑定的装配测试。
 * 验证 starter 按配置构造 StaticCredentialProvider：
 * 配置过的 server 视为有凭证要求；requirePrincipalMatch=true 时 resourceIds
 * 不含 server 的 principal 解析不到凭证（由 gateway fail-closed 拒绝）。
 */
@SpringBootTest(properties = {
        "keel.agent.mode=graph",
        // 测试 classpath 里的 mcp-multi skill 声明了这些工具，须登记进白名单才能通过装配校验
        "keel.tools.allowed[0]=search_orders",
        "keel.tools.allowed[1]=issue.create",
        "keel.tools.allowed[2]=gitlab.search_issues",
        "keel.tools.allowed[3]=oa.list_approvals",
        "keel.tools.mcp.credentials.gitlab=gitlab-token",
        "keel.tools.mcp.require-principal-match=true"
})
class McpCredentialAutoConfigurationTest {

    @Autowired
    private CredentialProvider credentialProvider;

    @Test
    void configuredServerIsCredentialRequiredAndResolvedForAllowedPrincipal() {
        assertThat(credentialProvider).isInstanceOf(StaticCredentialProvider.class);

        assertThat(credentialProvider.isCredentialRequired("gitlab")).isTrue();

        KeelPrincipal allowed =
                new KeelPrincipal("tenant", "subject", List.of("gitlab"));
        Optional<String> credential = credentialProvider.resolveCredential(allowed, "gitlab");
        assertThat(credential).contains("gitlab-token");
    }

    @Test
    void principalWithoutResourceGetsEmptyCredential() {
        // resourceIds 不含 gitlab → empty → gateway fail-closed 拒绝调用
        KeelPrincipal rejected =
                new KeelPrincipal("tenant", "subject", List.of());

        assertThat(credentialProvider.resolveCredential(rejected, "gitlab")).isEmpty();
    }

    @Test
    void unconfiguredServerHasNoCredentialRequirement() {
        assertThat(credentialProvider.isCredentialRequired("unknown-server")).isFalse();
    }

    @SpringBootApplication
    static class CredentialTestApplication {

        /** 图内核需要 ChatModel bean（不触网的脚本桩即可，本测试不跑 Agent 链路）。 */
        @Bean
        ChatModel chatModel() {
            return new GraphRagTestFixtures.QueuedChatModel();
        }
    }
}
