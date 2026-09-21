package io.keel.rag;

import io.keel.core.KeelPrincipal;

/**
 * 查询改写扩展点（FR-41 P0）：在向量检索前对用户原始问题做改写。
 *
 * <p>安全约束：改写只能改变「问法」，不得改变租户 / 资源语义；实现方拿不到、
 * 也不应该尝试绕开 {@link KeelPrincipal} 的 ACL 范围，过滤始终由
 * {@link AclScopeFilterFactory} 代码化强制。</p>
 */
@FunctionalInterface
public interface QueryRewriter {

    String rewrite(String query, KeelPrincipal principal);
}
