package io.keel.client;

import java.util.Objects;

/**
 * 会话 ID 解析与兜底生成。
 *
 * <p>会话 ID 是写确认链路的前置条件（FR-12/FR-13）：checkpoint 按 runId 存取，
 * 而 runId 由 sessionId 派生——没有稳定 sessionId，确认请求恢复不到待确认动作，
 * 用户点「确认」会静默什么都没发生。</p>
 *
 * <p><b>解析优先级</b>（由 {@link KeelAgentClient} 调用）：</p>
 * <ol>
 *     <li>调用方显式传入的 sessionId；</li>
 *     <li>当前 HTTP 请求头 {@code X-Session-Id}（Web 场景）；</li>
 *     <li>按 tenant + subject 派生的稳定值 {@code sess-{tenant}-{subject}}
 *         ——不是随机数，原因见下。</li>
 * </ol>
 *
 * <p><b>为什么兜底不用随机数：</b>早期示例用 {@code "sess-" + System.currentTimeMillis()}，
 * 每次请求都是新会话，导致 checkpoint 永远命中不到、写确认链路在多轮对话里必然失败。
 * 兜底值必须<b>对同一身份稳定</b>，才能在「没传 sessionId」时仍然保持多轮语义。
 * 代价是同一身份的不同对话会共享会话；需要真正并发多会话时，请显式传 sessionId
 * 或带 {@code X-Session-Id} 头。</p>
 */
public final class SessionIds {

    /** 会话 ID 的 HTTP 请求头名；示例与原 Controller 均用它。 */
    public static final String HEADER_SESSION_ID = "X-Session-Id";

    private SessionIds() {
    }

    /**
     * 按身份派生稳定兜底会话 ID。
     *
     * @param tenantId  租户
     * @param subjectId 主体
     * @return 形如 {@code sess-tenant-A-user-1}
     */
    public static String fallback(String tenantId, String subjectId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(subjectId, "subjectId 不能为空");
        return "sess-" + tenantId + "-" + subjectId;
    }

    /** 显式传入的 sessionId 是否可用（非 null、非空白）。 */
    public static boolean isUsable(String sessionId) {
        return sessionId != null && !sessionId.isBlank();
    }
}
