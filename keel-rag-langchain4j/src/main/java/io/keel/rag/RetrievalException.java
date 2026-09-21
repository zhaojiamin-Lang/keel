package io.keel.rag;

/**
 * 检索链路失败时抛出（embedding 服务不可用、向量库异常等）。
 *
 * <p>按 NFR-01 失败拒绝原则：检索失败绝不静默返回空召回（那会让模型在无依据下作答），
 * 图内核捕获后以 ERROR 终止本次执行。</p>
 */
public class RetrievalException extends RuntimeException {

    public static final String CODE = "RETRIEVAL_FAILED";

    public RetrievalException(String message, Throwable cause) {
        super(message, cause);
    }
}
