package io.keel.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.keel.client.DefaultPrincipalResolver;
import io.keel.client.KeelAgentClient;
import io.keel.client.PrincipalResolver;
import io.keel.core.KeelAgent;

/**
 * 接入适配层自动装配：让业务只写 {@code keelClient.chat(input)}。
 *
 * <p>装配两个 bean：</p>
 * <ul>
 *     <li>{@link PrincipalResolver}：业务注册自己的实现即覆盖（{@code @ConditionalOnMissingBean}）；
 *         没注册时回退 {@link DefaultPrincipalResolver}（读 {@code keel.principal.*}），
 *         保证零配置项目仍能启动——与 NFR-04 定下的「可选能力缺失不阻塞启动」一致。</li>
 *     <li>{@link KeelAgentClient}：把 AgentRequest 拼装 / session 解析 / 幂等键生成收口。
 *         依赖 {@link KeelAgent} 接口（keel-core 提供，非 optional），业务自备 agent 也能装配。</li>
 * </ul>
 *
 * <p>关闭方式：{@code keel.client.enabled=false}，此时业务退回自己拼 AgentRequest，
 * 与引入本适配层前的用法完全一致（向后兼容）。</p>
 */
@AutoConfiguration(after = KeelAutoConfiguration.class)
@ConditionalOnClass(KeelAgentClient.class)
@ConditionalOnProperty(
        prefix = "keel.client",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(KeelProperties.class)
public class KeelClientAutoConfiguration {

    /**
     * 兜底身份：只在业务没有 PrincipalResolver bean 时生效。
     * 多租户生产环境请注册请求级实现（见 {@link PrincipalResolver} 的说明）。
     */
    @Bean
    @ConditionalOnMissingBean(PrincipalResolver.class)
    public PrincipalResolver keelPrincipalResolver(KeelProperties properties) {
        KeelProperties.Principal principal = properties.getPrincipal();
        return new DefaultPrincipalResolver(
                principal.getTenantId(), principal.getSubjectId(), principal.getResourceIds());
    }

    /**
     * 接入适配层：注入 {@code KeelAgentClient} 即可 {@code chat(input)}。
     *
     * <p><b>为什么保留 {@code @ConditionalOnBean(KeelAgent.class)}：</b>
     * 不加这个条件时，只要 starter 在 classpath 上，本 bean 就会被注册，而它的构造参数
     * 强制要求容器里有一个 {@link KeelAgent}——于是在「只装配部分能力」的正常场景里
     * 直接炸开：例如只想用 RAG 检索 / 只想用工具装配的模块，容器里没有 agent，
     * 启动却因为 client 拿不到 agent 失败，且报错指向 client 这个用户根本没引用的组件。
     * 实测 {@code KeelRetrievalStoreAutoConfigurationTest} 就是这个场景，去掉条件后
     * 立刻从「装配检索链路」变成「No qualifying bean of type KeelAgent」。</p>
     *
     * <p>条件满足的是「能力齐备才建便利层」：没有 agent 时本层缺席是<b>正确</b>语义，
     * 不构成功能缺失——业务本就没有可用 agent。反过来业务自备 agent 时，
     * 只要它是容器里的 {@link KeelAgent} bean，条件同样成立（不论 bean 从哪来）。</p>
     */
    @Bean
    @ConditionalOnBean(KeelAgent.class)
    @ConditionalOnMissingBean(KeelAgentClient.class)
    public KeelAgentClient keelAgentClient(
            KeelAgent keelAgent,
            PrincipalResolver principalResolver,
            KeelProperties properties) {
        return new KeelAgentClient(
                keelAgent, principalResolver, properties.getClient().getIdempotencyKeyPrefix());
    }
}
