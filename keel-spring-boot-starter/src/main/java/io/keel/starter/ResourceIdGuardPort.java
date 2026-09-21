package io.keel.starter;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.keel.core.KeelPrincipal;
import io.keel.graph.LoopState;
import io.keel.graph.Verdict;
import io.keel.graph.spi.GuardPort;

/**
 * FR-61：运行时业务 ID 验证——模型在答案中提到的业务 ID（如 ISSUE-1234）
 * 必须在 principal.resourceIds 或工具调用已确认存在的 ID 集合内，否则视为编造，
 * 直接拒绝（FABRICATED_ID）。Harness 侧有 fabricated_id 检测，这里补运行时 Guard。
 *
 * <p>检测范围：草稿答案文本（state.draftAnswer）。工具调用参数中的 ID 由工具实现侧
 * 自行校验（如 ticket.query 收到不存在的 ISSUE-XXX 应返回业务错误），不在此处重复。</p>
 *
 * <p>跳过条件：principal.resourceIds 为空时不检测（无资源约束的场景不强制）。
 * 语义与 RAG ACL 一致：resourceIds 空时允许全租户检索，不视为违规。</p>
 */
public class ResourceIdGuardPort implements GuardPort {

    /** ISSUE-\\d+ 是工单 ID 的命名约定；未来其他业务 ID 模式可扩展为配置。 */
    private static final Pattern RESOURCE_ID_PATTERN = Pattern.compile("ISSUE-\\d+");

    @Override
    public Verdict inspect(LoopState state) {
        KeelPrincipal principal = state.getRequest().getPrincipal();
        if (principal == null) {
            return Verdict.approved();
        }
        List<String> allowed = principal.getResourceIds();
        if (allowed == null || allowed.isEmpty()) {
            return Verdict.approved();
        }
        Set<String> allowedSet = Set.copyOf(allowed);

        String draftAnswer = state.getDraftAnswer();
        if (draftAnswer == null || draftAnswer.isBlank()) {
            return Verdict.approved();
        }

        Matcher matcher = RESOURCE_ID_PATTERN.matcher(draftAnswer);
        while (matcher.find()) {
            String foundId = matcher.group();
            if (!allowedSet.contains(foundId)) {
                return Verdict.rejected(
                        "FABRICATED_ID",
                        "答案中包含未经授权的业务 ID: " + foundId
                                + "，不在 principal.resourceIds 内");
            }
        }
        return Verdict.approved();
    }
}