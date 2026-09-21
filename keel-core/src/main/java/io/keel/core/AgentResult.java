package io.keel.core;

import java.util.List;
import java.util.Objects;

public final class AgentResult {

    private final AgentStatus status;
    private final String text;
    private final List<Citation> citations;
    private final List<PendingAction> pendingActions;
    private final String traceId;
    private final String errorCode;
    private final String errorMessage;

    private AgentResult(Builder builder) {
        this.status = builder.status;
        this.text = builder.text;
        this.citations = builder.citations == null ? List.of() : List.copyOf(builder.citations);
        this.pendingActions =
                builder.pendingActions == null ? List.of() : List.copyOf(builder.pendingActions);
        this.traceId = builder.traceId;
        this.errorCode = builder.errorCode;
        this.errorMessage = builder.errorMessage;
    }

    public static Builder builder() {
        return new Builder();
    }

    public AgentStatus getStatus() {
        return status;
    }

    public String getText() {
        return text;
    }

    public List<Citation> getCitations() {
        return citations;
    }

    public List<PendingAction> getPendingActions() {
        return pendingActions;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public static final class Builder {

        private AgentStatus status;
        private String text;
        private List<Citation> citations;
        private List<PendingAction> pendingActions;
        private String traceId;
        private String errorCode;
        private String errorMessage;

        private Builder() {
        }

        public Builder status(AgentStatus status) {
            this.status = status;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder citations(List<Citation> citations) {
            this.citations = citations;
            return this;
        }

        public Builder pendingActions(List<PendingAction> pendingActions) {
            this.pendingActions = pendingActions;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public AgentResult build() {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(text, "text");
            return new AgentResult(this);
        }
    }
}
