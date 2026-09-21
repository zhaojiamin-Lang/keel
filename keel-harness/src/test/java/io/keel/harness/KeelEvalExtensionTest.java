package io.keel.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Parameter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ParameterResolutionException;

import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.Citation;
import io.keel.core.PendingAction;

/**
 * FR-71：Harness JUnit 扩展——数据集注入 + 规则断言接入标准测试生命周期。
 * 数据集位于独立目录 eval-ext，避免污染 src/main/resources/eval 的六条固定用例
 * （EvalCaseLoaderTest 依赖主数据集数量恒定）。
 */
@ExtendWith(KeelEvalExtension.class)
@EvalDataset("eval-ext")
class KeelEvalExtensionTest {

    @Test
    void injectsAllCasesAsList(List<EvalCase> cases) {
        assertEquals(2, cases.size());
        assertTrue(cases.stream().anyMatch(evalCase -> "eval-001".equals(evalCase.getId())));
        assertTrue(cases.stream().anyMatch(evalCase -> "eval-002".equals(evalCase.getId())));
    }

    @Test
    void checkPassesWhenResultComplies(List<EvalCase> cases) {
        EvalCase readOnly = byId(cases, "eval-001");

        // 带 citation 的 SUCCESS 结果应通过全部规则
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("该工单曾由二线处理。")
                .citations(List.of(new Citation("chunk-1", "kb://ticket", "工单曾由二线处理")))
                .pendingActions(List.of())
                .traceId("t-1")
                .build();

        KeelEvalExtension.check(readOnly, result);
        KeelEvalExtension.check(readOnly, readOnly.toAgentRequest(), result);
    }

    @Test
    void checkFailsWithMachineReadableRuleCodes() {
        EvalCaseLoader loader = new EvalCaseLoader();
        EvalCase readOnly = loader.load(getClass().getResourceAsStream("/eval-ext/read-only.yaml"));

        // 无引用的 SUCCESS → missing_citation；文本含禁词 → leak
        AgentResult bad = AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("结论是 SECRET_TOKEN 直接关单 ISSUE-9999")
                .citations(List.of())
                .pendingActions(List.of())
                .traceId("t-2")
                .build();

        AssertionError failure = assertThrows(
                AssertionError.class, () -> KeelEvalExtension.check(readOnly, bad));
        assertTrue(failure.getMessage().contains("eval-001"));
        assertTrue(failure.getMessage().contains("missing_citation"));
        assertTrue(failure.getMessage().contains("leak"));
    }

    @Test
    void writeCaseRequiresIdempotencyKey() {
        EvalCaseLoader loader = new EvalCaseLoader();
        EvalCase writeCase = loader.load(getClass().getResourceAsStream("/eval-ext/write-confirm.yaml"));

        // 文本不携带 ISSUE-xxxx 形态的 ID，避免误触 fabricated_id 规则
        AgentResult withoutKey = AgentResult.builder()
                .status(AgentStatus.NEEDS_CONFIRM)
                .text("建议关闭该工单，请确认")
                .citations(List.of())
                .pendingActions(List.of(new PendingAction(
                        "a1", "issue.close", "{}", false, null)))
                .traceId("t-3")
                .build();

        AssertionError failure = assertThrows(
                AssertionError.class, () -> KeelEvalExtension.check(writeCase, withoutKey));
        assertTrue(failure.getMessage().contains("write_without_idempotency"));

        AgentResult withKey = AgentResult.builder()
                .status(AgentStatus.NEEDS_CONFIRM)
                .text("建议关闭该工单，请确认")
                .citations(List.of())
                .pendingActions(List.of(new PendingAction(
                        "a1", "issue.close", "{}", false, "idem-eval-002")))
                .traceId("t-4")
                .build();

        KeelEvalExtension.check(writeCase, withKey);
    }

    /**
     * 直接验证参数解析语义：多条用例的目录注入单个 EvalCase 必须显式报错，
     * 而不是悄悄取第一条（fail-closed，与 NFR-01 一致）。
     * 通过包内可见的 resolveCases 入口测试，无需桩掉 ExtensionContext。
     */
    @Test
    void singleCaseInjectionRejectsMultiCaseDataset() throws NoSuchMethodException {
        KeelEvalExtension extension = new KeelEvalExtension();
        Parameter evalCaseParam = getClass()
                .getDeclaredMethod("placeholder", EvalCase.class).getParameters()[0];

        assertTrue(extension.supportsParameterType(evalCaseParam));
        ParameterResolutionException failure = assertThrows(
                ParameterResolutionException.class,
                () -> extension.resolveCases(
                        evalCaseParam, getClass().getDeclaredMethod("singleCaseInjectionRejectsMultiCaseDataset"),
                        KeelEvalExtensionTest.class));
        assertTrue(failure.getMessage().contains("List<EvalCase>"));
    }

    private static EvalCase byId(List<EvalCase> cases, String id) {
        return cases.stream()
                .filter(evalCase -> id.equals(evalCase.getId()))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unused")
    private void placeholder(EvalCase evalCase) {
        // 仅用于反射取 Parameter
    }
}
