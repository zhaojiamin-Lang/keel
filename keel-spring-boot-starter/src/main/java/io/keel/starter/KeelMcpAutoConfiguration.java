package io.keel.starter;

import java.util.LinkedHashSet;
import java.util.Map;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import io.keel.graph.spi.ToolPort;
import io.keel.mcp.ContentRedactor;
import io.keel.mcp.McpToolOptions;
import io.keel.mcp.RedactionMode;
import io.keel.mcp.McpToolPort;
import io.keel.mcp.gateway.McpToolGateway;
import io.keel.mcp.gateway.RoutingMcpToolGateway;
import io.keel.mcp.auth.CredentialProvider;
import io.keel.mcp.auth.StaticCredentialProvider;
import io.keel.mcp.gateway.SdkMcpToolGateway;
import io.keel.mcp.gateway.StaticMcpToolRouter;
import io.modelcontextprotocol.client.McpSyncClient;

/**
 * MCP 只读工具自动装配（FR-31/FR-32/FR-35）：
 * <ul>
 *   <li>classpath 同时存在 mcp SDK 与 keel-mcp 才生效；</li>
 *   <li>keel.tools.mcp.enabled 缺省按 true 处理（显式 false 关闭，fail-closed 交回图内核）；</li>
 *   <li>多 server：keel.tools.mcp.servers.* 非空时装配 {@link RoutingMcpToolGateway}，
 *       按工具名路由到对应 server，未登记工具 fail-closed；</li>
 *   <li>单 server：配置了 transport 才由 starter 创建并 initialize 默认 McpSyncClient；
 *       业务自定义 McpSyncClient bean 时（@ConditionalOnMissingBean）直接复用；</li>
 *   <li>网关、ToolPort 层层 @ConditionalOnMissingBean，全部允许业务覆盖。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass({McpSyncClient.class, McpToolPort.class})
@ConditionalOnProperty(prefix = "keel.tools.mcp", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KeelProperties.class)
public class KeelMcpAutoConfiguration {

    /**
     * 默认单 server client：仅当显式配置 transport 时创建；destroyMethod=close 保证容器关闭时
     * 释放连接 / 结束 stdio 子进程。业务自定义 McpSyncClient bean 优先，此 bean 不创建。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "keel.tools.mcp", name = "transport")
    public McpSyncClient keelMcpSyncClient(KeelProperties properties) {
        return McpClientFactory.createInitializedClient(properties.getTools().getMcp());
    }

    /**
     * FR-31：静态凭证提供者——从配置的 serverName→token 映射查凭证。
     * yaml：{@code keel.tools.mcp.credentials.<server>=<token>} +
     * {@code keel.tools.mcp.require-principal-match=true}（可选，resourceIds 必须含 server）。
     * 业务可自定义 CredentialProvider bean 覆盖（如按 principal 查 OAuth token）。
     */
    @Bean
    @ConditionalOnMissingBean(CredentialProvider.class)
    public CredentialProvider keelMcpCredentialProvider(KeelProperties properties) {
        KeelProperties.Tools.Mcp mcp = properties.getTools().getMcp();
        return new StaticCredentialProvider(mcp.getCredentials(), mcp.isRequirePrincipalMatch());
    }

    /**
     * 多 server 路由网关：仅当 keel.tools.mcp.servers 非空时装配。所有 server 建连 + initialize
     * 在装配阶段完成，任一失败 fail-fast；容器关闭时统一释放（destroyMethod=close）。
     */
    @Bean(destroyMethod = "close")
    @Conditional(McpServersConfigured.class)
    @ConditionalOnMissingBean(McpToolGateway.class)
    public McpToolGateway keelRoutingMcpToolGateway(
            KeelProperties properties, CredentialProvider credentialProvider) {
        Map<String, KeelProperties.Tools.Mcp> servers = properties.getTools().getMcp().getServers();
        Map<String, McpSyncClient> clients = McpClientFactory.createServerClients(servers);
        Map<String, String> index = McpClientFactory.buildToolServerIndex(clients, servers);
        return new RoutingMcpToolGateway(clients, new StaticMcpToolRouter(index), credentialProvider);
    }

    /** 单 server 网关：容器存在 McpSyncClient bean 时装配（默认 client 或业务自定义均可）。 */
    @Bean
    @ConditionalOnBean(McpSyncClient.class)
    @ConditionalOnMissingBean(McpToolGateway.class)
    public McpToolGateway keelMcpToolGateway(McpSyncClient client, CredentialProvider credentialProvider) {
        return new SdkMcpToolGateway(client, credentialProvider);
    }

    @Bean
    @ConditionalOnBean(McpToolGateway.class)
    @ConditionalOnMissingBean(ToolPort.class)
    public ToolPort keelMcpToolPort(McpToolGateway mcpToolGateway, KeelProperties properties) {
        KeelProperties.Tools tools = properties.getTools();
        KeelProperties.Tools.Mcp mcp = tools.getMcp();
        // FR-33：按 yml 配置的 redactMode 构建脱敏规则（TOKEN/MOBILE/SECRET/ALL/NONE）
        RedactionMode mode = parseRedactionMode(tools.getRedactMode());
        ContentRedactor redactor = ContentRedactor.forMode(mode);
        // 只读注册表 = keel.tools.allowed；重试 / 退避来自 mcp.* 配置
        McpToolOptions options = McpToolOptions.builder()
                .readOnlyTools(new LinkedHashSet<>(tools.getAllowed()))
                .maxRetries(mcp.getMaxRetries())
                .retryBackoffMillis(mcp.getRetryBackoff().toMillis())
                .contentRedactor(redactor)
                .build();
        return new McpToolPort(mcpToolGateway, options);
    }

    /** 解析 yml 配置的脱敏模式字符串为枚举，非法值回退 ALL（fail-safe）。 */
    private static RedactionMode parseRedactionMode(String value) {
        if (value == null || value.isBlank()) {
            return RedactionMode.ALL;
        }
        try {
            return RedactionMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RedactionMode.ALL;
        }
    }

    /**
     * 判断 keel.tools.mcp.servers 是否非空。map 根 key 不会被 Spring 环境扁平化，
     * 因此用 {@link ConditionalOnProperty} 无法可靠命中，这里直接绑定 KeelProperties 判断。
     */
    static final class McpServersConfigured extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            KeelProperties properties = Binder.get(context.getEnvironment())
                    .bind("keel", Bindable.of(KeelProperties.class))
                    .orElse(new KeelProperties());
            boolean configured = properties.getTools().getMcp().getServers() != null
                    && !properties.getTools().getMcp().getServers().isEmpty();
            return new ConditionOutcome(configured,
                    "keel.tools.mcp.servers " + (configured ? "已配置，走多 server 路由" : "未配置"));
        }
    }
}
