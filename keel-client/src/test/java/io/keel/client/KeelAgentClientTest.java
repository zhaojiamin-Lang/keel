package io.keel.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelAgent;
import io.keel.core.KeelPrincipal;

/**
 * {@link KeelAgentClient} 的装配契约测试。
 *
 * <p>重点验证「业务不再需要手写的那部分」是否真的被接管了：</p>
 * <ul>
 *     <li>AgentRequest 的 principal / skill / input / sessionId / idempotencyKey 是否都填对；</li>
 *     <li>sessionId 三级解析（显式 → 请求头 → 身份派生兜底）是否按预期生效；</li>
 *     <li>skill 为空时是否<b>不设</b>该字段（让内核自行路由），而不是塞一个空串；</li>
 *     <li>confirm 路径是否不带幂等键（幂等键在提议阶段已随 PendingAction 保存）。</li>
 * </ul>
 */
class KeelAgentClientTest {

    private final RecordingAgent agent = new RecordingAgent();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private KeelAgentClient clientWith(KeelPrincipal principal) {
        return new KeelAgentClient(agent, () -> principal);
    }

    // ==================== 请求装配 ====================

    @Test
    void chatFillsPrincipalInputSessionAndIdempotencyKey() {
        KeelPrincipal principal = new KeelPrincipal("tenant-A", "user-1", List.of("project-1"));
        KeelAgentClient client = clientWith(principal);

        client.chat("查询工单");

        AgentRequest sent = agent.lastRequest();
        assertThat(sent.getPrincipal()).isSameAs(principal);
        assertThat(sent.getInput()).isEqualTo("查询工单");
        // 会话兜底：没传 sessionId、也没有请求头 → 按身份派生稳定值（不是随机数）
        assertThat(sent.getSessionId()).isEqualTo("sess-tenant-A-user-1");
        // 幂等键由本层生成，语义为「同会话共用一个键」
        assertThat(sent.getIdempotencyKey()).isEqualTo("keel:tenant-A:sess-tenant-A-user-1");
    }

    @Test
    void blankSkillIsOmittedSoKernelCanRoute() {
        KeelAgentClient client = clientWith(new KeelPrincipal("t", "u", List.of()));

        client.chat("随便问问", "   ");

        // 关键：空白 skill 必须「不设置」而不是设置成空串——
        // 设空串会让内核拿空名去查 skill 而报错，而不是走 KeywordSkillRouter 自动路由
        assertThat(agent.lastRequest().getSkill()).isNull();
    }

    @Test
    void explicitSkillIsPassedThrough() {
        KeelAgentClient client = clientWith(new KeelPrincipal("t", "u", List.of()));

        client.chat("查询", "ticket-ops");

        assertThat(agent.lastRequest().getSkill()).isEqualTo("ticket-ops");
    }

    @Test
    void confirmSetsConfirmedActionIdAndCarriesNoIdempotencyKey() {
        KeelAgentClient client = clientWith(new KeelPrincipal("tenant-A", "user-1", List.of()));

        client.confirm("action-123");

        AgentRequest sent = agent.lastRequest();
        assertThat(sent.getConfirmedActionId()).isEqualTo("action-123");
        // 幂等键在提议阶段已写进 PendingAction 并随 checkpoint 保存，
        // confirm 只负责定位动作——此处不应再生成键（否则语义会漂移）
        assertThat(sent.getIdempotencyKey()).isNull();
    }

    @Test
    void confirmRejectsBlankActionId() {
        KeelAgentClient client = clientWith(new KeelPrincipal("t", "u", List.of()));

        // 空白与 null 同样无用——会构造出永远恢复不到 PendingAction 的请求，
        // 用户表现为「点了确认没反应」，所以必须在入口拒绝
        assertThatThrownBy(() -> client.confirm(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actionId");
        assertThatThrownBy(() -> client.confirm(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actionId");
    }

    // ==================== sessionId 三级解析 ====================

    @Test
    void sessionIdComesFromRequestHeaderWhenPresent() {
        withSessionHeader("header-session");
        KeelAgentClient client = clientWith(new KeelPrincipal("tenant-A", "user-1", List.of()));

        client.chat("查询");

        assertThat(agent.lastRequest().getSessionId()).isEqualTo("header-session");
        assertThat(agent.lastRequest().getIdempotencyKey())
                .isEqualTo("keel:tenant-A:header-session");
    }

    @Test
    void explicitSessionIdWinsOverRequestHeader() {
        withSessionHeader("header-session");
        KeelAgentClient client = clientWith(new KeelPrincipal("tenant-A", "user-1", List.of()));

        client.run("查询", "ticket-ops", "explicit-session");

        assertThat(agent.lastRequest().getSessionId()).isEqualTo("explicit-session");
    }

    @Test
    void fallsBackToIdentityDerivedSessionWithoutAnyContext() {
        // 非 Web 线程（批处理 / 定时任务）：没有请求头也不能崩，且兜底值必须稳定
        KeelAgentClient client = clientWith(new KeelPrincipal("tenant-A", "user-1", List.of()));

        client.chat("第一次");
        String first = agent.lastRequest().getSessionId();
        client.chat("第二次");
        String second = agent.lastRequest().getSessionId();

        assertThat(first).isEqualTo("sess-tenant-A-user-1");
        // 稳定而非随机：否则多轮对话永远命中不到 checkpoint（写确认会静默失效）
        assertThat(second).isEqualTo(first);
    }

    @Test
    void customIdempotencyKeyPrefixIsUsed() {
        KeelAgentClient client = new KeelAgentClient(
                agent, () -> new KeelPrincipal("tenant-A", "user-1", List.of()), "myapp");

        client.chat("查询");

        assertThat(agent.lastRequest().getIdempotencyKey()).startsWith("myapp:tenant-A:");
    }

    // ==================== 身份防御 ====================

    @Test
    void nullPrincipalFromResolverFailsFast() {
        // 身份是租户隔离的根（NFR-02 P0）：宁可此处报错，
        // 也不要带着 null 身份往下走到 RAG / Memory / MCP
        KeelAgentClient client = new KeelAgentClient(agent, () -> null);

        assertThatThrownBy(() -> client.chat("查询"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PrincipalResolver");
    }

    private static void withSessionHeader(String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SessionIds.HEADER_SESSION_ID, sessionId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 记录收到的请求，并按需返回结果；不涉及任何编排逻辑。 */
    private static final class RecordingAgent implements KeelAgent {

        private final List<AgentRequest> requests = new ArrayList<>();

        @Override
        public AgentResult run(AgentRequest request) {
            requests.add(request);
            return AgentResult.builder().status(AgentStatus.SUCCESS).text("ok").build();
        }

        AgentRequest lastRequest() {
            assertThat(requests).as("client 必须把请求转交给 KeelAgent").isNotEmpty();
            return requests.get(requests.size() - 1);
        }
    }
}
