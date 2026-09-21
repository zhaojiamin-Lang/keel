package io.keel.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.keel.graph.spi.TracePort;
import io.keel.langfuse.LangfuseTracePort;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;

/**
 * Langfuse Trace 自动装配（FR-70 / NFR-08）。
 *
 * <p>生效条件：classpath 存在 keel-langfuse（{@link OtlpHttpSpanExporter}）、
 * {@code keel.langfuse.enabled=true}（默认关闭）且密钥已配置。
 * 业务可自行注册 {@link TracePort} bean 覆盖默认实现。</p>
 */
@AutoConfiguration
@ConditionalOnClass({ OtlpHttpSpanExporter.class, TracePort.class, LangfuseTracePort.class })
@ConditionalOnProperty(prefix = "keel.langfuse", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(KeelProperties.class)
public class KeelLangfuseAutoConfiguration {

    /**
     * 返回类型声明为具体类型 {@link LangfuseTracePort} 而非接口 {@link TracePort}：
     * destroyMethod="close" 定义在实现类（AutoCloseable）上，接口上看不见，
     * 用具体类型让 IDE 静态解析与 Spring 生命周期检查都能找到 close()。
     * bean 仍以 TracePort 类型暴露给容器，@ConditionalOnMissingBean 判断不受影响。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(TracePort.class)
    public LangfuseTracePort keelLangfuseTracePort(KeelProperties properties) {
        KeelProperties.Langfuse config = properties.getLangfuse();
        // 配置不完整 fail-fast，避免静默丢 trace
        if (config.getPublicKey() == null || config.getPublicKey().isBlank()) {
            throw new IllegalArgumentException(
                    "keel.langfuse.enabled=true 但缺少 keel.langfuse.public-key");
        }
        if (config.getSecretKey() == null || config.getSecretKey().isBlank()) {
            throw new IllegalArgumentException(
                    "keel.langfuse.enabled=true 但缺少 keel.langfuse.secret-key");
        }
        return new LangfuseTracePort(
                config.getHost(),
                config.getPublicKey(),
                config.getSecretKey(),
                config.isRedact());
    }
}
