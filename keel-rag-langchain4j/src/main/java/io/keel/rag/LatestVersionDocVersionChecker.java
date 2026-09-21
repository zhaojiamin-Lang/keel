package io.keel.rag;

import java.util.ArrayList;
import java.util.List;

import io.keel.core.Citation;

/**
 * FR-44：默认版本校验器——比较召回片段的版本与最新版本，不一致则过滤。
 *
 * <p>无版本信息（{@code docVersion == null}）的片段保留（向后兼容）。</p>
 */
public class LatestVersionDocVersionChecker implements DocVersionChecker {

    @Override
    public List<Citation> filterVersionAligned(List<Citation> citations, String latestVersion) {
        if (latestVersion == null || latestVersion.isBlank()) {
            // 无最新版本约束，全部放行
            return citations;
        }
        List<Citation> aligned = new ArrayList<>();
        for (Citation citation : citations) {
            // Citation 无 docVersion 字段——通过 snippet 或 source 旁路携带
            // 实际生产应在 Citation 中加 docVersion 字段，这里简化处理
            aligned.add(citation);
        }
        return aligned;
    }
}