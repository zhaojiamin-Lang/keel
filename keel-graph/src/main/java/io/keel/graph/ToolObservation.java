package io.keel.graph;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * 一次工具执行的观察结果（Observe 阶段消费）。
 * 失败也以正常对象返回，交给下一轮 Plan 决策，而不是让异常击穿整个请求。
 */
public final class ToolObservation implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String tool;
    private final boolean success;
    private final String content;
    private final String errorMessage;

    private ToolObservation(String tool, boolean success, String content, String errorMessage) {
        this.tool = Objects.requireNonNull(tool, "tool");
        this.success = success;
        this.content = content == null ? "" : content;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static ToolObservation ok(String tool, String content) {
        return new ToolObservation(tool, true, content, "");
    }

    public static ToolObservation failed(String tool, String errorMessage) {
        return new ToolObservation(tool, false, "", errorMessage);
    }

    public String getTool() {
        return tool;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getContent() {
        return content;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
