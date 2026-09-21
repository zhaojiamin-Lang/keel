package io.keel.examples.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.keel.client.KeelAgentClient;
import io.keel.client.PrincipalResolver;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelPrincipal;

/**
 * 接入适配层的端到端验证（FR-60 / FR-13 / FR-32）：
 * 证明「业务只写 keelClient.chat(...)」在生产样本里真的够用。
 *
 * <p>覆盖四件事，都是改造前要靠 Controller 里手写代码才能做到的：</p>
 * <ol>
 *     <li><b>身份来自请求头</b>——{@code X-Tenant-Id} / {@code X-User-Id} 被业务 resolver
 *         解析，不再有硬编码的 {@code tenant-demo} 常量；</li>
 *     <li><b>会话隔离</b>——不同 {@code X-Session-Id} 互不干扰（写确认依赖它恢复 checkpoint）；</li>
 *     <li><b>幂等键自动带</b>——写链路能走到 NEEDS_CONFIRM（缺键会被内核 fail-closed
 *         报 WRITE_WITHOUT_IDEMPOTENCY）；</li>
 *     <li><b>重复确认不重复写</b>——FR-32 幂等语义未被适配层破坏。</li>
 * </ol>
 */
@SpringBootTest
class TicketAssistClientIntegrationTest {

    @Autowired
    private TicketController controller;

    @Autowired
    private KeelAgentClient keelClient;

    /** 直接注入业务 resolver，用于断言身份解析结果（真实链路，非重建）。 */
    @Autowired
    private PrincipalResolver principalResolver;

    @Autowired
    private TicketService ticketService;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 设置请求头，模拟真实 HTTP 请求（含租户 / 用户 / 会话）。 */
    private static void withHeaders(String tenantId, String userId, String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TicketIdentityConfiguration.HEADER_TENANT_ID, tenantId);
        request.addHeader(TicketIdentityConfiguration.HEADER_USER_ID, userId);
        request.addHeader("X-Session-Id", sessionId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void principalComesFromRequestHeadersNotHardcodedConstant() {
        withHeaders("tenant-acme", "alice", "sess-identity");

        KeelPrincipal principal = principalResolver.resolve();

        // 改造前这里是 tenant-demo / user-demo 常量，多租户下会串号（NFR-02 P0）
        assertThat(principal.getTenantId()).isEqualTo("tenant-acme");
        assertThat(principal.getSubjectId()).isEqualTo("alice");
    }

    @Test
    void resourceIdsFallBackToConfiguredDefaultsWhenHeaderAbsent() {
        withHeaders("tenant-demo", "user-demo", "sess-res");

        // 未传 X-Resource-Ids → 落回 application.yml 的 keel.principal.resource-ids
        assertThat(principalResolver.resolve().getResourceIds())
                .containsExactly("project-demo");
    }

    @Test
    void resourceIdsComeFromHeaderWhenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TicketIdentityConfiguration.HEADER_TENANT_ID, "tenant-demo");
        request.addHeader(TicketIdentityConfiguration.HEADER_USER_ID, "user-demo");
        request.addHeader(TicketIdentityConfiguration.HEADER_RESOURCE_IDS, "project-x,project-y");
        request.addHeader("X-Session-Id", "sess-res-header");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(principalResolver.resolve().getResourceIds())
                .containsExactly("project-x", "project-y");
    }

    @Test
    void differentSessionsDoNotSharePendingActions() {
        // 会话 A 提议建单
        withHeaders("tenant-demo", "user-demo", "sess-iso-a");
        AgentResult proposalA = controller.chat(Map.of("input", "创建工单 会话隔离A"));
        assertThat(proposalA.getStatus()).isEqualTo(AgentStatus.NEEDS_CONFIRM);

        // 会话 B 提议建单——不得看到 A 的待确认动作
        withHeaders("tenant-demo", "user-demo", "sess-iso-b");
        AgentResult proposalB = controller.chat(Map.of("input", "创建工单 会话隔离B"));
        assertThat(proposalB.getStatus()).isEqualTo(AgentStatus.NEEDS_CONFIRM);

        assertThat(proposalB.getPendingActions().get(0).getActionId())
                .as("不同会话必须持有各自的待确认动作，否则确认会串到别的会话")
                .isNotEqualTo(proposalA.getPendingActions().get(0).getActionId());
    }

    @Test
    void writeProposalCarriesRequestLevelIdempotencyKey() {
        withHeaders("tenant-demo", "user-demo", "sess-idem");

        AgentResult proposal = controller.chat(Map.of("input", "创建工单 幂等键检查"));

        assertThat(proposal.getStatus()).isEqualTo(AgentStatus.NEEDS_CONFIRM);
        // FR-32：写动作必须带幂等键，否则 GuardNode fail-closed 直接 REJECTED。
        // 能走到 NEEDS_CONFIRM 说明 client 生成的键被正确透传；
        // 键值本身锁定「同会话共用一个键」的历史语义
        assertThat(proposal.getPendingActions().get(0).getIdempotencyKey())
                .isEqualTo("keel:tenant-demo:sess-idem");
    }

    @Test
    void confirmedWriteAppliesExactlyOncePerSession() {
        int before = ticketService.listAll().size();
        withHeaders("tenant-demo", "user-demo", "sess-confirm");

        AgentResult proposal = controller.chat(Map.of("input", "创建工单 确认一次"));
        String actionId = proposal.getPendingActions().get(0).getActionId();

        controller.confirm(Map.of("actionId", actionId));
        assertThat(ticketService.listAll()).hasSize(before + 1);

        // 重复确认不得产生第二笔副作用（FR-32 幂等）
        controller.confirm(Map.of("actionId", actionId));
        assertThat(ticketService.listAll()).hasSize(before + 1);
    }

    @Test
    void chatWithoutSessionHeaderStillWorksViaDerivedSession() {
        // 只带身份头、不带 X-Session-Id：不能崩，且应走到正常终结状态
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TicketIdentityConfiguration.HEADER_TENANT_ID, "tenant-demo");
        request.addHeader(TicketIdentityConfiguration.HEADER_USER_ID, "user-demo");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        AgentResult result = keelClient.chat("查询工单 T-001", "ticket-ops");

        assertThat(result.getStatus()).isIn(AgentStatus.SUCCESS, AgentStatus.UNCERTAIN);
        assertThat(result.getErrorCode()).isNull();
    }
}
