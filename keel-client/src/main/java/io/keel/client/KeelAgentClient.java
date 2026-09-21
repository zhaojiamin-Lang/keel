package io.keel.client;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.KeelAgent;
import io.keel.core.KeelPrincipal;

/**
 * Keel 接入适配器：把「拼 {@link AgentRequest}」这件事从每个业务端点里收口。
 *
 * <p><b>它解决什么：</b>没有它时，每个业务端点都要重复写这段样板——</p>
 * <pre>{@code
 * AgentRequest request = AgentRequest.builder()
 *         .principal(new KeelPrincipal("tenant-A", "user-1", List.of("ISSUE-1")))  // 身份从哪来？
 *         .skill("ticket-ops")
 *         .input(body.get("input"))
 *         .sessionId(sessionId == null ? "sess-" + System.currentTimeMillis() : sessionId)  // 兜底写法各异
 *         .idempotencyKey("keel:" + tenant + ":" + sid)   // FR-32 P0 语义，拼错要很久才发现
 *         .build();
 * result = keelAgent.run(request);
 * }</pre>
 * <p>有了它之后：{@code keelClient.chat(input)}。身份走
 * {@link PrincipalResolver}（请求级、可换实现），session / 幂等键走本模块的
 * {@link SessionIds} / {@link IdempotencyKeys} 统一规则。</p>
 *
 * <p><b>它不做什么：</b>不做端点、不做鉴权决策、不做重试。端点属于业务（铁律 5：
 * 内核不生成 ChatController）；鉴权在 {@link PrincipalResolver} 实现里；重试策略在
 * MCP 层（{@code keel.tools.mcp.max-retries}）。本类只做<b>请求装配</b>，保持无状态。</p>
 *
 * <p>需要完全控制请求字段（例如自定义 skill 路由、手工构造幂等键）时，直接注入
 * {@link KeelAgent} 使用底层 API——本类是便利层，不是强制入口。</p>
 */
public class KeelAgentClient {

    private static final Logger log = LoggerFactory.getLogger(KeelAgentClient.class);

    private final KeelAgent keelAgent;
    private final PrincipalResolver principalResolver;
    private final String idempotencyKeyPrefix;

    public KeelAgentClient(KeelAgent keelAgent, PrincipalResolver principalResolver) {
        this(keelAgent, principalResolver, IdempotencyKeys.DEFAULT_PREFIX);
    }

    public KeelAgentClient(
            KeelAgent keelAgent, PrincipalResolver principalResolver, String idempotencyKeyPrefix) {
        this.keelAgent = Objects.requireNonNull(keelAgent, "keelAgent");
        this.principalResolver = Objects.requireNonNull(principalResolver, "principalResolver");
        this.idempotencyKeyPrefix =
                (idempotencyKeyPrefix == null || idempotencyKeyPrefix.isBlank())
                        ? IdempotencyKeys.DEFAULT_PREFIX
                        : idempotencyKeyPrefix;
    }

    // ==================== 对外三个主入口 ====================

    /**
     * 发送一次请求，skill 由内核按输入自动路由（FR-21）。
     *
     * @param input 用户输入
     */
    public AgentResult chat(String input) {
        return chat(input, null);
    }

    /**
     * 发送一次请求，指定 skill。
     *
     * @param input 用户输入
     * @param skill skill 名；{@code null} / 空白时交由内核按输入路由
     */
    public AgentResult chat(String input, String skill) {
        return run(input, skill, null);
    }

    /**
     * 确认此前返回的待执行写动作（FR-13）。
     *
     * <p>注意：确认请求<b>不带幂等键</b>——写动作的幂等键在提议阶段已经写进
     * {@code PendingAction} 并随 checkpoint 保存，此处再由 {@code confirmActionId}
     * 定位到那个动作即可。重复确认由原幂等键在服务端 dedupe。</p>
     *
     * @param actionId 提议阶段返回的 {@code pendingActions[].actionId}
     */
    public AgentResult confirm(String actionId) {
        // 空白 actionId 与 null 同样无用：它会构造出一个永远恢复不到 PendingAction 的请求，
        // 用户看到的就是「点了确认没反应」。必须在入口显式拒绝。
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("actionId 不能为空（confirm 需要待确认动作 ID）");
        }
        KeelPrincipal principal = currentPrincipal();
        return keelAgent.run(AgentRequest.builder()
                .principal(principal)
                .input("确认执行")
                .sessionId(resolveSessionId(principal, null))
                .confirmedActionId(actionId)
                .build());
    }

    /**
     * 完整控制入口：显式指定会话 ID。
     *
     * @param input     用户输入
     * @param skill     skill 名，可为 {@code null}（交由内核路由）
     * @param sessionId 会话 ID，可为 {@code null}（按 {@link SessionIds} 规则解析）
     */
    public AgentResult run(String input, String skill, String sessionId) {
        Objects.requireNonNull(input, "input 不能为空");
        KeelPrincipal principal = currentPrincipal();
        String effectiveSession = resolveSessionId(principal, sessionId);

        AgentRequest.Builder builder = AgentRequest.builder()
                .principal(principal)
                .input(input)
                .sessionId(effectiveSession)
                // FR-32 P0：幂等键由本层统一生成，业务不再手拼。
                // 语义为「同会话共用一个键」（见 IdempotencyKeys 类注释）
                .idempotencyKey(IdempotencyKeys.of(
                        idempotencyKeyPrefix, principal.getTenantId(), effectiveSession));
        // skill 为空时省略该字段，让内核按 KeywordSkillRouter 自动路由（FR-21）
        if (skill != null && !skill.isBlank()) {
            builder.skill(skill);
        }
        return keelAgent.run(builder.build());
    }

    // ==================== 身份与会话解析 ====================

    /**
     * 解析当前身份。{@link PrincipalResolver} 契约要求不返回 null，
     * 这里仍做一次防御性校验——身份是租户隔离的根（NFR-02 P0），
     * 宁可此处报错，也不要带着 null 身份往下走到 RAG / Memory / MCP。
     */
    private KeelPrincipal currentPrincipal() {
        KeelPrincipal principal = principalResolver.resolve();
        if (principal == null) {
            throw new IllegalStateException(
                    "PrincipalResolver 返回 null：身份是租户隔离的根（FR-60/NFR-02），"
                            + "请检查你的 PrincipalResolver 实现");
        }
        return principal;
    }

    /**
     * 会话 ID 解析：显式入参 → 请求头 → 按身份派生稳定兜底。
     * 请求头读取依赖 spring-web（optional），非 Web 线程下静默跳过。
     */
    private String resolveSessionId(KeelPrincipal principal, String explicitSessionId) {
        if (SessionIds.isUsable(explicitSessionId)) {
            return explicitSessionId;
        }
        String fromHeader = sessionIdFromRequest();
        if (SessionIds.isUsable(fromHeader)) {
            return fromHeader;
        }
        return SessionIds.fallback(principal.getTenantId(), principal.getSubjectId());
    }

    /**
     * 从当前 HTTP 请求读 {@code X-Session-Id}；非 Web 上下文或读不到时返回 null。
     *
     * <p>注意：必须通过 {@link ServletRequestAttributes#getRequest()} 拿
     * {@code HttpServletRequest} 再取 header。用
     * {@code RequestContextHolder.getRequestAttributes().getAttribute(name, SCOPE_REQUEST)}
     * <b>是错的</b>——那只查 request attribute（{@code setAttribute} 写入的值），
     * 不含 HTTP header，会安静地永远返回 null，导致会话识别整体失效。</p>
     */
    private String sessionIdFromRequest() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
                return null;
            }
            return servletAttributes.getRequest().getHeader(SessionIds.HEADER_SESSION_ID);
        } catch (NoClassDefFoundError | IllegalStateException ignored) {
            // classpath 没有 spring-web，或当前不是请求线程——两种情况都回退到身份派生兜底
            log.debug("无法从请求上下文读取 {}，回退到身份派生会话 ID", SessionIds.HEADER_SESSION_ID);
            return null;
        }
    }
}
