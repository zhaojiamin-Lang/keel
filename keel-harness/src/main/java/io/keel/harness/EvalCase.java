package io.keel.harness;

import java.util.List;
import java.util.Objects;

import io.keel.core.AgentRequest;
import io.keel.core.KeelPrincipal;

/**
 * A single evaluation case: one expected request shape plus the safety rules
 * that a produced {@link io.keel.core.AgentResult} must satisfy.
 */
public final class EvalCase {

    private final String id;
    private final String skill;
    private final boolean requireCitation;
    private final List<String> allowedIssueIds;
    private final List<String> forbiddenSubstrings;
    private final String tenantId;
    private final String subjectId;
    private final List<String> resourceIds;
    private final String input;
    private final String idempotencyKey;
    private final boolean expectWrite;

    private EvalCase(Builder builder) {
        this.id = builder.id;
        this.skill = builder.skill;
        this.requireCitation = builder.requireCitation;
        this.allowedIssueIds =
                builder.allowedIssueIds == null ? null : List.copyOf(builder.allowedIssueIds);
        this.forbiddenSubstrings = builder.forbiddenSubstrings == null
                ? List.of()
                : List.copyOf(builder.forbiddenSubstrings);
        this.tenantId = builder.tenantId;
        this.subjectId = builder.subjectId;
        this.resourceIds = builder.resourceIds == null ? List.of() : List.copyOf(builder.resourceIds);
        this.input = builder.input;
        this.idempotencyKey = builder.idempotencyKey;
        this.expectWrite = builder.expectWrite;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public String getSkill() {
        return skill;
    }

    public boolean isRequireCitation() {
        return requireCitation;
    }

    public List<String> getAllowedIssueIds() {
        return allowedIssueIds;
    }

    public List<String> getForbiddenSubstrings() {
        return forbiddenSubstrings;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public List<String> getResourceIds() {
        return resourceIds;
    }

    public String getInput() {
        return input;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public boolean isExpectWrite() {
        return expectWrite;
    }

    public AgentRequest toAgentRequest() {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(input, "input");

        KeelPrincipal principal = new KeelPrincipal(tenantId, subjectId, resourceIds);
        AgentRequest.Builder request = AgentRequest.builder()
                .principal(principal)
                .input(input);
        if (idempotencyKey != null) {
            request.idempotencyKey(idempotencyKey);
        }
        if (skill != null) {
            request.skill(skill);
        }
        return request.build();
    }

    public static final class Builder {

        private String id;
        private String skill;
        private boolean requireCitation;
        private List<String> allowedIssueIds;
        private List<String> forbiddenSubstrings;
        private String tenantId;
        private String subjectId;
        private List<String> resourceIds;
        private String input;
        private String idempotencyKey;
        private boolean expectWrite;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder skill(String skill) {
            this.skill = skill;
            return this;
        }

        public Builder requireCitation(boolean requireCitation) {
            this.requireCitation = requireCitation;
            return this;
        }

        public Builder allowedIssueIds(List<String> allowedIssueIds) {
            this.allowedIssueIds = allowedIssueIds;
            return this;
        }

        public Builder forbiddenSubstrings(List<String> forbiddenSubstrings) {
            this.forbiddenSubstrings = forbiddenSubstrings;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder subjectId(String subjectId) {
            this.subjectId = subjectId;
            return this;
        }

        public Builder resourceIds(List<String> resourceIds) {
            this.resourceIds = resourceIds;
            return this;
        }

        public Builder input(String input) {
            this.input = input;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder expectWrite(boolean expectWrite) {
            this.expectWrite = expectWrite;
            return this;
        }

        public EvalCase build() {
            Objects.requireNonNull(id, "id");
            return new EvalCase(this);
        }
    }
}
