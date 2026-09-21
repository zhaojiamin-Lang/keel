package io.keel.examples.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.PendingAction;

/**
 * ticket-assist 示例端到端集成测试（FR-80 / FR-13 / FR-32）：
 * 只读 + 确认后写入、写入前不得产生副作用、写操作幂等。
 * 全程用 ScriptedChatModel（脚本化模型），不触网、不需要真实模型 API Key。
 *
 * <p>Controller 已瘦身：不再接收 sessionId 参数，会话改由 {@code KeelAgentClient}
 * 从 {@code X-Session-Id} 请求头解析。所以这里用 {@link MockHttpServletRequest}
 * 设置请求头来指定会话——这也顺带覆盖了「请求头解析」这条真实链路。</p>
 */
@SpringBootTest
class TicketAssistApplicationTest {

    @Autowired
    private TicketController controller;

    @Autowired
    private TicketService ticketService;

    /** 设置当前线程的请求上下文，使 client 能读到 X-Session-Id（模拟真实 HTTP 请求）。 */
    private static void withSession(String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Session-Id", sessionId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @org.junit.jupiter.api.AfterEach
    void clearRequestContext() {
        // 请求作用域的 ThreadLocal 必须清掉，否则会污染同线程的下一个用例
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void writeRequiresConfirmationThenApplies() {
        int before = ticketService.listAll().size();
        withSession("sess-create-1");

        // 1. 提议创建 → 返回待确认动作，写操作此刻不得执行（FR-13）
        AgentResult proposal = controller.chat(Map.of("input", "创建工单 登录失败"));
        assertThat(proposal.getStatus()).isEqualTo(AgentStatus.NEEDS_CONFIRM);
        assertThat(proposal.getPendingActions()).hasSize(1);
        PendingAction action = proposal.getPendingActions().get(0);
        assertThat(action.getTool()).isEqualTo("ticket.create");
        assertThat(ticketService.listAll()).hasSize(before);

        // 2. 确认 → 写入真正执行（FR-13）。脚本模型恢复后用原始输入重提议写，
        //    但幂等键保证只写入一次——这里断言副作用而非状态码
        controller.confirm(Map.of("actionId", action.getActionId()));
        assertThat(ticketService.listAll()).hasSize(before + 1);
        assertThat(ticketService.listAll())
                .anyMatch(ticket -> ticket.getTitle().contains("创建工单"));
    }

    @Test
    void updateStatusRequiresConfirmationThenApplies() {
        // 预置工单 T-001 状态为 OPEN（TicketService 构造时即如此）
        assertThat(ticketService.findById("T-001").getStatus()).isEqualTo("OPEN");
        withSession("sess-update-1");

        AgentResult proposal = controller.chat(Map.of("input", "改状态 关闭"));
        assertThat(proposal.getStatus()).isEqualTo(AgentStatus.NEEDS_CONFIRM);
        assertThat(proposal.getPendingActions().get(0).getTool())
                .isEqualTo("ticket.updateStatus");
        // 确认前状态未变
        assertThat(ticketService.findById("T-001").getStatus()).isEqualTo("OPEN");

        controller.confirm(Map.of("actionId", proposal.getPendingActions().get(0).getActionId()));
        // 确认后状态真正变更（FR-13）；脚本模型重提议不影响已执行的写
        assertThat(ticketService.findById("T-001").getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void writeIsIdempotentByActionId() {
        // 同一幂等键重复写入只产生一笔副作用（FR-32）
        Ticket first = ticketService.create("idem-1", "重复提交");
        Ticket second = ticketService.create("idem-1", "重复提交");

        assertThat(second.getId()).isEqualTo(first.getId());
        long count = ticketService.listAll().stream()
                .filter(ticket -> ticket.getTitle().equals("重复提交"))
                .count();
        assertThat(count).isEqualTo(1);
    }
}
