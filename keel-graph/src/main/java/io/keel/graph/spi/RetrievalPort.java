package io.keel.graph.spi;

import java.util.List;

import io.keel.core.Citation;
import io.keel.core.KeelPrincipal;

/**
 * 知识检索端口（后续由 keel-rag-langchain4j 适配）。
 * 实现必须自行按 principal 做 tenant/project/acl 元数据过滤（FR-41/FR-60），
 * 图内核只负责把召回片段带入状态、交给 Critic 核对引用。
 */
public interface RetrievalPort {

    List<Citation> retrieve(String query, KeelPrincipal principal);
}
