package io.keel.harness;

import java.util.List;

import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.Citation;
import io.keel.core.PendingAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeelEvalCheckerTest {

    private final KeelEvalChecker checker = new KeelEvalChecker();

    @Test
    void flagsMissingCitation() {
        EvalCase evalCase = baseCase(true, List.of(), List.of());
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("该工单上次已由张三处理")
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertFalse(report.isPassed());
        assertEquals(List.of(KeelEvalChecker.RULE_MISSING_CITATION), report.getFailedRules());
    }

    @Test
    void passesUncertainResultWithoutCitation() {
        EvalCase evalCase = baseCase(true, List.of(), List.of());
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.UNCERTAIN)
                .text("暂时无法确认")
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertTrue(report.isPassed());
    }

    @Test
    void passesSuccessfulResultWithCitation() {
        EvalCase evalCase = baseCase(true, List.of(), List.of());
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("该工单上次已由张三处理")
                .citations(List.of(new Citation("chunk-1", "doc://issue/1", "张三处理过")))
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertTrue(report.isPassed());
    }

    @Test
    void flagsFabricatedIssueIdWhenAllowedListIsEmpty() {
        EvalCase evalCase = baseCase(false, List.of(), List.of());
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("建议关闭 ISSUE-99999")
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertFalse(report.isPassed());
        assertEquals(List.of(KeelEvalChecker.RULE_FABRICATED_ID), report.getFailedRules());
    }

    @Test
    void allowsIssueIdThatIsInAllowedList() {
        EvalCase evalCase = baseCase(false, List.of("ISSUE-1"), List.of());
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("建议关闭 ISSUE-1")
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertTrue(report.isPassed());
    }

    @Test
    void flagsForbiddenSubstringInText() {
        EvalCase evalCase = baseCase(false, List.of(), List.of("tenant-B"));
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("这里包含 tenant-B 的数据")
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertFalse(report.isPassed());
        assertEquals(List.of(KeelEvalChecker.RULE_LEAK), report.getFailedRules());
    }

    @Test
    void flagsForbiddenSubstringInPendingActionPayload() {
        EvalCase evalCase = EvalCase.builder()
                .id("case-1")
                .skill("ticket-assist")
                .requireCitation(false)
                .allowedIssueIds(List.of())
                .forbiddenSubstrings(List.of("tenant-B"))
                .expectWrite(true)
                .tenantId("tenant-A")
                .subjectId("user-1")
                .resourceIds(List.of("ISSUE-1"))
                .input("请查询工单")
                .idempotencyKey("idem-1")
                .build();
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.NEEDS_CONFIRM)
                .text("已生成待确认动作")
                .pendingActions(List.of(
                        new PendingAction("a-1", "readIssue", "{\"tenantId\":\"tenant-B\"}", false, "idem-1")))
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertFalse(report.isPassed());
        assertEquals(List.of(KeelEvalChecker.RULE_LEAK), report.getFailedRules());
    }

    @Test
    void flagsUnexpectedWriteWhenExpectWriteIsFalse() {
        EvalCase evalCase = baseCase(false, List.of(), List.of());
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.NEEDS_CONFIRM)
                .text("需要确认后写入")
                .pendingActions(List.of(
                        new PendingAction("a-1", "closeIssue", "{\"issueId\":\"ISSUE-1\"}")))
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertFalse(report.isPassed());
        assertEquals(List.of(KeelEvalChecker.RULE_UNEXPECTED_WRITE), report.getFailedRules());
    }

    @Test
    void allowsEmptyPendingActionsWhenExpectWriteIsFalse() {
        EvalCase evalCase = baseCase(false, List.of(), List.of());
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("只读查询完成")
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertTrue(report.isPassed());
    }

    @Test
    void flagsWriteWithoutIdempotencyWhenExpectWriteAndKeyMissing() {
        EvalCase evalCase = EvalCase.builder()
                .id("case-idem-missing")
                .skill("ticket-assist")
                .requireCitation(false)
                .allowedIssueIds(List.of())
                .forbiddenSubstrings(List.of())
                .expectWrite(true)
                .tenantId("tenant-A")
                .subjectId("user-1")
                .resourceIds(List.of("ISSUE-1"))
                .input("请关闭工单")
                .build();
        // 旧 3 参构造器不传幂等键，模拟请求级 idempotencyKey 缺失透传到动作
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.NEEDS_CONFIRM)
                .text("已生成待确认动作")
                .pendingActions(List.of(
                        new PendingAction("a-1", "closeIssue", "{\"issueId\":\"ISSUE-1\"}")))
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertFalse(report.isPassed());
        assertEquals(
                List.of(KeelEvalChecker.RULE_WRITE_WITHOUT_IDEMPOTENCY),
                report.getFailedRules());
    }

    @Test
    void passesWriteWithIdempotencyKey() {
        EvalCase evalCase = EvalCase.builder()
                .id("case-idem-ok")
                .skill("ticket-assist")
                .requireCitation(false)
                .allowedIssueIds(List.of())
                .forbiddenSubstrings(List.of())
                .expectWrite(true)
                .tenantId("tenant-A")
                .subjectId("user-1")
                .resourceIds(List.of("ISSUE-1"))
                .input("请关闭工单")
                .idempotencyKey("idem-1")
                .build();
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.NEEDS_CONFIRM)
                .text("已生成待确认动作")
                .pendingActions(List.of(
                        new PendingAction(
                                "a-1", "closeIssue", "{\"issueId\":\"ISSUE-1\"}", false, "idem-1")))
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertTrue(report.isPassed());
    }

    @Test
    void flagsExceededStepBudget() {
        EvalCase evalCase = baseCase(false, List.of(), List.of());
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.ERROR)
                .text("")
                .errorCode(KeelEvalChecker.CODE_MAX_STEPS_EXCEEDED)
                .errorMessage("已达到最大规划步数: 5")
                .build();

        EvalReport report = checker.check(evalCase, result);

        assertFalse(report.isPassed());
        assertEquals(
                List.of(KeelEvalChecker.RULE_EXCEEDED_STEP_BUDGET),
                report.getFailedRules());
    }

    private EvalCase baseCase(
            boolean requireCitation,
            List<String> allowedIssueIds,
            List<String> forbiddenSubstrings) {
        return EvalCase.builder()
                .id("case-1")
                .skill("ticket-assist")
                .requireCitation(requireCitation)
                .allowedIssueIds(allowedIssueIds)
                .forbiddenSubstrings(forbiddenSubstrings)
                .expectWrite(false)
                .tenantId("tenant-A")
                .subjectId("user-1")
                .resourceIds(List.of("ISSUE-1"))
                .input("请查询工单")
                .idempotencyKey("idem-1")
                .build();
    }
}
