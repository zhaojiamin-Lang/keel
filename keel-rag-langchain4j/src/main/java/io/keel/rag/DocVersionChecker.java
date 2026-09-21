package io.keel.rag;

import java.util.List;

import io.keel.core.Citation;

/**
 * FR-44：文档版本校验端口。
 *
 * <p>要求：召回的 {@link Citation} 若携带 {@code doc_version} 元数据，
 * 版本未对齐（与最新版本不一致）时不得作为关单/变更类操作的依据。</p>
 *
 * <p>默认实现 {@link LatestVersionDocVersionChecker} 比较召回片段的版本与最新版本，
 * 不一致则过滤掉。业务可自定义实现（如按时间戳或版本号语义比较）。</p>
 */
public interface DocVersionChecker {

    /**
     * 过滤掉版本未对齐的引用。
     *
     * @param citations 召回的引用列表
     * @param latestVersion 最新文档版本（由业务侧提供）
     * @return 版本对齐的引用列表
     */
    List<Citation> filterVersionAligned(List<Citation> citations, String latestVersion);
}