package io.keel.graph;

/**
 * 图执行期的配置/端口类错误（如模型决策要检索却没装 RetrievalPort）。
 * 由 GraphKeelAgent 统一兜底为 ERROR 结果并携带可解释 errorCode（NFR-04 方向）。
 */
public class GraphExecutionException extends RuntimeException {

    private final String code;

    public GraphExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
