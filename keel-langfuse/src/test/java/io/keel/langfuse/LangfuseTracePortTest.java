package io.keel.langfuse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelPrincipal;
import io.keel.core.SkillDefinition;
import io.keel.graph.LoopState;
import io.keel.graph.NodeName;
import io.keel.graph.PlanDecision;
import io.keel.graph.StepBudget;
import io.keel.graph.ToolCall;
import io.keel.graph.ToolObservation;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * {@link LangfuseTracePort} 单测（FR-70 / NFR-08）：用内存 SpanExporter 捕获 span，
 * 验证 ①root span + step span 结构与 gen_ai 语义属性；②默认脱敏对 token/password 生效；
 * 全程无网络外呼。
 */
class LangfuseTracePortTest {

    @Test
    void emitsRootAndStepSpansWithGenAiAttributesAndRedaction() {
        InMemorySpanExporter exporter = new InMemorySpanExporter();
        LangfuseTracePort tracePort = new LangfuseTracePort(exporter, true, false);

        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("tenant-1", "user-1", List.of()))
                .skill("mcp-query")
                .input("帮我查单 {\"password\":\"abc123\"}")
                .sessionId("sess-9")
                .build();
        SkillDefinition skill = SkillDefinition.builder()
                .name("mcp-query")
                .tools(List.of("search_orders"))
                .build();

        LoopState state = new LoopState(request, skill, StepBudget.defaults());
        state.setLastDecision(PlanDecision.callTool(
                new ToolCall("search_orders", "{\"id\":\"O-1\",\"api_key\":\"sk-secret\"}")));

        tracePort.onStart(request, skill);
        tracePort.onStep(NodeName.PLAN, state);
        state.addObservation(ToolObservation.ok("search_orders", "订单已送达"));
        tracePort.onStep(NodeName.TOOL, state);
        tracePort.onEnd(AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("订单已送达")
                .build());
        tracePort.close();

        List<SpanData> spans = exporter.exported;
        // root + 2 step（同步导出顺序：先 end 的先导出，root 最后 end）
        assertThat(spans).hasSize(3);

        // root span 为 SERVER 类型，其余为 CLIENT 子 span
        SpanData root = spans.stream()
                .filter(s -> s.getKind() == io.opentelemetry.api.trace.SpanKind.SERVER)
                .findFirst()
                .orElseThrow();
        Map<AttributeKey<?>, Object> rootAttrs = map(root);
        assertThat(rootAttrs.get(AttributeKey.stringKey("langfuse.user.id"))).isEqualTo("user-1");
        assertThat(rootAttrs.get(AttributeKey.stringKey("langfuse.session.id"))).isEqualTo("sess-9");
        assertThat(rootAttrs.get(AttributeKey.stringKey("keel.tenant.id"))).isEqualTo("tenant-1");
        // trace input 中的 password 被脱敏
        assertThat((String) rootAttrs.get(AttributeKey.stringKey("langfuse.trace.input")))
                .contains("***")
                .doesNotContain("abc123");
        assertThat(rootAttrs.get(AttributeKey.stringKey("langfuse.trace.output")))
                .isEqualTo("订单已送达");

        // 按名字定位 PLAN / TOOL step span
        SpanData planSpan = byName(spans, NodeName.PLAN.name());
        Map<AttributeKey<?>, Object> planAttrs = map(planSpan);
        assertThat(planAttrs.get(AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("chat");
        assertThat(planAttrs.get(AttributeKey.stringKey("gen_ai.tool.name")))
                .isEqualTo("search_orders");
        assertThat((String) planAttrs.get(
                AttributeKey.stringKey("langfuse.observation.input")))
                .contains("***")
                .doesNotContain("sk-secret");

        SpanData toolSpan = byName(spans, NodeName.TOOL.name());
        Map<AttributeKey<?>, Object> toolAttrs = map(toolSpan);
        assertThat(toolAttrs.get(AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("execute_tool");
        assertThat(toolAttrs.get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("订单已送达");
        // 子 span 挂在 root trace 下
        assertThat(toolSpan.getTraceId()).isEqualTo(root.getTraceId());
        assertThat(toolSpan.getParentSpanId()).isEqualTo(root.getSpanId());
    }

    @Test
    void redactDisabledKeepsRawValues() {
        InMemorySpanExporter exporter = new InMemorySpanExporter();
        LangfuseTracePort tracePort = new LangfuseTracePort(exporter, false, false);

        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t", "u", List.of()))
                .input("{\"token\":\"raw-token-value\"}")
                .build();
        tracePort.onStart(request, null);
        tracePort.onEnd(AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("ok")
                .build());
        tracePort.close();

        String traceInput = (String) map(exporter.exported.get(0))
                .get(AttributeKey.stringKey("langfuse.trace.input"));
        assertThat(traceInput).contains("raw-token-value");
    }

    private static SpanData byName(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<AttributeKey<?>, Object> map(SpanData span) {
        return (Map<AttributeKey<?>, Object>) (Map<?, ?>) span.getAttributes().asMap();
    }

    /** 同步收集导出 span 的内存导出器。 */
    static final class InMemorySpanExporter implements SpanExporter {

        final List<SpanData> exported = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exported.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public void close() {
        }
    }
}
