package io.keel.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * keel-core DTO 不变式测试：这些类型被所有模块共享，
 * 它们的默认值与校验语义是框架安全默认（NFR-01）的一部分。
 */
class KeelCoreDtoTest {

    // ---------- Citation（FR-62） ----------

    @Test
    void citationDefaultsToUntrusted() {
        // 检索引用默认 untrusted=true：默认不信任是 fail-closed 语义
        Citation citation = new Citation("c-1", "doc-1", "snippet");
        assertThat(citation.isUntrusted()).isTrue();

        assertThat(new Citation("c-1", "doc-1", "snippet", false).isUntrusted()).isFalse();
    }

    // ---------- MemoryBudget（FR-54） ----------

    @Test
    void memoryBudgetRejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new MemoryBudget(0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryBudget(5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryBudget(-1, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void memoryBudgetDefaultsAndEquality() {
        assertThat(MemoryBudget.defaults()).isEqualTo(new MemoryBudget(5, 200));
        assertThat(MemoryBudget.defaults()).isNotEqualTo(new MemoryBudget(3, 200));
        assertThat(MemoryBudget.defaults().hashCode())
                .isEqualTo(new MemoryBudget(5, 200).hashCode());
    }

    // ---------- AgentRequest（NPE 加固：反序列化复验 principal） ----------

    @Test
    void agentRequestSerializationRoundTrip() throws Exception {
        AgentRequest request = AgentRequest.builder()
                .principal(new KeelPrincipal("t1", "u1", List.of("r1")))
                .input("你好")
                .sessionId("s-1")
                .build();
        AgentRequest restored = (AgentRequest) roundTrip(request);
        assertThat(restored.getPrincipal().getTenantId()).isEqualTo("t1");
        assertThat(restored.getPrincipal().getSubjectId()).isEqualTo("u1");
        assertThat(restored.getInput()).isEqualTo("你好");
        assertThat(restored.getSessionId()).isEqualTo("s-1");
    }

    @Test
    void agentRequestDeserializationRejectsNullPrincipal() throws Exception {
        // 模拟损坏/被篡改的序列化流：principal 在流中被替换为 null（绕过 Builder 校验）。
        // readObject 复验必须拒绝，防止下游 getPrincipal() 调用点 NPE
        byte[] tampered = serializeWithNullPrincipal(AgentRequest.builder()
                .principal(new KeelPrincipal("t1", "u1", List.of()))
                .input("hi")
                .build());
        assertThatThrownBy(() -> new ObjectInputStream(new ByteArrayInputStream(tampered)).readObject())
                .isInstanceOf(InvalidObjectException.class);
    }

    private static Object roundTrip(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return in.readObject();
        }
    }

    /** 用 ObjectOutputStream 的 replaceObject 钩子把流中的 KeelPrincipal 引用写成 null，等价于被篡改的字段值。 */
    private static byte[] serializeWithNullPrincipal(AgentRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes) {
            {
                enableReplaceObject(true);
            }

            @Override
            protected Object replaceObject(Object obj) {
                return obj instanceof KeelPrincipal ? null : obj;
            }
        }) {
            out.writeObject(request);
        }
        return bytes.toByteArray();
    }

    // ---------- PendingAction（FR-13/FR-32） ----------

    @Test
    void pendingActionDefaultsToUnconfirmedWithoutIdempotencyKey() {
        PendingAction pending = new PendingAction("a-1", "issue.create", "{}");
        assertThat(pending.isConfirmed()).isFalse();
        assertThat(pending.getIdempotencyKey()).isNull();
        assertThat(pending.getPayloadJson()).isEqualTo("{}");

        PendingAction withKey = new PendingAction(
                "a-2", "issue.close", "{\"id\":\"ISSUE-1\"}", true, "idem-1");
        assertThat(withKey.isConfirmed()).isTrue();
        assertThat(withKey.getIdempotencyKey()).isEqualTo("idem-1");
    }

    // ---------- MemoryFragment（FR-53） ----------

    @Test
    void memoryFragmentRequiresTenantAndSubject() {
        assertThatThrownBy(() -> new MemoryFragment(
                MemoryLayer.L2, null, "u1", null, "x", Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MemoryFragment(
                MemoryLayer.L3, "t1", null, "k", "x", Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void memoryFragmentDefaultsCreatedAtToNow() {
        MemoryFragment fragment = new MemoryFragment(
                MemoryLayer.L3, "t1", "u1", "pref:lang", "偏好中文", null);
        assertThat(fragment.getCreatedAt()).isNotNull();
    }

    // ---------- SkillDefinition（FR-20/FR-22） ----------

    @Test
    void skillDefinitionDefaults() {
        SkillDefinition skill = SkillDefinition.builder()
                .name("wiki-qa")
                .description("只读问答")
                .build();
        // 安全默认：不可写、tools 为空列表
        assertThat(skill.isWritable()).isFalse();
        assertThat(skill.getTools()).isEmpty();
        // FR-22：版本号默认 1.0，用于与 Langfuse Prompt 版本关联
        assertThat(skill.getVersion()).isEqualTo("1.0");
    }

    @Test
    void skillDefinitionToolsListIsDefensivelyCopied() {
        List<String> tools = List.of("issue.read");
        SkillDefinition skill = SkillDefinition.builder()
                .name("ticket")
                .tools(tools)
                .build();
        assertThat(skill.getTools()).containsExactly("issue.read");
        assertThatThrownBy(() -> skill.getTools().add("issue.write"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
