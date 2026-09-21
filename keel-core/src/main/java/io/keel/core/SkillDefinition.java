package io.keel.core;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Immutable declaration of a skill that a {@link KeelAgent} may serve.
 *
 * <p>This type is intentionally kept in {@code keel-core} so both the
 * skill loader and the guard modules can share it without introducing
 * unwanted dependencies.</p>
 */
public final class SkillDefinition implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String name;
    private final String description;
    private final String version;
    private final boolean requireCitation;
    private final boolean writable;
    private final List<String> tools;

    private SkillDefinition(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.version = builder.version;
        this.requireCitation = builder.requireCitation;
        this.writable = builder.writable;
        this.tools = builder.tools == null ? List.of() : List.copyOf(builder.tools);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /** FR-22：skill 版本号，默认 "1.0"，与 Langfuse Prompt 版本关联 */
    public String getVersion() {
        return version;
    }

    public boolean isRequireCitation() {
        return requireCitation;
    }

    public boolean isWritable() {
        return writable;
    }

    public List<String> getTools() {
        return tools;
    }

    public static final class Builder {

        private String name;
        private String description;
        private String version = "1.0";
        private List<String> tools = List.of();
        private boolean requireCitation;
        private boolean writable;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** FR-22：skill 版本号，默认 "1.0" */
        public Builder version(String version) {
            this.version = version == null || version.isBlank() ? "1.0" : version;
            return this;
        }

        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        public Builder requireCitation(boolean requireCitation) {
            this.requireCitation = requireCitation;
            return this;
        }

        public Builder writable(boolean writable) {
            this.writable = writable;
            return this;
        }

        public SkillDefinition build() {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            return new SkillDefinition(this);
        }
    }
}
