package io.keel.examples.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.keel.core.AgentResult;
import io.keel.harness.EvalCase;
import io.keel.harness.EvalDataset;
import io.keel.harness.EvalReport;
import io.keel.harness.KeelEvalChecker;
import io.keel.harness.KeelEvalExtension;

/**
 * FR-73：真实 agent 端到端评测回归。
 *
 * <p>与 keel-harness 自身测试的关键区别：</p>
 * <ul>
 *   <li>{@code keel-harness} 模块的单测用桩 result 验证 checker 逻辑（规则本身对不对）；</li>
 *   <li>本测试用 ticket-assist 的**真实 {@code KeelAgent}**（含 Skill 路由、Guard、
 *       图循环、写确认链路、ToolPort）跑 {@code eval-e2e/} 数据集，再由同一个
 *       {@link KeelEvalChecker} 判定——回归的是 <b>agent 的实际行为</b>，而不只是规则实现。</li>
 * </ul>
 *
 * <p>模型用示例自带的 {@code ScriptedChatModel}（{@code ScriptedChatModelConfiguration}），
 * 因此结果确定、不触网、CI 无需任何 API Key。接入真实模型时本测试同样适用。</p>
 */
@SpringBootTest
@ExtendWith(KeelEvalExtension.class)
@EvalDataset("eval-e2e")
class TicketAssistEvalE2ETest {

    @Autowired
    private TicketController controller;

    private final KeelEvalChecker checker = new KeelEvalChecker();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 数据集逐条跑真实 agent 并断言。
     *
     * <p>通过示例 Controller 入口发起请求（{@code /api/chat} 同一条代码路径），
     * 保证覆盖「Web 层 → 客户端装配 AgentRequest → KeelAgent → 图循环」的完整链路。</p>
     */
    @Test
    void everyEvalCasePassesWithRealAgent(List<EvalCase> cases) {
        // 数据集加载校验：目录遗漏或 YAML 不合法时必须显式失败，不能静默通过
        assertThat(cases).as("e2e 数据集不得为空").isNotEmpty();
        assertThat(cases).extracting(EvalCase::getId).containsExactlyInAnyOrder(
                "e2e-read-only-query",
                "e2e-write-forbidden-on-read-only-skill",
                "e2e-write-carries-idempotency-key",
                "e2e-tenant-scoped-no-leak");

        for (EvalCase evalCase : cases) {
            withSession("e2e-" + evalCase.getId());

            AgentResult result = controller.chat(
                    java.util.Map.of("input", evalCase.getInput(), "skill", evalCase.getSkill()));

            EvalReport report = checker.check(evalCase, result);
            assertThat(report.isPassed())
                    .as("e2e 用例 %s 未通过，失败规则: %s（agent 状态 %s / 错误码 %s）",
                            evalCase.getId(), report.getFailedRules(),
                            result.getStatus(), result.getErrorCode())
                    .isTrue();
        }
    }

    /**
     * 会话 ID 改为从请求头读取（客户端统一样式）。评测用例之间必须隔离会话，
     * 否则同一身份会共享 checkpoint，前一个用例的待确认动作可能被后一个用例看到。
     */
    private static void withSession(String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Session-Id", sessionId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
