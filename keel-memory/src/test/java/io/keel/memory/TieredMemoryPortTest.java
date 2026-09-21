package io.keel.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.keel.core.AgentRequest;
import io.keel.core.AgentResult;
import io.keel.core.AgentStatus;
import io.keel.core.KeelPrincipal;
import io.keel.core.MemoryBudget;
import io.keel.core.MemoryFragment;
import io.keel.core.MemoryLayer;

/**
 * {@link TieredMemoryPort} 的召回合并 / 预算 / 租户隔离 / 写入测试（FR-51/FR-52/FR-53/FR-54）。
 */
class TieredMemoryPortTest {

    private InMemoryEpisodicStore episodic;
    private InMemoryLongTermStore longTerm;
    private TieredMemoryPort port;

    @BeforeEach
    void setUp() {
        episodic = new InMemoryEpisodicStore();
        longTerm = new InMemoryLongTermStore();
        port = new TieredMemoryPort(episodic, longTerm);
    }

    @Test
    void recallMergesLongTermFirstThenEpisodic() {
        longTerm.upsert(fragment(MemoryLayer.L3, "t1", "u1", "lang", "偏好中文回答"));
        episodic.append(fragment(MemoryLayer.L2, "t1", "u1", null, "上次查过工单 T-001"));

        List<MemoryFragment> recall = port.recall(request("t1", "u1"), MemoryBudget.defaults());

        assertThat(recall).hasSize(2);
        assertThat(recall.get(0).getLayer()).isEqualTo(MemoryLayer.L3);
        assertThat(recall.get(1).getLayer()).isEqualTo(MemoryLayer.L2);
    }

    @Test
    void recallRespectsMaxFragmentsBudget() {
        longTerm.upsert(fragment(MemoryLayer.L3, "t1", "u1", "k1", "偏好 A"));
        longTerm.upsert(fragment(MemoryLayer.L3, "t1", "u1", "k2", "偏好 B"));
        episodic.append(fragment(MemoryLayer.L2, "t1", "u1", null, "情景 1"));
        episodic.append(fragment(MemoryLayer.L2, "t1", "u1", null, "情景 2"));

        List<MemoryFragment> recall = port.recall(
                request("t1", "u1"), new MemoryBudget(2, 200));

        assertThat(recall).hasSize(2);
        // L3 优先，只取前 2 条
        assertThat(recall).allSatisfy(f -> assertThat(f.getLayer()).isEqualTo(MemoryLayer.L3));
    }

    @Test
    void recallTruncatesFragmentContent() {
        longTerm.upsert(fragment(MemoryLayer.L3, "t1", "u1", "k",
                "这是一段非常长的偏好描述，应该被预算截断。"));

        List<MemoryFragment> recall = port.recall(
                request("t1", "u1"), new MemoryBudget(5, 5));

        assertThat(recall).hasSize(1);
        assertThat(recall.get(0).getContent()).endsWith("...");
        assertThat(recall.get(0).getContent().length()).isLessThanOrEqualTo(8);
    }

    @Test
    void recallIsIsolatedByTenantAndSubject() {
        longTerm.upsert(fragment(MemoryLayer.L3, "other-tenant", "u1", "k", "不该被召回"));
        episodic.append(fragment(MemoryLayer.L2, "t1", "other-subject", null, "也不该被召回"));

        List<MemoryFragment> recall = port.recall(request("t1", "u1"), MemoryBudget.defaults());

        assertThat(recall).isEmpty();
    }

    @Test
    void recordAppendsL2Episode() {
        AgentRequest request = request("t1", "u1");
        AgentResult result = AgentResult.builder()
                .status(AgentStatus.SUCCESS)
                .text("工单 T-001 已查询")
                .build();

        port.record(request, result);

        List<MemoryFragment> recent = episodic.recent("t1", "u1", 5);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).getLayer()).isEqualTo(MemoryLayer.L2);
        assertThat(recent.get(0).getContent()).contains("SUCCESS");
    }

    private static AgentRequest request(String tenant, String subject) {
        return AgentRequest.builder()
                .principal(new KeelPrincipal(tenant, subject, List.of()))
                .input("hi")
                .build();
    }

    private static MemoryFragment fragment(MemoryLayer layer, String tenant, String subject,
            String key, String content) {
        return new MemoryFragment(layer, tenant, subject, key, content, Instant.now());
    }
}
