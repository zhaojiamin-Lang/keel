package io.keel.guard;

import java.util.List;
import java.util.regex.Pattern;

import io.keel.core.Citation;
import io.keel.core.PendingAction;
import io.keel.graph.LoopState;
import io.keel.graph.Verdict;
import io.keel.graph.spi.GuardPort;

/**
 * Prompt 注入防护（FR-62，铁律 4：安全规则用代码实现，不写在 Prompt 里）。
 *
 * <p>背景：检索回来的文本（{@code Citation}，标记 untrusted）可能携带恶意指令，
 * 诱导模型提权或执行写操作。本 Guard 的代码化规则：</p>
 *
 * <ol>
 *     <li>只有当本请求出现了 untrusted 检索片段、且片段命中注入话术特征时，才介入——
 *     正常「先检索后写入」业务流不受影响；</li>
 *     <li>命中注入特征且存在待确认写动作（{@link PendingAction}）→
 *     硬拒绝 {@code INJECTION_ESCALATION_BLOCKED}：untrusted 内容不得成为提权/写入的依据。</li>
 * </ol>
 *
 * <p>注意：写操作本身仍受 FR-13（外部确认）与 FR-32（幂等键）约束，本 Guard 是
 * 叠加在其上的注入防御纵深，不是替代。</p>
 */
public final class InjectionGuard implements GuardPort {

    /** 硬拒绝原因码：Harness 可据此断言「untrusted 内容未导致提权」。 */
    public static final String CODE_INJECTION_ESCALATION_BLOCKED = "INJECTION_ESCALATION_BLOCKED";

    /**
     * 注入话术特征（不区分大小写）。覆盖常见英文与中文越狱/提权话术。
     * 只做「检测 + 拦截写动作」，不尝试理解语义——宁可误拦写操作（可由用户重新确认），
     * 也不放行注入（NFR-01 默认偏安全）。
     */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // 英文：无视/覆盖系统指令
            Pattern.compile("ignore\\s+(all\\s+)?(previous|prior|above|earlier)\\s+instructions",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+)?(previous|prior|above)\\s+instructions",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+(a|an)\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system\\s*prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(grant|elevate)\\s+(me\\s+)?(admin|root|permission)", Pattern.CASE_INSENSITIVE),
            // 中文：忽略指令 / 扮演 / 提权 / 关闭防护
            Pattern.compile("忽略(之前|以上|前面|上面|所有|全部)的?(指令|规则|设定)"),
            Pattern.compile("无视(之前|以上|前面|上面|所有|全部)的?(指令|规则|设定)"),
            Pattern.compile("你现在是"),
            Pattern.compile("(提升|升高|申请|授予|获得).{0,4}(管理员|root|admin|最高)?权限"),
            Pattern.compile("关闭(防护|guard|安全检查)"));

    @Override
    public Verdict inspect(LoopState state) {
        PendingAction pending = state.getPendingAction();
        if (pending == null) {
            // 无写动作 = 无提权面，注入检测交给 Prompt 侧的 untrusted 标注即可
            return Verdict.approved();
        }
        for (Citation citation : state.getCitations()) {
            // FR-62：检索文本默认 untrusted（Citation 构造默认 true，fail-closed）；
            // 仅显式标记为可信的片段（如未来经代码校验的业务 ID 查询结果）跳过本规则
            if (!citation.isUntrusted()) {
                continue;
            }
            if (containsInjection(citation.getSnippet())) {
                return Verdict.rejected(
                        CODE_INJECTION_ESCALATION_BLOCKED,
                        "检索片段命中注入话术（chunkId=" + citation.getChunkId()
                                + "），untrusted 内容不得作为写操作 " + pending.getTool()
                                + " 的依据（FR-62）");
            }
        }
        return Verdict.approved();
    }

    /** 供测试与业务扩展复用：判断给定文本是否命中注入话术特征。 */
    public static boolean containsInjection(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
