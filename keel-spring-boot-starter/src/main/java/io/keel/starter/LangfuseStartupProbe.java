package io.keel.starter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;

import io.keel.graph.spi.TracePort;

/**
 * NFR-04 Langfuse 探针：keel.langfuse.enabled=true 时容器必须有 TracePort bean
 * （密钥缺失已由 {@code KeelLangfuseAutoConfiguration} fail-fast，这里补「有 bean
 * 但服务不可达」的探测），并对默认实现做真实 HTTP 探活——GET
 * {@code {host}/api/public/health}（Langfuse 官方健康检查端点，无需密钥）。
 *
 * <p>业务自备 TracePort（非 LangfuseTracePort）时跳过 HTTP 探活，由业务自管。</p>
 */
final class LangfuseStartupProbe implements StartupProbe {

    /** keel-langfuse 是 optional 依赖，不能直接 import 其类型，反射判定默认实现 */
    private static final String LANGFUSE_TRACE_PORT_CLASS = "io.keel.langfuse.LangfuseTracePort";

    private final KeelProperties properties;
    private final ObjectProvider<TracePort> tracePorts;

    LangfuseStartupProbe(KeelProperties properties, ObjectProvider<TracePort> tracePorts) {
        this.properties = properties;
        this.tracePorts = tracePorts;
    }

    @Override
    public String check() {
        KeelProperties.Langfuse config = properties.getLangfuse();
        if (!config.isEnabled()) {
            return null;
        }
        TracePort tracePort = tracePorts.getIfAvailable();
        if (tracePort == null) {
            return "Langfuse: keel.langfuse.enabled=true 但容器中没有 TracePort bean；"
                    + "请引入 keel-langfuse 依赖并配置 keel.langfuse.public-key / secret-key，"
                    + "或自备 TracePort bean";
        }
        if (!isDefaultImplementation(tracePort)) {
            return null;
        }
        String base = config.getHost() == null ? "" : config.getHost().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String healthUrl = base + "/api/public/health";
        Duration timeout = properties.getStartup().getProbeTimeout();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(healthUrl)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return fail(config, healthUrl, "HTTP " + response.statusCode());
            }
            return null;
        } catch (Exception exception) {
            return fail(config, healthUrl, exception.getMessage());
        }
    }

    private String fail(KeelProperties.Langfuse config, String healthUrl, String reason) {
        return "Langfuse: bean 'keelLangfuseTracePort'（LangfuseTracePort）健康检查失败: " + reason
                + "；请检查 keel.langfuse.host=" + config.getHost()
                + "（探活端点 " + healthUrl + "，host 需含 http(s):// 前缀）";
    }

    private boolean isDefaultImplementation(TracePort tracePort) {
        try {
            return Class.forName(LANGFUSE_TRACE_PORT_CLASS, false,
                    tracePort.getClass().getClassLoader()).isInstance(tracePort);
        } catch (ClassNotFoundException | LinkageError exception) {
            return false;
        }
    }
}
