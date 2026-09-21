package io.keel.skill;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.keel.core.SkillDefinition;

class SkillRegistryTest {

    @Test
    void duplicateSkillNameFails() {
        SkillDefinition first = SkillDefinition.builder().name("duplicate").build();
        SkillDefinition second = SkillDefinition.builder().name("duplicate").build();

        assertThatThrownBy(() -> new SkillRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }
}
