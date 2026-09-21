package io.keel.core;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一条检索引用（FR-42 citation 载体 / FR-62 untrusted 标记）。
 *
 * <p>{@code untrusted} 表示该片段来自检索（或其它外部来源），属于「不可信内容」：
 * 只能作为资料供模型参考，绝不能当作指令执行，更不能作为提权/写操作的依据（FR-62）。
 * 默认构造的检索引用一律 untrusted=true——默认不信任，fail-closed（NFR-01）。</p>
 */
public final class Citation implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String chunkId;
    private final String source;
    private final String snippet;
    private final boolean untrusted;

    /** 检索来源的引用默认标记为 untrusted（FR-62：检索文本天然不可信）。 */
    public Citation(String chunkId, String source, String snippet) {
        this(chunkId, source, snippet, true);
    }

    public Citation(String chunkId, String source, String snippet, boolean untrusted) {
        this.chunkId = chunkId;
        this.source = source;
        this.snippet = snippet;
        this.untrusted = untrusted;
    }

    public String getChunkId() {
        return chunkId;
    }

    public String getSource() {
        return source;
    }

    public String getSnippet() {
        return snippet;
    }

    public boolean isUntrusted() {
        return untrusted;
    }
}
