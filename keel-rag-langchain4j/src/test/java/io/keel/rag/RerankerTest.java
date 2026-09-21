package io.keel.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.Citation;

/**
 * FR-41：Rerank 端口——默认 NoOp 保持原序；业务可替换实现并确实生效于检索结果。
 */
class RerankerTest {

    @Test
    void noOpRerankerKeepsOriginalOrder() {
        Reranker reranker = new NoOpReranker();
        List<Citation> citations = List.of(
                new Citation("c-2", "doc-b", "beta"),
                new Citation("c-1", "doc-a", "alpha"));

        List<Citation> reranked = reranker.rerank("any query", citations);

        assertThat(reranked).isSameAs(citations);
        assertThat(reranked).extracting(Citation::getChunkId)
                .containsExactly("c-2", "c-1");
    }

    @Test
    void emptyAndNullInputsAreTolerated() {
        assertThat(new NoOpReranker().rerank("q", List.of())).isEmpty();
    }

    /**
     * 用测试内定义的关键词命中优先 Reranker 验证端口语义：
     * rerank 收到的是「已过滤 ACL 的初步召回」，输出会替换原顺序。
     */
    @Test
    void customRerankerCanReorderByQueryRelevance() {
        Reranker keywordFirst = (query, citations) -> {
            List<Citation> hits = new ArrayList<>();
            List<Citation> misses = new ArrayList<>();
            for (Citation citation : citations) {
                (citation.getSnippet().contains(query) ? hits : misses).add(citation);
            }
            List<Citation> ordered = new ArrayList<>(hits);
            ordered.addAll(misses);
            return List.copyOf(ordered);
        };

        List<Citation> retrieved = List.of(
                new Citation("c-miss", "doc-1", "无关内容"),
                new Citation("c-hit", "doc-2", "包含退款 refund 关键词"));

        List<Citation> reranked = keywordFirst.rerank("refund", retrieved);

        assertThat(reranked).extracting(Citation::getChunkId)
                .containsExactly("c-hit", "c-miss");
    }
}
