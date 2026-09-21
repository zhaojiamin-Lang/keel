package io.keel.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.SkillDefinition;

class SkillLoaderTest {

    @Test
    void loadsSkillsFromClasspath() {
        List<SkillDefinition> skills = new SkillLoader().load("skills");

        assertThat(skills)
                .extracting(SkillDefinition::getName)
                .contains("chat", "wiki-qa");
    }

    @Test
    void loadsChatWithoutCitationRequirement() {
        SkillDefinition chat = new SkillLoader().load("skills").stream()
                .filter(skill -> skill.getName().equals("chat"))
                .findFirst()
                .orElseThrow();

        assertThat(chat.isRequireCitation()).isFalse();
        assertThat(chat.getTools()).isEmpty();
        new ToolCatalog(List.of()).validate(chat);
    }

    @Test
    void rejectsDuplicateSkillNames() {
        assertThatThrownBy(() -> new SkillLoader().load("skills-duplicate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void loadsVersionFromYaml() {
        SkillDefinition chat = new SkillLoader().load("skills").stream()
                .filter(skill -> skill.getName().equals("chat"))
                .findFirst()
                .orElseThrow();

        // FR-22：未配 version 时默认 "1.0"
        assertThat(chat.getVersion()).isEqualTo("1.0");
    }
}
