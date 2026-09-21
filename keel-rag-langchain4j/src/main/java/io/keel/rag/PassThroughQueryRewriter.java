package io.keel.rag;

import io.keel.core.KeelPrincipal;

/**
 * 默认查询改写器：原样透传。
 * MVP 阶段不引入 LLM 改写（P1），保证检索链路确定、可测、无额外模型依赖。
 */
public class PassThroughQueryRewriter implements QueryRewriter {

    @Override
    public String rewrite(String query, KeelPrincipal principal) {
        return query;
    }
}
