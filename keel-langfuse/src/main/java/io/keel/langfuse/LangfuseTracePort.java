package io.keel.langfuse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.Citation;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;
import io.keel.graph.PlanDecision;
import io.keel.graph.ToolObservation;
import io.keel.graph.Verdict;
import io.keel.graph.spi.TracePort;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * 基于 OpenTelemetry OTLP 的 {@link TracePort} 实现（FR-70）。
 *
 * <p>Langfuse 官方为 Java 推荐的上报方式：span 经 {@link OtlpHttpSpanExporter} 以
 * HTTP/protobuf 发往 {@code {host}/api/public/otel/v1/traces}，Basic Auth 鉴权。
 * 映射规则（官方文档）：</p>
 * <ul>
 *   <li>root span = 一次 trace：{@code langfuse.trace.name/input/output}、
 *       {@code langfuse.user.id}、{@code langfuse.session.id}；</li>
 *   <li>带 {@code gen_ai.*} 属性的 step span 在 Langfuse 中渲染为 Generation/Tool/Retriever；</li>
 *   <li>其余 step span 渲染为普通 observation。</li>
 * </ul>
 *
 * <p>安全（NFR-08）：默认开启脱敏，JSON 中 token/password/secret/apiKey/authorization
 * 等字段的值替换为 {@code ***}。上报失败不影响请求主流程（OTel 异步批量导出）。</p>
 *
 * <p>本类不依赖 Spring；starter 负责装配与生命周期（destroyMethod=close 时 flush）。</p>
 */
public final class LangfuseTracePort implements TracePort, AutoCloseable {

    private static final int MAX_ATTRIBUTE_LENGTH = 8192;
    private static final AttributeKey<String> TRACE_NAME =
            AttributeKey.stringKey("langfuse.trace.name");
    private static final AttributeKey<String> TRACE_INPUT =
            AttributeKey.stringKey("langfuse.trace.input");
    private static final AttributeKey<String> TRACE_OUTPUT =
            AttributeKey.stringKey("langfuse.trace.output");
    private static final AttributeKey<String> USER_ID =
            AttributeKey.stringKey("langfuse.user.id");
    private static final AttributeKey<String> SESSION_ID =
            AttributeKey.stringKey("langfuse.session.id");
    private static final AttributeKey<String> OBSERVATION_INPUT =
            AttributeKey.stringKey("langfuse.observation.input");
    private static final AttributeKey<String> OBSERVATION_OUTPUT =
            AttributeKey.stringKey("langfuse.observation.output");

    /** 匹配 JSON 中敏感字段并脱敏（NFR-08） */
    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(\"(?:[^\"]*(?:token|password|passwd|secret|api[_-]?key|authorization|credential)[^\"]*)\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE);

    /** 一次请求对应的活动 trace：root span 及其挂接子 span 用的 Context */
    private record ActiveTrace(Span rootSpan, Context rootContext) {
    }

    private final ThreadLocal<ActiveTrace> active = new ThreadLocal<>();
    private final SdkTracerProvider tracerProvider;
    private final Tracer tracer;
    private final boolean redact;

    public LangfuseTracePort(String host, String publicKey, String secretKey, boolean redact) {
        this(buildOtlpExporter(host, publicKey, secretKey), redact, true);
    }

    /**
     * 包内测试构造器：注入自定义 {@link SpanExporter}（如内存导出器），不产生任何网络外呼。
     *
     * @param batchExporter true 用生产同款批量处理器；false 用同步处理器（span end 即导出）
     */
    LangfuseTracePort(SpanExporter exporter, boolean redact, boolean batchExporter) {
        Objects.requireNonNull(exporter, "exporter");
        this.redact = redact;

        SpanProcessor processor = batchExporter
                ? BatchSpanProcessor.builder(exporter).build()
                : io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter);

        this.tracerProvider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "keel"))))
                .addSpanProcessor(processor)
                .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        this.tracer = openTelemetry.getTracer("io.keel");
    }

    /** 构造 Langfuse OTLP/HTTP 导出器：端点归一化 + Basic Auth + v4 实时摄取头。 */
    private static SpanExporter buildOtlpExporter(String host, String publicKey, String secretKey) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(secretKey, "secretKey");

        // 端点归一化：{host}/api/public/otel → {host}/api/public/otel/v1/traces
        String base = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        if (base.endsWith("/v1/traces")) {
            // 已给出完整 traces 端点，直接使用
        } else if (base.endsWith("/otel")) {
            base = base + "/v1/traces";
        } else {
            base = base + "/api/public/otel/v1/traces";
        }
        String auth = publicKey + ":" + secretKey;
        String basic = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        return OtlpHttpSpanExporter.builder()
                .setEndpoint(base)
                .addHeader("Authorization", "Basic " + basic)
                // Langfuse v4 实时摄取头；缺失会延迟最多 10 分钟
                .addHeader("x-langfuse-ingestion-version", "4")
                .setTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void onStart(AgentRequest request, SkillDefinition skill) {
        Objects.requireNonNull(request, "request");
        String traceName = (skill != null ? skill.getName() : "keel-agent")
                + " / " + request.getInput();
        Span rootSpan = tracer.spanBuilder(traceName)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        rootSpan.setAttribute(TRACE_NAME, traceName);
        rootSpan.setAttribute(TRACE_INPUT, safe(request.getInput()));
        // 防御：principal 缺失（正常路径由 Builder/readObject 保证非空）时降级为
        // 无身份 trace——观测组件不击穿请求
        KeelPrincipal principal = request.getPrincipal();
        if (principal != null) {
            rootSpan.setAttribute(USER_ID, principal.getSubjectId());
            // FR-53：tenant 作为元数据
            rootSpan.setAttribute("keel.tenant.id", principal.getTenantId());
        }
        if (request.getSessionId() != null) {
            rootSpan.setAttribute(SESSION_ID, request.getSessionId());
        }
        if (skill != null) {
            rootSpan.setAttribute("keel.skill.name", skill.getName());
            rootSpan.setAttribute("keel.skill.version", skill.getVersion());
            rootSpan.setAttribute("keel.skill.require_citation", skill.isRequireCitation());
            rootSpan.setAttribute("keel.skill.writable", skill.isWritable());
        }
        rootSpan.setAttribute("keel.trace_id", traceIdOf(request));

        active.set(new ActiveTrace(rootSpan, Context.current().with(rootSpan)));
    }

    @Override
    public void onStep(NodeName node, LoopState state) {
        ActiveTrace current = active.get();
        // 极端情况下（未走 onStart）直接忽略，保证上报逻辑不影响主流程
        if (current == null) {
            return;
        }
        Span span = tracer.spanBuilder(node.name())
                .setParent(current.rootContext())
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        span.setAttribute("keel.node", node.name());
        span.setAttribute("keel.iteration", state.getIteration());
        try {
            switch (node) {
                case PLAN -> annotatePlan(span, state);
                case TOOL -> annotateTool(span, state);
                case RETRIEVE -> annotateRetrieve(span, state);
                case CRITIC -> annotateVerdict(span, state.getCriticVerdict(), "critic");
                case GUARD -> annotateVerdict(span, state.getGuardVerdict(), "guard");
                default -> {
                    // OBSERVE 等节点：记录当前观察数与草稿摘要即可
                    span.setAttribute("keel.observations", state.getObservations().size());
                }
            }
        } catch (RuntimeException ignored) {
            // trace 注解失败绝不影响请求
        }
        span.end();
    }

    @Override
    public void onEnd(AgentResult result) {
        ActiveTrace current = active.get();
        if (current == null) {
            return;
        }
        try {
            if (result != null) {
                if (result.getText() != null) {
                    current.rootSpan().setAttribute(TRACE_OUTPUT, safe(result.getText()));
                }
                current.rootSpan().setAttribute("keel.result.status", result.getStatus().name());
                if (result.getErrorCode() != null && !result.getErrorCode().isEmpty()) {
                    current.rootSpan().setAttribute("keel.result.error_code", result.getErrorCode());
                    current.rootSpan().setStatus(StatusCode.ERROR, result.getErrorCode());
                }
            }
        } finally {
            current.rootSpan().end();
            active.remove();
        }
    }

    @Override
    public void close() {
        // 关闭前 flush，避免短生命周期进程丢失最后一批 span
        tracerProvider.shutdown();
    }

    // ---- 节点注解 ----

    private void annotatePlan(Span span, LoopState state) {
        // PLAN/CRITIC 都是模型调用，带 gen_ai.* 后在 Langfuse 渲染为 Generation
        span.setAttribute("gen_ai.operation.name", "chat");
        PlanDecision decision = state.getLastDecision();
        if (decision == null) {
            return;
        }
        span.setAttribute("keel.plan.action", decision.getKind().name());
        if (decision.getToolCall() != null) {
            span.setAttribute("gen_ai.tool.name", decision.getToolCall().getTool());
            span.setAttribute(OBSERVATION_INPUT, safe(decision.getToolCall().getArgumentsJson()));
        } else if (decision.getText() != null && !decision.getText().isEmpty()) {
            span.setAttribute(OBSERVATION_OUTPUT, safe(decision.getText()));
        }
    }

    private void annotateTool(Span span, LoopState state) {
        List<ToolObservation> observations = state.getObservations();
        if (observations.isEmpty()) {
            return;
        }
        ToolObservation latest = observations.get(observations.size() - 1);
        // gen_ai.tool.name 使该 span 在 Langfuse 中渲染为 Tool observation
        span.setAttribute("gen_ai.operation.name", "execute_tool");
        span.setAttribute("gen_ai.tool.name", latest.getTool());
        if (latest.isSuccess()) {
            span.setAttribute(OBSERVATION_OUTPUT, safe(latest.getContent()));
        } else {
            span.setAttribute(OBSERVATION_OUTPUT, safe(latest.getErrorMessage()));
            span.setStatus(StatusCode.ERROR, latest.getErrorMessage());
        }
    }

    private void annotateRetrieve(Span span, LoopState state) {
        span.setAttribute("gen_ai.operation.name", "retrieve");
        PlanDecision decision = state.getLastDecision();
        if (decision != null) {
            span.setAttribute(OBSERVATION_INPUT, safe(decision.getText()));
        }
        List<Citation> citations = state.getCitations();
        span.setAttribute("keel.citation_count", citations.size());
        if (!citations.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (Citation citation : citations) {
                builder.append("- ").append(citation.getChunkId())
                        .append(" (").append(citation.getSource()).append(")\n");
            }
            span.setAttribute(OBSERVATION_OUTPUT, safe(builder.toString()));
        }
    }

    private void annotateVerdict(Span span, Verdict verdict, String role) {
        span.setAttribute("gen_ai.operation.name", "chat");
        if (verdict == null) {
            return;
        }
        span.setAttribute("keel." + role + ".approved", verdict.isApproved());
        if (!verdict.isApproved()) {
            span.setAttribute("keel." + role + ".code", nullToEmpty(verdict.getCode()));
            span.setAttribute(OBSERVATION_OUTPUT, safe(verdict.getReason()));
        }
    }

    // ---- 工具方法 ----

    /** 脱敏 + 截断，防止超长属性被 OTel 丢弃 */
    private String safe(String value) {
        if (value == null) {
            return "";
        }
        String processed = redact
                ? SENSITIVE_JSON_FIELD.matcher(value).replaceAll("$1***$3")
                : value;
        if (processed.length() > MAX_ATTRIBUTE_LENGTH) {
            return processed.substring(0, MAX_ATTRIBUTE_LENGTH) + "...(truncated)";
        }
        return processed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String traceIdOf(AgentRequest request) {
        // LoopState 的 traceId 在 onStart 时尚未生成（state 在其后构造），
        // 这里用 sessionId 关联；无 session 时 trace 由 OTel traceId 唯一标识
        return request.getSessionId() == null ? "(one-shot)" : request.getSessionId();
    }
}
